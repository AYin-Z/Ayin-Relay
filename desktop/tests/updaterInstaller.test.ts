import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { installerRunsSilently, updateCommand } from '../src/commands/update.js'
import {
  assetNameForPlatform,
  checkForUpdate,
  compareVersions,
  downloadAndInstall,
  finalizePendingUpdateAt,
  verifyPendingWindowsNode
} from '../src/updater.js'
import { VERSION } from '../src/version.js'
import {
  requireSameWindowsPublisher,
  requireWindowsNodeVersion,
  requireWindowsX64Executable
} from '../src/lib/windowsUpdateTrust.js'

async function captureStdout(run: () => Promise<number>): Promise<{ code: number; output: string }> {
  const originalWrite = process.stdout.write
  let output = ''
  process.stdout.write = ((chunk: string | Uint8Array) => {
    output += typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8')
    return true
  }) as typeof process.stdout.write
  try {
    return { code: await run(), output }
  } finally {
    process.stdout.write = originalWrite
  }
}

test('desktop installer selection downloads the requested asset to an exact verified path', async () => {
  const originalFetch = globalThis.fetch
  const scratch = await mkdtemp(join(tmpdir(), 'hermes-installer-update-'))
  const target = join(scratch, 'hermes-relay-setup.exe')
  const assetName = 'hermes-relay-windows-x64-setup.exe'
  const bytes = Buffer.from('verified desktop installer fixture')
  const checksum = createHash('sha256').update(bytes).digest('hex')

  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url.includes('/releases?')) {
      return new Response(JSON.stringify([{
        tag_name: 'desktop-v9.0.0',
        prerelease: false,
        published_at: '2026-08-11T00:00:00Z',
        assets: [{ name: assetName, browser_download_url: 'https://download.test/setup.exe', size: bytes.length }]
      }]), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    if (url.endsWith('/SHA256SUMS.txt')) {
      return new Response(`${checksum}  ${assetName}\n`, { status: 200 })
    }
    if (url === 'https://download.test/setup.exe') {
      return new Response(bytes, { status: 200, headers: { 'content-length': String(bytes.length) } })
    }
    return new Response('not found', { status: 404 })
  }

  try {
    const info = await checkForUpdate({ repo: 'example/hermes-relay', assetName })
    assert.ok(info)
    assert.equal(info.asset_name, assetName)
    assert.equal(info.asset_url, 'https://download.test/setup.exe')

    const result = await downloadAndInstall(info, { targetPath: target, cooperative: false })
    assert.deepEqual(result, { installedPath: target, needsRestart: false, sha256: checksum })

    const rejecting = join(scratch, 'rejected.exe')
    await assert.rejects(downloadAndInstall(info, { targetPath: rejecting, cooperative: false,
      verifyCandidate: async candidate => {
        assert.deepEqual(await readFile(candidate), bytes)
        throw new Error('Publisher rejected')
      }
    }), /Publisher rejected/)
    await assert.rejects(stat(rejecting), { code: 'ENOENT' })
    assert.deepEqual(await readdir(scratch), ['hermes-relay-setup.exe'])
    assert.deepEqual(await readFile(target), bytes)
  } finally {
    globalThis.fetch = originalFetch
    await rm(scratch, { recursive: true, force: true })
  }
})

test('the default update channel queries only Ayin-Relay releases', async () => {
  const originalFetch = globalThis.fetch
  const requested: string[] = []
  globalThis.fetch = async (input) => {
    requested.push(String(input))
    return new Response(JSON.stringify([]), { status: 200 })
  }

  try {
    assert.equal(await checkForUpdate(), null)
    assert.deepEqual(requested, [
      'https://api.github.com/repos/AYin-Z/Ayin-Relay/releases?per_page=100&page=1'
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('release discovery paginates past mixed-surface releases', async () => {
  const originalFetch = globalThis.fetch
  const requested: string[] = []
  const nonDesktopRelease = (index: number) => ({
    tag_name: index % 2 === 0 ? `android-v1.${index}.0` : `server-v1.${index}.0`,
    prerelease: false,
    published_at: '2026-08-11T00:00:00Z',
    assets: []
  })
  globalThis.fetch = async (input) => {
    const url = String(input)
    requested.push(url)
    if (url.endsWith('page=1')) {
      return new Response(JSON.stringify(Array.from({ length: 100 }, (_, index) => nonDesktopRelease(index))), { status: 200 })
    }
    if (url.endsWith('page=2')) {
      return new Response(JSON.stringify([{
        tag_name: 'desktop-v9.1.0',
        prerelease: false,
        published_at: '2026-08-12T00:00:00Z',
        assets: []
      }]), { status: 200 })
    }
    return new Response('not found', { status: 404 })
  }

  try {
    const info = await checkForUpdate({ repo: 'example/hermes-relay', assetName: 'hermes-relay-linux-arm64' })
    assert.ok(info)
    assert.equal(info.latest_tag, 'desktop-v9.1.0')
    assert.equal(info.asset_name, 'hermes-relay-linux-arm64')
    assert.deepEqual(requested, [
      'https://api.github.com/repos/example/hermes-relay/releases?per_page=100&page=1',
      'https://api.github.com/repos/example/hermes-relay/releases?per_page=100&page=2'
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('Linux arm64 maps to the published Bun asset', () => {
  assert.equal(assetNameForPlatform('linux', 'arm64'), 'hermes-relay-linux-arm64')
})

test('cooperative updater preserves the previous build when there is no pending update', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'hermes-updater-old-cleanup-'))
  const target = join(scratch, 'hermes-relay.exe')
  const oldPath = join(scratch, 'hermes-relay.old.exe')
  try {
    await writeFile(target, 'current')
    await writeFile(oldPath, 'previous')
    await finalizePendingUpdateAt(target, async () => assert.fail('no candidate'))
    assert.equal(await readFile(oldPath, 'utf8'), 'previous')
    assert.equal(await readFile(target, 'utf8'), 'current')
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
})

test('Windows update publisher requires two matching SHA-256 fingerprints', () => {
  const pin = 'a'.repeat(64)
  assert.doesNotThrow(() => requireSameWindowsPublisher(`${pin}\r\n${pin}\r\n`))
  for (const output of ['', pin, `${pin}\n${'b'.repeat(64)}`, 'invalid\ninvalid']) {
    assert.throws(() => requireSameWindowsPublisher(output), /publisher/)
  }
})

test('Windows node verification matches the exact approved version and product', () => {
  assert.equal(compareVersions('1.2.3-rc-hotfix.2', '1.2.3-rc-hotfix.1'), 1)
  assert.equal(compareVersions('1.2.3-rc-hotfix.1', '1.2.3-rc-hotfix.2'), -1)
  const fingerprint = 'c'.repeat(64)
  const probe = (product: string, version: string) =>
    `${fingerprint}\n${fingerprint}\n${JSON.stringify({ product, description: `Ayin-Relay Windows Node ${version}` })}`
  const product = 'Ayin-Relay'
  assert.doesNotThrow(() => requireWindowsNodeVersion(probe(product, '1.2.3-rc.2'), '1.2.3-rc.2'))
  for (const mismatch of [probe(product, '1.2.3'), probe(product, '1.2.3-rc.1'), probe('Other product', '1.2.3-rc.2')]) {
    assert.throws(() => requireWindowsNodeVersion(mismatch, '1.2.3-rc.2'), /product and version/)
  }
})

test('Windows node verification rejects other architectures and truncated executables', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'ayin-relay-update-pe-'))
  const candidate = join(scratch, 'candidate.exe')
  const bytes = Buffer.alloc(74)
  bytes.writeUInt16LE(0x5a4d, 0)
  bytes.writeUInt32LE(68, 60)
  bytes.writeUInt32LE(0x4550, 68)
  try {
    bytes.writeUInt16LE(0x8664, 72)
    await writeFile(candidate, bytes)
    await requireWindowsX64Executable(candidate)
    for (const machine of [0x014c, 0xaa64]) {
      bytes.writeUInt16LE(machine, 72)
      await writeFile(candidate, bytes)
      await assert.rejects(requireWindowsX64Executable(candidate), /x64/)
    }
    await writeFile(candidate, bytes.subarray(0, 64))
    await assert.rejects(requireWindowsX64Executable(candidate))
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
})

test('pending Windows node verifies the selected release again after restart', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'ayin-relay-update-receipt-'))
  const current = join(scratch, 'hermes-relay.exe')
  const candidate = join(scratch, 'hermes-relay.new.exe')
  const record = `${candidate}.json`
  const contents = 'approved fixture'
  const sha256 = createHash('sha256').update(contents).digest('hex')
  const verification: unknown[][] = []
  const verify = async (...args: unknown[]) => { verification.push(args) }
  try {
    await writeFile(candidate, contents)
    await assert.rejects(verifyPendingWindowsNode(current, candidate, '1.0.0', verify))
    for (const version of ['0.9.0', '1.0.0', 'next', 'v1.2.0']) {
      await writeFile(record, JSON.stringify({ version, sha256 }))
      await assert.rejects(verifyPendingWindowsNode(current, candidate, '1.0.0', verify), /approved newer release/)
    }
    await writeFile(record, JSON.stringify({ version: '1.1.0', sha256 }))
    await writeFile(candidate, 'different fixture')
    await assert.rejects(verifyPendingWindowsNode(current, candidate, '1.0.0', verify), /bytes differ/)
    assert.deepEqual(verification, [])
    await writeFile(candidate, contents)
    await verifyPendingWindowsNode(current, candidate, '1.0.0', verify)
    assert.deepEqual(verification, [[current, candidate, '1.1.0']])
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
})

test('cooperative swap re-verifies the stage and restores the original after a failed replacement', async () => {
  const scratch = await mkdtemp(join(tmpdir(), 'hermes-updater-restore-'))
  const target = join(scratch, 'hermes-relay.exe')
  const staged = join(scratch, 'hermes-relay.new.exe')
  const backup = join(scratch, 'hermes-relay.old.exe')
  try {
    await writeFile(target, 'current')
    await writeFile(staged, 'candidate')
    await finalizePendingUpdateAt(target, async candidate => {
      assert.equal(candidate, staged)
      throw new Error('Publisher mismatch')
    })
    assert.equal(await readFile(target, 'utf8'), 'current')
    await assert.rejects(stat(backup), { code: 'ENOENT' })

    await finalizePendingUpdateAt(target, async () => {}, async (from, to) => {
      if (from === staged) throw new Error('simulated Windows sharing violation')
      await rename(from, to)
    })
    assert.equal(await readFile(target, 'utf8'), 'current')
    assert.equal(await readFile(staged, 'utf8'), 'candidate')

    await finalizePendingUpdateAt(target, async () => {})
    assert.equal(await readFile(target, 'utf8'), 'candidate')
    assert.equal(await readFile(backup, 'utf8'), 'current')
    await assert.rejects(stat(staged), { code: 'ENOENT' })
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
})

test('installer check reports a newer local preview without treating it as an error', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify([{
    tag_name: 'desktop-v0.4.0-alpha.2',
    prerelease: true,
    published_at: '2026-07-14T01:13:36Z',
    assets: [{
      name: 'hermes-relay-windows-x64-setup.exe',
      browser_download_url: 'https://download.test/setup.exe',
      size: 27_335_311
    }]
  }]), { status: 200, headers: { 'content-type': 'application/json' } })

  try {
    const check = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { installer: true, check: true, json: true, repo: 'example/hermes-relay' }
    }, {
      platform: 'win32',
      bundle: {
        installed: false,
        path: 'C:\\Hermes\\hermes-relay-tray.exe',
        version: null,
        running: false
      }
    }))
    assert.equal(check.code, 0)
    const report = JSON.parse(check.output)
    assert.equal(report.current, VERSION)
    assert.equal(report.latest_version, '0.4.0-alpha.2')
    assert.equal(report.up_to_date, true)
    assert.equal(report.ahead_of_latest, true)
    assert.equal(report.error, null)

    const install = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { installer: true, yes: true, json: true, repo: 'example/hermes-relay' }
    }, {
      platform: 'win32',
      bundle: {
        installed: false,
        path: 'C:\\Hermes\\hermes-relay-tray.exe',
        version: null,
        running: false
      }
    }))
    assert.equal(install.code, 2)
    assert.match(JSON.parse(install.output).error, /older than the installed CLI/)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('default Windows update targets the installer when an older UI is installed', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify([{
    tag_name: `desktop-v${VERSION}`,
    prerelease: VERSION.includes('-'),
    published_at: '2026-09-01T00:00:00Z',
    assets: [
      {
        name: 'hermes-relay-win-x64.exe',
        browser_download_url: 'https://download.test/hermes-relay.exe',
        size: 10
      },
      {
        name: 'hermes-relay-windows-x64-setup.exe',
        browser_download_url: 'https://download.test/setup.exe',
        size: 20
      }
    ]
  }]), { status: 200, headers: { 'content-type': 'application/json' } })

  try {
    const check = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { check: true, json: true, repo: 'example/hermes-relay' }
    }, {
      platform: 'win32',
      bundle: {
        installed: true,
        path: 'C:\\Hermes\\hermes-relay-tray.exe',
        version: '0.4.0-beta.4',
        running: true
      }
    }))
    assert.equal(check.code, 0)
    const report = JSON.parse(check.output)
    assert.equal(report.target, 'cli_ui')
    assert.equal(report.asset_name, 'hermes-relay-windows-x64-setup.exe')
    assert.equal(report.ui_installed, true)
    assert.equal(report.ui_version, '0.4.0-beta.4')
    assert.equal(report.versions_in_sync, false)
    assert.equal(report.up_to_date, false)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('default Windows update preserves a headless CLI-only installation', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify([{
    tag_name: `desktop-v${VERSION}`,
    prerelease: VERSION.includes('-'),
    published_at: '2026-09-01T00:00:00Z',
    assets: [{
      name: 'hermes-relay-win-x64.exe',
      browser_download_url: 'https://download.test/hermes-relay.exe',
      size: 10
    }]
  }]), { status: 200, headers: { 'content-type': 'application/json' } })

  try {
    const check = await captureStdout(() => updateCommand({
      command: 'update',
      positional: [],
      flags: { check: true, json: true, repo: 'example/hermes-relay' }
    }, {
      platform: 'win32',
      bundle: {
        installed: false,
        path: 'C:\\Hermes\\hermes-relay-tray.exe',
        version: null,
        running: false
      }
    }))
    assert.equal(check.code, 0)
    const report = JSON.parse(check.output)
    assert.equal(report.target, 'cli')
    assert.equal(report.asset_name, 'hermes-relay-win-x64.exe')
    assert.equal(report.ui_installed, false)
    assert.equal(report.versions_in_sync, true)
    assert.equal(report.up_to_date, true)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('existing bundle updates suppress the installer finish-page restart choice', () => {
  assert.equal(installerRunsSilently(true, false, false), true)
  assert.equal(installerRunsSilently(false, false, false), false)
  assert.equal(installerRunsSilently(false, true, false), true)
})
