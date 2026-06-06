@echo off
chcp 65001 >nul

echo.
echo ===============================
echo   LingoAI - Quick Start
echo ===============================
echo.

REM ===================== 依赖检查 =====================
echo [1/8] Checking dependencies...

REM --- ffmpeg ---
where ffmpeg >nul 2>nul
if errorlevel 1 (
    echo [WARN] ffmpeg not found. Attempting to install...
    winget install "FFmpeg (Essentials Build)" --accept-source-agreements --silent 2>nul
    where ffmpeg >nul 2>nul && echo   [OK] ffmpeg installed || echo   [WARN] ffmpeg installation may have failed
) else (
    echo   [OK] ffmpeg
)

REM --- Python ---
python --version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python not found! Please install Python 3.11+ first.
    pause
    exit /b 1
)
python --version 2>&1

REM --- Node.js ---
node --version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js not found! Please install Node.js 18+ first.
    pause
    exit /b 1
)
node --version

REM --- Docker ---
docker --version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Docker not found! Please install Docker Desktop first.
    pause
    exit /b 1
)
docker --version


REM ===================== 启动基础设施 =====================
echo.
echo [2/8] Starting PostgreSQL and Redis (Docker)...

docker compose up -d postgres redis
echo   [OK] PostgreSQL + Redis started

echo [3/8] Waiting for database (5s)...
timeout /t 5 /nobreak >nul


REM ===================== 启动 ASR 服务 =====================
echo.
echo [4/8] Starting ASR Service (Faster-Whisper)...

REM 检查是否有已存在的 ASR 服务窗口
tasklist /fi "WindowTitle eq LingoAI ASR*" 2>nul | findstr "cmd.exe" >nul
if %errorlevel% equ 0 (
    echo   [SKIP] ASR Service already running
) else (
    start "LingoAI ASR" /min cmd /k "cd /d %~dp0asr-service && rd /s /q app\__pycache__ 2>nul & rd /s /q app\proto\__pycache__ 2>nul && python generate_proto.py && python -m app.server --device cpu --model medium --compute-type int8"
    echo   [OK] ASR Service launching...
)

echo [5/8] Waiting for ASR Service (8s)...
timeout /t 8 /nobreak >nul


REM ===================== 启动 TTS 服务 =====================
echo.
echo [6/8] Starting TTS Service (Piper + Edge TTS)...

set "TTS_DIR=%~dp0tts-service"
set "PIPER_BIN=%TTS_DIR%\piper\piper.exe"
set "TTS_MODELS_DIR=%TTS_DIR%\models\piper"

REM 检查 Piper 二进制
if exist "%PIPER_BIN%" (
    echo   [OK] Piper binary found
) else (
    echo   [WARN] Piper binary not found at %PIPER_BIN%
    echo   [WARN] Only Edge TTS will be available (needs network)
)

REM 检查语音模型
if exist "%TTS_MODELS_DIR%\en_US-amy-medium.onnx" (
    echo   [OK] Piper voice model found
) else (
    echo   [WARN] Piper voice model not found. Run:
    echo     cd tts-service ^&^& python -m app.download_model en_US-amy-medium
)

REM 检查是否有已存在的 TTS 服务窗口
tasklist /fi "WindowTitle eq LingoAI TTS*" 2>nul | findstr "cmd.exe" >nul
if %errorlevel% equ 0 (
    echo   [SKIP] TTS Service already running
) else (
    start "LingoAI TTS" /min cmd /k "cd /d %TTS_DIR% && pip install -r requirements.txt && set "PIPER_BIN=%PIPER_BIN%" && set "TTS_MODELS_DIR=%TTS_MODELS_DIR%" && uvicorn app.server:app --host 0.0.0.0 --port 8000"
    echo   [OK] TTS Service launching on http://localhost:8000
)

echo [6/8] Waiting for TTS Service (3s)...
timeout /t 3 /nobreak >nul


REM ===================== 启动后端 =====================
echo.
echo [7/8] Starting Backend Service (Spring Boot)...

tasklist /fi "WindowTitle eq LingoAI Backend*" 2>nul | findstr "cmd.exe" >nul
if %errorlevel% equ 0 (
    echo   [SKIP] Backend already running
) else (
    start "LingoAI Backend" /min cmd /k "cd /d %~dp0backend && echo [Backend] Starting Spring Boot... && mvn spring-boot:run"
    echo   [OK] Backend launching on http://localhost:8080
)

echo [7/8] Waiting for Backend (12s)...
timeout /t 12 /nobreak >nul


REM ===================== 启动前端 =====================
echo.
echo [8/8] Starting Frontend Service (Vue.js)...

tasklist /fi "WindowTitle eq LingoAI Frontend*" 2>nul | findstr "cmd.exe" >nul
if %errorlevel% equ 0 (
    echo   [SKIP] Frontend already running
) else (
    start "LingoAI Frontend" /min cmd /k "cd /d %~dp0frontend && echo [Frontend] Installing npm packages... && call npm install --silent 2>nul && echo [Frontend] Starting dev server... && npm run dev"
    echo   [OK] Frontend launching on http://localhost:5173
)


REM ===================== 完成 =====================
echo.
echo ===============================
echo   All Services Started!
echo ===============================
echo.
echo   Frontend  : http://localhost:5173
echo   Backend   : http://localhost:8080
echo   Swagger   : http://localhost:8080/swagger-ui.html
echo   TTS       : http://localhost:8000
echo   ASR (gRPC): localhost:50051
echo.
echo   Close this window to stop all services.
echo.
pause >nul
