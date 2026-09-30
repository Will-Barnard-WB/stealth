@echo off
rem Runs the stealth CLI from this checkout, rebuilding it first if the sources changed.
rem Put this directory on your PATH, then use `stealth-dev` wherever you would use `stealth`.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stealth-dev.ps1" %*
exit /b %ERRORLEVEL%
