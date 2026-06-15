@echo off
setlocal enabledelayedexpansion

set "RC_EXE="

rem 1. Try PATH first
where rc.exe >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    set "RC_EXE=rc.exe"
)

rem 2. Search Windows Kits 10 (newest version first)
if not defined RC_EXE (
    for /f "delims=" %%d in ('dir /b /ad /o-n "C:\Program Files (x86)\Windows Kits\10\bin\*" 2^>nul') do (
        if exist "C:\Program Files (x86)\Windows Kits\10\bin\%%d\x64\rc.exe" (
            set "RC_EXE=C:\Program Files (x86)\Windows Kits\10\bin\%%d\x64\rc.exe"
            goto :found_rc
        )
    )
)

rem 3. Search Windows Kits 11 (newest version first)
if not defined RC_EXE (
    for /f "delims=" %%d in ('dir /b /ad /o-n "C:\Program Files (x86)\Windows Kits\11\bin\*" 2^>nul') do (
        if exist "C:\Program Files (x86)\Windows Kits\11\bin\%%d\x64\rc.exe" (
            set "RC_EXE=C:\Program Files (x86)\Windows Kits\11\bin\%%d\x64\rc.exe"
            goto :found_rc
        )
    )
)

:found_rc
if not defined RC_EXE (
    echo rc.exe not found, skipping icon resource compilation
    exit /b 0
)

set OUTDIR=%~dp1
if not exist "%OUTDIR%" mkdir "%OUTDIR%"
"%RC_EXE%" /fo %1 %2
if %ERRORLEVEL% NEQ 0 (
    echo icon resource compilation failed (non-fatal)
    exit /b 0
)
echo icon resource compiled: %1
