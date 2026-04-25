@echo off
chcp 65001 >nul
cls

echo ==============================================
echo Step 0: Setting Environment
echo ==============================================
set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set VCVARS_PATH="C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
if exist %VCVARS_PATH% (
    call %VCVARS_PATH% x64
) else (
    echo [ERROR] 找不到 vcvarsall.bat，请检查 VS 安装路径！
    pause
    exit /b
)

set MAVEN_BIN=%~dp0temp-maven\apache-maven-3.9.6\bin\mvn.cmd

echo ==============================================
echo Step 1: Cleaning and Packaging
echo ==============================================
call %MAVEN_BIN% clean package -DskipTests

echo ==============================================
echo Step 2: Copying Dependencies
echo ==============================================
if not exist "target\libs" mkdir target\libs
call %MAVEN_BIN% dependency:copy-dependencies -DoutputDirectory=target/libs

echo ==============================================
echo Step 3: GraalVM Native Compilation
echo ==============================================
native-image ^
  -o target\RocoMapTracker ^
  -H:+UnlockExperimentalVMOptions ^
  -cp "target/classes;target/libs/*" ^
  com.luoke.app.Main ^
  --no-fallback ^
  --verbose ^
  -Dfile.encoding=UTF-8 ^
  -Dstdout.encoding=UTF-8 ^
  -Dstderr.encoding=UTF-8 ^
  -H:+StripDebugInfo ^
  -Djava.awt.headless=false ^
  --enable-http ^
  --enable-https ^
  -Djna.nosys=true ^
  -Dorg.bytedeco.javacpp.loadlibraries=true ^
  --initialize-at-run-time=ai.onnxruntime.OnnxRuntime,ai.onnxruntime.OrtEnvironment ^
  -H:IncludeResources="model/.*" ^
  -H:IncludeResources=".*\.onnx$" ^
  -H:IncludeResources=".*\.json$" ^
  -H:IncludeResources=".*\.txt$" ^
  -H:IncludeResources=".*\.png$" ^
  -H:IncludeResources=".*\.properties$"

if exist "target\RocoMapTracker.exe" (
    echo ==============================================
    echo Step 4: Removing Console Window (GUI Mode)
    echo ==============================================
    call editbin /subsystem:windows "target\RocoMapTracker.exe"
    echo [SUCCESS] 构建完成，已转换为 GUI 模式。
) else (
    echo [ERROR] Native Image 编译失败，未生成 EXE 文件。
)

pause