import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

import { verifyWindowsUpdate } from '../../src/lib/windowsUpdateTrust.js'
import { verifyPendingWindowsNode } from '../../src/updater.js'

async function check(): Promise<void> {
  const [, , directory, version] = process.argv
  assert(directory && version && process.platform === 'win32')
  const current = join(directory, 'current.exe')
  const approved = join(directory, 'approved.exe')
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(approved)) hash.update(chunk)
  const sha256 = hash.digest('hex')

  await writeFile(approved + '.json', JSON.stringify({ version, sha256 }))
  assert.deepEqual(await verifyPendingWindowsNode(current, approved, '0.0.0'), { version, sha256 })
  await assert.rejects(verifyWindowsUpdate(current, approved, '999.0.0'), /approved product and version/)
  await assert.rejects(verifyWindowsUpdate(current, join(directory, 'other.exe'), version), /publisher does not match/)
  await assert.rejects(verifyWindowsUpdate(current, join(directory, 'unsigned.exe'), version), /valid Authenticode/)
  await writeFile(approved + '.json', JSON.stringify({ version, sha256: '0'.repeat(64) }))
  await assert.rejects(verifyPendingWindowsNode(current, approved, '0.0.0'), /bytes differ/)
  process.stdout.write('PASS: Windows accepted the approved staged bytes and rejected different publishers, missing signatures, versions and digests.\n')
}

void check().catch(error => {
  process.stderr.write(String(error) + '\n')
  process.exitCode = 1
})
