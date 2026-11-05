@echo off
setlocal
cd /d %~dp0
title AccountBook
cls
echo ==========================================
echo   Account Book  -  202505183125
echo ==========================================
echo.
where java >nul 2^>^&1
if errorlevel 1 goto NOJAVA
if not exist accountbook.jar goto MISSING
if not exist config.properties goto MISSING
if not exist 202505183125.csv goto MISSING
echo Starting...
echo.
java -jar accountbook.jar
echo.
pause
exit /b 0
:MISSING
echo [ERROR] Missing files in this folder.
pause
exit /b 1
:NOJAVA
echo [ERROR] Java not found. Install JDK 11+ from https://adoptium.net
pause
exit /b 1
