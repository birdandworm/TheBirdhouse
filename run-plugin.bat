@echo off
REM Build the plugin and side-load it into RuneLite for local testing.
REM
REM The previous version of this script pointed at C:\Birdhouse (which does not exist)
REM and ran a scratch test main class, so it could not launch anything. Testing now goes
REM through the real client instead: see sideload.ps1 for the one-time launcher setup,
REM which is also what makes Jagex account logins work.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sideload.ps1"
pause
