@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Phone Remote 120FPS

where scrcpy >nul 2>nul
if errorlevel 1 (
  echo [LOI] Chua cai scrcpy. Mo PowerShell va chay:
  echo        winget install Genymobile.scrcpy
  echo Sau do mo lai file nay.
  pause
  exit /b 1
)

rem ---- Liet ke dien thoai dang cam cap USB ----
set COUNT=0
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
  if "%%b"=="device" (
    set /a COUNT+=1
    set DEV!COUNT!=%%a
  )
  if "%%b"=="unauthorized" (
    echo [CHU Y] May %%a chua cho phep go loi USB. Hay bam "Cho phep" tren dien thoai.
  )
)

if %COUNT%==0 (
  echo [LOI] Khong thay dien thoai nao. Kiem tra cap USB va "Go loi USB".
  pause
  exit /b 1
)

set PICK=1
if %COUNT% GTR 1 (
  echo Co %COUNT% dien thoai dang ket noi:
  for /l %%i in (1,1,%COUNT%) do echo   %%i. !DEV%%i!
  set /p PICK=Chon so thu tu may muon dieu khien:
)
set SERIAL=!DEV%PICK%!
if "%SERIAL%"=="" (
  echo [LOI] Lua chon khong hop le.
  pause
  exit /b 1
)

echo Dang mo %SERIAL% o 120 FPS...
rem --max-fps=120        : toi da 120 khung/giay (man hinh dien thoai phai la 120Hz)
rem --video-bit-rate=24M : hinh net khi chuyen dong nhanh
rem --video-buffer=0     : do tre thap nhat
rem --stay-awake         : khong tat man hinh khi dang cam cap
rem --print-fps          : in so khung/giay thuc te ra cua so nay
scrcpy --serial=%SERIAL% --max-fps=120 --video-bit-rate=24M --video-buffer=0 --stay-awake --print-fps --window-title="Phone Remote 120FPS - %SERIAL%"

if errorlevel 1 pause
