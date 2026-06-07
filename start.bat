@echo off
chcp 65001 >nul

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
echo [1/4] Starting PostgreSQL and Redis...
docker compose -f "%ROOT%docker-compose.yml" up -d postgres redis
echo   [OK] PostgreSQL + Redis

echo [2/4] Starting ASR Service (Faster-Whisper) on gRPC:50051...
if "%ASR_DEVICE%"=="" set "ASR_DEVICE=cpu"
start "LingoAI ASR" cmd /k "cd /d "%ROOT%asr-service" && python -m app.server --device %ASR_DEVICE% --model medium --compute-type int8 --download-root ./models"
echo   [OK] ASR port 50051

echo [3/4] Starting TTS Service (Piper) on port 8001...
start "LingoAI TTS" cmd /k "cd /d "%ROOT%tts-service" && set PIPER_BIN=%ROOT%tts-service\piper\piper.exe && set PATH=%%PATH%%;%ROOT%tts-service\piper && uvicorn app.server:app --host 0.0.0.0 --port 8001"
echo   [OK] TTS port 8001

echo [4/4] Starting all backend/frontend services...
start "LingoAI Backend" cmd /k "cd /d "%ROOT%backend" && set TTS_SERVICE_URL=http://localhost:8001 && mvn spring-boot:run"
start "LingoAI Frontend" cmd /k "cd /d "%ROOT%frontend" && npm run dev"
echo   [OK] Backend :8080 + Frontend :5173

echo.
echo ===============================
echo   All Services Started!
echo ===============================
echo.
echo   Frontend  : http://localhost:5173
echo   Backend   : http://localhost:8080
echo   TTS       : http://localhost:8001
echo   ASR (gRPC): localhost:50051
echo.
echo   Close each service window to stop it.
echo.
pause
