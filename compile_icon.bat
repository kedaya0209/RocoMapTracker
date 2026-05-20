@echo off
where rc.exe >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo rc.exe not found, skipping icon resource compilation
    exit /b 0
)
set OUTDIR=%~dp1
if not exist "%OUTDIR%" mkdir "%OUTDIR%"
rc.exe /fo %1 %2
if %ERRORLEVEL% NEQ 0 (
    echo icon resource compilation failed (non-fatal)
    exit /b 0
)
echo icon resource compiled: %1
