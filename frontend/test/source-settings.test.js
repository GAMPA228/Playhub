import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { setImmediate as flush } from 'node:timers/promises'
import { beforeEach, test } from 'node:test'

// Exercise the real Zustand actions with only HTTP and browser storage replaced.
const apiModuleUrl = `data:text/javascript,${encodeURIComponent(`
  export const api = {};
  ${['getSourceSettings', 'saveSourceSettings', 'loadConfig', 'sourceHome', 'sourceCategory', 'sourceDetail', 'sourcePlay', 'batchDetail', 'getProgress', 'liveBootstrap'].map((name) => `export const ${name} = (...args) => api.${name}(...args);`).join('\n')}
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
  removeItem: (key) => storage.delete(key),
}
const { useStore } = await import(`data:text/javascript,${encodeURIComponent(source)}`)
const state = () => useStore.getState()
const videoUrl = 'https://sources.example/api.json'
const liveUrl = 'https://live.example/channels.m3u'
const epgUrl = 'https://live.example/epg.xml'
let server
let writes
let loads

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function putLegacy() {
  storage.set('playhub:configUrl', 'https://old.example/api.json')
  storage.set('playhub:config-history', JSON.stringify(['https://old.example/api.json']))
  storage.set('playhub:settings', JSON.stringify({ theme: 'dark', aiKey: 'private-key', liveSourceUrl: 'https://old.example/live.m3u', liveEpgUrl: 'https://old.example/epg.xml' }))
  storage.set('playhub:search-history', JSON.stringify(['previous search']))
  storage.set('playhub:history:keep', JSON.stringify([{ title: 'watched' }]))
}

function detail(name = 'sample movie') {
  return { list: [{ vod_id: 'm1', vod_name: name, vod_play_from: 'APP', vod_play_url: '正片$opaque-id' }] }
}

const groupSources = [{ uid: 's0', name: 'sample', vodId: 'm1' }]

beforeEach(() => {
  storage.clear()
  for (const key of Object.keys(api)) delete api[key]
  useStore.setState(useStore.getInitialState(), true)
  server = { initialized: false, configUrl: '', configHistory: [], liveSourceUrl: '', liveEpgUrl: '' }
  writes = []
  loads = []
  api.getSourceSettings = async () => ({ ...server })
  api.saveSourceSettings = async (patch) => {
    writes.push(patch)
    server = { ...server, ...patch, initialized: true }
    return { ...server }
  }
  api.loadConfig = async (url, options) => {
    loads.push([url, options])
    if (options.persist) server = { ...server, configUrl: url, configHistory: [url], initialized: true }
    return { ok: true, config: { sites: [{ uid: 's0', name: 'sample', searchable: 1 }] }, sourceSettings: { ...server } }
  }
  api.sourceHome = async () => ({ list: [{ vod_id: 'm1', vod_name: 'sample movie' }] })
})

test('a fresh browser restores shared video and live URLs without rewriting the server', async () => {
  server = { ...server, initialized: true, configUrl: videoUrl, configHistory: [videoUrl], liveSourceUrl: liveUrl, liveEpgUrl: epgUrl }
  await state().bootstrap()
  assert.equal(state().configUrl, videoUrl)
  assert.deepEqual(state().configHistory, [videoUrl])
  assert.equal(state().settings.liveSourceUrl, liveUrl)
  assert.equal(state().settings.liveEpgUrl, epgUrl)
  assert.equal(state().sources.length, 1)
  assert.equal(state().wallVideos[0].id, 'm1')
  assert.equal(state().bootstrapped, true)
  assert.equal(state().sourceSettingsError, '')
  assert.deepEqual(writes, [])
  assert.equal(loads.length, 1)
  assert.equal(loads[0][1].persist, false)
  assert.equal(storage.has('playhub:configUrl'), false)
  assert.equal(storage.has('playhub:config-history'), false)
  assert.equal(JSON.parse(storage.get('playhub:settings') || '{}').liveSourceUrl, undefined)
})

test('existing server settings win over legacy browser addresses and legacy is offered only explicitly', async () => {
  putLegacy()
  server = { ...server, initialized: true, configUrl: videoUrl, configHistory: [videoUrl], liveSourceUrl: liveUrl }
  await state().bootstrap()
  assert.equal(state().configUrl, videoUrl)
  assert.equal(state().settings.liveSourceUrl, liveUrl)
  assert.equal(state().settings.theme, 'dark')
  assert.equal(state().settings.aiKey, 'private-key')
  assert.equal(state().legacySourceSettings.configUrl, 'https://old.example/api.json')
  assert.equal(writes.length, 0)
  assert.equal(storage.get('playhub:configUrl'), 'https://old.example/api.json')
})

test('an empty server never silently imports browser URLs, including after clearing and reloading', async () => {
  putLegacy()
  await state().bootstrap()
  assert.equal(state().configUrl, '')
  assert.equal(state().settings.liveSourceUrl, '')
  assert.equal(loads.length, 0)
  assert.equal(writes.length, 0)
  await state().clearVideoConfig()
  assert.equal(storage.has('playhub:configUrl'), false)
  assert.equal(storage.has('playhub:config-history'), false)
  await state().bootstrap({ force: true })
  assert.equal(state().configUrl, '')
  assert.equal(loads.length, 0)
  assert.equal(storage.has('playhub:history:keep'), true)
  assert.equal(JSON.parse(storage.get('playhub:settings')).aiKey, 'private-key')
})

test('bootstrap deduplicates pending loads and repeated calls', async () => {
  const pending = deferred()
  let reads = 0
  api.getSourceSettings = async () => { reads++; return pending.promise }
  const first = state().bootstrap()
  const second = state().bootstrap()
  assert.equal(first, second)
  pending.resolve({ ...server, configUrl: videoUrl })
  server.configUrl = videoUrl
  await Promise.all([first, second])
  await state().bootstrap()
  assert.equal(reads, 1)
  assert.equal(loads.length, 1)
})

test('read failure exposes a retryable error and never publishes local sources', async () => {
  putLegacy()
  api.getSourceSettings = async () => { throw new Error('offline') }
  await state().bootstrap()
  assert.equal(state().sourceSettingsLoaded, false)
  assert.equal(state().sourceSettingsError, 'offline')
  assert.equal(state().configUrl, '')
  assert.equal(writes.length, 0)
  assert.equal(loads.length, 0)
  api.getSourceSettings = async () => ({ ...server, liveSourceUrl: liveUrl })
  await state().bootstrap({ force: true })
  assert.equal(state().sourceSettingsLoaded, true)
  assert.equal(state().sourceSettingsError, '')
  assert.equal(state().settings.liveSourceUrl, liveUrl)
})

test('mutations are blocked while bootstrap is pending, preventing a late read overwriting edits', async () => {
  const pending = deferred()
  api.getSourceSettings = async () => pending.promise
  const boot = state().bootstrap()
  await assert.rejects(state().saveSourceSettings({ liveSourceUrl: liveUrl }), /正在进行/)
  await assert.rejects(state().loadConfig(videoUrl), /正在进行/)
  pending.resolve(server)
  await boot
  assert.equal(writes.length, 0)
})

test('successful video loads persist via config/load and do not write source addresses to local storage', async () => {
  putLegacy()
  await state().loadConfig(videoUrl)
  assert.equal(loads[0][1].persist, true)
  assert.equal(server.configUrl, videoUrl)
  assert.deepEqual(state().configHistory, [videoUrl])
  assert.equal(storage.has('playhub:configUrl'), false)
  assert.equal(storage.has('playhub:config-history'), false)
  assert.equal(JSON.parse(storage.get('playhub:settings')).liveSourceUrl, 'https://old.example/live.m3u')
})

test('failed writes retain active live data and all legacy migration values', async () => {
  putLegacy()
  state().applySourceSettings({ ...server, liveSourceUrl: liveUrl, liveEpgUrl: epgUrl })
  useStore.setState({ liveChannels: [{ id: 'old-channel' }], liveGroups: [{ name: 'old' }] })
  api.saveSourceSettings = async () => { throw new Error('disk full') }
  await assert.rejects(state().saveSourceSettings({ liveSourceUrl: 'https://other.example/live.m3u' }), /disk full/)
  assert.equal(state().settings.liveSourceUrl, liveUrl)
  assert.equal(state().liveChannels[0].id, 'old-channel')
  assert.equal(state().sourceSaving, false)
  assert.equal(JSON.parse(storage.get('playhub:settings')).liveSourceUrl, 'https://old.example/live.m3u')
})

test('partial live saves preserve video and EPG, clear live caches, and exclude private preferences', async () => {
  server = { ...server, configUrl: videoUrl, configHistory: [videoUrl], liveSourceUrl: liveUrl, liveEpgUrl: epgUrl }
  state().applySourceSettings(server)
  useStore.setState({ liveChannels: [{ id: 'old-channel' }], liveGroups: [{ name: 'old' }], liveSummary: { total: 1 }, currentLiveChannelId: 'old-channel' })
  await state().saveSourceSettings({ liveSourceUrl: '', aiKey: 'never-send', theme: 'dark' })
  assert.deepEqual(writes, [{ liveSourceUrl: '' }])
  assert.equal(state().configUrl, videoUrl)
  assert.equal(state().settings.liveEpgUrl, epgUrl)
  assert.deepEqual(state().liveChannels, [])
  assert.deepEqual(state().liveGroups, [])
  assert.equal(state().liveSummary, null)
  assert.equal(state().currentLiveChannelId, '')
})

test('a slow old live response cannot resurrect a cleared playlist', async () => {
  server = { ...server, liveSourceUrl: liveUrl }
  state().applySourceSettings(server)
  const pending = deferred()
  api.liveBootstrap = async () => pending.promise
  const load = state().loadLiveData(liveUrl, '')
  await state().saveSourceSettings({ liveSourceUrl: '' })
  pending.resolve({ channels: [{ id: 'late', name: 'late', url: 'https://stream.example/live.m3u8' }] })
  await load
  assert.deepEqual(state().liveChannels, [])
  assert.equal(state().settings.liveSourceUrl, '')
})

test('live loading failures reject instead of reporting false success', async (t) => {
  t.mock.method(console, 'error', () => {})
  state().applySourceSettings({ ...server, liveSourceUrl: liveUrl })
  api.liveBootstrap = async () => { throw new Error('playlist offline') }
  await assert.rejects(state().loadLiveData(liveUrl, ''), /playlist offline/)
})

test('preferences ignore live source edits and never write shared live addresses into browser settings', () => {
  state().applySourceSettings({ ...server, liveSourceUrl: liveUrl })
  state().updateSettings({ theme: 'dark', aiKey: 'local-key', liveSourceUrl: 'https://wrong.example/live' })
  assert.equal(state().settings.theme, 'dark')
  assert.equal(state().settings.liveSourceUrl, liveUrl)
  assert.deepEqual(JSON.parse(storage.get('playhub:settings')), { theme: 'dark', searchScope: 'all', aiEnabled: false, aiUrl: '', aiKey: 'local-key', aiModel: '' })
  assert.equal(writes.length, 0)
})

test('explicit migration persists only source fields and cleans legacy only after success', async () => {
  putLegacy()
  await state().bootstrap()
  const pending = deferred()
  api.saveSourceSettings = async (patch) => {
    writes.push(patch)
    await pending.promise
    server = { ...server, ...patch, initialized: true }
    return server
  }
  const migration = state().migrateLegacySources()
  assert.equal(storage.has('playhub:configUrl'), true)
  assert.equal(state().sourceSaving, true)
  pending.resolve()
  await migration
  assert.deepEqual(Object.keys(writes[0]).sort(), ['configHistory', 'configUrl', 'liveEpgUrl', 'liveSourceUrl'])
  assert.equal(storage.has('playhub:configUrl'), false)
  assert.equal(storage.has('playhub:config-history'), false)
  const preferences = JSON.parse(storage.get('playhub:settings'))
  assert.equal(preferences.liveSourceUrl, undefined)
  assert.equal(preferences.liveEpgUrl, undefined)
  assert.equal(preferences.theme, 'dark')
  assert.equal(preferences.aiKey, 'private-key')
  assert.equal(storage.has('playhub:history:keep'), true)
  assert.equal(storage.has('playhub:search-history'), true)
  assert.equal(state().legacySourceSettings, null)
})

test('failed migration leaves browser values and does not load an unsaved source', async () => {
  putLegacy()
  api.saveSourceSettings = async () => { throw new Error('read only') }
  await assert.rejects(state().migrateLegacySources(), /read only/)
  assert.equal(storage.get('playhub:configUrl'), 'https://old.example/api.json')
  assert.equal(loads.length, 0)
})

test('import awaits shared persistence and config restoration while keeping AI settings local', async () => {
  const pending = deferred()
  let completed = false
  api.loadConfig = async (url, options) => {
    loads.push([url, options])
    await pending.promise
    return { config: { sites: [] }, sourceSettings: server }
  }
  const imported = state().importSettings({ configUrl: videoUrl, configHistory: [videoUrl], settings: { liveSourceUrl: liveUrl, aiKey: 'imported-private', theme: 'dark' }, searchHistory: ['imported search'] }).then(() => { completed = true })
  await flush()
  assert.equal(completed, false)
  assert.deepEqual(writes, [{ configUrl: videoUrl, configHistory: [videoUrl], liveSourceUrl: liveUrl }])
  pending.resolve()
  await imported
  assert.equal(completed, true)
  assert.equal(loads[0][1].persist, false)
  assert.equal(state().settings.aiKey, 'imported-private')
  assert.deepEqual(state().searchHistory, ['imported search'])
  assert.equal(storage.has('playhub:configUrl'), false)
})

test('import save failure changes neither shared state nor local preferences', async () => {
  api.saveSourceSettings = async () => { throw new Error('no permission') }
  await assert.rejects(state().importSettings({ configUrl: videoUrl, settings: { aiKey: 'not-applied' } }), /no permission/)
  assert.equal(state().configUrl, '')
  assert.equal(state().settings.aiKey, '')
  assert.equal(loads.length, 0)
})

test('history deletion persists and a failed deletion does not hide the saved address', async () => {
  server = { ...server, configHistory: [videoUrl, 'https://second.example/api.json'] }
  state().applySourceSettings(server)
  await state().removeConfigHistory(videoUrl)
  assert.deepEqual(writes, [{ configHistory: ['https://second.example/api.json'] }])
  api.saveSourceSettings = async () => { throw new Error('offline') }
  await assert.rejects(state().removeConfigHistory('https://second.example/api.json'), /offline/)
  assert.deepEqual(state().configHistory, ['https://second.example/api.json'])
})

test('clearing a video config invalidates pending home responses and visible playback caches', async () => {
  server = { ...server, configUrl: videoUrl, configHistory: [videoUrl] }
  await state().bootstrap()
  useStore.setState({ currentDetail: { name: 'old' }, currentPlayback: { url: 'old' }, detailCache: { old: {} } })
  const pending = deferred()
  api.sourceHome = async () => pending.promise
  const home = state().loadHome()
  await state().clearVideoConfig()
  pending.resolve({ list: [{ vod_id: 'late', vod_name: 'late movie' }] })
  await home
  assert.equal(state().config, null)
  assert.deepEqual(state().sources, [])
  assert.deepEqual(state().wallVideos, [])
  assert.equal(state().currentPlayback, null)
  assert.equal(state().currentDetail, null)
  assert.deepEqual(state().detailCache, {})
  assert.equal(state().loading.wall, false)
})

test('clearing video configuration discards a delayed single-source detail response', async () => {
  const pending = deferred()
  api.sourceDetail = async () => pending.promise
  const old = state().ensureDetail('s0', 'm1')
  await state().clearVideoConfig()
  pending.resolve(detail('old detail'))
  assert.equal(await old, null)
  assert.equal(state().currentDetail, null)
  assert.deepEqual(state().detailCache, {})
  assert.equal(state().loading.detail, false)
})

test('same-URL reload discards old details without clearing a newer request loading state', async () => {
  await state().loadConfig(videoUrl)
  const oldResponse = deferred()
  api.sourceDetail = async () => oldResponse.promise
  const old = state().ensureDetail('s0', 'm1')
  await state().loadConfig(videoUrl)
  const newResponse = deferred()
  api.sourceDetail = async () => newResponse.promise
  const current = state().ensureDetail('s0', 'm1')
  oldResponse.resolve(detail('old detail'))
  assert.equal(await old, null)
  assert.equal(state().loading.detail, true)
  assert.equal(state().currentDetail, null)
  newResponse.resolve(detail('new detail'))
  assert.equal((await current).name, 'new detail')
  assert.equal(state().currentDetail.name, 'new detail')
  assert.equal(state().loading.detail, false)
})

test('group detail lookup does not write or continue to another old source after config clear', async () => {
  const pending = deferred()
  let requests = 0
  api.sourceDetail = async () => { requests++; return pending.promise }
  const old = state().ensureGroupDetail([...groupSources, { uid: 's1', vodId: 'm1' }])
  await state().clearVideoConfig()
  pending.resolve(detail('old detail'))
  assert.equal(await old, null)
  assert.equal(requests, 1)
  assert.equal(state().currentDetail, null)
})

for (const action of ['playEpisode', 'autoSelectAndPlay']) {
  test(`${action} discards late playback after clear without writing viewing history`, async () => {
    api.sourceDetail = async () => detail()
    await state().ensureDetail('s0', 'm1')
    const pending = deferred()
    api.sourcePlay = async () => pending.promise
    const old = action === 'playEpisode'
      ? state().playEpisode('s0', 'm1', 'APP', 0)
      : state().autoSelectAndPlay(groupSources, 'm1', 'APP', 0)
    await flush()
    await state().clearVideoConfig()
    pending.resolve({ url: 'https://cdn.example/old.m3u8' })
    assert.equal(await old, null)
    assert.equal(state().currentPlayback, null)
    assert.equal(state().currentDetail, null)
    assert.deepEqual(state().history, [])
    assert.equal(state().loading.player, false)
    assert.equal(storage.has('playhub:history'), false)
  })
}

test('auto-select does not request playback or later sources after its old detail request fails late', async () => {
  const pending = deferred()
  let detailRequests = 0
  api.sourceDetail = async () => { detailRequests++; return pending.promise }
  api.sourcePlay = () => { assert.fail('must not play from the previous config') }
  const old = state().autoSelectAndPlay([...groupSources, { uid: 's1' }], 'm1', 'APP', 0)
  await state().clearVideoConfig()
  pending.reject(new Error('old failure'))
  assert.equal(await old, null)
  assert.equal(detailRequests, 1)
  assert.equal(state().loading.player, false)
})

test('late aggregated-line responses cannot repopulate caches after clear', async () => {
  const pending = deferred()
  api.batchDetail = async () => pending.promise
  const old = state().ensureGroupLines(groupSources)
  await state().clearVideoConfig()
  pending.resolve({ results: [{ key: 's0', detail: detail('old detail') }] })
  assert.deepEqual((await old).lines, [])
  let calls = 0
  api.batchDetail = async () => { calls++; return { results: [{ key: 's0', detail: detail('new detail') }] } }
  const current = await state().ensureGroupLines(groupSources)
  assert.equal(calls, 1)
  assert.equal(current.detail.name, 'new detail')
})

test('late aggregated progress after same-URL reload is discarded without stale progress callbacks', async () => {
  await state().loadConfig(videoUrl)
  api.batchDetail = async () => ({ progressId: 'old-batch' })
  const pending = deferred()
  api.getProgress = async () => pending.promise
  const progress = []
  const old = state().ensureGroupLines(groupSources, (...values) => progress.push(values))
  await flush()
  await state().loadConfig(videoUrl)
  pending.resolve({ found: true, done: true, completed: 1, total: 1, results: [{ key: 's0', detail: detail('old detail') }] })
  assert.deepEqual((await old).lines, [])
  assert.deepEqual(progress, [[0, 1]])
})

test('late aggregate fallback detail cannot continue trying the previous source configuration', async (t) => {
  t.mock.method(console, 'error', () => {})
  api.batchDetail = async () => { throw new Error('batch unavailable') }
  const pending = deferred()
  let requests = 0
  api.sourceDetail = async () => { requests++; return pending.promise }
  const old = state().ensureGroupLines([...groupSources, { uid: 's1', vodId: 'm1' }])
  await flush()
  await state().clearVideoConfig()
  pending.resolve(detail('old detail'))
  assert.deepEqual((await old).lines, [])
  assert.equal(requests, 1)
})

test('source API wrappers preserve partial fields and persist=false for session restoration', async () => {
  const clientUrl = `data:text/javascript,${encodeURIComponent(`
    export const calls = [];
    export const getJson = async (...args) => { calls.push(['get', ...args]); return {}; };
    export const postJson = async (...args) => { calls.push(['post', ...args]); return {}; };
  `)}`
  const { calls } = await import(clientUrl)
  let apiSource = await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')
  apiSource = apiSource.replace("from './client'", `from ${JSON.stringify(clientUrl)}`)
  const wrapper = await import(`data:text/javascript,${encodeURIComponent(apiSource)}`)
  await wrapper.getSourceSettings({ silentError: true })
  await wrapper.saveSourceSettings({ liveEpgUrl: '' }, { silentError: true })
  await wrapper.loadConfig(videoUrl, { persist: false, config: { silentError: true } })
  await wrapper.loadConfig(videoUrl)
  assert.deepEqual(calls, [
    ['get', '/api/source-settings', { silentError: true }],
    ['post', '/api/source-settings', { liveEpgUrl: '' }, { silentError: true }],
    ['post', '/api/config/load', { url: videoUrl, persist: false }, { silentError: true }],
    ['post', '/api/config/load', { url: videoUrl, persist: true }, {}],
  ])
})
