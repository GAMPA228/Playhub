import { create } from 'zustand'
import * as api from '../api'
import {
  normalizeList, normalizeClasses, normalizeVideoCard, normalizeDetail,
  resolvePlaybackUrl, mergeVideosByName, classIdOf,
} from '../utils'

const HISTORY_KEY = 'playhub:history'
const SETTINGS_KEY = 'playhub:settings'
const DEFAULT_SETTINGS = {
  theme: 'light', searchScope: 'all', liveSourceUrl: '', liveEpgUrl: '',
  aiEnabled: false, aiUrl: '', aiKey: '', aiModel: '',
}
const CONFIG_HISTORY_KEY = 'playhub:config-history'
const SEARCH_HISTORY_KEY = 'playhub:search-history'
const MAX_HISTORY = 120
const MAX_SEARCH_HISTORY = 20
const groupLinesCache = new Map()
let activeSearch = null
let bootstrapPromise = null

function localPreferences(settings) {
  if (!settings || typeof settings !== 'object') return {}
  return Object.fromEntries(Object.keys(DEFAULT_SETTINGS)
    .filter((key) => !key.startsWith('live') && Object.hasOwn(settings, key))
    .map((key) => [key, settings[key]]))
}

function legacySources() {
  const settings = loadLS(SETTINGS_KEY, {}) || {}
  const patch = {}
  const url = localStorage.getItem('playhub:configUrl') || ''
  const history = loadLS(CONFIG_HISTORY_KEY, [])
  if (url) patch.configUrl = url
  if (Array.isArray(history) && history.length) patch.configHistory = history.slice(0, 10)
  for (const key of ['liveSourceUrl', 'liveEpgUrl']) {
    if (settings[key]) patch[key] = settings[key]
  }
  return Object.keys(patch).length ? patch : null
}

function clearLegacySources(fields) {
  try {
    if (fields.includes('configUrl')) localStorage.removeItem('playhub:configUrl')
    if (fields.includes('configHistory')) localStorage.removeItem(CONFIG_HISTORY_KEY)
    const settings = loadLS(SETTINGS_KEY, {}) || {}
    for (const key of ['liveSourceUrl', 'liveEpgUrl']) {
      if (fields.includes(key)) delete settings[key]
    }
    saveLS(SETTINGS_KEY, settings)
  } catch {}
}

function emptyVideoState() {
  groupLinesCache.clear()
  return {
    config: null, sources: [], selectedSourceUid: '', classes: [], activeClassId: '',
    wallVideos: [], wallMode: 'home', wallTitle: '',
    wallPagination: { page: 1, pageCount: 1, total: 0 },
    searchKeyword: '', searchCache: null, detailCache: {}, currentDetail: null, currentPlayback: null,
    history: [], _configRun: null, _wallRun: null, _videoContext: {},
  }
}

async function cancelRemoteSearch(run) {
  if (!run.progressId || run.cancelSent) return
  run.cancelSent = true
  try {
    await api.cancelSearch(run.progressId, { silentError: true, timeout: 5000 })
  } catch (err) {
    console.warn('停止后台搜索失败:', err.message)
  }
}

function waitForSearchPoll(signal) {
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    const timer = setTimeout(finish, 500)
    signal.addEventListener('abort', finish, { once: true })
    if (signal.aborted) finish()
  })
}

function groupKey(entry) {
  if (!entry.isGroup) return `${entry.sourceUid}::${entry.vodId}`
  const sources = entry.sources || []
  const sourceHash = sources.map((s) => s.uid || s.key || '').sort().join(',')
  return `group::${entry.title}::${sourceHash}`
}

function configHistoryKey(url) {
  const raw = String(url || '').trim()
  if (!raw) return HISTORY_KEY
  let h = 0
  for (let i = 0; i < raw.length; i++) {
    h = ((h << 5) - h + raw.charCodeAt(i)) | 0
  }
  return `${HISTORY_KEY}:${Math.abs(h).toString(36)}`
}

function loadLS(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

function saveLS(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch {}
}

async function aiMergeVideos(videos, settings, keyword, signal) {
  const names = [...new Set(videos.map((v) => v.name).filter(Boolean))]
  if (!names.length) return null

  const prompt = `你是视频分析助手。用户搜索了"${keyword}"。

请完成以下任务：
1. 分析下面的视频列表，将"同一部影片"的不同源合并（名字相似有细微差异如"流浪地球2"和"流浪地球2 4K"）
2. 注意区分：vlog、演唱会、复盘、番外、特别篇、续集、不同季数 不算同一部影片
3. 只有正片内容才算同一部，如"流浪地球2"和"流浪地球2 高清版"算同一部，但"流浪地球2"和"流浪地球2番外"不算
4. 从所有合并后的影片中，选出"一个"最符合用户搜索"${keyword}"的影片
5. 没有相似名称的视频映射到自己

只输出JSON：{"mapping": {"原名": "标准名", ...}, "best": "最符合的标准名"}

视频列表：
${names.map((n, i) => `${i + 1}. ${n}`).join('\n')}`

  try {
    const res = await fetch(`${settings.aiUrl}/v1/chat/completions`, {
      method: 'POST',
      signal,
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${settings.aiKey}` },
      body: JSON.stringify({ model: settings.aiModel || 'gpt-4o-mini', messages: [{ role: 'user', content: prompt }], temperature: 0.1 }),
    })
    const data = await res.json()
    const content = data.choices?.[0]?.message?.content || ''
    const jsonMatch = content.match(/\{[\s\S]*\}/)
    if (!jsonMatch) return null
    const parsed = JSON.parse(jsonMatch[0])
    return { mapping: parsed.mapping || parsed, best: parsed.best || '' }
  } catch (err) {
    if (signal?.aborted) return null
    console.error('AI merge error:', err)
    return null
  }
}

async function aiMergeDetails(details, settings) {
  const validDetails = details.filter((d) => d && (d.desc || d.director || d.actor))
  if (validDetails.length === 0) return null
  if (validDetails.length === 1) return validDetails[0]

  const prompt = `你是视频信息整合助手。下面是同一部影片从不同源获取的详细信息，请整合成一个最完整的版本。

规则：
1. 保留所有有用的信息（导演、演员、简介、类型、年份、地区等）
2. 如果不同源的信息有冲突，选择最详细、最准确的那个
3. 简介要完整，不要截断
4. 只输出JSON格式

输出格式：
{"name": "影片名", "desc": "完整简介", "director": "导演", "actor": "演员", "type": "类型", "year": "年份", "area": "地区"}

源信息：
${validDetails.map((d, i) => `源${i + 1}: ${JSON.stringify({ name: d.name, desc: d.desc?.substring(0, 200), director: d.director, actor: d.actor, type: d.type, year: d.year, area: d.area })}`).join('\n')}`

  try {
    const res = await fetch(`${settings.aiUrl}/v1/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${settings.aiKey}` },
      body: JSON.stringify({ model: settings.aiModel || 'gpt-4o-mini', messages: [{ role: 'user', content: prompt }], temperature: 0.1 }),
    })
    const data = await res.json()
    const content = data.choices?.[0]?.message?.content || ''
    const jsonMatch = content.match(/\{[\s\S]*\}/)
    if (!jsonMatch) return validDetails[0]
    const parsed = JSON.parse(jsonMatch[0])
    return { ...validDetails[0], ...parsed }
  } catch {
    return validDetails[0]
  }
}

export const useStore = create((set, get) => ({
  configUrl: '',
  config: null,
  configHistory: [],
  bootstrapped: false,
  bootstrapping: false,
  sourceSettingsLoaded: false,
  sourceSettingsError: '',
  sourceSaving: false,
  legacySourceSettings: legacySources(),
  _configRun: null,
  _wallRun: null,
  _liveRun: null,
  _videoContext: {},

  sources: [],
  selectedSourceUid: '',

  classes: [],
  activeClassId: '',

  wallVideos: [],
  wallMode: 'home',
  wallTitle: '',
  wallPagination: { page: 1, pageCount: 1, total: 0 },
  searchKeyword: '',
  searchProgress: { active: false, total: 0, completed: 0, failed: 0, cancelled: false, error: '', aiProcessing: false },
  searchCache: null,
  searchHistory: loadLS(SEARCH_HISTORY_KEY, []),

  currentDetail: null,
  detailCache: {},

  currentPlayback: null,

  history: [],

  liveGroups: [],
  liveChannels: [],
  currentLiveChannelId: '',
  currentLiveSourceIndex: 0,
  liveSummary: null,

  settings: { ...DEFAULT_SETTINGS, ...localPreferences(loadLS(SETTINGS_KEY, {})) },

  loading: { config: false, wall: false, detail: false, player: false },

  getSelectedSource() {
    return get().sources.find((x) => x.uid === get().selectedSourceUid) || get().sources[0] || null
  },

  bootstrap({ force = false } = {}) {
    if (get().bootstrapping) return bootstrapPromise
    if (!force && get().bootstrapped) return Promise.resolve()
    if (get().sourceSaving || get().loading.config) return Promise.resolve()
    set((s) => ({
      bootstrapping: true, sourceSettingsError: '', legacySourceSettings: legacySources(),
      settings: { ...s.settings, ...localPreferences(loadLS(SETTINGS_KEY, {})) },
    }))
    bootstrapPromise = (async () => {
      try {
        const saved = await api.getSourceSettings({ silentError: true })
        get().applySourceSettings(saved)
        set({ selectedSourceUid: localStorage.getItem('playhub:selectedSourceUid') || '' })
        if (saved.configUrl) await get().loadConfig(saved.configUrl, { persist: false })
      } catch (err) {
        set({ sourceSettingsError: err.message || '服务端数据源配置加载失败' })
      } finally {
        set({ bootstrapping: false, bootstrapped: true })
        bootstrapPromise = null
      }
    })()
    return bootstrapPromise
  },

  applySourceSettings(saved) {
    const current = get()
    const videoChanged = saved.configUrl !== current.configUrl
    const liveChanged = saved.liveSourceUrl !== current.settings.liveSourceUrl || saved.liveEpgUrl !== current.settings.liveEpgUrl
    if (videoChanged) current.stopSearch()
    set((s) => ({
      ...(videoChanged ? emptyVideoState() : {}),
      ...(liveChanged ? { liveGroups: [], liveChannels: [], liveSummary: null, currentLiveChannelId: '', currentLiveSourceIndex: 0, _liveRun: null } : {}),
      configUrl: saved.configUrl || '', configHistory: saved.configHistory || [],
      settings: { ...s.settings, liveSourceUrl: saved.liveSourceUrl || '', liveEpgUrl: saved.liveEpgUrl || '' },
      sourceSettingsLoaded: true, sourceSettingsError: '',
      loading: videoChanged ? { ...s.loading, config: false, wall: false, detail: false, player: false } : s.loading,
    }))
  },

  async saveSourceSettings(patch) {
    if (get().sourceSaving || get().bootstrapping || get().loading.config) throw new Error('数据源操作正在进行，请稍候')
    const shared = Object.fromEntries(['configUrl', 'configHistory', 'liveSourceUrl', 'liveEpgUrl']
      .filter((key) => Object.hasOwn(patch, key)).map((key) => [key, patch[key]]))
    if (!Object.keys(shared).length) return
    set({ sourceSaving: true })
    try {
      const saved = await api.saveSourceSettings(shared, { silentError: true })
      get().applySourceSettings(saved)
      clearLegacySources(Object.keys(shared))
      set({ legacySourceSettings: legacySources() })
      return saved
    } finally {
      set({ sourceSaving: false })
    }
  },

  async loadConfig(url, { persist = true } = {}) {
    if (get().sourceSaving || get().loading.config || (persist && get().bootstrapping)) throw new Error('数据源操作正在进行，请稍候')
    url = url.trim()
    get().stopSearch()
    const run = {}
    set((s) => ({ _configRun: run, loading: { ...s.loading, config: true } }))
    try {
      const res = await api.loadConfig(url, { persist, config: { silentError: true } })
      if (get()._configRun !== run) return
      if (res.sourceSettings) get().applySourceSettings(res.sourceSettings)
      if (!persist && res.sourceSettings && res.sourceSettings.configUrl !== url) throw new Error('服务器数据源已更新，请重新同步')
      const cfg = res.config || res
      const sites = (cfg.sites || []).filter((s) => s.searchable !== 0)
      const classes = normalizeClasses(cfg)

      const skipApis = new Set(['csp_Config', 'csp_LocalFile', 'csp_YGP', 'csp_Push', 'csp_Market', 'csp_Douban'])
      const prevUid = get().selectedSourceUid
      let selectedUid = ''
      if (prevUid) {
        const prev = sites.find((s) => s.uid === prevUid)
        if (prev && !skipApis.has(prev.api)) selectedUid = prevUid
      }
      if (!selectedUid) {
        const rec = sites.find((s) => s.searchable !== 0 && !skipApis.has(s.api)) || sites[0]
        selectedUid = rec?.uid || ''
      }

      groupLinesCache.clear()
      set({
        configUrl: url, config: cfg, sources: sites, selectedSourceUid: selectedUid,
        classes,
        activeClassId: '', searchCache: null, bootstrapped: true,
        detailCache: {}, currentDetail: null, currentPlayback: null,
        history: loadLS(configHistoryKey(url), []),
        wallVideos: [], wallMode: 'home', wallTitle: '', _configRun: run, _videoContext: {},
        loading: { ...get().loading, config: true, detail: false, player: false },
      })
      localStorage.setItem('playhub:selectedSourceUid', selectedUid)
      if (persist) {
        clearLegacySources(['configUrl', 'configHistory'])
        set({ legacySourceSettings: legacySources() })
      }

      if (selectedUid) await get().loadHome()
    } catch (err) {
      console.error('loadConfig error:', err)
      throw err
    } finally {
      if (get()._configRun === run) set((s) => ({ _configRun: null, loading: { ...s.loading, config: false } }))
    }
  },

  async clearVideoConfig() {
    await get().saveSourceSettings({ configUrl: '', configHistory: [] })
    get().stopSearch()
    set((s) => ({ ...emptyVideoState(), loading: { ...s.loading, wall: false, detail: false, player: false } }))
  },

  async removeConfigHistory(url) {
    const list = get().configHistory.filter((u) => u !== url)
    await get().saveSourceSettings({ configHistory: list })
  },

  async importSettings(data) {
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('配置数据格式不正确')
    const patch = {}
    for (const key of ['configUrl', 'configHistory']) {
      if (Object.hasOwn(data, key)) patch[key] = data[key]
    }
    for (const key of ['liveSourceUrl', 'liveEpgUrl']) {
      if (data.settings && Object.hasOwn(data.settings, key)) patch[key] = data.settings[key]
    }
    await get().saveSourceSettings(patch)
    if (data.settings) get().updateSettings(data.settings)
    if (Array.isArray(data.searchHistory)) {
      const history = [...new Set(data.searchHistory.filter((item) => typeof item === 'string' && item.trim()))].slice(0, MAX_SEARCH_HISTORY)
      set({ searchHistory: history })
      saveLS(SEARCH_HISTORY_KEY, history)
    }
    if (patch.configUrl) {
      try {
        await get().loadConfig(get().configUrl, { persist: false })
      } catch (err) {
        throw new Error('配置已保存到服务器，但视频源加载失败：' + err.message)
      }
    }
  },

  async migrateLegacySources() {
    const legacy = legacySources()
    if (!legacy) return
    await get().importSettings({
      ...(Object.hasOwn(legacy, 'configUrl') ? { configUrl: legacy.configUrl } : {}),
      ...(legacy.configHistory ? { configHistory: legacy.configHistory } : {}),
      settings: Object.fromEntries(['liveSourceUrl', 'liveEpgUrl'].filter((key) => Object.hasOwn(legacy, key)).map((key) => [key, legacy[key]])),
    })
  },

  addSearchHistory(keyword) {
    const list = get().searchHistory.filter((k) => k !== keyword)
    list.unshift(keyword)
    const trimmed = list.slice(0, MAX_SEARCH_HISTORY)
    set({ searchHistory: trimmed })
    saveLS(SEARCH_HISTORY_KEY, trimmed)
  },

  removeSearchHistory(keyword) {
    const list = get().searchHistory.filter((k) => k !== keyword)
    set({ searchHistory: list })
    saveLS(SEARCH_HISTORY_KEY, list)
  },

  clearSearchHistory() {
    set({ searchHistory: [] })
    saveLS(SEARCH_HISTORY_KEY, [])
  },

  async selectSource(uid) {
    const { loading, searchProgress, sources, selectedSourceUid } = get()
    if (loading.wall || loading.config || searchProgress.active || searchProgress.aiProcessing) return
    if (uid === selectedSourceUid || !sources.some((source) => source.uid === uid)) return

    set({
      selectedSourceUid: uid, searchCache: null, searchKeyword: '',
      wallVideos: [], wallMode: 'home', classes: [], activeClassId: '',
      wallPagination: { page: 1, pageCount: 1, total: 0 },
    })
    localStorage.setItem('playhub:selectedSourceUid', uid)
    await get().loadHome()
  },

  async loadHome() {
    get().stopSearch()
    const source = get().getSelectedSource()
    if (!source) {
      set({ wallVideos: [], loading: { ...get().loading, wall: false } })
      return
    }
    const run = {}
    const config = get().config
    const isCurrent = () => get()._wallRun === run && get().config === config
    set((s) => ({ _wallRun: run, loading: { ...s.loading, wall: true }, wallMode: 'home', wallTitle: source.name, searchCache: null }))
    try {
      const payload = await api.sourceHome(source.uid)
      if (!isCurrent()) return
      const classes = normalizeClasses(payload)
      if (classes.length) set({ classes })
      const list = normalizeList(payload).map((v) => normalizeVideoCard(v, source))
      set({ wallVideos: list, wallPagination: { page: 1, pageCount: 1, total: list.length } })
      if (!list.length && classes.length) await get().loadCategory(classIdOf(classes[0]))
    } catch (err) {
      console.error('loadHome error:', err)
      if (isCurrent()) set({ wallVideos: [] })
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, wall: false } }))
    }
  },

  async loadCategory(tid, page = 1) {
    const source = get().getSelectedSource()
    if (!source) return
    const run = {}
    const config = get().config
    const isCurrent = () => get()._wallRun === run && get().config === config
    set((s) => ({ _wallRun: run, loading: { ...s.loading, wall: true }, wallMode: 'category', activeClassId: tid, wallTitle: get().classes.find((c) => classIdOf(c) === tid)?.type_name || tid }))
    try {
      const payload = await api.sourceCategory(source.uid, tid, page)
      if (!isCurrent()) return
      const list = normalizeList(payload).map((v) => normalizeVideoCard(v, source))
      set({
        wallVideos: page === 1 ? list : [...get().wallVideos, ...list],
        wallPagination: { page: Number(payload.page || payload.pg || 1), pageCount: Number(payload.pagecount || payload.pageCount || 1), total: Number(payload.total || list.length) },
      })
    } catch (err) {
      console.error('loadCategory error:', err)
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, wall: false } }))
    }
  },

  stopSearch() {
    const run = activeSearch
    if (!run) return
    activeSearch = null
    run.controller.abort()
    void cancelRemoteSearch(run)
    set((s) => ({
      searchProgress: { ...s.searchProgress, active: false, aiProcessing: false, cancelled: true },
      searchCache: { keyword: s.searchKeyword, videos: s.wallVideos },
      loading: { ...s.loading, wall: false },
    }))
  },

  async searchVideos(keyword) {
    if (!keyword?.trim()) return
    get().stopSearch()
    const kw = keyword.trim()
    const { settings, sources } = get()
    const source = settings.searchScope === 'current' ? get().getSelectedSource() : null
    const total = settings.searchScope === 'current' ? (source ? 1 : 0) : sources.filter((s) => s.searchable !== 0).length
    const useAI = settings.aiEnabled && settings.aiUrl && settings.aiKey
    const run = { controller: new AbortController(), progressId: '', cancelSent: false }
    activeSearch = run
    const isCurrent = () => activeSearch === run && !run.controller.signal.aborted
    get().addSearchHistory(kw)
    set((s) => ({
      wallMode: 'search', searchKeyword: kw, wallTitle: `搜索: ${kw}`,
      wallVideos: [], searchCache: null,
      searchProgress: { active: true, total, completed: 0, failed: 0, cancelled: false, error: '', aiProcessing: false },
      loading: { ...s.loading, wall: true },
    }))

    try {
      if (settings.searchScope === 'current' && !source) throw new Error('请先选择视频源')
      // Keep the start response readable after stopping so a late job ID can be cancelled.
      const response = await api.searchAll(kw, false, {
        silentError: true,
        timeout: 15000,
        params: source ? { key: source.uid } : {},
      })
      run.progressId = response.progressId
      if (!isCurrent()) {
        await cancelRemoteSearch(run)
        return
      }
      if (!run.progressId) throw new Error('搜索任务未成功创建')

      let failures = 0
      while (isCurrent()) {
        let progress
        try {
          progress = await api.getProgress(run.progressId, { signal: run.controller.signal, silentError: true, timeout: 5000 })
          failures = 0
        } catch (err) {
          if (!isCurrent()) return
          failures++
          if (failures >= 3) throw new Error(`获取搜索进度失败：${err.message || '网络错误'}`)
          await waitForSearchPoll(run.controller.signal)
          continue
        }
        if (!isCurrent()) return
        if (!progress.found) throw new Error('搜索任务已失效，请重新搜索')
        const cards = (progress.results || []).map((v) => normalizeVideoCard(v, {
          uid: v.source_uid || v.key,
          name: v.source_name || v.name,
        }))
        set((s) => ({
          wallVideos: cards,
          searchProgress: {
            ...s.searchProgress,
            total: progress.total, completed: progress.completed, failed: progress.failed || 0,
            cancelled: Boolean(progress.cancelled), error: progress.error || '',
          },
        }))
        if (progress.error) throw new Error(progress.error)
        if (progress.done || progress.cancelled) break
        await waitForSearchPoll(run.controller.signal)
      }
      if (!isCurrent()) return
      if (useAI && !get().searchProgress.cancelled) {
        set((s) => ({ searchProgress: { ...s.searchProgress, aiProcessing: true } }))
        const aiResult = await aiMergeVideos(get().wallVideos, settings, kw, run.controller.signal)
        if (!isCurrent()) return
        if (aiResult) {
          const videos = mergeVideosByName(get().wallVideos, aiResult.mapping)
          const best = videos.find((v) => v.name === aiResult.best)
          if (best) best.isBestMatch = true
          set({ wallVideos: videos })
        }
      }
    } catch (err) {
      if (!isCurrent()) return
      set((s) => ({ searchProgress: { ...s.searchProgress, error: err.message || '搜索失败' } }))
      void cancelRemoteSearch(run)
    } finally {
      if (isCurrent()) {
        activeSearch = null
        set((s) => ({
          searchCache: { keyword: kw, videos: s.wallVideos },
          searchProgress: { ...s.searchProgress, active: false, aiProcessing: false },
          loading: { ...s.loading, wall: false },
        }))
      }
    }
  },

  async ensureDetail(sourceUid, vodId, options = {}) {
    const context = get()._videoContext
    const isCurrent = () => get()._videoContext === context
    const cacheKey = `${sourceUid}::${vodId}`
    if (!options.force && get().detailCache[cacheKey]) {
      set({ currentDetail: get().detailCache[cacheKey] })
      return get().detailCache[cacheKey]
    }
    set((s) => ({ loading: { ...s.loading, detail: true } }))
    try {
      const payload = await api.sourceDetail(sourceUid, vodId)
      if (!isCurrent()) return null
      const source = get().sources.find((s) => s.uid === sourceUid)
      const detail = normalizeDetail(payload, source)
      if (!detail) throw new Error('详情数据为空')
      set((s) => ({ currentDetail: detail, detailCache: { ...s.detailCache, [cacheKey]: detail } }))
      return detail
    } catch (err) {
      if (!isCurrent()) return null
      console.error('ensureDetail error:', err)
      return null
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, detail: false } }))
    }
  },

  async ensureGroupDetail(sources) {
    const context = get()._videoContext
    const isCurrent = () => get()._videoContext === context
    set((s) => ({ loading: { ...s.loading, detail: true } }))
    try {
      for (const src of sources) {
        try {
          const payload = await api.sourceDetail(src.uid, src.vodId)
          if (!isCurrent()) return null
          const source = get().sources.find((s) => s.uid === src.uid) || src
          const detail = normalizeDetail(payload, source)
          if (detail && detail.playGroups?.length > 0) {
            set((s) => ({ currentDetail: detail }))
            return detail
          }
        } catch {
          if (!isCurrent()) return null
          continue
        }
      }
      return null
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, detail: false } }))
    }
  },

  async ensureGroupLines(sources, onProgress, onAiProcessing) {
    const context = get()._videoContext
    const isCurrent = () => get()._videoContext === context
    const discarded = () => ({ lines: [], detail: null, time: Date.now() })
    const cacheKey = sources.map((s) => `${s.uid}:${s.vodId}`).sort().join('|')
    const cached = groupLinesCache.get(cacheKey)
    if (cached && Date.now() - cached.time < 86400000) return cached

    const allLines = []
    const allDetails = []
    const total = sources.length

    onProgress?.(0, total)

    try {
      const items = sources.map((src) => ({ key: src.uid, id: src.vodId }))
      const response = await api.batchDetail(items)
      if (!isCurrent()) return discarded()
      const progressId = response.progressId

      if (progressId) {
        // Poll progress until done
        const results = await new Promise((resolve, reject) => {
          const poll = async () => {
            if (!isCurrent()) { resolve([]); return }
            try {
              const progress = await api.getProgress(progressId)
              if (!isCurrent()) { resolve([]); return }
              if (progress.found) {
                onProgress?.(progress.completed, progress.total)
                if (progress.done) {
                  resolve(progress.results || [])
                } else {
                  setTimeout(poll, 300)
                }
              } else {
                setTimeout(poll, 300)
              }
            } catch (err) {
              reject(err)
            }
          }
          poll()
        })
        if (!isCurrent()) return discarded()

        // Process results
        for (const r of results) {
          if (r.detail) {
            const source = get().sources.find((s) => s.uid === r.key) || { uid: r.key, name: r.key }
            const detail = normalizeDetail(r.detail, source)
            if (detail?.playGroups?.length) {
              allDetails.push(detail)
              for (const group of detail.playGroups) {
                allLines.push({
                  sourceUid: r.key,
                  sourceName: source.name,
                  groupName: group.flag,
                  displayName: `${source.name}-${group.flag}`,
                  episodes: group.episodes || [],
                })
              }
            }
          }
        }
      } else {
        // No progressId, use results directly
        const results = response.results || response
        for (let i = 0; i < results.length; i++) {
          const r = results[i]
          if (r.detail) {
            const source = get().sources.find((s) => s.uid === r.key) || { uid: r.key, name: r.key }
            const detail = normalizeDetail(r.detail, source)
            if (detail?.playGroups?.length) {
              allDetails.push(detail)
              for (const group of detail.playGroups) {
                allLines.push({
                  sourceUid: r.key,
                  sourceName: source.name,
                  groupName: group.flag,
                  displayName: `${source.name}-${group.flag}`,
                  episodes: group.episodes || [],
                })
              }
            }
          }
          onProgress?.(i + 1, total)
        }
      }
    } catch (err) {
      if (!isCurrent()) return discarded()
      console.error('batchDetail error, falling back:', err)
      let done = 0
      for (const src of sources) {
        try {
          const payload = await api.sourceDetail(src.uid, src.vodId)
          if (!isCurrent()) return discarded()
          const source = get().sources.find((s) => s.uid === src.uid) || src
          const detail = normalizeDetail(payload, source)
          if (detail?.playGroups?.length) {
            allDetails.push(detail)
            for (const group of detail.playGroups) {
              allLines.push({
                sourceUid: src.uid,
                sourceName: src.name,
                groupName: group.flag,
                displayName: `${src.name}-${group.flag}`,
                episodes: group.episodes || [],
              })
            }
          }
        } catch {
          if (!isCurrent()) return discarded()
        }
        done++
        onProgress?.(done, total)
      }
    }

    onProgress?.(total, total)

    let bestDetail = allDetails.find((d) => d.desc && d.director && d.actor) || allDetails[0] || null

    const { settings } = get()
    if (settings.aiEnabled && settings.aiUrl && settings.aiKey && allDetails.length > 1) {
      onAiProcessing?.(true)
      const merged = await aiMergeDetails(allDetails, settings)
      if (!isCurrent()) return discarded()
      if (merged) bestDetail = merged
      onAiProcessing?.(false)
    }

    const result = { lines: allLines, detail: bestDetail, time: Date.now() }
    groupLinesCache.set(cacheKey, result)
    return result
  },

  async playEpisode(sourceUid, vodId, flagName, episodeIndex) {
    const context = get()._videoContext
    const isCurrent = () => get()._videoContext === context
    const detail = get().currentDetail
    if (!detail) return null
    set((s) => ({ loading: { ...s.loading, player: true } }))
    try {
      const group = detail.playGroups.find((g) => g.flag === flagName)
      if (!group) return null
      const episode = group.episodes[episodeIndex]
      if (!episode) return null
      const episodeId = episode.id || episode.url
      const playUrl = await resolvePlaybackUrl(() => api.sourcePlay(sourceUid, flagName, episodeId), episodeId)
      if (!isCurrent()) return null
      const playback = { sourceUid, vodId, title: detail.name, poster: detail.pic, flagName, episodeIndex, episodeName: episode.name, url: playUrl }
      set({ currentPlayback: playback })
      get().upsertHistory(playback)
      return playback
    } catch (err) {
      if (!isCurrent()) return null
      console.error('playEpisode error:', err)
      throw err
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, player: false } }))
    }
  },

  async autoSelectAndPlay(sources, vodId, flagName, episodeIndex) {
    const context = get()._videoContext
    const isCurrent = () => get()._videoContext === context
    set((s) => ({ loading: { ...s.loading, player: true } }))
    let lastError = null
    try {
      for (const src of sources) {
        try {
          const uid = src.uid || src.key
          const payload = await api.sourceDetail(uid, vodId)
          if (!isCurrent()) return null
          const source = get().sources.find((s) => s.uid === uid) || src
          const detail = normalizeDetail(payload, source)
          if (!detail) continue
          const group = detail.playGroups.find((g) => g.flag === flagName) || detail.playGroups[0]
          if (!group?.episodes?.length) continue
          const epIdx = Math.min(episodeIndex || 0, group.episodes.length - 1)
          const episode = group.episodes[epIdx]
          const episodeId = episode.id || episode.url
          const playUrl = await resolvePlaybackUrl(() => api.sourcePlay(uid, group.flag, episodeId), episodeId)
          if (!isCurrent()) return null
          const playback = { sourceUid: uid, vodId, title: detail.name, poster: detail.pic, flagName: group.flag, episodeIndex: epIdx, episodeName: episode.name, url: playUrl, detail }
          set({ currentPlayback: playback, currentDetail: detail })
          get().upsertHistory(playback)
          return playback
        } catch (err) {
          if (!isCurrent()) return null
          lastError = err
        }
      }
      throw lastError || new Error('没有可用的播放源')
    } finally {
      if (isCurrent()) set((s) => ({ loading: { ...s.loading, player: false } }))
    }
  },

  upsertHistory(entry) {
    const historyEntry = {
      ...entry,
      timestamp: Date.now(),
      sources: entry.sources || undefined,
      isGroup: entry.isGroup || undefined,
      lineIndex: entry.lineIndex ?? 0,
      episodeIndex: entry.episodeIndex ?? 0,
      currentTime: entry.currentTime ?? 0,
    }

    const key = groupKey(entry)
    const list = get().history.filter((h) => groupKey(h) !== key)
    list.unshift(historyEntry)
    const trimmed = list.slice(0, MAX_HISTORY)
    set({ history: trimmed })
    saveLS(configHistoryKey(get().configUrl), trimmed)
  },

  updateHistoryEntry(patch) {
    const key = groupKey(patch)
    const list = get().history.map((h) => {
      if (groupKey(h) === key) {
        return { ...h, ...patch, timestamp: Date.now() }
      }
      return h
    })
    set({ history: list })
    saveLS(configHistoryKey(get().configUrl), list)
  },

  updateHistoryTime(sourceUid, vodId, currentTime, isGroup, title) {
    const list = get().history.map((h) => {
      let match = false
      if (isGroup && h.isGroup) {
        match = h.title === title
      } else if (!isGroup && !h.isGroup) {
        match = h.sourceUid === sourceUid && h.vodId === vodId
      }
      if (match) {
        return { ...h, currentTime: Math.floor(currentTime), timestamp: Date.now() }
      }
      return h
    })
    set({ history: list })
    saveLS(configHistoryKey(get().configUrl), list)
  },

  getHistoryEntry(sourceUid, vodId, isGroup, title) {
    return get().history.find((h) => {
      if (isGroup && h.isGroup) return h.title === title
      if (!isGroup && !h.isGroup) return h.sourceUid === sourceUid && h.vodId === vodId
      return false
    })
  },

  clearHistory() {
    set({ history: [] })
    saveLS(configHistoryKey(get().configUrl), [])
  },

  async loadLiveData(playlistUrl, epgUrl) {
    if (!playlistUrl) return
    const run = {}
    set({ _liveRun: run })
    try {
      const res = await api.liveBootstrap(playlistUrl, epgUrl)
      if (get()._liveRun !== run || get().settings.liveSourceUrl !== playlistUrl || get().settings.liveEpgUrl !== (epgUrl || '')) return
      const rawChannels = res.channels || []
      const channelMap = new Map()
      for (const ch of rawChannels) {
        const name = String(ch.name || '').trim()
        if (!name) continue
        if (!channelMap.has(name)) {
          channelMap.set(name, { id: ch.id || name, name, group: ch.group || '未分组', logo: ch.logo || '', sources: [], programmes: [] })
        }
        const entry = channelMap.get(name)
        if (ch.url) entry.sources.push({ url: ch.url, label: `源${entry.sources.length + 1}` })
        if (ch.programmes?.length) entry.programmes = ch.programmes
      }
      const channels = [...channelMap.values()]
      const groupMap = new Map()
      for (const ch of channels) { groupMap.set(ch.group, (groupMap.get(ch.group) || 0) + 1) }
      const groups = [...groupMap.entries()].map(([name, count]) => ({ name, count }))
      set({ liveGroups: groups, liveChannels: channels, liveSummary: res.summary, currentLiveChannelId: channels[0]?.id || '', currentLiveSourceIndex: 0 })
    } catch (err) {
      if (get()._liveRun !== run) return
      console.error('loadLiveData error:', err)
      throw err
    }
  },

  selectLiveChannel(id) { set({ currentLiveChannelId: id, currentLiveSourceIndex: 0 }) },
  selectLiveSource(index) { set({ currentLiveSourceIndex: index }) },

  selectNextLiveSource() {
    const { currentLiveChannelId, currentLiveSourceIndex, liveChannels } = get()
    const ch = liveChannels.find((c) => c.id === currentLiveChannelId)
    if (!ch) return false
    const next = currentLiveSourceIndex + 1
    if (next >= ch.sources.length) return false
    set({ currentLiveSourceIndex: next })
    return true
  },

  updateSettings(patch) {
    const preferences = localPreferences(patch)
    const next = { ...get().settings, ...preferences }
    set({ settings: next })
    // Preserve old, un-migrated fields only; never copy shared URLs or AI keys to the server.
    saveLS(SETTINGS_KEY, { ...loadLS(SETTINGS_KEY, {}), ...localPreferences(next) })
  },
}))
