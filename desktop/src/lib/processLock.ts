import { randomUUID } from 'node:crypto'
import * as fs from 'node:fs/promises'
import * as path from 'node:path'

import { processOwnerIsAlive, windowsProcessSnapshot } from './daemonStatus.js'

const LOCK_POLL_MS = 50
const INCOMPLETE_LOCK_GRACE_MS = 1_000

interface ProcessLockOwner {
  pid: number
  process_name: string
  executable_path?: string
  process_started_at?: number
  token: string
  created_at: number
  purpose: string
}

export interface ProcessLock {
  owner: ProcessLockOwner
  release: () => Promise<void>
}

interface ProcessLockOptions {
  timeoutMs: number
  purpose: string
  now?: () => number
  sleep?: (ms: number) => Promise<void>
  ownerAlive?: (owner: ProcessLockOwner) => boolean
}

async function readProcessLockOwner(lockPath: string): Promise<ProcessLockOwner | null> {
  try {
    const parsed = JSON.parse(await fs.readFile(path.join(lockPath, 'owner.json'), 'utf8')) as Partial<ProcessLockOwner>
    if (
      typeof parsed.pid !== 'number' ||
      typeof parsed.process_name !== 'string' ||
      typeof parsed.token !== 'string' ||
      typeof parsed.created_at !== 'number' ||
      typeof parsed.purpose !== 'string'
    ) return null
    return parsed as ProcessLockOwner
  } catch {
    return null
  }
}

function processLockOwnerAlive(owner: ProcessLockOwner): boolean {
  return processOwnerIsAlive(owner)
}

async function releaseProcessLock(lockPath: string, token: string): Promise<void> {
  const owner = await readProcessLockOwner(lockPath)
  if (owner?.token !== token) return
  try {
    // Remove the ownership record before the directory. mkdir remains blocked
    // until rmdir succeeds, so a newer owner cannot appear between the token
    // check and release.
    await fs.unlink(path.join(lockPath, 'owner.json'))
    await fs.rmdir(lockPath)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
}

/** Atomic directory lock shared by compiled Bun binaries and Node-based dev
 * invocations. Stale recovery quarantines an observed directory before it is
 * removed, while token-checked release never recursively removes the path. */
export async function acquireProcessLock(lockPath: string, options: ProcessLockOptions): Promise<ProcessLock> {
  const now = options.now ?? Date.now
  const sleep = options.sleep ?? (ms => new Promise(resolve => setTimeout(resolve, ms)))
  const ownerAlive = options.ownerAlive ?? processLockOwnerAlive
  const deadline = now() + options.timeoutMs
  const processSnapshot = process.platform === 'win32' ? windowsProcessSnapshot(process.pid) : null
  const owner: ProcessLockOwner = {
    pid: process.pid,
    executable_path: process.platform === 'win32'
      ? processSnapshot?.state === 'alive' ? processSnapshot.executablePath ?? undefined : undefined
      : process.execPath,
    process_started_at: processSnapshot?.state === 'alive' ? processSnapshot.startedAt ?? undefined : undefined,
    process_name: path.basename(process.execPath),
    token: randomUUID(),
    created_at: now(),
    purpose: options.purpose
  }
  await fs.mkdir(path.dirname(lockPath), { recursive: true })

  while (true) {
    try {
      await fs.mkdir(lockPath)
      try {
        await fs.writeFile(
          path.join(lockPath, 'owner.json'),
          JSON.stringify(owner) + '\n',
          { encoding: 'utf8', flag: 'wx', mode: 0o600 }
        )
      } catch (error) {
        await fs.rm(lockPath, { recursive: true, force: true }).catch(() => undefined)
        throw error
      }
      return { owner, release: () => releaseProcessLock(lockPath, owner.token) }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
    }

    const observed = await readProcessLockOwner(lockPath)
    let stale = observed !== null && !ownerAlive(observed)
    if (!observed) {
      try {
        const stat = await fs.stat(lockPath)
        stale = now() - stat.mtimeMs >= INCOMPLETE_LOCK_GRACE_MS
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue
        throw error
      }
    }
    if (stale) {
      const quarantinePath = `${lockPath}.stale-${process.pid}-${randomUUID()}`
      try {
        await fs.rename(lockPath, quarantinePath)
        await fs.rm(quarantinePath, { recursive: true, force: true })
        continue
      } catch (error) {
        if (['ENOENT', 'EEXIST', 'ENOTEMPTY'].includes((error as NodeJS.ErrnoException).code ?? '')) continue
        throw error
      }
    }
    if (now() >= deadline) {
      const detail = observed
        ? `owner pid ${observed.pid} (${observed.purpose})`
        : 'owner metadata is still being created'
      throw new Error(`timed out after ${options.timeoutMs}ms waiting for ${options.purpose} lock; ${detail}`)
    }
    await sleep(Math.min(LOCK_POLL_MS, Math.max(1, deadline - now())))
  }
}
