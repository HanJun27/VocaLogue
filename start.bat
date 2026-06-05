@echo off
chcp 65001 >nul

echo.
echo ===============================
echo   LingoAI - Quick Start
echo ===============================
echo.

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
docker-compose up -d
echo [OK] Docker services started

REM Wait for database
echo [WAIT] Initializing database...
timeout /t 5 /nobreak >nul

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