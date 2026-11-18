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
  File "${PAYLOAD}/launch.ps1"
  File "${PAYLOAD}/install.ps1"
  File "${PAYLOAD}/Stop.cmd"
  File "${PAYLOAD}/Status.cmd"
  File "${PAYLOAD}/hermes-relay.exe"
  File "${PAYLOAD}/manifest.json"
  File "${PAYLOAD}/portal.json"
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
