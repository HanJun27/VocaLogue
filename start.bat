@echo off
chcp 65001 >nul

echo.
echo ===============================
echo   LingoAI - Quick Start
echo ===============================
echo.

REM Check ffmpeg, install if missing
echo [CHECK] ffmpeg...
where ffmpeg >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] ffmpeg not found. Installing via winget...
    winget install "FFmpeg (Essentials Build)" --accept-source-agreements --silent >nul 2>&1
    REM 刷新 PATH 使刚安装的 ffmpeg 在当前会话可用
    for /f "tokens=*" %%i in ('where ffmpeg 2^>nul') do set "PATH=%%~dpi;%PATH%" & goto :ffmpeg_done
    if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe" (
        for /d %%d in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe\*") do (
            if exist "%%d\bin\ffmpeg.exe" set "PATH=%%d\bin;%PATH%"
        )
    )
    :ffmpeg_done
    echo [OK] ffmpeg installation attempted
) else (
    echo [OK] ffmpeg ready
)

REM Check Docker
echo [CHECK] Docker...
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker not found! Please install Docker Desktop first.
    pause
    exit /b 1
)
echo [OK] Docker ready


REM Start Docker services
echo [START] PostgreSQL and Redis...
docker-compose up -d postgres redis
echo [OK] Docker services started

REM Wait for database
echo [WAIT] Initializing database...
timeout /t 5 /nobreak >nul

REM Start ASR Service (Faster-Whisper)
echo [START] ASR Service...
start "LingoAI ASR" /min cmd /k "cd asr-service && pip install -r requirements.txt >nul 2>&1 && python generate_proto.py && python -m app.server --device cpu --model medium --compute-type int8"

REM Wait for ASR
echo [WAIT] ASR Service initializing...
timeout /t 8 /nobreak >nul

REM Start Backend
echo [START] Backend Service...
start "LingoAI Backend" /min cmd /k "cd backend && mvn spring-boot:run"

REM Wait for backend
timeout /t 12 /nobreak >nul

REM Start Frontend
echo [START] Frontend Service...
start "LingoAI Frontend" /min cmd /k "cd frontend && npm run dev"

echo.
echo ===============================
echo   Services Started!
echo ===============================
echo Frontend: http://localhost:5173
echo Backend:  http://localhost:8080
echo Swagger:  http://localhost:8080/swagger-ui.html
echo.
echo Press any key to close this window...
pause >nul


