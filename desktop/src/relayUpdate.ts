import { createWriteStream } from 'node:fs'
import { open, stat } from 'node:fs/promises'
import { pipeline } from 'node:stream/promises'

import { compareVersions, isWindowsNodeVersion, stageWindowsNodeRelease } from './updater.js'
import { VERSION } from './version.js'

const MAX_RELEASE_BYTES = 128 * 1024 * 1024

export interface RelayReleaseOffer {
  version: string
  sha256: string
  size: number
  rolloutId?: string
}

async function requestRelease(
  relay: string, token: string, path: string, timeoutMs: number, receipt?: RolloutReceipt
): Promise<Response> {
  const origin = new URL(relay)
  const relayPath = origin.pathname
  if (origin.protocol !== 'wss:' || (relayPath !== '/' && !relayPath.endsWith('/ws')) ||
      origin.username || origin.password || origin.search || origin.hash ||
      process.env.NODE_TLS_REJECT_UNAUTHORIZED === '0') {
    throw new Error('Updates require an authenticated WSS Relay origin')
  }
  origin.protocol = 'https:'
  origin.pathname = relayPath === '/' ? path : relayPath.slice(0, -'/ws'.length) + path
  return fetch(origin, {
    method: receipt === undefined ? 'GET' : 'POST',
    headers: {
      Authorization: `Bearer ${token}`, 'Accept-Encoding': 'identity', 'Cache-Control': 'no-store',
      ...(receipt === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: receipt === undefined ? undefined : JSON.stringify(receipt),
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs)
  })
}

async function *exactBytes(response: Response, limit: number): AsyncGenerator<Uint8Array> {
  if (!response.body) throw new Error('The Relay returned no update data')
  const reader = response.body.getReader()
  try {
    let received = 0
    for (;;) {
      const next = await reader.read()
      if (next.done) break
      received += next.value.byteLength
      if (received > limit) throw new Error('The Relay update response exceeds its size limit')
      yield next.value
    }
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

export async function fetchRelayRelease(relay: string, token: string, managed = false): Promise<RelayReleaseOffer | null> {
  const route = managed ? '/desktop/rollout/assignment' : '/desktop/rollout/release'
  const response = await requestRelease(relay, token, route, 15_000)
  if (response.status !== 200) {
    await response.body?.cancel()
    if (response.status === 404 || (managed && response.status === 403)) return null
    throw new Error(`Relay release offer returned HTTP ${response.status}`)
  }
  const chunks = []
  for await (const chunk of exactBytes(response, 4096)) chunks.push(chunk)
  let offer: unknown
  try {
    offer = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new Error('Invalid Windows release offer')
  }
  if (!offer || typeof offer !== 'object' ||
      !('platform' in offer) || offer.platform !== 'win32' ||
      !('arch' in offer) || offer.arch !== 'x64' ||
      !('version' in offer) || typeof offer.version !== 'string' || !isWindowsNodeVersion(offer.version) ||
      !('sha256' in offer) || typeof offer.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(offer.sha256) ||
      !('size' in offer) || typeof offer.size !== 'number' || !Number.isSafeInteger(offer.size) ||
      offer.size < 1 || offer.size > MAX_RELEASE_BYTES) {
    throw new Error('Invalid Windows release offer')
  }
  const metadata = { version: offer.version, sha256: offer.sha256, size: offer.size }
  if (!managed) return metadata
  if (!('rollout_id' in offer) || typeof offer.rollout_id !== 'string' || !/^[0-9a-f]{32}$/.test(offer.rollout_id)) {
    throw new Error('Invalid Windows rollout assignment ID')
  }
  return { ...metadata, rolloutId: offer.rollout_id }
}

export async function stageRelayRelease(
  relay: string, token: string, offer: RelayReleaseOffer, target = process.execPath
): ReturnType<typeof stageWindowsNodeRelease> {
  return stageWindowsNodeRelease(target, offer.version, offer.sha256, async path => {
    const asset = `/desktop/rollout/releases/${offer.version}/${offer.sha256}`
    const response = await requestRelease(relay, token, asset, 10 * 60_000)
    const encoding = response.headers.get('content-encoding')
    if (response.status !== 200 || response.headers.get('content-length') !== String(offer.size) ||
        (encoding !== null && encoding !== 'identity')) {
      await response.body?.cancel()
      throw new Error(`The Relay did not return the approved update file (HTTP ${response.status})`)
    }
    await pipeline(exactBytes(response, offer.size), createWriteStream(path, { flags: 'wx' }))
    if ((await stat(path)).size !== offer.size) throw new Error('The Relay update download is incomplete')
  }, undefined, offer.rolloutId)
}

interface RolloutReceipt {
  rollout_id: string
  version: string
  sha256: string
  attempt_id: string
  state: 'applied' | 'rolled_back'
  completed_at: number
}

async function readRolloutReceipt(target: string): Promise<RolloutReceipt | null> {
  let file
  try {
    file = await open(`${target}.rollout-result.json`, 'r')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return null
    throw error
  }
  try {
    const info = await file.stat()
    if (!info.isFile() || info.size > 1024) return null
    const bytes = Buffer.alloc(1025)
    const { bytesRead } = await file.read(bytes, 0, bytes.length, 0)
    if (bytesRead > 1024) return null
    let value: Partial<RolloutReceipt> | null
    try {
      value = JSON.parse(bytes.subarray(0, bytesRead).toString('utf8')) as Partial<RolloutReceipt> | null
    } catch {
      return null
    }
    if (!value || typeof value.rollout_id !== 'string' || !/^[0-9a-f]{32}$/.test(value.rollout_id) ||
        typeof value.version !== 'string' || !isWindowsNodeVersion(value.version) ||
        typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.sha256) ||
        typeof value.attempt_id !== 'string' || !/^[0-9a-f]{32}$/.test(value.attempt_id) ||
        (value.state !== 'applied' && value.state !== 'rolled_back') ||
        typeof value.completed_at !== 'number' || !Number.isSafeInteger(value.completed_at) || value.completed_at <= 0) return null
    const { rollout_id, version, sha256, attempt_id, state, completed_at } = value
    return { rollout_id, version, sha256, attempt_id, state, completed_at }
  } finally {
    await file.close()
  }
}

export async function pollWindowsRollout(
  relay: string, token: string, observed: { receipt?: string }, target = process.execPath
): Promise<void> {
  const receipt = await readRolloutReceipt(target)
  if (receipt && receipt.attempt_id !== observed.receipt) {
    const response = await requestRelease(relay, token, '/desktop/rollout/receipt', 15_000, receipt)
    await response.body?.cancel()
    if (![204, 403, 404, 409].includes(response.status)) {
      throw new Error(`Relay rollout receipt returned HTTP ${response.status}`)
    }
    observed.receipt = receipt.attempt_id
  }
  for (const suffix of ['.rollout-stage', '.rollout-trial']) {
    try {
      await stat(target + suffix)
      return
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
  }
  const offer = await fetchRelayRelease(relay, token, true)
  if (offer && compareVersions(offer.version, VERSION) > 0) {
    // A lost response cannot trigger a second attempt at a locally settled assignment.
    if (offer.rolloutId === receipt?.rollout_id) return
    await stageRelayRelease(relay, token, offer, target)
  }
}

export function startWindowsRolloutPolling(
  relay: string, token: string, onFailure: (message: string) => void
): NodeJS.Timeout {
  const observed: { receipt?: string } = {}
  let busy = false
  const timer = setInterval(async () => {
    if (busy) return
    busy = true
    try {
      await pollWindowsRollout(relay, token, observed)
    } catch (error) {
      onFailure(error instanceof Error ? error.message : 'Unable to check managed Windows updates')
    } finally {
      busy = false
    }
  }, 60_000)
  return timer.unref()
}
