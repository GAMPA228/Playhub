import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { transform } from 'esbuild'
import { bilibiliRefreshOptions, originalBilibiliUrl, validateBilibiliResult } from '../src/utils/bilibili.js'

function ready(overrides = {}) {
  return { ok: true, kind: 'video', status: 'ready', sourceUrl: 'https://www.bilibili.com/video/BV1xx411c7mD', playback: { url: '/api/bilibili/play/local/manifest.mpd', format: 'dash' }, ...overrides }
}

test('B站 results accept DASH, HLS, MP4 and explicit offline rooms', () => {
  for (const format of ['dash', 'hls', 'mp4']) {
    const result = ready({ playback: { url: '/api/bilibili/play/local/stream?r=abc&sig=test', format } })
    assert.equal(validateBilibiliResult(result), result)
  }
  for (const status of ['offline', 'loop']) {
    const result = ready({ kind: 'live', status, sourceUrl: 'https://live.bilibili.com/6', playback: null })
    assert.equal(validateBilibiliResult(result), result)
  }
})

test('B站 refresh preserves playable quality but omits the zero quality of offline or looping rooms', () => {
  assert.deepEqual(bilibiliRefreshOptions({ page: 2, quality: 32 }), { page: 2, quality: 32 })
  for (const status of ['offline', 'loop']) {
    const options = bilibiliRefreshOptions({ page: 1, quality: 0, status })
    assert.deepEqual(options, { page: 1 })
    assert.equal(JSON.stringify(options), '{"page":1}')
  }
})

test('B站 results reject missing media, unsafe URLs and unknown formats', () => {
  for (const url of ['https://cdn.example/movie.mp4', '//cdn.example/movie.mp4', '/api/bilibili/../config', '/api/bilibili/%2e%2e/config', '/api/bilibili/a\\b', '/api/bilibili/a#b']) {
    assert.throws(() => validateBilibiliResult(ready({ playback: { url, format: 'mp4' } })), /不支持/)
  }
  assert.throws(() => validateBilibiliResult(ready({ playback: null })), /未返回/)
  assert.throws(() => validateBilibiliResult(ready({ playback: { url: '/api/bilibili/a', format: 'flv' } })), /不支持/)
  assert.throws(() => validateBilibiliResult({ ok: false, error: '平台限制' }), /平台限制/)
  assert.throws(() => validateBilibiliResult(ready({ kind: 'other' })), /无效/)
  assert.throws(() => validateBilibiliResult(ready({ status: 'unknown' })), /未知/)
})

test('original-site links only allow canonical HTTPS B站 domains without credentials', () => {
  assert.equal(originalBilibiliUrl('https://live.bilibili.com/6'), 'https://live.bilibili.com/6')
  for (const url of ['javascript:alert(1)', 'https://bilibili.com.evil.example/video/a', 'https://evil.example/', 'https://user:pass@www.bilibili.com/video/a', 'https://www.bilibili.com:8080/video/a']) {
    assert.equal(originalBilibiliUrl(url), '')
  }
})

test('B站 API wrapper forwards cancellation and quality/part without legacy source configuration', async () => {
  const client = `data:text/javascript,${encodeURIComponent('export const calls=[]; export function postJson(...args){calls.push(args);return Promise.resolve({ok:true})}; export function getJson(){}')}`
  const { calls } = await import(client)
  const source = (await readFile(new URL('../src/api/index.js', import.meta.url), 'utf8')).replace("from './client'", `from ${JSON.stringify(client)}`)
  const { resolveBilibili } = await import(`data:text/javascript,${encodeURIComponent(source)}`)
  const controller = new AbortController()
  const body = { input: 'BV1xx411c7mD', page: 2, quality: 64 }
  const config = { signal: controller.signal, silentError: true }
  await resolveBilibili(body, config)
  assert.deepEqual(calls, [['/api/bilibili/resolve', body, config]])
})

test('actual resolver page exposes its form and scope without requiring a configured TVBox source', async () => {
  let source = await readFile(new URL('../src/views/BilibiliView.jsx', import.meta.url), 'utf8')
  for (const name of ['react', 'antd', '@ant-design/icons']) {
    source = source.replace(`from '${name}'`, `from ${JSON.stringify(import.meta.resolve(name))}`)
  }
  source = source
    .replace("from '../api'", "from 'data:text/javascript,export async function resolveBilibili(){}'")
    .replace("from '../api/client'", "from 'data:text/javascript,export function isAbortError(){return false}'")
    .replace("from '../utils/bilibili'", `from ${JSON.stringify(new URL('../src/utils/bilibili.js', import.meta.url).href)}`)
    .replace("from '../components/BilibiliPlayer'", "from 'data:text/javascript,export default function Player(){return null}'")
  const { code } = await transform(source, { loader: 'jsx', format: 'esm' })
  const { default: BilibiliView } = await import(`data:text/javascript,${encodeURIComponent(code)}`)
  const html = renderToStaticMarkup(React.createElement(BilibiliView))
  assert.ok(html.includes('aria-label="B站视频或直播间地址"'))
  assert.ok(html.includes('解析链接'))
  assert.ok(html.includes('普通视频和直播间'))
  assert.ok(html.includes('无需配置影视源'))
  assert.ok(html.includes('不支持番剧、付费课堂及其他平台'))
  assert.ok(html.includes('粘贴一个 B站视频或直播间链接开始播放'))
})
