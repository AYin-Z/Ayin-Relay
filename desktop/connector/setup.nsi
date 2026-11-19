Unicode True
!include "FileFunc.nsh"
RequestExecutionLevel user
SetCompressor /SOLID lzma
Name "Ayin-Relay Windows Connector"
OutFile "${OUTPUT}"
VIProductVersion "${VERSION_NUM}"
VIAddVersionKey "ProductName" "Ayin-Relay"
VIAddVersionKey "ProductVersion" "${VERSION_NUM}"
VIAddVersionKey "FileDescription" "Ayin-Relay Windows Connector Setup ${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION_NUM}"
VIAddVersionKey "LegalCopyright" "Ayin-Relay contributors, MIT License"

Page instfiles

Function .onInit
  IfSilent 0 interactive
  SetErrorLevel 1
  Quit
interactive:
FunctionEnd

Section
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  File "launch.ps1"
  File "install.ps1"
  File "Stop.cmd"
  File "Status.cmd"
  File "Uninstall.cmd"
  File "hermes-relay.exe"
  File "manifest.json"
  File "portal.json"
  ${GetParameters} $1
  StrCmp $1 "/MIGRATECERT" 0 ordinary
  StrCpy $1 "-MigrateCertificate"
  Goto invoke
ordinary:
  StrCpy $1 ""
invoke:
  ClearErrors
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "$PLUGINSDIR\launch.ps1" -InstallerPath "$EXEPATH" $1' $0
  IfErrors failed
  StrCmp $0 "0" installed
failed:
  SetErrorLevel 1
  MessageBox MB_OK|MB_ICONSTOP "Ayin-Relay setup did not finish. Check the installation window and try again."
  Abort
installed:
SectionEnd
