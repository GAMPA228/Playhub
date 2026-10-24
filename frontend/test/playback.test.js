import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { beforeEach, test } from 'node:test'
import { buildPlayableUrl, normalizeDetail, parsePlayGroups, resolvePlaybackUrl } from '../src/utils/index.js'

const directUrl = 'https://cdn.example/movie.m3u8?token=a,b&ref=x@y'
const spiderId = '|https://cdn.example/movie.m3u8||复仇者联盟4|1'

for (const id of [spiderId, 'token@https://cdn.example/movie.m3u8', 'token,https://cdn.example/movie.m3u8', '12345', 'id$signature', directUrl]) {
  test(`playlist keeps the complete ID: ${id}`, () => {
    const groups = parsePlayGroups({ vod_play_from: 'APP', vod_play_url: `正片$${id}` })
    assert.equal(groups[0].episodes[0].id, id)
    assert.equal(groups[0].episodes[0].name, '正片')
  })
}

test('playlist without episode names preserves the opaque ID and multiple lines stay aligned', () => {
  const groups = parsePlayGroups({ vod_play_from: 'APP$$$直链', vod_play_url: `${spiderId}#第2集$second$$$正片$${directUrl}` })
  assert.deepEqual(groups.map((group) => group.episodes.map((episode) => episode.id)), [[spiderId, 'second'], [directUrl]])
})

test('resolved URLs retain direct, protocol-relative and output URL cleanup behavior', () => {
  assert.equal(buildPlayableUrl({ url: directUrl }, spiderId), directUrl)
  assert.equal(buildPlayableUrl({ url: '//cdn.example/movie.m3u8' }), 'https://cdn.example/movie.m3u8')
  assert.equal(buildPlayableUrl({ url: 'label@https://cdn.example/movie.m3u8' }), 'https://cdn.example/movie.m3u8')
  assert.equal(buildPlayableUrl({}, directUrl), directUrl)
})

test('parse=1 retains existing parser-prefix, relative and absolute target behavior', () => {
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=', url: 'episode-id' }), 'https://parser.example/?url=episode-id')
  assert.equal(buildPlayableUrl({ parse: '1', playUrl: 'https://parser.example/path/', url: '/movie.m3u8' }), 'https://parser.example/movie.m3u8')
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=', url: directUrl }), `https://parser.example/?url=${directUrl}`)
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=', url: 'https://media.example/watch/1' }), 'https://parser.example/?url=https://media.example/watch/1')
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/path/', url: 'https://media.example/watch/1' }), 'https://media.example/watch/1')
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=' }, 'episode-id'), 'https://parser.example/?url=episode-id')
})

test('unresolved opaque IDs are not extracted into URLs or accepted as playback addresses', () => {
  for (const id of [spiderId, 'token@https://cdn.example/movie.m3u8', 'token,https://cdn.example/movie.m3u8', '12345', `${directUrl}|title|1`, 'https://cdn.example/movie.m3u8@field@extra', 'https://cdn.example/movie.m3u8qiaojihttps://parser.example/?url=@end', 'https://media.example/watch/1', 'javascript:alert(1)']) {
    assert.equal(buildPlayableUrl({}, id), '', id)
  }
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=' }, spiderId), '')
  assert.equal(buildPlayableUrl({ parse: 1, playUrl: 'https://parser.example/?url=' }), '')
})

test('resolver failures only fall back for an already direct URL', async () => {
  const error = new Error('Spider 调用失败: Index 1 out of bounds for length 1')
  const fail = async () => { throw error }
  assert.equal(await resolvePlaybackUrl(fail, directUrl), directUrl)
  assert.equal(await resolvePlaybackUrl(fail, '//cdn.example/movie.m3u8'), 'https://cdn.example/movie.m3u8')
  await assert.rejects(resolvePlaybackUrl(fail, spiderId), (err) => err === error)
  await assert.rejects(resolvePlaybackUrl(fail, `token@${directUrl}`), (err) => err === error)
  for (const id of [
    'https://cdn.example/movie.m3u8@field@extra',
    'https://cdn.example/movie.m3u8qiaojihttps://parser.example/?url=@end',
    'https://media.example/watch/1',
    'https://user:password@cdn.example/movie.m3u8',
  ]) {
    await assert.rejects(resolvePlaybackUrl(fail, id), (err) => err === error, id)
  }
})

test('resolver rejects explicit error payloads and missing playback addresses', async () => {
  await assert.rejects(resolvePlaybackUrl(async () => ({ ok: false, error: 'Spider 参数错误' }), spiderId), /Spider 参数错误/)
  await assert.rejects(resolvePlaybackUrl(async () => ({}), spiderId), /未返回可用/)
  await assert.rejects(resolvePlaybackUrl(async () => ({ url: '12345' }), spiderId), /未返回可用/)
  assert.equal(await resolvePlaybackUrl(async () => ({ url: directUrl }), spiderId), directUrl)
})

test('an empty resolver response only falls back for a true media URL, not HTTP-prefixed Spider metadata', async () => {
  const empty = async () => ({})
  assert.equal(await resolvePlaybackUrl(empty, directUrl), directUrl)
  assert.equal(await resolvePlaybackUrl(empty, '//cdn.example/movie.mp4'), 'https://cdn.example/movie.mp4')
  for (const id of [
    'https://cdn.example/movie.m3u8@field@extra',
    'https://cdn.example/movie.m3u8qiaojihttps://parser.example/?url=@end',
    'https://media.example/watch/1',
  ]) {
    await assert.rejects(resolvePlaybackUrl(empty, id), /未返回可用/, id)
  }
  assert.equal(await resolvePlaybackUrl(async () => ({ url: 'https://media.example/watch/1' }), spiderId), 'https://media.example/watch/1')
})

// Node does not resolve Vite's directory imports. Only rewire those imports to real
// modules or this API stub; the Zustand initializer and actions run unchanged.
const apiModuleUrl = `data:text/javascript,${encodeURIComponent(`
  export const api = {};
  ${['loadConfig', 'sourceHome', 'sourceDetail', 'sourcePlay', 'batchDetail'].map((name) => `export const ${name} = (...args) => api.${name}(...args);`).join('\n')}
`)}`
const { api } = await import(apiModuleUrl)
const storeUrl = new URL('../src/stores/useStore.js', import.meta.url)
let storeSource = await readFile(storeUrl, 'utf8')
for (const [from, to] of [
  ["from 'zustand'", `from ${JSON.stringify(import.meta.resolve('zustand'))}`],
  ["from '../api'", `from ${JSON.stringify(apiModuleUrl)}`],
  ["from '../utils'", `from ${JSON.stringify(new URL('../src/utils/index.js', import.meta.url).href)}`],
]) {
  assert.ok(storeSource.includes(from), `test module wiring must match ${from}`)
  storeSource = storeSource.replace(from, to)
}
const storage = new Map()
globalThis.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key),
}
const { useStore } = await import(`data:text/javascript,${encodeURIComponent(storeSource)}`)

function detailPayload(name = '测试影片', id = spiderId) {
  return { list: [{ vod_id: 'movie', vod_name: name, vod_play_from: 'APP', vod_play_url: `正片$${id}` }] }
}

beforeEach(() => {
  storage.clear()
  for (const key of Object.keys(api)) delete api[key]
  useStore.setState(useStore.getInitialState(), true)
})

test('playEpisode sends the original ID, propagates errors, and never writes failed history', async (t) => {
  t.mock.method(console, 'error', () => {})
  const oldPlayback = { url: 'https://cdn.example/old.mp4' }
  useStore.setState({ currentDetail: normalizeDetail(detailPayload(), { uid: 's0' }), currentPlayback: oldPlayback })
  let rejectPlay
  api.sourcePlay = async (uid, flag, id) => {
    assert.deepEqual([uid, flag, id], ['s0', 'APP', spiderId])
    return new Promise((resolve, reject) => { rejectPlay = reject })
  }
  const pending = useStore.getState().playEpisode('s0', 'movie', 'APP', 0)
  assert.equal(useStore.getState().loading.player, true)
  const rejected = assert.rejects(pending, /Spider 参数错误/)
  rejectPlay(new Error('Spider 参数错误'))
  await rejected
  assert.equal(useStore.getState().loading.player, false)
  assert.equal(useStore.getState().currentPlayback, oldPlayback)
  assert.deepEqual(useStore.getState().history, [])
})

test('playEpisode records only the resolved URL and supports a true direct fallback', async () => {
  useStore.setState({ currentDetail: normalizeDetail(detailPayload(), { uid: 's0' }) })
  api.sourcePlay = async () => ({ url: directUrl })
  const playback = await useStore.getState().playEpisode('s0', 'movie', 'APP', 0)
  assert.equal(playback.url, directUrl)
  assert.equal(useStore.getState().history[0].url, directUrl)
  assert.equal(useStore.getState().loading.player, false)

  useStore.setState({ currentDetail: normalizeDetail(detailPayload('直链', directUrl), { uid: 's0' }) })
  api.sourcePlay = async () => { throw new Error('resolver offline') }
  assert.equal((await useStore.getState().playEpisode('s0', 'movie', 'APP', 0)).url, directUrl)
  assert.equal(useStore.getState().loading.player, false)
})

test('auto-select skips an unresolved opaque source and restores loading after success', async () => {
  const calls = []
  api.sourceDetail = async () => detailPayload()
  api.sourcePlay = async (uid, flag, id) => {
    calls.push([uid, flag, id])
    if (uid === 'bad') throw new Error('bad source')
    return { url: directUrl }
  }
  const playback = await useStore.getState().autoSelectAndPlay([{ uid: 'bad' }, { uid: 'good' }], 'movie', 'APP', 0)
  assert.equal(playback.sourceUid, 'good')
  assert.deepEqual(calls, [['bad', 'APP', spiderId], ['good', 'APP', spiderId]])
  assert.equal(useStore.getState().history.length, 1)
  assert.equal(useStore.getState().history[0].sourceUid, 'good')
  assert.equal(useStore.getState().loading.player, false)
})

test('auto-select exposes total failure without a playback or history entry', async () => {
  api.sourceDetail = async () => detailPayload()
  api.sourcePlay = async () => ({ ok: false, error: 'all sources failed' })
  await assert.rejects(useStore.getState().autoSelectAndPlay([{ uid: 'bad' }], 'movie', 'APP', 0), /all sources failed/)
  assert.equal(useStore.getState().currentPlayback, null)
  assert.deepEqual(useStore.getState().history, [])
  assert.equal(useStore.getState().loading.player, false)
})

test('successful config reload clears detail, group and playback caches even when UIDs are reused', async () => {
  const sources = [{ uid: 's0', name: '源', vodId: 'movie', searchable: 1 }]
  let config = 'old'
  let detailCalls = 0
  let groupCalls = 0
  api.loadConfig = async () => ({ sites: sources })
  api.sourceHome = async () => ({ list: [] })
  api.sourceDetail = async () => { detailCalls++; return detailPayload(config) }
  api.batchDetail = async () => { groupCalls++; return { results: [{ key: 's0', detail: detailPayload(config) }] } }

  await useStore.getState().loadConfig('https://config.example/old.json')
  assert.equal((await useStore.getState().ensureDetail('s0', 'movie')).name, 'old')
  assert.equal((await useStore.getState().ensureGroupLines(sources)).detail.name, 'old')
  useStore.setState({ currentPlayback: { url: directUrl } })
  config = 'new'
  await useStore.getState().loadConfig('https://config.example/new.json')
  assert.equal(useStore.getState().currentDetail, null)
  assert.equal(useStore.getState().currentPlayback, null)
  assert.deepEqual(useStore.getState().detailCache, {})
  assert.equal((await useStore.getState().ensureDetail('s0', 'movie')).name, 'new')
  assert.equal((await useStore.getState().ensureGroupLines(sources)).detail.name, 'new')
  assert.equal(detailCalls, 2)
  assert.equal(groupCalls, 2)
})

test('failed config loads do not discard the active detail or playback', async (t) => {
  t.mock.method(console, 'error', () => {})
  const detail = normalizeDetail(detailPayload(), { uid: 's0' })
  const playback = { url: directUrl }
  const detailCache = { 's0::movie': detail }
  useStore.setState({ currentDetail: detail, currentPlayback: playback, detailCache })
  api.loadConfig = async () => { throw new Error('config offline') }
  await assert.rejects(useStore.getState().loadConfig('https://config.example/fail.json'), /config offline/)
  assert.equal(useStore.getState().currentDetail, detail)
  assert.equal(useStore.getState().currentPlayback, playback)
  assert.equal(useStore.getState().detailCache, detailCache)
  assert.equal(useStore.getState().loading.config, false)
})
