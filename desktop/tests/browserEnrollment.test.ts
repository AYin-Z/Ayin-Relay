import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { request } from 'node:http'
import test from 'node:test'

import { enrollmentPortalUrl, receiveBrowserEnrollment } from '../src/lib/browserEnrollment.js'

const portal = 'https://admin.example.test/relay/private/enroll'
const relay = 'wss://relay.example.test/ws'

test('browser enrollment requires a clean HTTPS enrollment URL and WSS relay', async () => {
  for (const url of [portal.replace('https:', 'http:'), portal + '?query=x', portal + '#fragment',
    portal.replace('admin.', 'visitor@admin.'), portal.replace('/enroll', '/download')]) {
    assert.throws(() => enrollmentPortalUrl(url))
  }
  await assert.rejects(receiveBrowserEnrollment(portal, relay.replace('wss:', 'ws:'), async () => {
    assert.fail('should not open browser')
  }), /WSS/)
})

test('browser enrollment binds callback to the local session, expected relay and expiry', async () => {
  let challenge = ''
  let acceptedPath = ''
  const expiresAt = Math.floor(Date.now() / 1000) + 240
  const result = await receiveBrowserEnrollment(portal, relay, async prompt => {
    const browserUrl = new URL(prompt.url)
    const state = browserUrl.searchParams.get('state')!
    challenge = browserUrl.searchParams.get('challenge')!
    assert.match(challenge, /^[0-9a-f]{64}$/)
    assert.equal(prompt.confirmationCode, state.slice(0, 8).toUpperCase())
    assert.equal(browserUrl.origin + browserUrl.pathname, portal)
    assert.equal(browserUrl.searchParams.has('enrollment_verifier'), false)
    const callback = new URL(`http://127.0.0.1:${browserUrl.searchParams.get('port')}/ayin-relay/enrollment`)
    callback.search = new URLSearchParams({ state, invite: 'a'.repeat(64), expires_at: String(expiresAt), relay_url: relay }).toString()
    acceptedPath = callback.href
    const wrongHost = await new Promise<number | undefined>((resolve, reject) => {
      const req = request(callback, { headers: { Host: 'wrong.example' } }, response => {
        response.resume()
        response.on('end', () => resolve(response.statusCode))
      })
      req.on('error', reject)
      req.end()
    })
    assert.equal(wrongHost, 400)
    for (const [key, value] of [
      ['state', 'b'.repeat(32)], ['invite', 'short'], ['expires_at', '1'],
      ['expires_at', String(expiresAt + 300)], ['relay_url', 'wss://other.example.test/ws']
    ] as const) {
      const tampered = new URL(callback)
      tampered.searchParams.set(key, value)
      assert.equal((await fetch(tampered)).status, 400)
    }
    assert.equal((await fetch(callback.href + '&state=' + state)).status, 400)
    const approved = await fetch(callback)
    assert.equal(approved.status, 200)
    assert.equal(approved.headers.get('cache-control'), 'no-store')
    assert.match(await approved.text(), /Continue in the Ayin-Relay installer/)
  })
  assert.equal(result.version, 2)
  assert.equal(result.relay_url, relay)
  assert.equal(result.invite, 'a'.repeat(64))
  assert.equal(result.expires_at, expiresAt)
  assert.equal(createHash('sha256').update(result.enrollment_verifier, 'ascii').digest('hex'), challenge)
  await assert.rejects(fetch(acceptedPath))
})

test('browser enrollment closes its listener after cancellation or timeout', async () => {
  let listener = ''
  await assert.rejects(receiveBrowserEnrollment(portal, relay, async ({ url }) => {
    listener = 'http://127.0.0.1:' + new URL(url).searchParams.get('port')
  }, 30), /timed out/)
  await assert.rejects(fetch(listener))
})
