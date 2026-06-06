FROM python:3.11-slim

WORKDIR /app

# 安装系统依赖 (webrtcvad 需要 gcc)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    g++ \
    libsndfile1 \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY app/ ./app/
COPY generate_proto.py .

# 生成 gRPC 代码
RUN python generate_proto.py

# 创建模型目录
RUN mkdir -p /app/models

# 暴露 gRPC 端口
EXPOSE 50051

# 环境变量
ENV ASR_PORT=50051
ENV ASR_MODEL=large-v2
ENV ASR_DEVICE=cpu
ENV ASR_COMPUTE_TYPE=int8
ENV ASR_DOWNLOAD_ROOT=/app/models

# 启动服务
ENTRYPOINT ["python", "-m", "app.server"]
