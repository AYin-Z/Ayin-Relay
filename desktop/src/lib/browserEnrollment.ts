import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { once } from 'node:events'
import { createServer, type ServerResponse } from 'node:http'

import type { ConnectorSetup } from '../commands/connector.js'
import { canonicalRelayUrl } from './hostAccessPolicy.js'

const callbackPath = '/ayin-relay/enrollment'
const waitMilliseconds = 5 * 60 * 1000

interface BrowserEnrollmentPrompt {
  url: string
  confirmationCode: string
}

type BrowserSetup = Extract<ConnectorSetup, { version: 2 }>

export function enrollmentPortalUrl(value: string): URL {
  const url = new URL(value)
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      !/^\/[a-zA-Z0-9/_-]+\/private\/enroll$/.test(url.pathname)) {
    throw new Error('An HTTPS browser enrollment endpoint is required')
  }
  return url
}

function reply(response: ServerResponse, status: number, message: string): void {
  response.writeHead(status, {
    'Cache-Control': 'no-store',
    'Connection': 'close',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
    'Content-Type': 'text/plain; charset=utf-8',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff'
  })
  response.end(message)
}

/** Await approval at the exact loopback endpoint for this installer instance. */
export async function receiveBrowserEnrollment(
  portal: string,
  remote: string,
  openApproval: (prompt: BrowserEnrollmentPrompt) => Promise<void>,
  timeoutMs = waitMilliseconds
): Promise<BrowserSetup> {
  const target = enrollmentPortalUrl(portal)
  const relayUrl = canonicalRelayUrl(remote)
  if (!relayUrl?.startsWith('wss://')) throw new Error('A WSS relay endpoint is required')
  const state = randomBytes(16).toString('hex')
  const verifier = randomBytes(32).toString('hex')
  const challenge = createHash('sha256').update(verifier, 'ascii').digest('hex')
  let host = ''
  let accepted = false
  let settle: ((setup: BrowserSetup) => void) | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined
  const result = new Promise<BrowserSetup>(resolve => { settle = resolve })
  const server = createServer({ headersTimeout: 5000, requestTimeout: 5000, maxHeaderSize: 8192 }, (req, res) => {
    if (req.method !== 'GET' || req.headersDistinct.host?.length !== 1 || req.headers.host !== host) {
      reply(res, 400, 'Invalid local enrollment request.')
      return
    }
    const url = new URL(`http://${host}${req.url?.startsWith('/') ? req.url : '/'}`)
    if (url.pathname !== callbackPath) {
      reply(res, 404, 'Enrollment endpoint not found.')
      return
    }
    const fields = ['state', 'invite', 'expires_at', 'relay_url'] as const
    if (accepted || url.searchParams.size !== fields.length || fields.some(field => url.searchParams.getAll(field).length !== 1)) {
      reply(res, 400, 'Invalid local enrollment response.')
      return
    }
    const received = url.searchParams.get('state')!
    const invite = url.searchParams.get('invite')!
    const expiration = url.searchParams.get('expires_at')!
    const expiresAt = Number(expiration)
    const now = Math.floor(Date.now() / 1000)
    const expectedState = Buffer.from(state, 'ascii')
    const receivedState = Buffer.from(received, 'utf8')
    if (receivedState.length !== expectedState.length || !timingSafeEqual(expectedState, receivedState) ||
        invite.length !== 64 || !/^[0-9a-f]{64}$/i.test(invite) ||
        !/^\d{1,12}$/.test(expiration) || expiresAt <= now || expiresAt > now + 360 ||
        canonicalRelayUrl(url.searchParams.get('relay_url')!) !== relayUrl) {
      reply(res, 400, 'Invalid local enrollment response. Return to the installer and try again.')
      return
    }
    accepted = true
    res.once('finish', () => settle?.({
      version: 2, relay_url: relayUrl, invite, expires_at: expiresAt, enrollment_verifier: verifier
    }))
    reply(res, 200, 'Registration approval returned. Continue in the Ayin-Relay installer.')
  })
  // Bound abandoned connections as well as the time spent waiting for approval.
  server.setTimeout(5000, socket => socket.destroy())
  try {
    const listening = once(server, 'listening')
    server.listen(0, '127.0.0.1')
    await listening
    const address = server.address()
    if (!address || typeof address === 'string') throw new Error('Local enrollment listener failed')
    host = `127.0.0.1:${address.port}`
    target.search = new URLSearchParams({ port: String(address.port), state, challenge }).toString()
    const expired = new Promise<never>((_, reject) => {
      timeout = setTimeout(() => reject(new Error('Browser enrollment timed out')), timeoutMs)
    })
    return await Promise.race([
      expired,
      (async () => {
        await openApproval({ url: target.href, confirmationCode: state.slice(0, 8).toUpperCase() })
        return result
      })()
    ])
  } finally {
    clearTimeout(timeout)
    server.close()
    server.closeAllConnections()
  }
}
