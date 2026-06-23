# build.ps1 - PE post-processing for single-file distribution (v3)
# 用法: powershell -File build.ps1 [-EngineDir <path>]
# 输出: <EngineDir>\RocoMapTracker.exe
param(
    [string]$EngineDir = ""
)

$ErrorActionPreference = "Stop"
$BOOT = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $EngineDir) {
    $EngineDir = Join-Path $BOOT "..\roco-ui\target"
}

Push-Location $BOOT

try {
    # Step 1: Stage engine
    Write-Output "[1] Staging engine..."
    $engine = Join-Path $EngineDir "RocoMapTracker.engine.exe"
    if (-not (Test-Path $engine)) {
        throw "Engine not found at $engine"
    }

    # Verify DLL source directories
    $jfxDll = Join-Path $EngineDir "classes\javafx-dll"
    $shDll  = Join-Path $EngineDir "classes\dll"
    if (-not (Test-Path "$jfxDll\glass.dll")) {
        throw "JavaFX DLLs not found at $jfxDll — run 'mvn -Pnative package -pl roco-ui -am' first"
    }
    if (-not (Test-Path "$shDll\jvm.dll")) {
        throw "JVM shim DLLs not found at $shDll — run 'mvn -Pnative package -pl roco-ui -am' first"
    }

    Copy-Item $engine "$BOOT\engine.exe" -Force
    Write-Output "  engine.exe: $((Get-Item "$BOOT\engine.exe").Length) bytes"

    # Step 2: Assemble loader_stub.asm
    Write-Output "[2] Assembling loader_stub.asm..."
    $asmResult = & ml64.exe /nologo /c /Fo"$BOOT\loader_stub.obj" "$BOOT\loader_stub.asm" 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Output $asmResult; throw "MASM failed" }
    Write-Output "  loader_stub.obj created."

    # Step 3: Compile pe_patch.c
    Write-Output "[3] Compiling pe_patch.c..."
    $clResult = & cl.exe /nologo /O1 /GS- /Fe"$BOOT\pe_patch.exe" "$BOOT\pe_patch.c" 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Output $clResult; throw "CL failed" }
    Write-Output "  pe_patch.exe created."

    # Step 4: Build --embed arguments
    Write-Output "[4] Collecting DLLs..."

    $embedArgs = @()

    # VC++ runtime (from javafx-dll/ — pe_patch auto-detects IAT patching)
    $embedArgs += "--embed", "vcruntime140.dll=$jfxDll\vcruntime140.dll"
    $embedArgs += "--embed", "vcruntime140_1.dll=$jfxDll\vcruntime140_1.dll"
    $embedArgs += "--embed", "msvcp140.dll=$jfxDll\msvcp140.dll"
    $embedArgs += "--embed", "msvcp140_1.dll=$jfxDll\msvcp140_1.dll"
    $embedArgs += "--embed", "msvcp140_2.dll=$jfxDll\msvcp140_2.dll"

    # JavaFX DLLs (from javafx-dll/) — prism_common first (other prism DLLs depend on it)
    $embedArgs += "--embed", "prism_common.dll=$jfxDll\prism_common.dll"
    $embedArgs += "--embed", "prism_d3d.dll=$jfxDll\prism_d3d.dll"
    $embedArgs += "--embed", "prism_sw.dll=$jfxDll\prism_sw.dll"
    $embedArgs += "--embed", "glass.dll=$jfxDll\glass.dll"
    $embedArgs += "--embed", "decora_sse.dll=$jfxDll\decora_sse.dll"
    $embedArgs += "--embed", "javafx_font.dll=$jfxDll\javafx_font.dll"
    $embedArgs += "--embed", "javafx_iio.dll=$jfxDll\javafx_iio.dll"

    # JVM shim DLLs (from dll/)
    $embedArgs += "--embed", "jvm.dll=$shDll\jvm.dll"
    $embedArgs += "--embed", "java.dll=$shDll\java.dll"
    $embedArgs += "--embed", "awt.dll=$shDll\awt.dll"
    $embedArgs += "--embed", "jawt.dll=$shDll\jawt.dll"
    $embedArgs += "--embed", "fontmanager.dll=$shDll\fontmanager.dll"
    $embedArgs += "--embed", "freetype.dll=$shDll\freetype.dll"
    $embedArgs += "--embed", "javaaccessbridge.dll=$shDll\javaaccessbridge.dll"
    $embedArgs += "--embed", "javajpeg.dll=$shDll\javajpeg.dll"
    $embedArgs += "--embed", "lcms.dll=$shDll\lcms.dll"
    $embedArgs += "--embed", "jniframe.dll=$shDll\jniframe.dll"

    Write-Output "  VC++:   $jfxDll"
    Write-Output "  JavaFX: $jfxDll"
    Write-Output "  JVM:    $shDll"
    Write-Output "  Total DLLs to embed: $($embedArgs.Count / 2)"

    # Step 5: Run pe_patch
    Write-Output "[5] Running PE patcher..."
    $output = Join-Path $EngineDir "RocoMapTracker.exe"
    $peArgs = @(
        "--engine", "$BOOT\engine.exe",
        "--output", $output,
        "--stub", "$BOOT\loader_stub.obj"
    ) + $embedArgs

    & "$BOOT\pe_patch.exe" $peArgs
    if ($LASTEXITCODE -ne 0) { throw "pe_patch failed" }

    # Step 6: Verify output
    Write-Output "[6] Verifying output..."
    if (Test-Path $output) {
        $size = (Get-Item $output).Length
        Write-Output "  RocoMapTracker.exe: $size bytes"
        Write-Output "BUILD SUCCESS"
    } else {
        throw "Output file not found"
    }
} finally {
    # Cleanup
    Remove-Item "$BOOT\engine.exe" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\loader_stub.obj" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\pe_patch.obj" -Force -ErrorAction SilentlyContinue
    Remove-Item "$BOOT\pe_patch.exe" -Force -ErrorAction SilentlyContinue
    Pop-Location
}
