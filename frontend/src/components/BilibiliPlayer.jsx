import React, { useEffect, useRef } from 'react'
import Hls from 'hls.js'
import Plyr from 'plyr'
import 'plyr/dist/plyr.css'

export default function BilibiliPlayer({ playback, poster, live, startAt = 0, autoPlay = false, onError, onTimeUpdate, onPlaybackState }) {
  const videoRef = useRef(null)
  const callbacks = useRef({ onError, onTimeUpdate, onPlaybackState })
  callbacks.current = { onError, onTimeUpdate, onPlaybackState }

  useEffect(() => {
    const video = videoRef.current
    if (!video || !playback?.url) return
    let destroyed = false
    let reported = false
    let restored = false
    let resumed = false
    let hls
    let dash
    let player
    const fail = (message) => {
      if (destroyed || reported) return
      reported = true
      callbacks.current.onError?.(message)
    }
    const restoreTime = () => {
      if (!restored && !live && startAt > 0 && Number.isFinite(video.duration) && video.duration > 0) {
        restored = true
        video.currentTime = Math.min(startAt, Math.max(0, video.duration - 0.5))
      }
      if (autoPlay && !resumed) {
        resumed = true
        video.play().catch(() => {})
      }
    }
    const handleTime = () => callbacks.current.onTimeUpdate?.(video.currentTime)
    const handleError = () => fail('播放失败，可能是地址过期、网络异常或浏览器不支持该编码')
    const handlePlay = () => callbacks.current.onPlaybackState?.(true)
    const handlePause = () => callbacks.current.onPlaybackState?.(false)
    video.addEventListener('canplay', restoreTime)
    video.addEventListener('timeupdate', handleTime)
    video.addEventListener('error', handleError)
    video.addEventListener('play', handlePlay)
    video.addEventListener('pause', handlePause)

    const initialize = async () => {
      try {
        player = new Plyr(video, {
          controls: ['play-large', 'play', ...(live ? [] : ['progress', 'current-time', 'duration']), 'mute', 'volume', 'settings', 'pip', 'fullscreen'],
          settings: live ? [] : ['speed'],
          speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
          keyboard: { focused: true, global: false },
        })
        if (playback.format === 'dash') {
          if (!window.MediaSource && !window.ManagedMediaSource) {
            fail('当前浏览器不支持 DASH 分轨播放，请使用较新的 Chrome、Edge 或 Safari')
            return
          }
          // Only load the DASH engine when a B站 video actually needs it.
          const library = await import('dashjs')
          if (destroyed) return
          const MediaPlayer = library.MediaPlayer || library.default?.MediaPlayer
          if (!MediaPlayer) throw new Error('DASH engine unavailable')
          dash = MediaPlayer().create()
          dash.updateSettings({ debug: { logLevel: 0 } })
          dash.on(MediaPlayer.events.ERROR, () => fail('DASH 音视频加载失败，请重新解析或检查网络'))
          dash.initialize(video, playback.url, false)
        } else if (playback.format === 'hls' && Hls.isSupported()) {
          hls = new Hls({ enableWorker: true, lowLatencyMode: Boolean(live), backBufferLength: 60 })
          hls.on(Hls.Events.ERROR, (_, data) => {
            if (data.fatal) fail('直播流加载失败，可能已下播或播放地址已过期')
          })
          hls.loadSource(playback.url)
          hls.attachMedia(video)
        } else if (playback.format === 'hls' && !video.canPlayType('application/vnd.apple.mpegurl')) {
          fail('当前浏览器不支持 HLS 直播播放，请使用较新的浏览器')
        } else {
          video.src = playback.url
        }
      } catch {
        fail('播放器初始化失败，请重新解析或更新浏览器')
      }
    }
    void initialize()
    return () => {
      destroyed = true
      video.removeEventListener('canplay', restoreTime)
      video.removeEventListener('timeupdate', handleTime)
      video.removeEventListener('error', handleError)
      video.removeEventListener('play', handlePlay)
      video.removeEventListener('pause', handlePause)
      hls?.destroy()
      dash?.reset()
      player?.destroy()
      video.pause()
      video.removeAttribute('src')
      video.load()
    }
  }, [playback?.url, playback?.format, live, startAt, autoPlay])

  return (
    <div className="bilibili-player">
      <video ref={videoRef} poster={poster || undefined} controls playsInline preload="metadata" />
    </div>
  )
}
