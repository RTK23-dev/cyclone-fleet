; Kill running One + legacy Companion + sidecar processes before overwrite/uninstall.
; The 3.6.0-beta installer failed with:
;   Error opening file for writing: ...\CycloneAgentMCP.exe
; because the previous Companion left that sidecar running.
; Current-user NSIS product is Cyclone One; stop that GUI so upgrades can overwrite.
; Doctor and MCP warn if a sibling Cyclone PC Companion 3.8.x directory remains;
; skip a MessageBox here so silent installs stay quiet.
; Every comment line must start with ';' — NSIS treats a bare word as a command.

; Stop the adb server Cyclone started from its own android-platform-tools folder. adb keeps running after Cyclone
; One closes and holds adb.exe, AdbWinApi.dll and AdbWinUsbApi.dll open, so an upgrade failed with
;   Error opening file for writing: ...\Cyclone One\android-platform-tools\adb.exe
; A graceful kill-server first, then only adb/fastboot processes whose executable lives in a Cyclone One
; android-platform-tools folder; an Android Studio or system adb elsewhere is never touched.
!macro CYCLONE_STOP_BUNDLED_ADB
  ClearErrors
  IfFileExists "$INSTDIR\android-platform-tools\adb.exe" 0 +2
    ExecWait '"$INSTDIR\android-platform-tools\adb.exe" kill-server'
  ClearErrors
  ExecWait `cmd /C powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -Command "Get-Process adb,fastboot -ErrorAction SilentlyContinue | Where-Object { $$_.Path -like '*\Cyclone One\android-platform-tools\*' } | Stop-Process -Force -ErrorAction SilentlyContinue" >NUL 2>&1`
!macroend

!macro NSIS_HOOK_PREINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone One.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "%LOCALAPPDATA%\Cyclone One\mcp-tunnel\scripts\stop-tunnel.ps1" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneLivePhone.exe >NUL 2>&1'
  !insertmacro CYCLONE_STOP_BUNDLED_ADB
  Sleep 1500
!macroend

; Install the `cyclone` terminal command for this user (cyclone.cmd in %LOCALAPPDATA%\Cyclone One\bin, added to
; the user PATH). New terminals can then type: cyclone
!macro NSIS_HOOK_POSTINSTALL
  ClearErrors
  ExecWait '"$INSTDIR\CyclonePCRuntime.exe" install-cli'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone One.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "%LOCALAPPDATA%\Cyclone One\mcp-tunnel\scripts\stop-tunnel.ps1" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneLivePhone.exe >NUL 2>&1'
  !insertmacro CYCLONE_STOP_BUNDLED_ADB
  Sleep 1500
  ClearErrors
  ExecWait '"$INSTDIR\CyclonePCRuntime.exe" install-cli --remove'
!macroend
