import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { setImmediate as flush } from 'node:timers/promises'
import { beforeEach, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { transform } from 'esbuild'

const apiUrl = `data:text/javascript,${encodeURIComponent(`
  export const api = {};
  export const getBilibiliAuth = (...args) => api.get(...args);
  export const saveBilibiliAuth = (...args) => api.save(...args);
`)}`
const { api } = await import(apiUrl)
const hooksUrl = `data:text/javascript,${encodeURIComponent(`
  export { default } from ${JSON.stringify(import.meta.resolve('react'))};
  ${['useState', 'useRef', 'useEffect', 'useCallback'].map((name) => `export const ${name} = (...args) => globalThis.bilibiliAuthTestHooks.${name}(...args);`).join('\n')}
`)}`

async function loadComponent(reactUrl) {
  let source = await readFile(new URL('../src/components/BilibiliAuthSettings.jsx', import.meta.url), 'utf8')
  for (const [from, to] of [
    ['react', reactUrl], ['antd', import.meta.resolve('antd')], ['../api', apiUrl],
    ['../api/client', 'data:text/javascript,export const isAbortError = err => err?.name === "AbortError";'],
  ]) source = source.replace(`from '${from}'`, `from ${JSON.stringify(to)}`)
  const { code } = await transform(source, { loader: 'jsx', format: 'esm' })
  return (await import(`data:text/javascript,${encodeURIComponent(code)}`)).default
}

const Component = await loadComponent(hooksUrl)
const ServerComponent = await loadComponent(import.meta.resolve('react'))

// Drive real component event handlers/effect cleanup without introducing a DOM dependency.
function harness() {
  const slots = []
  let cursor = 0
  let pending = []
  const same = (a, b) => a && b && a.length === b.length && a.every((value, index) => Object.is(value, b[index]))
  const hooks = {
    useState(initial) {
      const index = cursor++
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial
      return [slots[index], (next) => { slots[index] = typeof next === 'function' ? next(slots[index]) : next }]
    },
    useRef(initial) {
      const index = cursor++
      if (!(index in slots)) slots[index] = { current: initial }
      return slots[index]
    },
    useCallback(callback, deps) {
      const index = cursor++
      if (!same(slots[index]?.deps, deps)) slots[index] = { deps, callback }
      return slots[index].callback
    },
    useEffect(effect, deps) {
      const index = cursor++
      if (!same(slots[index]?.deps, deps)) pending.push(() => {
        slots[index]?.cleanup?.()
        slots[index] = { deps, cleanup: effect() }
      })
    },
  }
  return {
    render(open = true) {
      cursor = 0
      pending = []
      globalThis.bilibiliAuthTestHooks = hooks
      const tree = Component({ open })
      pending.forEach((effect) => effect())
      return tree
    },
    unmount() {
      slots.forEach((slot) => slot?.cleanup?.())
    },
  }
}

function elements(tree) {
  if (!tree || typeof tree !== 'object') return []
  return [tree, ...React.Children.toArray(tree.props?.children).flatMap(elements)]
}

function find(tree, predicate) {
  const element = elements(tree).find(predicate)
  assert.ok(element, 'Expected component control to exist')
  return element
}

function text(tree) {
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree)
  if (Array.isArray(tree)) return tree.map(text).join('')
  return tree?.props ? text(tree.props.children) : ''
}

function input(tree) {
  return find(tree, (element) => element.props?.['aria-label'] === '新的 B站 Cookie')
}

function save(tree) {
  return find(tree, (element) => typeof element.props?.onFinish === 'function').props.onFinish()
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

beforeEach(() => {
  api.get = async () => ({ configured: false, source: 'anonymous', updatedAt: null })
  api.save = async (cookie) => ({ configured: Boolean(cookie), source: 'saved', updatedAt: '2026-11-12T02:00:00Z' })
})

test('B站 settings render a write-only password control and explicit shared-account warning', () => {
  const html = renderToStaticMarkup(React.createElement(ServerComponent))
  assert.ok(html.includes('type="password"'))
  assert.match(html, /autocomplete="new-password"/i)
  assert.ok(html.includes('保存 Cookie'))
  assert.ok(html.includes('清除 Cookie'))
  assert.ok(html.includes('全站共用服务器登录态'))
  assert.ok(html.includes('过期后在此替换'))
  assert.ok(html.includes('保存后无需重启容器'))
  assert.equal(renderToStaticMarkup(React.createElement(ServerComponent, { open: false })), '')
})

test('B站 cookie API wrappers forward credentials only in the write body and keep cancellation options', async () => {
  const client = `data:text/javascript,${encodeURIComponent('export const calls=[]; export function getJson(...a){calls.push(["get",...a])}; export function postJson(...a){calls.push(["post",...a])}')}`
  const { calls } = await import(client)
  const source = (await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')).replace("from './client'", `from ${JSON.stringify(client)}`)
  const { getBilibiliAuth, saveBilibiliAuth } = await import(`data:text/javascript,${encodeURIComponent(source)}`)
  const config = { signal: new AbortController().signal, silentError: true, headers: { 'X-Test': 'retained' } }
  await getBilibiliAuth(config)
  await saveBilibiliAuth('SESSDATA=unit-test', config)
  await saveBilibiliAuth('', config)
  assert.deepEqual(calls, [
    ['get', '/api/bilibili/auth', config],
    ['post', '/api/bilibili/auth', { cookie: 'SESSDATA=unit-test' }, { ...config, headers: { ...config.headers, 'X-Mujian-Settings': '1' } }],
    ['post', '/api/bilibili/auth', { cookie: '' }, { ...config, headers: { ...config.headers, 'X-Mujian-Settings': '1' } }],
  ])
})

test('saving replaces the cookie, clears its draft and displays configuration rather than validity', async () => {
  const h = harness()
  let body
  api.save = async (cookie) => {
    body = cookie
    return { configured: true, source: 'saved', updatedAt: null, cookie: 'never-render-a-server-secret' }
  }
  h.render()
  await flush()
  input(h.render()).props.onChange({ target: { value: '  SESSDATA=unit-test  ' } })
  save(h.render())
  await flush()
  const tree = h.render()
  assert.equal(body, 'SESSDATA=unit-test')
  assert.equal(input(tree).props.value, '')
  assert.ok(text(tree).includes('已配置 Cookie'))
  assert.ok(elements(tree).some((element) => element.props?.type === 'success'))
  assert.ok(!text(tree).includes('never-render-a-server-secret'))
  h.unmount()
})

test('save failure preserves the draft and old status without claiming success, then clear sends empty cookie', async () => {
  const h = harness()
  api.get = async () => ({ configured: true, source: 'environment', updatedAt: null })
  api.save = async () => { throw new Error('服务端保存失败') }
  h.render()
  await flush()
  input(h.render()).props.onChange({ target: { value: 'SESSDATA=retry' } })
  save(h.render())
  await flush()
  let tree = h.render()
  assert.equal(input(tree).props.value, 'SESSDATA=retry')
  assert.ok(text(tree).includes('已配置 Cookie'))
  assert.ok(elements(tree).some((element) => element.props?.type === 'error' && element.props.message === '服务端保存失败'))
  assert.ok(!elements(tree).some((element) => element.props?.type === 'success'))
  let cleared
  api.save = async (cookie) => { cleared = cookie; return { configured: false, source: 'saved', updatedAt: null } }
  await find(tree, (element) => typeof element.props?.onConfirm === 'function').props.onConfirm()
  tree = h.render()
  assert.equal(cleared, '')
  assert.equal(input(tree).props.value, '')
  assert.ok(text(tree).includes('未配置（匿名）'))
  h.unmount()
})

test('failed status reads permit explicit replacement and malformed drafts never reach the API', async () => {
  const h = harness()
  api.get = async () => { throw new Error('配置文件读取失败') }
  let writes = 0
  api.save = async () => { writes++; return { configured: true, source: 'saved', updatedAt: null } }
  h.render()
  await flush()
  assert.equal(input(h.render()).props.disabled, false)
  for (const value of ['SESSDATA=中文', 'SESSDATA=x\nvalue', 'x'.repeat(16385)]) {
    input(h.render()).props.onChange({ target: { value } })
    save(h.render())
    await flush()
  }
  assert.equal(writes, 0)
  input(h.render()).props.onChange({ target: { value: 'SESSDATA=repaired' } })
  save(h.render())
  await flush()
  assert.equal(writes, 1)
  assert.equal(input(h.render()).props.value, '')
  h.unmount()
})

test('closing cancels in-flight saves, clears secrets and ignores late responses before rereading on reopen', async () => {
  const h = harness()
  const late = deferred()
  let signal
  api.save = (_, config) => { signal = config.signal; return late.promise }
  h.render()
  await flush()
  input(h.render()).props.onChange({ target: { value: 'SESSDATA=discard' } })
  save(h.render())
  assert.equal(h.render(false), null)
  assert.equal(signal.aborted, true)
  late.resolve({ configured: true, source: 'saved', updatedAt: null })
  await flush()
  h.render(true)
  await flush()
  const tree = h.render()
  assert.equal(input(tree).props.value, '')
  assert.ok(text(tree).includes('未配置（匿名）'))
  assert.ok(!elements(tree).some((element) => element.props?.type === 'success'))
  h.unmount()
})

test('actual settings drawer includes B站 auth section with its visibility passed through', async () => {
  let source = await readFile(new URL('../src/components/SettingsDrawer.jsx', import.meta.url), 'utf8')
  for (const name of ['react', 'antd', '@ant-design/icons']) source = source.replace(`from '${name}'`, `from ${JSON.stringify(import.meta.resolve(name))}`)
  const store = { configUrl: '', configHistory: [], settings: { theme: 'light', liveSourceUrl: '', liveEpgUrl: '', searchScope: 'all', aiEnabled: false }, loading: {}, sourceSettingsLoaded: true }
  source = source
    .replace("from '../stores/useStore'", `from ${JSON.stringify(`data:text/javascript,export const useStore = () => (${JSON.stringify(store)});`)}`)
    .replace("from './BilibiliAuthSettings'", `from ${JSON.stringify(`data:text/javascript,import React from ${JSON.stringify(import.meta.resolve('react'))}; export default function Auth({open}){return React.createElement('section',{'data-auth-open':String(open)},'B站入口')}`)}`)
  const { code } = await transform(source, { loader: 'jsx', format: 'esm' })
  const Drawer = (await import(`data:text/javascript,${encodeURIComponent(code)}`)).default
  for (const open of [true, false]) {
    const html = renderToStaticMarkup(React.createElement(Drawer, { open }))
    assert.ok(html.includes(`data-auth-open="${open}"`))
    assert.ok(html.includes('B站入口'))
  }
})
