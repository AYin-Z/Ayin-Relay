Unicode True
RequestExecutionLevel user
SetCompressor /SOLID lzma
Name "Ayin-Relay Windows Connector"
OutFile "${OUTPUT}"
VIProductVersion "${VERSION_NUM}"
VIAddVersionKey "ProductName" "Ayin-Relay Windows Connector"
VIAddVersionKey "FileDescription" "Ayin-Relay Windows Connector Setup"
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
  ClearErrors
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "$PLUGINSDIR\launch.ps1" -InstallerPath "$EXEPATH"' $0
  IfErrors failed
  StrCmp $0 "0" installed
failed:
  SetErrorLevel 1
  MessageBox MB_OK|MB_ICONSTOP "Ayin-Relay setup did not finish. Check the installation window and try again."
  Abort
installed:
SectionEnd
