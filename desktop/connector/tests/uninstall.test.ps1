$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../install.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw ($errors -join [Environment]::NewLine) }
$controlAssignment = $ast.Find({ param($n)
    $n -is [Management.Automation.Language.AssignmentStatementAst] -and
    $n.Left.VariablePath.UserPath -eq 'control' -and $n.Right.Extent.Text.StartsWith("@'")
},$true)
if (-not $controlAssignment) { throw 'Missing generated connector control script.' }
$literal = $controlAssignment.Right.Find({ param($n)
    $n -is [Management.Automation.Language.StringConstantExpressionAst] -and
    $n.StringConstantType -eq 'SingleQuotedHereString'
},$true)
if (-not $literal) { throw 'Missing generated control body.' }
$control = $literal.Value
$rendered = [regex]::Replace($control,'__[A-Z_]+__',{ param($token)
    if ($token.Value -eq '__FUNCTIONS__') { return '' }
    "'fixture'"
})
$controlErrors = $null
$null = [Management.Automation.Language.Parser]::ParseInput($rendered,[ref]$tokens,[ref]$controlErrors)
if ($controlErrors.Count) { throw ($controlErrors -join [Environment]::NewLine) }

foreach ($name in @('Get-ConnectorRolloutDigest','Read-ConnectorRolloutJson','Assert-ConnectorTask',
                    'Find-ConnectorTask','Suspend-ConnectorTask','Remove-ConnectorInstalledFile','Get-ConnectorUninstallHashes')) {
    $node = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
    if (-not $node) { throw ('Missing uninstall test target '+$name) }
    . ([ScriptBlock]::Create($node.Extent.Text))
}
function Assert-Case([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([scriptblock]$Action) {
    try { & $Action } catch { return }
    throw 'An unrelated or elevated task was accepted.'
}

$root = Join-Path ([IO.Path]::GetTempPath()) ('relay-uninstall-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($root)
try {
    $name = 'HermesRelay-Connector-0123456789ab'
    $sid = 'S-1-5-21-1000000-1000000-1000000-1001'
    $launcher = Join-Path $root 'wscript.exe'
    $launcherScript = Join-Path $root 'relay-connector-hidden.vbs'
    $arguments = '//B //Nologo "'+$launcherScript+'"'
    $actions = [pscustomobject]@{Count=1; Entry=@{Type=0; Path=$launcher; Arguments=$arguments}}
    $actions | Add-Member -MemberType ScriptMethod -Name Item -Value { param($index) $this.Entry }
    $task = [pscustomobject]@{
        Path=('\'+$name);Definition=@{Principal=@{RunLevel=0;LogonType=3;UserId=$sid};Actions=$actions}
        Enabled=$true;Instances=1;StopCalls=0
    }
    $task | Add-Member -MemberType ScriptMethod -Name GetInstances -Value { param($flags) @{Count=$this.Instances} }
    $task | Add-Member -MemberType ScriptMethod -Name Stop -Value { param($flags) $this.Instances=0; $this.StopCalls++ }
    Assert-ConnectorTask $task $name $sid $launcher $launcherScript
    $task.Definition.Principal.RunLevel = 1
    Assert-Rejected { Assert-ConnectorTask $task $name $sid $launcher $launcherScript }
    $task.Definition.Principal.RunLevel = 0
    Assert-Rejected { Assert-ConnectorTask $task $name ($sid+'2') $launcher $launcherScript }
    $actions.Entry.Arguments = '//B "other.vbs"'
    Assert-Rejected { Assert-ConnectorTask $task $name $sid $launcher $launcherScript }
    $actions.Entry.Arguments = $arguments
    Suspend-ConnectorTask $task
    Suspend-ConnectorTask $task
    Assert-Case (-not $task.Enabled -and $task.Instances -eq 0 -and $task.StopCalls -eq 1) 'Pause must disable the task and drain its instances.'

    $missing = [pscustomobject]@{}
    $missing | Add-Member -MemberType ScriptMethod -Name GetTask -Value { param($requested) throw [IO.FileNotFoundException]::new() }
    Assert-Case ($null -eq (Find-ConnectorTask $missing $name)) 'Absence must permit retry after removing the task.'
    $denied = [pscustomobject]@{}
    $denied | Add-Member -MemberType ScriptMethod -Name GetTask -Value { param($requested) throw [UnauthorizedAccessException]::new() }
    Assert-Rejected { Find-ConnectorTask $denied $name }

    $file = Join-Path $root 'hermes-relay.exe'
    Remove-ConnectorInstalledFile $file @()
    [IO.File]::WriteAllText($file,'original fixture')
    $installHash = Get-ConnectorRolloutDigest $file
    [IO.File]::WriteAllText($file,'restored fixture')
    $restoredHash = Get-ConnectorRolloutDigest $file
    $state = @{state='rolled_back';sha256=('a'*64);active_sha256=$restoredHash}
    [IO.File]::WriteAllText(($file+'.rollout-result.json'),($state | ConvertTo-Json -Compress))
    Remove-ConnectorInstalledFile $file @(Get-ConnectorUninstallHashes $file $installHash)
    Assert-Case (-not [IO.File]::Exists($file)) 'A verified restored connector should be removed.'
    [IO.File]::WriteAllText($file,'unrelated replacement')
    Remove-ConnectorInstalledFile $file @(Get-ConnectorUninstallHashes $file $installHash) 3>$null
    Assert-Case ([IO.File]::ReadAllText($file) -eq 'unrelated replacement') 'Unidentified replacement must be preserved.'
    [IO.File]::WriteAllText(($file+'.rollout-result.json'),'malformed')
    Assert-Case (@(Get-ConnectorUninstallHashes $file $installHash).Count -eq 1) 'Malformed receipt must not authorize file removal.'
    Write-Output 'PASS: exact task identity, disable and drain, already absent versus denied, retained unrelated files, and managed rollback ownership'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
