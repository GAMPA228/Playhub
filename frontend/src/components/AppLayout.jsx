import React, { useEffect, useState } from 'react'
import { Layout, Button, Drawer, Typography } from 'antd'
import {
  HomeOutlined,
  HistoryOutlined,
  VideoCameraOutlined,
  SettingOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons'
import { Link, useLocation } from 'react-router-dom'
import { useStore } from '../stores/useStore'
import SettingsDrawer from './SettingsDrawer'

const { Content } = Layout
const { Text, Title } = Typography

const navItems = [
  { key: '/', icon: <HomeOutlined />, label: '首页' },
  { key: '/live', icon: <VideoCameraOutlined />, label: '直播' },
  { key: '/history', icon: <HistoryOutlined />, label: '观看记录' },
]

export default function AppLayout({ children }) {
  const location = useLocation()
  const bootstrap = useStore((s) => s.bootstrap)
  const historyCount = useStore((s) => s.history.length)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [error, setError] = useState(null)
  const activeKey = location.pathname === '/live' || location.pathname === '/history' ? location.pathname : '/'

  useEffect(() => {
    bootstrap().catch((err) => {
      console.error('Bootstrap error:', err)
      setError(err.message)
    })
  }, [bootstrap])

  if (location.pathname === '/player') {
    return (
      <Layout style={{ minHeight: '100vh', background: '#000' }}>
        <Content>{children}</Content>
      </Layout>
    )
  }

  return (
    <Layout className="app-shell">
      <header className="app-header">
        <div className="app-header__inner">
          <Link to="/" className="app-brand" aria-label="MUJIAN 首页">
            <span className="app-brand__icon"><PlayCircleOutlined /></span>
            <span>MUJIAN</span>
          </Link>
          <nav className="app-navigation" aria-label="主导航">
            {navItems.map((item) => (
              <Link
                key={item.key}
                to={item.key}
                className={`app-navigation__link${activeKey === item.key ? ' app-navigation__link--active' : ''}`}
                aria-current={activeKey === item.key ? 'page' : undefined}
              >
                {item.icon}
                <span>{item.label}</span>
                {item.key === '/history' && historyCount > 0 && (
                  <span className="app-navigation__count" aria-label={`${historyCount} 条观看记录`}>
                    {historyCount > 99 ? '99+' : historyCount}
                  </span>
                )}
              </Link>
            ))}
          </nav>
        </div>
      </header>

      <Content className="app-content">
        {error ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Title level={3} style={{ color: 'var(--color-error)' }}>加载出错</Title>
            <Text type="secondary">{error}</Text>
            <br /><br />
            <Button type="primary" onClick={() => window.location.reload()}>刷新页面</Button>
          </div>
        ) : children}
      </Content>

      <Button
        className="app-settings"
        icon={<SettingOutlined />}
        aria-label="设置"
        aria-haspopup="dialog"
        aria-expanded={settingsOpen}
        onClick={() => setSettingsOpen(true)}
      >
        设置
      </Button>

      <Drawer
        title="设置"
        placement="right"
        width="min(480px, 100vw)"
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
      >
        <SettingsDrawer onClose={() => setSettingsOpen(false)} />
      </Drawer>
    </Layout>
  )
}
