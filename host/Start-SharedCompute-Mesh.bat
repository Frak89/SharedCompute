@echo off
setlocal EnableExtensions
chcp 65001 >nul
color 0B
title SharedCompute MESH - Windows/Android
set "ROOT=D:\CYBER_AI\SHAREDCOMPUTE"
if not exist "%ROOT%\" set "ROOT=%~dp0"
:MENU
cls
echo ========================================================
echo   SHAREDCOMPUTE MESH 1.3
echo   MASTER / WORKER / AUTO - LAN - NO ADB
echo ========================================================
echo   [1] Start MESH discovery and pairing
echo   [2] Start LAN Host ^(RPC model server^)
echo   [3] Open Mesh received files
echo   [4] Open SSD SharedCompute folder
echo   [0] Exit
echo.
set /p "CHOICE=Choose: "
if "%CHOICE%"=="1" goto MESH
if "%CHOICE%"=="2" goto HOST
if "%CHOICE%"=="3" goto INBOX
if "%CHOICE%"=="4" (start "" "%ROOT%" & goto MENU)
if "%CHOICE%"=="0" exit /b 0
goto MENU
:MESH
set "APP="
for %%F in ("%ROOT%\SharedCompute-Mesh.exe" "%~dp0SharedCompute-Mesh.exe") do if not defined APP if exist "%%~fF" set "APP=%%~fF"
if not defined APP for /r "%ROOT%" %%F in (SharedCompute-Mesh.exe) do if not defined APP set "APP=%%~fF"
if not defined APP (
  echo Mesh EXE not found. Extract the new Windows Mesh artifact to %ROOT%.
  pause
  goto MENU
)
start "SharedCompute Mesh" "%APP%"
goto MENU
:HOST
set "HOST="
for %%F in ("%ROOT%\SharedCompute-LAN-Host.exe" "%~dp0SharedCompute-LAN-Host.exe") do if not defined HOST if exist "%%~fF" set "HOST=%%~fF"
if not defined HOST for /r "%ROOT%" %%F in (SharedCompute-LAN-Host.exe) do if not defined HOST set "HOST=%%~fF"
if not defined HOST (
  echo LAN Host not found. Use the corrected LAN Host artifact.
  pause
  goto MENU
)
start "SharedCompute LAN Host" "%HOST%"
goto MENU
:INBOX
if exist "%ROOT%\mesh-data\inbox" (start "" "%ROOT%\mesh-data\inbox") else echo No Mesh inbox yet.
pause
goto MENU
