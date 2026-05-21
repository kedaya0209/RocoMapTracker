@echo off
chcp 65001 >nul

set GRAALVM_HOME=D:\Documents\environment\java\graalvm-win\graalvm-jdk-25.0.2+10.1
set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
set "KIT_ROOT=C:\Program Files (x86)\Windows Kits\10"
set "UCRT_INC=%KIT_ROOT%\Include\10.0.26100.0\ucrt"
set "SHARED_INC=%KIT_ROOT%\Include\10.0.26100.0\shared"
cl /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
   /I"%UCRT_INC%" /I"%SHARED_INC%" ^
   /LD src\main\c\jniframe.c /Fe:src/main/resources/dll/jniframe.dll
pause