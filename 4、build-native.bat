@echo off
chcp 65001 >nul 2>&1

echo ============================================
echo  RocoMapTracker Native Image 构建脚本
echo ============================================

:: 设置 Visual Studio 环境
set "VS_DIR=C:\Program Files\Microsoft Visual Studio\18\Insiders"
set "VCVARSALL=%VS_DIR%\VC\Auxiliary\Build\vcvarsall.bat"

if not exist "%VCVARSALL%" (
    echo [错误] 找不到 vcvarsall.bat: %VCVARSALL%
    echo 请检查 Visual Studio 安装路径
    pause
    exit /b 1
)

echo [1/2] 初始化 Visual Studio 环境...
call "%VCVARSALL%" x64
if %ERRORLEVEL% neq 0 (
    echo [错误] VS 环境初始化失败
    pause
    exit /b 1
)
echo [OK] VS 环境已就绪

:: 执行 Native Image 构建（含 rcedit 后处理嵌入图标）
echo [2/2] 开始 Native Image 构建...
mvn -Pnative clean install -pl roco-ui -am

if %ERRORLEVEL% equ 0 (
    echo ============================================
    echo  构建成功！
    echo  产物: roco-ui\target\RocoMapTracker.exe
    echo  图标已通过 rcedit 嵌入
    echo ============================================
) else (
    echo [错误] 构建失败，请检查日志
)

pause
