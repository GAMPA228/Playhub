import React, { useLayoutEffect } from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import './styles/global.css'
import { initMessageHandler } from './api/client'
import { useStore } from './stores/useStore'

initMessageHandler()

const lightTheme = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: '#1677ff',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f5f8ff',
    colorBgElevated: '#ffffff',
    colorText: '#1a1a2e',
    colorTextSecondary: '#6b7280',
    colorBorder: '#e5e7eb',
    colorBorderSecondary: '#f0f0f0',
    borderRadius: 12,
    fontFamily: "'Inter', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif",
    fontSize: 14,
    controlHeight: 40,
    colorLink: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    boxShadow: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
    boxShadowSecondary: '0 4px 16px rgba(0,0,0,0.08)',
  },
  components: {
    Button: {
      borderRadius: 8,
      controlHeight: 40,
      paddingInline: 20,
    },
    Card: {
      borderRadius: 16,
      paddingLG: 24,
    },
    Input: {
      borderRadius: 10,
      controlHeight: 44,
    },
    Select: {
      borderRadius: 10,
      controlHeight: 44,
    },
    Tag: {
      borderRadius: 6,
    },
    Tabs: {
      borderRadius: 8,
    },
  },
}

const darkTheme = {
  ...lightTheme,
  algorithm: antdTheme.darkAlgorithm,
  token: {
    ...lightTheme.token,
    colorPrimary: '#4096ff',
    colorBgBase: '#141824',
    colorBgContainer: '#1c2230',
    colorBgLayout: '#10141e',
    colorBgElevated: '#252d3d',
    colorText: '#e5e7eb',
    colorTextSecondary: '#a4adbd',
    colorBorder: '#3a4559',
    colorBorderSecondary: '#30394b',
    colorLink: '#69b1ff',
    colorSuccess: '#73d13d',
    colorWarning: '#ffc53d',
    colorError: '#ff7875',
    boxShadow: '0 1px 3px rgba(0,0,0,0.3)',
    boxShadowSecondary: '0 4px 16px rgba(0,0,0,0.4)',
  },
}

document.documentElement.dataset.theme = useStore.getState().settings.theme === 'dark' ? 'dark' : 'light'

function ThemedApp() {
  const isDark = useStore((state) => state.settings.theme === 'dark')
  const activeTheme = isDark ? darkTheme : lightTheme

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = isDark ? 'dark' : 'light'
    // Static message/modal APIs render outside the application provider.
    ConfigProvider.config({
      holderRender: (children) => (
        <ConfigProvider locale={zhCN} theme={activeTheme}>{children}</ConfigProvider>
      ),
    })
  }, [isDark, activeTheme])

  return (
    <ConfigProvider locale={zhCN} theme={activeTheme}>
      <App />
    </ConfigProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')).render(<ThemedApp />)
