@echo off
rem One-click build entry. Real logic lives in build.ps1 (UTF-8 with BOM).
rem Usage: build.cmd [gradle tasks...]   default: assembleDebug
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
