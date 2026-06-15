# PE 头方案构建脚本
# 用法: powershell -File build.ps1
$ErrorActionPreference = "Stop"

$MSVC = 'C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.51.36231'
$SDK = 'C:\Program Files (x86)\Windows Kits\10'
$SDKVER = '10.0.26100.0'
$BOOT = 'D:\Documents\code\Roco-tools\RocoMapTracker\bootstrapper'
$ENGINE = 'D:\Documents\code\Roco-tools\RocoMapTracker\roco-ui\target'

$env:INCLUDE = "$MSVC\include;$SDK\Include\$SDKVER\ucrt;$SDK\Include\$SDKVER\um;$SDK\Include\$SDKVER\shared"
$env:LIB = "$MSVC\lib\x64;$SDK\Lib\$SDKVER\ucrt\x64;$SDK\Lib\$SDKVER\um\x64"
$env:Path = "$MSVC\bin\Hostx64\x64;$SDK\bin\$SDKVER\x64;$env:Path"

Push-Location $BOOT

try {
    # Step 1: Stage files
    Write-Output '[1] Staging files...'
    Copy-Item "$ENGINE\RocoMapTracker.engine.exe" "$BOOT\engine.exe" -Force
    Copy-Item "$ENGINE\classes\javafx-dll\vcruntime140.dll" "$BOOT\vcruntime140.dll" -Force
    Copy-Item "$ENGINE\classes\javafx-dll\vcruntime140_1.dll" "$BOOT\vcruntime140_1.dll" -Force
    Write-Output ('  engine.exe: ' + (Get-Item "$BOOT\engine.exe").Length + ' bytes')
    Write-Output '  Done.'

    # Step 2: Assemble loader_stub.asm -> loader_stub.obj
    Write-Output '[2] Assembling loader_stub.asm...'
    $asmResult = & ml64.exe /nologo /c /Fo"$BOOT\loader_stub.obj" "$BOOT\loader_stub.asm" 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Output $asmResult; throw "MASM failed" }
    Write-Output '  loader_stub.obj created.'

    # Step 3: Compile pe_patch.c -> pe_patch.exe
    Write-Output '[3] Compiling pe_patch.c...'
    $clResult = & cl.exe /nologo /O1 /GS- /Fe"$BOOT\pe_patch.exe" "$BOOT\pe_patch.c" 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Output $clResult; throw "CL failed" }
    Write-Output '  pe_patch.exe created.'

    # Step 4: Run pe_patch
    Write-Output '[4] Running PE patcher...'
    & "$BOOT\pe_patch.exe" `
        --engine "$BOOT\engine.exe" `
        --output "$ENGINE\RocoMapTracker.exe" `
        --vcr140 "$BOOT\vcruntime140.dll" `
        --vcr140_1 "$BOOT\vcruntime140_1.dll" `
        --stub "$BOOT\loader_stub.obj"
    if ($LASTEXITCODE -ne 0) { throw "pe_patch failed" }

    # Step 5: Verify output
    Write-Output '[5] Verifying output...'
    $outPath = "$ENGINE\RocoMapTracker.exe"
    if (Test-Path $outPath) {
        $size = (Get-Item $outPath).Length
        Write-Output "  RocoMapTracker.exe: $size bytes"
        Write-Output 'BUILD SUCCESS'
    } else {
        throw "Output file not found"
    }
} finally {
    # Cleanup
    Remove-Item "$BOOT\engine.exe" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\vcruntime140.dll" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\vcruntime140_1.dll" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\loader_stub.obj" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\pe_patch.obj" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\pe_patch.exe" -Force -ErrorAction SilentlyContinue
    Pop-Location
}
