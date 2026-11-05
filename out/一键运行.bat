@echo off
chcp 65001 >nul
cd /d %~dp0
echo ====== 个人记账本 自测启动 ======
if not exist accountbook.jar (echo [错误] 缺少 accountbook.jar & pause & exit /b)
if not exist config.properties (echo [错误] 缺少 config.properties & pause & exit /b)
if not exist 202505183125.csv (echo [错误] 缺少 202505183125.csv & pause & exit /b)
java -version >nul 2>&1
if errorlevel 1 (echo [错误] 未检测到Java，请先安装JDK11+后重试 & pause & exit /b)
java -jar accountbook.jar
pause
