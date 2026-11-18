import { execFile } from 'node:child_process'
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
  '} finally { $sha.Dispose() }'
].join('\n')

export function requireSameWindowsPublisher(output: string): void {
  const fingerprints = output.trim().split(/\r?\n/)
  if (fingerprints.length !== 2 || !/^[0-9a-f]{64}$/.test(fingerprints[0]!) ||
      fingerprints[0] !== fingerprints[1]) {
    throw new Error('Windows update publisher does not match the installed program')
  }
}

/** Both the current installation and the update must have valid Windows signatures. */
export async function verifyWindowsUpdate(current: string, candidate: string): Promise<void> {
  const program = win32.join(process.env.SystemRoot || 'C:\\Windows',
    'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
  let output: string
  try {
    const { stdout } = await promisify(execFile)(program, ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', probe], {
      env: { ...process.env, AYIN_UPDATE_CURRENT: current, AYIN_UPDATE_CANDIDATE: candidate },
      windowsHide: true,
      timeout: 20000,
      maxBuffer: 4096
    })
    output = stdout
  } catch {
    throw new Error('Windows update requires two valid Authenticode signatures. Use a trusted signed installer to migrate an unsigned installation.')
  }
  requireSameWindowsPublisher(output)
}
