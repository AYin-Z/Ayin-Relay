import { execFile } from 'node:child_process'
import { open } from 'node:fs/promises'
import { win32 } from 'node:path'
import { promisify } from 'node:util'

const probe = [
  '$ErrorActionPreference = "Stop"',
  '$sha = [System.Security.Cryptography.SHA256]::Create()',
  'try {',
  '  foreach ($file in @($env:AYIN_UPDATE_CURRENT, $env:AYIN_UPDATE_CANDIDATE)) {',
  '    $signature = Get-AuthenticodeSignature -LiteralPath $file',
  '    if ($signature.Status -ne "Valid" -or -not $signature.SignerCertificate) { exit 1 }',
  '    [BitConverter]::ToString($sha.ComputeHash($signature.SignerCertificate.RawData)).Replace("-", "").ToLowerInvariant()',
  '  }',
  '} finally { $sha.Dispose() }',
  'if ($env:AYIN_UPDATE_READ_IDENTITY -eq "1") {',
  '  $metadata = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($env:AYIN_UPDATE_CANDIDATE)',
  '  @{ product = $metadata.ProductName; description = $metadata.FileDescription } | ConvertTo-Json -Compress',
  '}'
].join('\n')

export function requireSameWindowsPublisher(output: string): void {
  const fingerprints = output.trim().split(/\r?\n/)
  if (fingerprints.length !== 2 || !/^[0-9a-f]{64}$/.test(fingerprints[0]!) ||
      fingerprints[0] !== fingerprints[1]) {
    throw new Error('Windows update publisher does not match the installed program')
  }
}

const windowsNodeProduct = 'Ayin-Relay Windows Node'

export function requireWindowsNodeVersion(output: string, expectedVersion: string): void {
  const lines = output.trim().split(/\r?\n/)
  if (lines.length !== 3) throw new Error('Windows node update identity is missing')
  requireSameWindowsPublisher(lines.slice(0, 2).join('\n'))
  const identity: unknown = JSON.parse(lines[2]!)
  if (!identity || typeof identity !== 'object' ||
      !('product' in identity) || identity.product !== windowsNodeProduct ||
      !('description' in identity) || identity.description !== `${windowsNodeProduct} ${expectedVersion}`) {
    throw new Error('Windows node update does not match the approved product and version')
  }
}

/** Reject Win32, ARM64 and non-PE files before applying a Windows x64 node update. */
export async function requireWindowsX64Executable(candidate: string): Promise<void> {
  const file = await open(candidate, 'r')
  try {
    const dos = Buffer.alloc(64)
    const header = Buffer.alloc(6)
    const first = await file.read(dos, 0, dos.length, 0)
    const offset = dos.readUInt32LE(60)
    if (first.bytesRead !== dos.length || dos.readUInt16LE(0) !== 0x5a4d || offset < 64 || offset > 1024 * 1024) {
      throw new Error('Invalid Windows update executable')
    }
    const second = await file.read(header, 0, header.length, offset)
    if (second.bytesRead !== header.length || header.readUInt32LE(0) !== 0x00004550 ||
        header.readUInt16LE(4) !== 0x8664) {
      throw new Error('Windows node update must target x64')
    }
  } finally {
    await file.close()
  }
}

/** Both the current installation and the update must have valid Windows signatures. */
export async function verifyWindowsUpdate(
  current: string, candidate: string, expectedNodeVersion?: string
): Promise<void> {
  if (expectedNodeVersion !== undefined) await requireWindowsX64Executable(candidate)
  const program = win32.join(process.env.SystemRoot || 'C:\\Windows',
    'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
  let output: string
  try {
    const { stdout } = await promisify(execFile)(program, ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', probe], {
      env: { ...process.env, AYIN_UPDATE_CURRENT: current, AYIN_UPDATE_CANDIDATE: candidate,
        AYIN_UPDATE_READ_IDENTITY: expectedNodeVersion === undefined ? '0' : '1' },
      windowsHide: true,
      timeout: 20000,
      maxBuffer: 4096
    })
    output = stdout
  } catch {
    throw new Error('Windows update requires two valid Authenticode signatures. Use a trusted signed installer to migrate an unsigned installation.')
  }
  if (expectedNodeVersion === undefined) {
    requireSameWindowsPublisher(output)
  } else {
    requireWindowsNodeVersion(output, expectedNodeVersion)
  }
}
