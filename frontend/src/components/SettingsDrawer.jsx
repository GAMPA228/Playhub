import React, { useState, useRef, useEffect } from 'react'
import { Form, Input, Button, Select, Space, Divider, Typography, message, List, Popconfirm, AutoComplete, Switch, Modal, Alert } from 'antd'
import { DeleteOutlined, LinkOutlined, HistoryOutlined, ClockCircleOutlined, RobotOutlined, CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined, ExportOutlined, ImportOutlined, LockOutlined } from '@ant-design/icons'
import { useStore } from '../stores/useStore'

const { Text, Title } = Typography

export default function SettingsDrawer({ onClose }) {
  const store = useStore()
  const [configInput, setConfigInput] = useState(store.configUrl || '')
  const [loading, setLoading] = useState(false)
  const [liveSourceInput, setLiveSourceInput] = useState(store.settings.liveSourceUrl)
  const [liveEpgInput, setLiveEpgInput] = useState(store.settings.liveEpgUrl)
  const [importing, setImporting] = useState(false)
  const [aiTesting, setAiTesting] = useState(false)
  const [aiTestResult, setAiTestResult] = useState(null)
  const [exportModalOpen, setExportModalOpen] = useState(false)
  const [importModalOpen, setImportModalOpen] = useState(false)
  const [exportPassword, setExportPassword] = useState('')
  const [importPassword, setImportPassword] = useState('')
  const [importData, setImportData] = useState('')
  const fileInputRef = useRef(null)
  const sourceBusy = loading || importing || store.sourceSaving || store.bootstrapping || store.loading.config
  const sourceDisabled = sourceBusy || !store.sourceSettingsLoaded

  useEffect(() => { setConfigInput(store.configUrl) }, [store.configUrl])
  useEffect(() => { setLiveSourceInput(store.settings.liveSourceUrl) }, [store.settings.liveSourceUrl])
  useEffect(() => { setLiveEpgInput(store.settings.liveEpgUrl) }, [store.settings.liveEpgUrl])

  const handleLoadConfig = async (url) => {
    if (sourceDisabled) return
    const target = (url || configInput).trim()
    if (!target) { message.warning('请输入配置地址'); return }
    setLoading(true)
    try {
      await store.loadConfig(target)
      setConfigInput(target)
      message.success('配置已加载并保存到服务器')
    } catch (err) {
      message.error('加载失败: ' + err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleClearVideo = async () => {
    try {
      await store.clearVideoConfig()
      setConfigInput('')
      message.success('服务器视频配置及地址历史已清空')
    } catch (err) { message.error('清空失败：' + err.message) }
  }

  const handleSaveLive = async () => {
    const patch = {}
    if (liveSourceInput.trim() !== store.settings.liveSourceUrl) patch.liveSourceUrl = liveSourceInput.trim()
    if (liveEpgInput.trim() !== store.settings.liveEpgUrl) patch.liveEpgUrl = liveEpgInput.trim()
    try {
      await store.saveSourceSettings(patch)
      message.success('直播设置已保存到服务器')
    } catch (err) { message.error('保存失败：' + err.message) }
  }

  const handleMigrate = async () => {
    setLoading(true)
    try {
      await store.migrateLegacySources()
      message.success('此浏览器的数据源配置已迁移到服务器')
    } catch (err) { message.error('迁移结果：' + err.message) }
    finally { setLoading(false) }
  }

  const historyOptions = store.configHistory.map((url) => ({
    value: url,
    label: (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <ClockCircleOutlined style={{ color: 'var(--color-text-tertiary)', fontSize: 12 }} />
        <Text ellipsis style={{ flex: 1, fontSize: 13 }}>{url}</Text>
      </div>
    ),
  }))

  const handleTestAi = async () => {
    const { aiUrl, aiKey, aiModel } = store.settings
    if (!aiUrl || !aiKey) { message.warning('请先填写 AI 接口地址和 API Key'); return }
    setAiTesting(true)
    setAiTestResult(null)
    try {
      const res = await fetch(`${aiUrl}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${aiKey}` },
        body: JSON.stringify({ model: aiModel || 'gpt-4o-mini', messages: [{ role: 'user', content: '回复"OK"' }], max_tokens: 10 }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data.choices?.[0]?.message?.content) {
        setAiTestResult({ ok: true, model: data.model || aiModel })
      } else {
        setAiTestResult({ ok: false, error: '返回格式异常' })
      }
    } catch (err) {
      setAiTestResult({ ok: false, error: err.message })
    } finally {
      setAiTesting(false)
    }
  }

  const encryptData = (data, password) => {
    const jsonStr = JSON.stringify(data)
    const encoded = btoa(unescape(encodeURIComponent(jsonStr)))
    if (!password) return encoded
    // Simple XOR encryption with password
    let result = ''
    for (let i = 0; i < encoded.length; i++) {
      result += String.fromCharCode(encoded.charCodeAt(i) ^ password.charCodeAt(i % password.length))
    }
    return btoa(result)
  }

  const decryptData = (encrypted, password) => {
    try {
      if (!password) {
        return JSON.parse(decodeURIComponent(escape(atob(encrypted))))
      }
      const decoded = atob(encrypted)
      let result = ''
      for (let i = 0; i < decoded.length; i++) {
        result += String.fromCharCode(decoded.charCodeAt(i) ^ password.charCodeAt(i % password.length))
      }
      return JSON.parse(decodeURIComponent(escape(atob(result))))
    } catch {
      throw new Error('解密失败，密码可能不正确')
    }
  }

  const handleExport = () => {
    if (!exportPassword) { message.warning('请设置配置密码'); return }
    const configData = {
      configUrl: store.configUrl,
      settings: store.settings,
      configHistory: store.configHistory,
      searchHistory: store.searchHistory,
    }
    const encrypted = encryptData(configData, exportPassword)
    const blob = new Blob([encrypted], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `mujian-config-${Date.now()}.txt`
    a.click()
    URL.revokeObjectURL(url)
    message.success('配置已导出')
    setExportModalOpen(false)
    setExportPassword('')
  }

  const handleImport = async () => {
    if (!importData.trim()) { message.warning('请粘贴配置数据'); return }
    if (!importPassword) { message.warning('请输入配置密码'); return }
    setImporting(true)
    try {
      const configData = decryptData(importData.trim(), importPassword)
      await store.importSettings(configData)
      message.success('配置导入成功，数据源已保存到服务器')
      setImportModalOpen(false)
      setImportPassword('')
      setImportData('')
    } catch (err) {
      message.error(err.message || '导入失败')
    } finally {
      setImporting(false)
    }
  }

  const handleFileImport = (e) => {
    const file = e.target.files[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => {
      setImportData(ev.target.result)
    }
    reader.readAsText(file)
    e.target.value = '' // Reset file input
  }

  return (
    <div>
      <Title level={5} style={{ marginBottom: 16 }}>外观设置</Title>
      <Form layout="vertical">
        <Form.Item label="暗色模式" extra="立即生效，并在当前浏览器中记住你的选择。">
          <Switch
            aria-label="暗色模式"
            checked={store.settings.theme === 'dark'}
            onChange={(checked) => store.updateSettings({ theme: checked ? 'dark' : 'light' })}
            checkedChildren="开启"
            unCheckedChildren="关闭"
          />
        </Form.Item>
      </Form>

      <Divider />

      <Title level={5} style={{ marginBottom: 16 }}>数据源配置</Title>
      {store.sourceSettingsError && <Alert type="error" showIcon message={store.sourceSettingsError} description="不会自动使用旧浏览器数据覆盖服务器配置。" action={<Button size="small" loading={store.bootstrapping} disabled={sourceBusy} onClick={() => store.bootstrap({ force: true })}>重新同步</Button>} style={{ marginBottom: 16 }} />}
      {store.legacySourceSettings && (
        <Popconfirm title="将此浏览器旧数据源迁移到服务器？" description="会覆盖对应的服务器配置，并影响其他设备。" onConfirm={handleMigrate} disabled={sourceDisabled}>
          <Button disabled={sourceDisabled} style={{ marginBottom: 16 }}>迁移此浏览器源配置到服务器</Button>
        </Popconfirm>
      )}
      <Form layout="vertical">
        <Form.Item label="配置地址" style={{ marginBottom: 12 }}>
          <Space.Compact style={{ width: '100%' }}>
            <AutoComplete
              value={configInput}
              disabled={sourceDisabled}
              options={historyOptions}
              onChange={setConfigInput}
              onSelect={(val) => { setConfigInput(val); handleLoadConfig(val) }}
              style={{ flex: 1, minWidth: 0 }}
            >
              <Input style={{ height: 44 }} prefix={<LinkOutlined />} placeholder="输入 TVBox 配置 JSON 地址" onPressEnter={() => handleLoadConfig()} />
            </AutoComplete>
            <Button type="primary" style={{ height: 44, flexShrink: 0 }} disabled={sourceDisabled} loading={loading || store.loading.config} onClick={() => handleLoadConfig()}>加载并保存</Button>
          </Space.Compact>
        </Form.Item>
        <Popconfirm title="清空服务器视频配置和地址历史？" description="其他设备刷新后也将不再加载该配置。" onConfirm={handleClearVideo} disabled={sourceDisabled}>
          <Button danger disabled={sourceDisabled || (!store.configUrl && !store.configHistory.length)}>清空视频配置</Button>
        </Popconfirm>
      </Form>

      {store.configHistory.length > 0 && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
            <HistoryOutlined style={{ color: 'var(--color-text-tertiary)', fontSize: 13 }} />
            <Text type="secondary" style={{ fontSize: 13 }}>历史配置</Text>
          </div>
          <List
            size="small"
            dataSource={store.configHistory}
            renderItem={(url) => (
              <List.Item
                style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 4, background: store.configUrl === url ? 'var(--color-primary-bg)' : 'var(--color-bg-muted)', border: `1px solid ${store.configUrl === url ? 'var(--color-primary-border)' : 'var(--color-border-light)'}`, cursor: 'pointer', transition: 'all 0.2s ease' }}
                onClick={() => { setConfigInput(url); handleLoadConfig(url) }}
                actions={[
                  <Popconfirm key="delete" title="从服务器地址历史中删除？" disabled={sourceDisabled} onConfirm={async (e) => { e?.stopPropagation(); try { await store.removeConfigHistory(url) } catch (err) { message.error('删除失败：' + err.message) } }} onCancel={(e) => e?.stopPropagation()}>
                    <Button type="text" size="small" disabled={sourceDisabled} danger icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} />
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  title={<Text ellipsis style={{ maxWidth: 260, fontSize: 13, fontWeight: store.configUrl === url ? 600 : 400 }}>{url}</Text>}
                  description={store.configUrl === url ? <Text style={{ fontSize: 11, color: 'var(--color-success)' }}>当前使用</Text> : null}
                />
              </List.Item>
            )}
          />
        </>
      )}

      <Divider />

      <Title level={5} style={{ marginBottom: 16 }}>搜索设置</Title>
      <Form layout="vertical">
        <Form.Item label="搜索范围">
          <Select
            value={store.settings.searchScope}
            onChange={(v) => store.updateSettings({ searchScope: v })}
            options={[
              { label: '全部源', value: 'all' },
              { label: '当前源', value: 'current' },
            ]}
          />
        </Form.Item>
      </Form>

      <Divider />

      <Title level={5} style={{ marginBottom: 16 }}>
        <RobotOutlined style={{ marginRight: 8 }} />
        AI 增强
      </Title>
      <Form layout="vertical">
        <Form.Item label="启用 AI 增强">
          <Switch
            checked={store.settings.aiEnabled}
            onChange={(v) => store.updateSettings({ aiEnabled: v })}
          />
          <Text type="secondary" style={{ marginLeft: 12, fontSize: 13 }}>
            使用 AI 增强您的视频播放体验
          </Text>
        </Form.Item>
        {store.settings.aiEnabled && (
          <>
            <Form.Item label="AI 接口地址">
              <Input
                placeholder="如: https://api.openai.com"
                value={store.settings.aiUrl}
                onChange={(e) => store.updateSettings({ aiUrl: e.target.value })}
              />
              <Text type="secondary" style={{ fontSize: 12 }}>
                仅支持 OpenAI completions 模式，填写 baseURL，域名后不加 /
              </Text>
            </Form.Item>
            <Form.Item label="模型 ID">
              <Input
                placeholder="如: gpt-4o-mini, deepseek-chat"
                value={store.settings.aiModel}
                onChange={(e) => store.updateSettings({ aiModel: e.target.value })}
              />
            </Form.Item>
            <Form.Item label="API Key">
              <Input.Password
                placeholder="sk-..."
                value={store.settings.aiKey}
                onChange={(e) => store.updateSettings({ aiKey: e.target.value })}
              />
            </Form.Item>
            <Form.Item>
              <Space>
                <Button
                  icon={aiTesting ? <LoadingOutlined /> : undefined}
                  loading={aiTesting}
                  onClick={handleTestAi}
                >
                  测试连接
                </Button>
                {aiTestResult && (
                  aiTestResult.ok ? (
                    <Text style={{ color: 'var(--color-success)', fontSize: 13 }}>
                      <CheckCircleOutlined style={{ marginRight: 4 }} />
                      连接成功 {aiTestResult.model && `(${aiTestResult.model})`}
                    </Text>
                  ) : (
                    <Text style={{ color: 'var(--color-error)', fontSize: 13 }}>
                      <CloseCircleOutlined style={{ marginRight: 4 }} />
                      {aiTestResult.error}
                    </Text>
                  )
                )}
              </Space>
            </Form.Item>
          </>
        )}
      </Form>

      <Divider />

      <Title level={5} style={{ marginBottom: 16 }}>直播设置</Title>
      <Form layout="vertical">
        <Form.Item label="直播源地址">
          <Input
            placeholder="输入 M3U 直播源地址"
            value={liveSourceInput}
            disabled={sourceDisabled}
            allowClear
            onChange={(e) => setLiveSourceInput(e.target.value)}
          />
        </Form.Item>
        <Form.Item label="EPG 地址 (可选)">
          <Input
            placeholder="输入 EPG XML 地址"
            value={liveEpgInput}
            disabled={sourceDisabled}
            allowClear
            onChange={(e) => setLiveEpgInput(e.target.value)}
          />
        </Form.Item>
        <Button type="primary" disabled={sourceDisabled} loading={store.sourceSaving} onClick={handleSaveLive}>保存直播设置</Button>
        <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>留空并保存即可清空对应地址，未保存的修改不会生效。</Text>
      </Form>

      <Divider />

      <Title level={5} style={{ marginBottom: 16 }}>
        <LockOutlined style={{ marginRight: 8 }} />
        配置导入导出
      </Title>
      <Space>
        <Button icon={<ExportOutlined />} onClick={() => setExportModalOpen(true)}>
          导出配置
        </Button>
        <Button disabled={sourceDisabled} icon={<ImportOutlined />} onClick={() => setImportModalOpen(true)}>
          导入配置
        </Button>
      </Space>

      <Modal
        title="导出配置"
        open={exportModalOpen}
        onOk={handleExport}
        onCancel={() => { setExportModalOpen(false); setExportPassword('') }}
        okText="导出"
        cancelText="取消"
      >
        <Form layout="vertical">
          <Form.Item label="设置配置密码" required>
            <Input.Password
              placeholder="设置密码用于加密配置"
              value={exportPassword}
              onChange={(e) => setExportPassword(e.target.value)}
              prefix={<LockOutlined />}
            />
          </Form.Item>
          <Text type="secondary" style={{ fontSize: 12 }}>
            导出的配置包含：配置地址、外观设置、搜索设置、AI 设置、直播设置、历史配置
          </Text>
        </Form>
      </Modal>

      <Modal
        title="导入配置"
        open={importModalOpen}
        confirmLoading={importing}
        okButtonProps={{ disabled: sourceBusy && !importing }}
        closable={!importing}
        maskClosable={!importing}
        onOk={handleImport}
        onCancel={() => { setImportModalOpen(false); setImportPassword(''); setImportData('') }}
        okText="导入"
        cancelText="取消"
      >
        <Alert type="warning" showIcon message="导入会覆盖文件中包含的服务器数据源字段，并影响其他设备；AI 密钥等偏好仍仅导入到此浏览器。" style={{ marginBottom: 16 }} />
        <Form layout="vertical">
          <Form.Item label="配置密码">
            <Input.Password
              placeholder="输入导出时设置的密码"
              value={importPassword}
              onChange={(e) => setImportPassword(e.target.value)}
              prefix={<LockOutlined />}
            />
          </Form.Item>
          <Form.Item label="配置数据">
            <Input.TextArea
              placeholder="粘贴配置数据，或点击下方按钮导入文件"
              value={importData}
              onChange={(e) => setImportData(e.target.value)}
              rows={4}
            />
          </Form.Item>
          <Button onClick={() => fileInputRef.current?.click()}>
            从文件导入
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".txt"
            style={{ display: 'none' }}
            onChange={handleFileImport}
          />
        </Form>
      </Modal>
    </div>
  )
}
