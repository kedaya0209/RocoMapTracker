@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

echo ============================================
echo  RocoMapTracker Native Image 构建 + PE 后处理
echo ============================================

:: 检测 Visual Studio 环境
where ml64.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [1/3] MSVC 工具已在 PATH 中
    goto :build
)

echo [1/3] 查找 Visual Studio...
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto :no_vs

for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -property installationPath`) do set "VS_DIR=%%i"
if not defined VS_DIR goto :no_vs

echo 找到 VS: !VS_DIR!
call "!VS_DIR!\VC\Auxiliary\Build\vcvarsall.bat" x64
if !ERRORLEVEL! neq 0 (
    echo [错误] VS 环境初始化失败
    pause
    exit /b 1
)
goto :build

:no_vs
echo [错误] 找不到 Visual Studio, 请从 VS x64 Native Tools 命令行运行
pause
exit /b 1

:build
echo [2/3] 开始 Native Image 构建...
set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dsun.zip.alwaysUseUtf8=true"
mvn -Pnative clean verify -pl roco-ui -am -DskipTests

if %ERRORLEVEL% neq 0 (
    echo [错误] 构建失败
    pause
    exit /b 1
)

echo.
echo [3/3] PE 后处理 (从 engine.exe 生成最终 exe)...
echo 此步骤由 Maven exec-maven-plugin 在 verify 阶段自动完成
echo.

echo ============================================
echo  构建成功！
echo  产物: roco-ui\target\RocoMapTracker.exe
echo  - 无需 VC++ Redistributable
echo  - 单文件分发, 支持中文路径
echo ============================================

pause
