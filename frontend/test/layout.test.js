import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { transform } from 'esbuild'

// Render the actual layout and router; only the bootstrap store and drawer contents are replaced.
const storeUrl = `data:text/javascript,${encodeURIComponent('export const useStore = (selector) => selector({ bootstrap: async () => {}, history: Array.from({ length: globalThis.layoutHistoryCount || 0 }) });')}`
let source = await readFile(new URL('../src/components/AppLayout.jsx', import.meta.url), 'utf8')
for (const name of ['react', 'antd', '@ant-design/icons', 'react-router-dom']) {
  source = source.replace(`from '${name}'`, `from ${JSON.stringify(import.meta.resolve(name))}`)
}
source = source
  .replace("from '../stores/useStore'", `from ${JSON.stringify(storeUrl)}`)
  .replace("from './SettingsDrawer'", `from 'data:text/javascript,export default function SettingsDrawer() { return null }'`)
const { code } = await transform(source, { loader: 'jsx', format: 'esm' })
const { default: AppLayout } = await import(`data:text/javascript,${encodeURIComponent(code)}`)

function render(path = '/', historyCount = 0) {
  globalThis.layoutHistoryCount = historyCount
  const error = console.error
  console.error = (message, ...args) => {
    if (typeof message === 'string' && message.startsWith('Warning: useLayoutEffect does nothing on the server')) return
    error(message, ...args)
  }
  try {
    return renderToStaticMarkup(React.createElement(MemoryRouter, { initialEntries: [path] },
      React.createElement(AppLayout, null, React.createElement('div', { id: 'route-content' }, '页面内容'))))
  } finally {
    console.error = error
  }
}

function navLinks(html) {
  const nav = html.match(/<nav\b[^>]*>([\s\S]*?)<\/nav>/)?.[1] || ''
  return [...nav.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].map(([, attrs, body]) => ({
    path: attrs.match(/href="([^"]*)"/)?.[1],
    active: attrs.includes('aria-current="page"'),
    text: body.replace(/<[^>]*>/g, ''),
  }))
}

test('MUJIAN branding is used in the header, tab title and favicon metadata', async () => {
  const html = render()
  assert.ok(html.includes('aria-label="MUJIAN 首页"'))
  assert.ok(html.includes('<span>MUJIAN</span>'))
  assert.equal(/playhub/i.test(html), false)
  const entry = await readFile(new URL('../index.html', import.meta.url), 'utf8')
  const icon = await readFile(new URL('../public/playhub.svg', import.meta.url), 'utf8')
  assert.ok(entry.includes('<title>MUJIAN</title>'))
  assert.ok(icon.includes('<title>MUJIAN</title>'))
})

test('top navigation has exactly home, live, history in order and defaults to home', () => {
  const html = render()
  const links = navLinks(html)
  assert.deepEqual(links.map((item) => item.path), ['/', '/live', '/history'])
  assert.deepEqual(links.map((item) => item.text), ['首页', '直播', '观看记录'])
  assert.deepEqual(links.filter((item) => item.active).map((item) => item.path), ['/'])
  assert.equal(html.includes('ant-layout-sider'), false)
  assert.equal(html.includes('menu-fold'), false)
  assert.equal((html.match(/class="[^"]*\bapp-settings\b[^"]*"/g) || []).length, 1)
  assert.ok(html.includes('id="route-content"'))
})

for (const path of ['/live', '/history', '/detail?source=s0&vod=m1']) {
  test(`route ${path} selects its own section without redirecting to home`, () => {
    const active = navLinks(render(path)).filter((item) => item.active)
    assert.deepEqual(active.map((item) => item.path), [path.startsWith('/detail') ? '/' : path])
  })
}

test('history count has only one navigation entry and is capped visually', () => {
  const html = render('/history', 120)
  assert.equal((html.match(/class="app-navigation__count"/g) || []).length, 1)
  assert.ok(html.includes('aria-label="120 条观看记录">99+</span>'))
  assert.equal(navLinks(html).filter((item) => item.path === '/history').length, 1)
  assert.equal(render().includes('app-navigation__count'), false)
})

test('player keeps the immersive layout without navigation or floating settings', () => {
  const html = render('/player?url=local-test')
  assert.equal(navLinks(html).length, 0)
  assert.equal(html.includes('app-settings'), false)
  assert.ok(html.includes('id="route-content"'))
})
