import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { updateCommand } from '../src/commands/update.js'
import { setStorePath } from '../src/remoteSessions.js'
import { fetchRelayRelease, pollWindowsRollout, stageRelayRelease } from '../src/relayUpdate.js'
import { stageWindowsNodeRelease } from '../src/updater.js'

const relay = 'wss://relay.example.test/ws'
const token = 'fixture-connector-credential'
const bytes = Buffer.from('node fixture')
const sha256 = createHash('sha256').update(bytes).digest('hex')
const offer = { version: '8.2.0', sha256, size: bytes.length }

test('Windows update requests stay on the stored Relay origin and refuse redirects', async t => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    ...offer, platform: 'win32', arch: 'x64'
  })))
  assert.deepEqual(await fetchRelayRelease(relay, token), offer)
  const first = fetch.mock.calls[0]
  assert.ok(first)
  const [url, options] = first.arguments as unknown as [URL, RequestInit]
  assert.equal(url.href, 'https://relay.example.test/desktop/rollout/release')
  assert.equal(options.redirect, 'error')
  assert.equal(new Headers(options.headers).get('authorization'), `Bearer ${token}`)
  for (const invalid of ['ws://relay.example.test', `${relay}?route=other`, 'wss://relay.example.test/other']) {
    await assert.rejects(fetchRelayRelease(invalid, token), /authenticated WSS Relay/)
  }
  assert.equal(fetch.mock.calls.length, 1)
  assert.deepEqual(await fetchRelayRelease('wss://relay.example.test/relay/ws', token), offer)
  const prefixed = fetch.mock.calls[1]
  assert.ok(prefixed)
  const [prefixedUrl] = prefixed.arguments as unknown as [URL]
  assert.equal(prefixedUrl.href, 'https://relay.example.test/relay/desktop/rollout/release')
})

test('release metadata is bounded, versioned and limited to Windows x64', async t => {
  let response = new Response(null, { status: 404 })
  t.mock.method(globalThis, 'fetch', async () => response)
  assert.equal(await fetchRelayRelease(relay, token), null)
  response = new Response(null, { status: 403 })
  await assert.rejects(fetchRelayRelease(relay, token), /HTTP 403/)
  const valid = { ...offer, platform: 'win32', arch: 'x64' }
  for (const invalid of [
    { ...valid, version: '8.2.0-01' },
    { ...valid, version: '65536.0.0' },
    { ...valid, arch: 'arm64' },
    { ...valid, size: 128 * 1024 * 1024 + 1 },
    { ...valid, sha256: 'a'.repeat(63) }
  ]) {
    response = new Response(JSON.stringify(invalid))
    await assert.rejects(fetchRelayRelease(relay, token), /Invalid Windows release offer/)
  }
  response = new Response(' '.repeat(4097))
  await assert.rejects(fetchRelayRelease(relay, token), /size limit/)
})

test('a Relay download must match the exact path, size and SHA-256 before Windows verification', async t => {
  const root = await mkdtemp(join(tmpdir(), 'ayin-relay-offer-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  let content = Buffer.alloc(bytes.length)
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(content, {
    headers: { 'content-length': String(offer.size) }
  }))
  const target = join(root, 'hermes-relay.exe')
  await writeFile(target, 'old executable')
  await assert.rejects(stageRelayRelease(relay, token, offer, target), /approved SHA-256/)
  const first = fetch.mock.calls[0]
  assert.ok(first)
  const [url] = first.arguments as unknown as [URL]
  assert.equal(url.href, `https://relay.example.test/desktop/rollout/releases/${offer.version}/${offer.sha256}`)
  content = bytes.subarray(0, -1)
  await assert.rejects(stageRelayRelease(relay, token, offer, target), /incomplete/)
  content = Buffer.concat([bytes, Buffer.from('extra')])
  await assert.rejects(stageRelayRelease(relay, token, offer, target), /size limit/)
  assert.deepEqual(await readdir(root), ['hermes-relay.exe'])
  assert.equal(await readFile(target, 'utf8'), 'old executable')
})

test('a verified release is staged with its approval record and retains the active binary', async t => {
  const root = await mkdtemp(join(tmpdir(), 'ayin-relay-approved-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const target = join(root, 'hermes-relay.exe')
  await writeFile(target, 'old executable')
  const download = async (path: string) => { await writeFile(path, bytes) }
  await assert.rejects(stageWindowsNodeRelease(target, offer.version, offer.sha256, download, async () => {
    throw new Error('Windows publisher mismatch')
  }), /publisher mismatch/)
  assert.deepEqual(await readdir(root), ['hermes-relay.exe'])
  const staged = await stageWindowsNodeRelease(target, offer.version, offer.sha256, download,
    async (current, path, version) => {
      assert.equal(current, target)
      assert.equal(version, offer.version)
      assert.deepEqual(await readFile(path), bytes)
    })
  assert.equal(staged.needsRestart, true)
  assert.equal(staged.installedPath, join(`${target}.rollout-stage`, 'candidate.exe'))
  await assert.rejects(stageWindowsNodeRelease(target, offer.version, offer.sha256, download, async () => {}))
  assert.deepEqual((await readdir(root)).sort(), ['hermes-relay.exe', 'hermes-relay.exe.rollout-stage'])
  assert.equal(await readFile(target, 'utf8'), 'old executable')
  assert.deepEqual(await readFile(staged.installedPath), bytes)
  assert.deepEqual(JSON.parse(await readFile(`${staged.installedPath}.json`, 'utf8')), {
    version: offer.version, sha256: offer.sha256
  })
})

test('the CLI checks with its saved desktop-only authorization and never stages in check mode', async t => {
  const root = await mkdtemp(join(tmpdir(), 'ayin-relay-local-session-'))
  const file = join(root, 'remote-sessions.json')
  setStorePath(file)
  t.after(async () => {
    setStorePath(null)
    await rm(root, { recursive: true, force: true })
  })
  const save = async (grants: object) => writeFile(file, JSON.stringify({
    sessions: { [relay]: { token, paired_at: 1, ttl_expires_at: null, grants } }
  }))
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    ...offer, platform: 'win32', arch: 'x64'
  })))
  const stdout = t.mock.method(process.stdout, 'write', () => true)
  const args = {
    command: 'update', positional: [],
    flags: { 'from-relay': true, check: true, json: true, remote: relay } as const
  }
  const windows = { platform: 'win32', arch: 'x64' } as const

  await save({ desktop: null, api: null })
  assert.equal(await updateCommand(args, windows), 1)
  assert.equal(fetch.mock.calls.length, 0)

  await save({ desktop: null })
  assert.equal(await updateCommand(args, windows), 0)
  assert.equal(fetch.mock.calls.length, 1)
  const last = stdout.mock.calls.at(-1)
  assert.ok(last)
  const [report] = last.arguments as unknown as [string]
  assert.equal(JSON.parse(report).staged, false)
  assert.deepEqual(JSON.parse(report).offer, offer)
})

test('a signed-node assignment uses its fixed origin and requires an explicit rollout ID', async t => {
  const release = { ...offer, platform: 'win32', arch: 'x64' }
  let message = release as object
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify(message)))
  await assert.rejects(fetchRelayRelease(relay, token, true), /rollout assignment ID/)
  message = { ...release, rollout_id: '5'.repeat(32) }
  assert.deepEqual(await fetchRelayRelease(relay, token, true), { ...offer, rolloutId: '5'.repeat(32) })
  const [url] = fetch.mock.calls[1]!.arguments as unknown as [URL]
  assert.equal(url.href, 'https://relay.example.test/desktop/rollout/assignment')
})

test('managed polling retries an unacknowledged receipt and does not reapply a settled assignment', async t => {
  const root = await mkdtemp(join(tmpdir(), 'ayin-relay-node-receipt-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const target = join(root, 'node.exe')
  const receipt = {
    rollout_id: '5'.repeat(32), attempt_id: '6'.repeat(32), state: 'rolled_back',
    version: offer.version, sha256, completed_at: 100
  }
  await writeFile(`${target}.rollout-result.json`, JSON.stringify({ ...receipt, unauthorized: 'ignored' }))
  let postStatus = 425
  const requests: string[] = []
  t.mock.method(globalThis, 'fetch', async (url: URL, request: RequestInit) => {
    requests.push(url.pathname)
    if (url.pathname.endsWith('/receipt')) {
      assert.equal(request.method, 'POST')
      assert.equal(request.redirect, 'error')
      assert.equal(new Headers(request.headers).get('authorization'), `Bearer ${token}`)
      assert.deepEqual(JSON.parse(request.body as string), receipt)
      return new Response(null, { status: postStatus })
    }
    assert.equal(url.pathname, '/desktop/rollout/assignment')
    return new Response(JSON.stringify({ ...offer, platform: 'win32', arch: 'x64', rollout_id: receipt.rollout_id }))
  })
  const state: { receipt?: string } = {}
  await assert.rejects(pollWindowsRollout(relay, token, state, target), /HTTP 425/)
  assert.equal(state.receipt, undefined)
  postStatus = 204
  await pollWindowsRollout(relay, token, state, target)
  await pollWindowsRollout(relay, token, state, target)
  assert.equal(state.receipt, receipt.attempt_id)
  assert.deepEqual(requests.map(path => path.split('/').at(-1)),
    ['receipt', 'receipt', 'assignment', 'assignment'])
})
