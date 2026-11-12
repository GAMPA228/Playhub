import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Form, Input, Popconfirm, Space, Tag, Typography } from 'antd'
import { getBilibiliAuth, saveBilibiliAuth } from '../api'
import { isAbortError } from '../api/client'

const { Text, Title } = Typography

export default function BilibiliAuthSettings({ open = true }) {
  const [status, setStatus] = useState(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const requestRef = useRef(null)
  const sequence = useRef(0)

  const request = useCallback(async (cookie) => {
    if (!open || requestRef.current) return
    const controller = new AbortController()
    requestRef.current = controller
    const current = ++sequence.current
    const saving = cookie !== undefined
    setBusy(true)
    setError('')
    setFeedback('')
    try {
      const config = { signal: controller.signal, silentError: true }
      const response = saving ? await saveBilibiliAuth(cookie, config) : await getBilibiliAuth(config)
      if (controller.signal.aborted || sequence.current !== current) return
      if (typeof response?.configured !== 'boolean' || !['saved', 'environment', 'anonymous'].includes(response.source)) {
        throw new Error('服务器返回了无效的 B站登录态配置状态')
      }
      setStatus({ configured: response.configured, source: response.source, updatedAt: response.updatedAt })
      if (saving) {
        setDraft('')
        setFeedback(cookie ? 'Cookie 已保存，后续解析立即生效；请重新解析当前视频。' : 'Cookie 已清除，后续解析使用匿名模式；请重新解析当前视频。')
      }
    } catch (err) {
      if (controller.signal.aborted || sequence.current !== current || isAbortError(err)) return
      setError(err.message || (saving ? 'B站 Cookie 保存失败，请重试' : 'B站登录态状态读取失败，请重试'))
    } finally {
      if (sequence.current === current) {
        requestRef.current = null
        setBusy(false)
      }
    }
  }, [open])

  useEffect(() => {
    setDraft('')
    setStatus(null)
    setError('')
    setFeedback('')
    setBusy(false)
    if (open) void request()
    return () => {
      sequence.current++
      requestRef.current?.abort()
      requestRef.current = null
    }
  }, [open, request])

  const save = () => {
    const cookie = draft.trim()
    if (!cookie) {
      setError('请先粘贴新的 Cookie；要恢复匿名模式，请使用“清除 Cookie”。')
      return
    }
    if (cookie.length > 16384 || /[^\x20-\x7E]/.test(cookie)) {
      setError('Cookie 格式不正确，请使用完整的 Cookie 字符串，勿包含换行或中文。')
      return
    }
    void request(cookie)
  }

  if (!open) return null

  return (
    <section aria-labelledby="bilibili-auth-heading" aria-busy={busy}>
      <Title id="bilibili-auth-heading" level={5} style={{ marginBottom: 12 }}>B站登录态</Title>
      <Space size={8} wrap style={{ marginBottom: 12 }}>
        <Tag color={status?.configured ? 'blue' : 'default'}>
          {status ? status.configured ? '已配置 Cookie' : '未配置（匿名）' : busy ? '正在读取配置状态…' : '配置状态未知'}
        </Tag>
        <Button type="link" size="small" disabled={busy} onClick={() => void request()}>刷新状态</Button>
      </Space>
      {status?.source === 'environment' && <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>当前使用环境变量，保存后将由设置中的值覆盖。</Text>}
      {status?.updatedAt && Number.isFinite(Date.parse(status.updatedAt)) && <Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 12 }}>上次修改：{new Date(status.updatedAt).toLocaleString()}</Text>}
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      {feedback && <Alert type="success" showIcon message={feedback} style={{ marginBottom: 12 }} />}
      <Form layout="vertical" onFinish={save}>
        <Form.Item label="新的 B站 Cookie" extra="只写入服务器，不回显旧值，也不加入配置导出。已配置不代表仍有效，过期后在此替换。" style={{ marginBottom: 12 }}>
          <Input.Password
            aria-label="新的 B站 Cookie"
            name="bilibili-cookie"
            value={draft}
            onChange={(event) => { setDraft(event.target.value); setFeedback('') }}
            placeholder={status?.configured ? '粘贴新 Cookie 以替换，留空不修改' : '可选：粘贴完整 Cookie'}
            autoComplete="new-password"
            spellCheck={false}
            visibilityToggle={false}
            maxLength={16384}
            disabled={busy}
          />
        </Form.Item>
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={busy} disabled={!draft.trim()}>保存 Cookie</Button>
          <Popconfirm
            title="清除服务器 B站 Cookie？"
            description="所有设备后续将匿名解析，即使有环境变量也不会自动回退。"
            onConfirm={() => request('')}
            disabled={busy || (!status?.configured && !error)}
          >
            <Button danger disabled={busy || (!status?.configured && !error)}>清除 Cookie</Button>
          </Popconfirm>
        </Space>
      </Form>
      <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
        全站共用服务器登录态，仅在可信内网或安全组网中设置；保存后无需重启容器。Cookie 可能提前失效，系统不会自动续期，也不会增加账号原本没有的权限。
      </Text>
    </section>
  )
}
