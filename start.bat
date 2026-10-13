@echo off
cd /d "%~dp0"
call gradlew.bat --quiet jar
if errorlevel 1 (pause & exit /b 1)
java -Xmx2g -Dfile.encoding=UTF-8 -jar build\libs\FlameStudio-1.0.0.jar %*
pause
