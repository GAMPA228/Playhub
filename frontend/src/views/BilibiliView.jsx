import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Empty, Input, Select, Space, Spin, Tag, Typography } from 'antd'
import { LinkOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { resolveBilibili } from '../api'
import { isAbortError } from '../api/client'
import { bilibiliRefreshOptions, originalBilibiliUrl, validateBilibiliResult } from '../utils/bilibili'
import BilibiliPlayer from '../components/BilibiliPlayer'

const { Title, Text } = Typography

export default function BilibiliView() {
  const [input, setInput] = useState('')
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [playbackError, setPlaybackError] = useState('')
  const [startAt, setStartAt] = useState(0)
  const [resumePlay, setResumePlay] = useState(false)
  const requestRef = useRef(null)
  const sequence = useRef(0)
  const resultRef = useRef(null)
  const position = useRef(0)
  const wasPlaying = useRef(false)
  const refreshed = useRef(false)

  const load = useCallback(async (text, options = {}, { resume = false, automatic = false } = {}) => {
    const target = text.trim()
    if (!target) { setError('请输入 B站视频链接、BV/av 号或直播间地址'); return }
    requestRef.current?.abort()
    const currentSequence = ++sequence.current
    const controller = new AbortController()
    requestRef.current = controller
    if (!automatic) refreshed.current = false
    if (!resume) {
      position.current = 0
      wasPlaying.current = false
      resultRef.current = null
      setResult(null)
    }
    setLoading(true)
    setError('')
    setPlaybackError('')
    try {
      const response = validateBilibiliResult(await resolveBilibili({ input: target, ...options }, {
        signal: controller.signal, silentError: true,
      }))
      if (sequence.current !== currentSequence || controller.signal.aborted) return
      setStartAt(resume ? position.current : 0)
      setResumePlay(resume && wasPlaying.current)
      resultRef.current = response
      setResult(response)
    } catch (err) {
      if (sequence.current !== currentSequence || controller.signal.aborted || isAbortError(err)) return
      setError(err.message || '解析失败，请稍后重试')
      if (automatic) setPlaybackError('自动刷新失败，请手动重新解析')
    } finally {
      if (sequence.current === currentSequence) {
        requestRef.current = null
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => () => {
    sequence.current++
    requestRef.current?.abort()
  }, [])

  const refresh = useCallback((automatic = false) => {
    const current = resultRef.current
    if (!current || requestRef.current) return
    void load(current.sourceUrl, bilibiliRefreshOptions(current), { resume: true, automatic })
  }, [load])

  const handlePlaybackError = useCallback((text) => {
    if (requestRef.current) return
    if (!refreshed.current && resultRef.current?.playback) {
      refreshed.current = true
      refresh(true)
    } else {
      setPlaybackError(text)
    }
  }, [refresh])

  const changeInput = (value) => {
    setInput(value)
    sequence.current++
    requestRef.current?.abort()
    requestRef.current = null
    setLoading(false)
    setError('')
    setPlaybackError('')
    resultRef.current = null
    setResult(null)
  }

  return (
    <div className="bilibili-page">
      <section className="bilibili-panel" aria-labelledby="bilibili-heading">
        <Title id="bilibili-heading" level={3} style={{ margin: '0 0 8px' }}><LinkOutlined /> 视频解析</Title>
        <Text type="secondary">先支持 B站普通视频和直播间，直接粘贴链接即可，无需配置影视源。</Text>
        <form onSubmit={(event) => { event.preventDefault(); if (!loading) void load(input) }}>
          <div className="bilibili-input-row">
            <Input
              aria-label="B站视频或直播间地址"
              value={input}
              onChange={(event) => changeInput(event.target.value)}
              prefix={<LinkOutlined />}
              placeholder="B站视频 / b23 分享链接 / BV号 / 直播间地址"
              maxLength={4096}
              allowClear
            />
            <Button type="primary" htmlType="submit" icon={<PlayCircleOutlined />} loading={loading}>解析链接</Button>
          </div>
        </form>
        <Text type="secondary" style={{ fontSize: 12 }}>
          支持多 P 视频、BV/av 号和纯数字直播间号；不支持番剧、付费课堂及其他平台。画质取决于平台与账号权限。
        </Text>
      </section>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      {loading && <div role="status" aria-live="polite" style={{ textAlign: 'center', padding: 24 }}><Spin /> <Text type="secondary">正在解析 B站链接…</Text></div>}

      {result ? (
        <section className="bilibili-panel" aria-label="B站解析结果" aria-busy={loading}>
          <div className="bilibili-summary">
            {result.cover && <img className="bilibili-cover" src={result.cover} alt="" referrerPolicy="no-referrer" onError={(event) => { event.currentTarget.style.display = 'none' }} />}
            <div className="bilibili-summary__text">
              <Space size={6} wrap style={{ marginBottom: 8 }}>
                <Tag color="blue">哔哩哔哩</Tag>
                <Tag color={result.kind === 'live' && result.status === 'ready' ? 'green' : 'default'}>
                  {result.kind === 'video' ? '视频' : result.status === 'ready' ? '直播中' : result.status === 'loop' ? '轮播中' : '未开播'}
                </Tag>
              </Space>
              <Title level={4} style={{ margin: '0 0 6px', overflowWrap: 'anywhere' }}>{result.title || 'B站内容'}</Title>
              {result.author && <Text type="secondary">{result.author}</Text>}
            </div>
            <Space wrap>
              <Button icon={<ReloadOutlined />} disabled={loading} onClick={() => refresh()}>{result.playback ? '重新解析' : '重新检查'}</Button>
              <Button href={originalBilibiliUrl(result.sourceUrl)} target="_blank" rel="noopener noreferrer">打开原站</Button>
            </Space>
          </div>

          {result.notice && <Alert type="info" showIcon message={result.notice} style={{ marginBottom: 18 }} />}
          {(result.pages?.length > 1 || result.qualities?.length > 0) && (
            <div className="bilibili-options">
              {result.pages?.length > 1 && (
                <label className="bilibili-option"><Text type="secondary">视频分 P</Text>
                  <Select aria-label="视频分 P" value={result.page} disabled={loading} options={result.pages.map((part) => ({ value: part.page, label: `P${part.page} ${part.title}` }))}
                    onChange={(page) => void load(result.sourceUrl, { page, quality: result.quality })} />
                </label>
              )}
              {result.qualities?.length > 0 && (
                <label className="bilibili-option"><Text type="secondary">清晰度</Text>
                  <Select aria-label="清晰度" value={result.quality} disabled={loading} options={result.qualities.map((quality) => ({ value: quality.id, label: quality.label }))}
                    onChange={(quality) => void load(result.sourceUrl, { page: result.page, quality }, { resume: true })} />
                </label>
              )}
            </div>
          )}

          {playbackError && <Alert type="warning" showIcon message={playbackError} description="播放地址仅短期有效，可点击“重新解析”刷新；失败后最多自动刷新一次，不会持续重试。" style={{ marginBottom: 16 }} />}
          {result.playback ? (
            <BilibiliPlayer key={result.playback.url} playback={result.playback} poster={result.cover} live={result.kind === 'live'} startAt={startAt} autoPlay={resumePlay}
              onError={handlePlaybackError} onTimeUpdate={(time) => { if (time > 0) position.current = time }} onPlaybackState={(playing) => { wasPlaying.current = playing }} />
          ) : <Empty description={result.status === 'loop' ? '当前房间处于轮播状态，暂未开播' : '主播暂未开播，可以稍后重新检查'} style={{ padding: '32px 0' }} />}
          <Text type="secondary" style={{ display: 'block', marginTop: 14, fontSize: 12 }}>
            若平台要求登录或限制清晰度，可在“设置 → B站登录态”中保存或替换 Cookie；服务器不会回显已保存的 Cookie。
          </Text>
        </section>
      ) : !loading && !error && <Empty description="粘贴一个 B站视频或直播间链接开始播放" style={{ padding: '48px 0' }} />}
    </div>
  )
}
