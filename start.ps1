# LingoAI - 启动所有服务（独立窗口版）

$ROOT = $PSScriptRoot

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  LingoAI - 启动所有服务" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Docker
Write-Host "[1/5] 启动 PostgreSQL 和 Redis..." -ForegroundColor Yellow
docker compose -f "$ROOT\docker-compose.yml" up -d postgres redis | Out-Null
Write-Host "  [OK] PostgreSQL :5432, Redis :6379" -ForegroundColor Green

# 2. TTS 窗口
Write-Host "[2/5] 打开 TTS 服务窗口..." -ForegroundColor Yellow
$tts = "cd '$ROOT\tts-service'; Write-Host '=== TTS (Piper) :8001 ===' -Foreground Cyan; uvicorn app.server:app --host 0.0.0.0 --port 8001"
Start-Process powershell -ArgumentList @('-NoExit', '-Command', $tts) -WindowStyle Normal
Start-Sleep -Seconds 3
Write-Host "  [OK] TTS 窗口已打开" -ForegroundColor Green

# 3. ASR 窗口
Write-Host "[3/5] 打开 ASR 服务窗口..." -ForegroundColor Yellow
$asr = "cd '$ROOT\asr-service'; Write-Host '=== ASR (Faster-Whisper) gRPC:50051 ===' -Foreground Cyan; python -m app.server --device cpu --model medium --compute-type int8 --download-root ./models"
Start-Process powershell -ArgumentList @('-NoExit', '-Command', $asr) -WindowStyle Normal
Start-Sleep -Seconds 8
Write-Host "  [OK] ASR 窗口已打开" -ForegroundColor Green

# 4. 后端窗口
Write-Host "[4/5] 打开后端服务窗口..." -ForegroundColor Yellow
$be = "cd '$ROOT\backend'; Write-Host '=== 后端 (Spring Boot) :8080 ===' -Foreground Cyan; mvn spring-boot:run"
Start-Process powershell -ArgumentList @('-NoExit', '-Command', $be) -WindowStyle Normal
Write-Host "  [OK] 后端窗口已打开" -ForegroundColor Green

# 5. 前端窗口
Write-Host "[5/5] 打开前端服务窗口..." -ForegroundColor Yellow
$fe = "cd '$ROOT\frontend'; Write-Host '=== 前端 (Vite) :5173 ===' -Foreground Cyan; npm run dev"
Start-Process powershell -ArgumentList @('-NoExit', '-Command', $fe) -WindowStyle Normal
Write-Host "  [OK] 前端窗口已打开" -ForegroundColor Green

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  所有服务窗口已打开！" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  请在各独立窗口中查看服务日志" -ForegroundColor White
Write-Host ""
Write-Host "  访问地址：" -ForegroundColor White
Write-Host "    Frontend: http://localhost:5173" -ForegroundColor White
Write-Host "    Backend : http://localhost:8080" -ForegroundColor White
Write-Host "    TTS     : http://localhost:8001" -ForegroundColor White
Write-Host "    ASR     : localhost:50051 (gRPC)" -ForegroundColor White
Write-Host ""
Write-Host "  关闭对应窗口即可停止该服务" -ForegroundColor Yellow
Write-Host ""
