import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { setImmediate as flush } from 'node:timers/promises'
import { afterEach, beforeEach, test } from 'node:test'

const apiModuleUrl = `data:text/javascript,${encodeURIComponent(`
  export const api = {};
  ${['loadConfig', 'sourceHome', 'sourceSearch', 'searchAll', 'getProgress', 'cancelSearch'].map((name) => `export const ${name} = (...args) => api.${name}(...args);`).join('\n')}
`)}`
const { api } = await import(apiModuleUrl)
let source = await readFile(new URL('../src/stores/useStore.js', import.meta.url), 'utf8')
for (const [from, to] of [
  ["from 'zustand'", `from ${JSON.stringify(import.meta.resolve('zustand'))}`],
  ["from '../api'", `from ${JSON.stringify(apiModuleUrl)}`],
  ["from '../utils'", `from ${JSON.stringify(new URL('../src/utils/index.js', import.meta.url).href)}`],
]) {
  assert.ok(source.includes(from))
  source = source.replace(from, to)
}
const storage = new Map()
globalThis.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
}
const { useStore } = await import(`data:text/javascript,${encodeURIComponent(source)}`)

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function progress(patch = {}) {
  return { found: true, total: 77, completed: 1, failed: 0, done: false, cancelled: false, results: [], ...patch }
}

const movie = { vod_id: 'm1', vod_name: '影片一', source_uid: 's0', source_name: '源0' }

beforeEach(() => {
  useStore.getState().stopSearch()
  for (const key of Object.keys(api)) delete api[key]
  storage.clear()
  useStore.setState(useStore.getInitialState(), true)
  useStore.setState({ sources: Array.from({ length: 77 }, (_, i) => ({ uid: `s${i}`, name: `源${i}`, searchable: 1 })), selectedSourceUid: 's0' })
  api.cancelSearch = async () => ({ ok: true })
})

afterEach(() => useStore.getState().stopSearch())

test('ordinary search uses one backend job, streams partial results and preserves failures when stopped', async () => {
  let polls = 0
  const cancelled = []
  api.searchAll = async (wd, quick, config) => {
    assert.deepEqual([wd, quick, config.params, config.silentError], ['测试', false, {}, true])
    assert.equal(config.timeout, 15000)
    return { progressId: 'job1' }
  }
  api.sourceSearch = () => { assert.fail('must not start a second per-source request pipeline') }
  api.getProgress = async (id, config) => {
    polls++
    assert.equal(id, 'job1')
    assert.equal(config.silentError, true)
    assert.equal(config.signal.aborted, false)
    assert.equal(config.timeout, 5000)
    return progress({ completed: 3, failed: 2, results: [movie] })
  }
  api.cancelSearch = async (id, config) => {
    assert.equal(config.timeout, 5000)
    cancelled.push(id)
    return { ok: true }
  }
  const pending = useStore.getState().searchVideos('测试')
  await flush()
  assert.equal(useStore.getState().searchProgress.active, true)
  assert.deepEqual(useStore.getState().wallVideos.map((v) => v.id), ['m1'])
  useStore.getState().stopSearch()
  await pending
  assert.equal(polls, 1)
  assert.deepEqual(cancelled, ['job1'])
  assert.equal(useStore.getState().searchProgress.failed, 2)
  assert.equal(useStore.getState().searchProgress.cancelled, true)
  assert.equal(useStore.getState().loading.wall, false)
  assert.equal(useStore.getState().searchCache.videos.length, 1)
})

test('successive progress snapshots replace results without duplicates and finish with failure summary', async () => {
  let polls = 0
  api.searchAll = async () => ({ progressId: 'job' })
  api.getProgress = async () => ++polls === 1
    ? progress({ results: [movie] })
    : progress({ done: true, completed: 77, failed: 4, results: [movie, { ...movie, vod_id: 'm2' }] })
  await useStore.getState().searchVideos('测试')
  assert.equal(polls, 2)
  assert.deepEqual(useStore.getState().wallVideos.map((v) => v.id), ['m1', 'm2'])
  assert.equal(useStore.getState().searchProgress.failed, 4)
  assert.equal(useStore.getState().searchProgress.active, false)
  assert.equal(useStore.getState().searchProgress.error, '')
})

test('late start response is cancelled even after a newer search has started', async () => {
  const oldStart = deferred()
  const newPoll = deferred()
  const cancelled = []
  api.searchAll = async (wd) => wd === '旧搜索' ? oldStart.promise : { progressId: 'new' }
  api.getProgress = async () => newPoll.promise
  api.cancelSearch = async (id) => { cancelled.push(id); return { ok: true } }
  const oldSearch = useStore.getState().searchVideos('旧搜索')
  useStore.getState().stopSearch()
  const newSearch = useStore.getState().searchVideos('新搜索')
  await flush()
  oldStart.resolve({ progressId: 'old' })
  await oldSearch
  assert.deepEqual(cancelled, ['old'])
  assert.equal(useStore.getState().searchKeyword, '新搜索')
  assert.equal(useStore.getState().loading.wall, true)
  newPoll.resolve(progress({ done: true, completed: 77, results: [movie] }))
  await newSearch
  assert.equal(useStore.getState().wallVideos.length, 1)
})

test('late progress cannot overwrite results or loading state of a newer run', async () => {
  const oldPoll = deferred()
  const newPoll = deferred()
  let oldSignal
  api.searchAll = async (wd) => ({ progressId: wd })
  api.getProgress = async (id, config) => {
    if (id === 'old') { oldSignal = config.signal; return oldPoll.promise }
    return newPoll.promise
  }
  const oldSearch = useStore.getState().searchVideos('old')
  await flush()
  const newSearch = useStore.getState().searchVideos('new')
  await flush()
  assert.equal(oldSignal.aborted, true)
  oldPoll.resolve(progress({ done: true, results: [{ ...movie, vod_id: 'old-movie' }] }))
  await oldSearch
  assert.equal(useStore.getState().wallVideos.length, 0)
  assert.equal(useStore.getState().loading.wall, true)
  newPoll.resolve(progress({ done: true, results: [movie] }))
  await newSearch
  assert.equal(useStore.getState().wallVideos[0].id, 'm1')
})

test('AI current-source search passes the selected key and AI uses the same cancellation signal', async (t) => {
  useStore.setState({ settings: { ...useStore.getState().settings, searchScope: 'current', aiEnabled: true, aiUrl: 'https://ai.example', aiKey: 'test' }, selectedSourceUid: 's3' })
  let pollSignal
  let aiSignal
  const aiResponse = deferred()
  api.searchAll = async (wd, quick, config) => {
    assert.equal(config.params.key, 's3')
    return { progressId: 'ai' }
  }
  api.getProgress = async (id, config) => {
    pollSignal = config.signal
    return progress({ total: 1, done: true, results: [movie] })
  }
  t.mock.method(globalThis, 'fetch', async (url, config) => {
    assert.equal(url, 'https://ai.example/v1/chat/completions')
    aiSignal = config.signal
    return aiResponse.promise
  })
  const pending = useStore.getState().searchVideos('测试')
  await flush()
  assert.equal(useStore.getState().searchProgress.aiProcessing, true)
  assert.equal(aiSignal, pollSignal)
  useStore.getState().stopSearch()
  assert.equal(aiSignal.aborted, true)
  useStore.setState({ wallVideos: [{ id: 'new-page' }] })
  aiResponse.resolve({ json: async () => ({ choices: [{ message: { content: '{"mapping":{"影片一":"旧合并"}}' } }] }) })
  await pending
  assert.deepEqual(useStore.getState().wallVideos, [{ id: 'new-page' }])
  assert.equal(useStore.getState().loading.wall, false)
})

test('missing progress terminates immediately instead of polling forever', async () => {
  let polls = 0
  const cancelled = []
  api.searchAll = async () => ({ progressId: 'expired' })
  api.getProgress = async () => { polls++; return { found: false } }
  api.cancelSearch = async (id) => { cancelled.push(id) }
  await useStore.getState().searchVideos('测试')
  assert.equal(polls, 1)
  assert.match(useStore.getState().searchProgress.error, /已失效/)
  assert.equal(useStore.getState().loading.wall, false)
  assert.deepEqual(cancelled, ['expired'])
})

test('progress network failures are retried only three times and retain partial results', async () => {
  let polls = 0
  api.searchAll = async () => ({ progressId: 'job' })
  api.getProgress = async () => {
    polls++
    if (polls === 1) return progress({ results: [movie], failed: 2 })
    throw new Error('offline')
  }
  await useStore.getState().searchVideos('测试')
  assert.equal(polls, 4)
  assert.match(useStore.getState().searchProgress.error, /获取搜索进度失败.*offline/)
  assert.equal(useStore.getState().wallVideos.length, 1)
  assert.equal(useStore.getState().searchProgress.failed, 2)
  assert.equal(useStore.getState().loading.wall, false)
})

test('backend cancellation and whole-job failure are reflected in the summary', async () => {
  api.searchAll = async () => ({ progressId: 'job' })
  api.getProgress = async () => progress({ done: true, cancelled: true, completed: 2, results: [movie] })
  await useStore.getState().searchVideos('测试')
  assert.equal(useStore.getState().searchProgress.cancelled, true)
  api.getProgress = async () => progress({ done: true, error: '任务启动失败', results: [movie] })
  await useStore.getState().searchVideos('另一个')
  assert.equal(useStore.getState().searchProgress.error, '任务启动失败')
  assert.equal(useStore.getState().wallVideos.length, 1)
})

test('returning home stops a running search before replacing its results', async () => {
  api.searchAll = async () => ({ progressId: 'job' })
  api.getProgress = async () => progress({ results: [movie] })
  api.sourceHome = async () => ({ list: [{ vod_id: 'home', vod_name: '推荐' }] })
  const pending = useStore.getState().searchVideos('测试')
  await flush()
  await useStore.getState().loadHome()
  await pending
  assert.equal(useStore.getState().wallMode, 'home')
  assert.equal(useStore.getState().wallVideos[0].id, 'home')
})

test('search failures can be silent without suppressing normal foreground error toasts', async () => {
  const messageUrl = `data:text/javascript,${encodeURIComponent('export const errors = []; export const message = {error: (...args) => errors.push(args)};')}`
  const { errors } = await import(messageUrl)
  let clientSource = await readFile(new URL('../src/api/client.js', import.meta.url), 'utf8')
  clientSource = clientSource.replace("from 'axios'", `from ${JSON.stringify(import.meta.resolve('axios'))}`)
    .replace("from 'antd'", `from ${JSON.stringify(messageUrl)}`)
  const { default: client, initMessageHandler, getJson } = await import(`data:text/javascript,${encodeURIComponent(clientSource)}`)
  client.defaults.adapter = async (config) => {
    throw Object.assign(new Error('failure'), { config, response: { status: 500, data: { error: '插件失败' } } })
  }
  initMessageHandler()
  await assert.rejects(getJson('/search', { silentError: true }), /插件失败/)
  assert.equal(errors.length, 0)
  await assert.rejects(getJson('/detail'), /插件失败/)
  assert.equal(errors.length, 1)
})

test('API wrappers preserve keyword, current-source key, signals, timeouts and quiet flags', async () => {
  const clientUrl = `data:text/javascript,${encodeURIComponent(`
    export const calls = [];
    export const getJson = async (...args) => { calls.push(['get', ...args]); return {}; };
    export const postJson = async (...args) => { calls.push(['post', ...args]); return {}; };
  `)}`
  const { calls } = await import(clientUrl)
  let apiSource = await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')
  apiSource = apiSource.replace("from './client'", `from ${JSON.stringify(clientUrl)}`)
  const wrapper = await import(`data:text/javascript,${encodeURIComponent(apiSource)}`)
  const signal = new AbortController().signal
  await wrapper.searchAll('复仇者联盟4', false, { params: { key: 's3' }, timeout: 15000, silentError: true })
  assert.deepEqual(calls[0], ['get', '/api/search/all', { params: { wd: '复仇者联盟4', quick: false, key: 's3' }, timeout: 15000, silentError: true }])
  await wrapper.getProgress('job/1', { signal, timeout: 5000, silentError: true })
  assert.deepEqual(calls[1], ['get', '/api/progress/job%2F1', { signal, timeout: 5000, silentError: true }])
  await wrapper.cancelSearch('job/1', { timeout: 5000, silentError: true })
  assert.deepEqual(calls[2], ['post', '/api/search/job%2F1/cancel', {}, { timeout: 5000, silentError: true }])
})

test('initial global-search count excludes disabled sources', async () => {
  const start = deferred()
  useStore.setState({ sources: [{ uid: 'enabled', searchable: 1 }, { uid: 'disabled', searchable: 0 }] })
  api.searchAll = async () => start.promise
  const pending = useStore.getState().searchVideos('测试')
  assert.equal(useStore.getState().searchProgress.total, 1)
  useStore.getState().stopSearch()
  start.resolve({ progressId: 'job' })
  await pending
})
