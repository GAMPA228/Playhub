export function originalBilibiliUrl(value) {
  try {
    const url = new URL(value)
    const hosts = ['www.bilibili.com', 'bilibili.com', 'm.bilibili.com', 'live.bilibili.com']
    return url.protocol === 'https:' && hosts.includes(url.hostname) && !url.username && !url.password && !url.port
      ? url.href : ''
  } catch {
    return ''
  }
}

export function bilibiliRefreshOptions(result) {
  return {
    page: result.page,
    ...(result.quality > 0 ? { quality: result.quality } : {}),
  }
}

export function validateBilibiliResult(result) {
  if (result?.ok === false) throw new Error(result.error || 'B站解析失败')
  if (!['video', 'live'].includes(result?.kind) || !originalBilibiliUrl(result.sourceUrl)) {
    throw new Error('B站返回了无效的解析结果')
  }
  if (!['ready', 'offline', 'loop'].includes(result.status)) {
    throw new Error('B站返回了未知的播放状态')
  }
  const playback = result.playback
  let localPlayback = false
  if (typeof playback?.url === 'string') {
    const parsed = new URL(playback.url, 'https://mujian.invalid')
    localPlayback = parsed.origin === 'https://mujian.invalid' && parsed.pathname.startsWith('/api/bilibili/')
  }
  if (playback && (!['dash', 'hls', 'mp4'].includes(playback.format)
    || !localPlayback || !playback.url.startsWith('/api/bilibili/')
    || /[\\\s#]/.test(playback.url))) {
    throw new Error('B站返回了不支持的播放地址或格式')
  }
  if (result.status === 'ready' && !playback) {
    throw new Error('B站未返回可用的播放地址')
  }
  return result
}
