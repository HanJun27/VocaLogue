﻿@echo off
chcp 65001 >nul 2>nul

echo.
echo ===============================
echo   LingoAI - Quick Start
echo ===============================
echo.

set "ROOT=%~dp0"

REM ---- Check Node.js ----
node --version >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found. Please install Node.js 18+.
    pause
    exit /b 1
)
for /f %%i in ('node --version') do echo   [OK] Node %%i

REM ---- Check Python ----
python --version >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python not found. Please install Python 3.11+.
    pause
    exit /b 1
)
for /f %%i in ('python --version') do echo   [OK] %%i

REM ---- Check Docker ----
docker --version >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Docker not found. Please install Docker Desktop.
    pause
    exit /b 1
)
for /f %%i in ('docker --version') do echo   [OK] %%i

echo.

REM ============================================================================
REM Step 1/6: PostgreSQL + Redis
REM ============================================================================
echo [1/6] Starting PostgreSQL and Redis...
docker compose -f "%ROOT%docker-compose.yml" up -d postgres redis >nul 2>nul
if %errorlevel% equ 0 (
    echo   [OK] PostgreSQL + Redis
) else (
    echo   [WARN] PostgreSQL/Redis start failed, check docker-compose.yml
)

REM ============================================================================
REM Step 2/6: Faster-Whisper ASR Service (gRPC :50051)
REM ============================================================================
echo [2/6] Starting ASR Service (Faster-Whisper) gRPC:50051...
if "%ASR_DEVICE%"=="" set "ASR_DEVICE=cpu"
if exist "%ROOT%asr-service\app\server.py" (
    start "LingoAI ASR" cmd /k "cd /d "%ROOT%asr-service" && python -m app.server --device %ASR_DEVICE% --model medium --compute-type int8 --download-root ./models"
    echo   [OK] ASR gRPC:50051
) else (
    echo   [SKIP] ASR service directory not found
)

REM ============================================================================
REM Step 3/6: TTS Service (Piper) on port 8001
REM ============================================================================
echo [3/6] Starting TTS Service (Piper) port 8001...
if exist "%ROOT%tts-service\app\server.py" (
    start "LingoAI TTS" cmd /k "cd /d "%ROOT%tts-service" && set PIPER_BIN=%ROOT%tts-service\piper\piper.exe && set PATH=%%PATH%%;%ROOT%tts-service\piper && uvicorn app.server:app --host 0.0.0.0 --port 8001"
    echo   [OK] TTS port 8001
) else (
    echo   [SKIP] TTS service directory not found
)

REM ============================================================================
REM Step 4/6: Pronunciation Service (wav2vec2) on port 8002 (Docker)
REM ============================================================================
echo [4/6] Starting Pronunciation Service (wav2vec2) port 8002...
docker image inspect pronunciation-service:latest >nul 2>nul
if %errorlevel% neq 0 (
    echo   [WARN] Docker image pronunciation-service:latest not found
    echo          Build it first: cd pronunciation-service ^&^& docker build -t pronunciation-service .
    echo   [SKIP] Skipping pronunciation service
) else (
    docker rm -f pronunciation-service >nul 2>nul
    docker run -d -p 8002:8001 --name pronunciation-service pronunciation-service >nul 2>nul
    if %errorlevel% equ 0 (
        echo   [OK] Pronunciation port 8002
    ) else (
        echo   [WARN] Pronunciation service start failed, port 8002 may be in use
    )
)

REM ============================================================================
REM Step 5/6: Backend (Spring Boot :8080)
REM ============================================================================
echo [5/6] Starting Backend (Spring Boot) port 8080...
ping -n 3 127.0.0.1 >nul
start "LingoAI Backend" cmd /k "cd /d "%ROOT%backend" && set TTS_SERVICE_URL=http://localhost:8001 && mvn spring-boot:run"
echo   [OK] Backend :8080

REM ============================================================================
REM Step 6/6: Frontend (Vite :5173)
REM ============================================================================
echo [6/6] Starting Frontend (Vite) port 5173...
start "LingoAI Frontend" cmd /k "cd /d "%ROOT%frontend" && npm run dev"
echo   [OK] Frontend :5173

echo.
echo ===============================
echo   All Services Started!
echo ===============================
echo.
echo   Frontend      : http://localhost:5173
echo   Backend       : http://localhost:8080
echo   TTS           : http://localhost:8001
echo   Pronunciation : http://localhost:8002
echo   ASR (gRPC)    : localhost:50051
echo.
echo   Close each service window to stop it.
echo.
pause
