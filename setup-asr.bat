@echo off
REM ========================================================================
REM Faster-Whisper ASR 服务 - Windows 本地运行脚本
REM ========================================================================
echo ============================================
echo  Faster-Whisper ASR 服务安装
echo ============================================
echo.

REM 检查 Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python，请先安装 Python 3.10+
    pause
    exit /b 1
)

set ASR_DIR=%~dp0asr-service

echo [1/3] 安装 Python 依赖...
cd /d "%ASR_DIR%"
pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo [错误] 依赖安装失败
    pause
    exit /b 1
)

echo.
echo [2/3] 生成 gRPC 代码...
python generate_proto.py
if %errorlevel% neq 0 (
    echo [错误] gRPC 代码生成失败
    pause
    exit /b 1
)

echo.
echo [3/3] 创建模型目录...
if not exist "%ASR_DIR%\models" mkdir "%ASR_DIR%\models"

echo.
echo ============================================
echo  安装完成！
echo.
echo  启动命令:
echo    cd asr-service
echo    python -m app.server --device cpu --model large-v2
echo.
echo  可用参数:
echo    --port PORT           gRPC 端口 (默认 50051)
echo    --model MODEL         模型 (large-v2, medium, small, base, tiny)
echo    --device DEVICE       推理设备 (cuda / cpu)
echo    --compute-type TYPE   计算类型 (int8 / float16 / int8_float16 / float32)
echo    --download-root DIR   模型目录 (默认 ./models)
echo ============================================
pause
