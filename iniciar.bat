@echo off
setlocal
set "JAR=%~dp0target\breakinv-0.5.0.jar"
if not exist "%JAR%" (
    echo Build not found: "%JAR%". Run mvn clean package first. 1>&2
    exit /b 1
)
java %* -jar "%JAR%"
exit /b %errorlevel%
