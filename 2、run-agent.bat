@echo off
chcp 65001 >nul

echo ==============================================
echo  RocoMapTracker - agent for JNI/reflect config
echo ==============================================

set "CONFIG_DIR=roco-ui\src\main\resources\META-INF\native-image"

echo [1] rebuild fat jar
call mvn package -pl roco-ui -am -DskipTests -q 2>&1 | findstr /V "^$"

echo [2] find fat jar
for %%f in (roco-ui\target\roco-ui-*.jar) do set "JAR_FILE=%%f"
if not defined JAR_FILE (
    echo [ERROR] fat jar not found
    pause
    exit /b 1
)

echo [3] run agent
echo    exercise all features then close the window.
echo.
java "-agentlib:native-image-agent=config-merge-dir=%CONFIG_DIR%,experimental-class-loader-support,config-write-period-secs=10,config-write-initial-delay-secs=5" -jar "%JAR_FILE%"

echo.
echo ==============================================
echo  agent done. Config saved to %CONFIG_DIR%
echo  Check %CONFIG_DIR% and merge into reachability-metadata.json
echo ==============================================
pause
