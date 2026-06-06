# =============================================================================
# TTS Service Dockerfile
# Piper TTS 二进制在容器启动时下载（不在构建时下载，避免网络问题）
#
# 构建:
#   docker build -t tts-service ./tts-service
#
# 运行:
#   docker run -p 8000:8000 -v tts_models:/app/models/piper tts-service
#
# 首次启动会自动下载 Piper 二进制和默认语音模型
# =============================================================================

FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsndfile1 \
    espeak-ng-data \
    ffmpeg \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY app/ ./app/

# 创建模型目录
RUN mkdir -p /app/models/piper

# 创建 startup 脚本 - 在容器启动时下载 Piper 二进制
RUN echo '#!/bin/bash' > /startup.sh && \
    echo 'set -e' >> /startup.sh && \
    echo '' >> /startup.sh && \
    echo '# 下载 Piper 二进制（不阻塞启动，失败时 Edge TTS 仍可用）' >> /startup.sh && \
    echo 'PIPER_BIN=/usr/local/bin/piper' >> /startup.sh && \
    echo 'if [ ! -f "$PIPER_BIN" ]; then' >> /startup.sh && \
    echo '  echo ">>> Downloading Piper TTS binary..."' >> /startup.sh && \
    echo '  PIPER_URL="https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.zip"' >> /startup.sh && \
    echo '  if wget -q "$PIPER_URL" -O /tmp/piper.zip; then' >> /startup.sh && \
    echo '    unzip -o /tmp/piper.zip -d /piper && \' >> /startup.sh && \
    echo '    cp /piper/piper "$PIPER_BIN" && \' >> /startup.sh && \
    echo '    chmod +x "$PIPER_BIN" && \' >> /startup.sh && \
    echo '    rm -rf /tmp/piper.zip /piper && \' >> /startup.sh && \
    echo '    echo ">>> Piper TTS binary installed"' >> /startup.sh && \
    echo '  else' >> /startup.sh && \
    echo '    echo ">>> WARNING: Failed to download Piper binary. Only Edge TTS will be available."' >> /startup.sh && \
    echo '    rm -f /tmp/piper.zip' >> /startup.sh && \
    echo '  fi' >> /startup.sh && \
    echo 'fi' >> /startup.sh && \
    echo '' >> /startup.sh && \
    echo '# 下载默认 Piper 语音模型（后台下载，不阻塞启动）' >> /startup.sh && \
    echo 'DEFAULT_VOICE="en_US-amy-medium"' >> /startup.sh && \
    echo 'MODEL_FILE="$TTS_MODELS_DIR/$DEFAULT_VOICE.onnx"' >> /startup.sh && \
    echo 'if [ -f "$PIPER_BIN" ] && [ ! -f "$MODEL_FILE" ]; then' >> /startup.sh && \
    echo '  echo ">>> Downloading default voice model ($DEFAULT_VOICE) in background..."' >> /startup.sh && \
    echo '  (python -m app.download_model "$DEFAULT_VOICE" 2>/dev/null && \' >> /startup.sh && \
    echo '    echo ">>> Voice model downloaded") &' >> /startup.sh && \
    echo 'fi' >> /startup.sh && \
    echo '' >> /startup.sh && \
    echo '# 启动 uvicorn' >> /startup.sh && \
    echo 'echo ">>> Starting TTS Service..."' >> /startup.sh && \
    echo 'exec uvicorn app.server:app --host 0.0.0.0 --port 8000' >> /startup.sh && \
    chmod +x /startup.sh

# 暴露 HTTP 端口
EXPOSE 8000

# 环境变量
ENV TTS_MODELS_DIR=/app/models/piper
ENV PIPER_BIN=/usr/local/bin/piper

# 使用 startup 脚本确保 Piper 二进制就绪
ENTRYPOINT ["/startup.sh"]
