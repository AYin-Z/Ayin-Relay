import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'
import test from 'node:test'

import { daemonCommand } from '../src/commands/daemon.js'
import {
  daemonProcessState,
  parseWindowsProcessSnapshot,
  windowsProcessMatchesOwner,
  windowsProcessOwnerState,
  windowsProcessProbeScript
} from '../src/lib/daemonStatus.js'

test('daemon liveness rejects a recycled pid owned by another executable', () => {
  assert.equal(daemonProcessState({ pid: process.pid, process_name: 'unrelated.exe' }), 'gone')
})

test('daemon liveness accepts the recorded executable case-insensitively', () => {
  assert.equal(daemonProcessState({ pid: process.pid, process_name: basename(process.execPath).toUpperCase() }), 'alive')
})

test('daemon status and lifecycle commands distinguish an unverified process from a confirmed exit', async t => {
  const home = await mkdtemp(join(tmpdir(), 'relay-daemon-status-'))
  t.after(() => rm(home, { recursive: true, force: true }))
  const homeKey = process.platform === 'win32' ? 'USERPROFILE' : 'HOME'
  const oldHome = process.env[homeKey]
  process.env[homeKey] = home
  t.after(() => {
    if (oldHome === undefined) delete process.env[homeKey]
    else process.env[homeKey] = oldHome
  })
  const profile = join(home, '.hermes')
  const statusPath = join(profile, 'daemon-status.json')
  await mkdir(profile)
  const record = { pid: 0, state: 'connected', started_at: 1, updated_at: 1 }
  const original = JSON.stringify(record)
  await writeFile(statusPath, original)
  const stdout = t.mock.method(process.stdout, 'write', () => true)
  const stderr = t.mock.method(process.stderr, 'write', () => true)
  try {
    const run = (subcommand: string, json = false) =>
      daemonCommand({ command: 'daemon', positional: [subcommand], flags: { json, 'no-color': true } })
    assert.equal(await run('status', true), 1)
    const status = JSON.parse(String(stdout.mock.calls.at(-1)?.arguments[0]))
    assert.deepEqual(status, { ...record, alive: false, process_state: 'unknown' })
    for (const subcommand of ['start', 'stop', 'restart']) {
      assert.equal(await run(subcommand), 1)
      assert.match(String(stderr.mock.calls.at(-1)?.arguments[0]), /could not verify daemon pid/)
      assert.equal(await readFile(statusPath, 'utf8'), original)
    }
    await writeFile(statusPath, JSON.stringify({ ...record, pid: process.pid, process_name: 'unrelated.exe' }))
    assert.equal(await run('stop'), 0)
    await assert.rejects(readFile(statusPath, 'utf8'), { code: 'ENOENT' })
  } finally {
    stdout.mock.restore()
    stderr.mock.restore()
  }
})

const owner = { pid: 123, process_name: 'HERMES-RELAY.EXE', executable_path: 'C:\\Relay\\hermes-relay.exe', process_started_at: 123456 }
const alive = { state: 'alive' as const, name: 'hermes-relay', executablePath: 'c:/relay/hermes-relay.exe', startedAt: 123456 }

test('Windows liveness recognizes only exact native markers and keeps unknown results locked', () => {
  assert.deepEqual(parseWindowsProcessSnapshot('HERMES_RELAY_PID_V1:{"state":"gone"}\r\n'), { state: 'gone' })
  for (const output of ['', 'INFO: No tasks are running', 'Access denied', '{"state":"gone"}',
    'HERMES_RELAY_PID_V1:broken', 'HERMES_RELAY_PID_V1:{"state":"alive"}',
    'noise\nHERMES_RELAY_PID_V1:{"state":"gone"}']) {
    const snapshot = parseWindowsProcessSnapshot(output)
    assert.equal(snapshot.state, 'unknown')
    assert.equal(windowsProcessOwnerState(owner, snapshot), 'unknown')
    assert.equal(windowsProcessMatchesOwner(owner, snapshot), true)
  }
  assert.equal(windowsProcessMatchesOwner(owner, { state: 'gone' }), false)
})

test('Windows lock owner distinguishes recycled PID images, paths and process creation times', () => {
  assert.equal(windowsProcessMatchesOwner(owner, alive), true)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, name: 'powershell' }), false)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, executablePath: 'C:\\Other\\hermes-relay.exe' }), false)
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, startedAt: 234567 }), false)
  assert.equal(windowsProcessOwnerState(owner, { ...alive, executablePath: null }), 'unknown')
  assert.equal(windowsProcessOwnerState(owner, { ...alive, startedAt: null }), 'unknown')
  // Hold the lock while optional process identity fields are inaccessible.
  assert.equal(windowsProcessMatchesOwner(owner, { ...alive, executablePath: null, startedAt: null }), true)
  // Legacy locks have no path/start-time metadata; still compare their image.
  assert.equal(windowsProcessMatchesOwner({ pid: 123, process_name: 'hermes-relay.exe' }, alive), true)
})

test('Windows probe builds fixed native markers with only validated numeric PID input', () => {
  assert.match(windowsProcessProbeScript(123), /GetProcessById\(123\)/)
  assert.match(windowsProcessProbeScript(123), /catch \[ArgumentException\]/)
  assert.match(windowsProcessProbeScript(123), /state='unknown'/)
  for (const pid of [0, -1, NaN, Infinity, 1.1]) assert.throws(() => windowsProcessProbeScript(pid))
})
