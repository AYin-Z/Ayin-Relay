import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const [, major, minor, patch] = /^(\d+)\.(\d+)\.(\d+)(?:[+-].+)?$/.exec(version) ?? []
if (!patch || [major, minor, patch].some(part => Number(part) > 65535)) {
  throw new Error('A desktop version representable in a Windows version resource is required')
}

const args = [
  'build', '--compile', '--minify', '--sourcemap', '--target=bun-windows-x64',
  'src/bunWindowsEntry.ts', '--outfile', 'dist/bin/hermes-relay-win-x64'
]
if (process.platform === 'win32') {
  args.push('--windows-title=Ayin-Relay',
    `--windows-description=Ayin-Relay Windows Node ${version}`,
    `--windows-version=${major}.${minor}.${patch}.0`)
} else {
  console.warn('Cross-built Windows executable is for testing. Build on Windows for release metadata.')
}
const result = spawnSync('bun', args, { stdio: 'inherit', windowsHide: true })
if (result.error) throw result.error
process.exitCode = result.status ?? 1
