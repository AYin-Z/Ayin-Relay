import { createWriteStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { pipeline } from 'node:stream/promises'

import { isWindowsNodeVersion, stageWindowsNodeRelease } from './updater.js'

const MAX_RELEASE_BYTES = 128 * 1024 * 1024

export interface RelayReleaseOffer {
  version: string
  sha256: string
  size: number
}

async function requestRelease(
  relay: string, token: string, path: string, timeoutMs: number
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
    headers: { Authorization: `Bearer ${token}`, 'Accept-Encoding': 'identity', 'Cache-Control': 'no-store' },
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

export async function fetchRelayRelease(relay: string, token: string): Promise<RelayReleaseOffer | null> {
  const response = await requestRelease(relay, token, '/desktop/rollout/release', 15_000)
  if (response.status !== 200) {
    await response.body?.cancel()
    if (response.status === 404) return null
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
  return { version: offer.version, sha256: offer.sha256, size: offer.size }
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
  })
}
