"""
TTS 微服务 - FastAPI 服务器
支持 Piper TTS（本地离线）和 Edge TTS（免费在线）双引擎

启动方式:
    uvicorn app.server:app --host 0.0.0.0 --port 8000

Docker 部署:
    docker run -p 8000:8000 -v tts_models:/app/models tts-service
"""

import io
import logging
import os
import time
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, FileResponse
from pydantic import BaseModel, Field

from .piper_engine import PiperEngine
from .edge_engine import EdgeTTSEngine
from .voices import PIPER_VOICES, EDGE_TTS_VOICES

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("TTSServer")

# ---------------------------------------------------------------------------
# FastAPI 应用
# ---------------------------------------------------------------------------
app = FastAPI(
    title="LingoAI TTS Service",
    description="本地 TTS 微服务 — 支持 Piper TTS（离线）和 Edge TTS（免费在线）",
    version="1.0.0",
)

# ---------------------------------------------------------------------------
# CORS 配置 - 允许前端跨域访问
# ---------------------------------------------------------------------------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# 引擎单例
# ---------------------------------------------------------------------------
_default_models_dir = str(Path(__file__).resolve().parent.parent / "models" / "piper")
models_dir = os.environ.get("TTS_MODELS_DIR", _default_models_dir)
piper = PiperEngine(models_dir=models_dir)
edge = EdgeTTSEngine()

_startup_time = time.time()

# ---------------------------------------------------------------------------
# 请求/响应模型
# ---------------------------------------------------------------------------


class TTSRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=5000, description="待合成文本")
    engine: str = Field(
        default="piper",
        description="TTS 引擎: 'piper' (本地离线) 或 'edge-tts' (免费在线)",
    )
    voice: str = Field(
        default="en_US-amy-medium",
        description="语音 ID（不同引擎的语音列表不同）",
    )
    speed: float = Field(
        default=1.0, ge=0.5, le=2.0,
        description="语速倍率 (仅 Piper 支持, 0.5~2.0)",
    )
    rate: str = Field(
        default="+0%",
        description="语速调节 (仅 Edge TTS 支持, 如 +10%, -20%)",
    )
    pitch: str = Field(
        default="+0Hz",
        description="音调调节 (仅 Edge TTS 支持, 如 +10Hz, -5Hz)",
    )
    output_format: str = Field(
        default="wav",
        pattern=r"^(wav|mp3)$",
        description="输出音频格式: wav (Piper 原生) 或 mp3",
    )


class VoiceInfo(BaseModel):
    id: str
    name: str
    language: str
    quality: Optional[str] = None
    gender: Optional[str] = None
    downloaded: bool = False
    engine: str


class VoicesResponse(BaseModel):
    engine: str
    voices: list[VoiceInfo]


class HealthResponse(BaseModel):
    status: str
    piper_available: bool
    edge_available: bool
    piper_models_dir: str
    uptime_seconds: float

# ---------------------------------------------------------------------------
# API 端点
# ---------------------------------------------------------------------------


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查"""
    return HealthResponse(
        status="ok",
        piper_available=piper.is_available(),
        edge_available=True,  # Edge TTS 作为 Python 库始终可用
        piper_models_dir=models_dir,
        uptime_seconds=time.time() - _startup_time,
    )


@app.get("/voices", response_model=VoicesResponse)
async def list_voices(
    engine: str = Query("piper", regex=r"^(piper|edge-tts)$"),
    language: Optional[str] = Query(None, description="按语言过滤，如 en-US"),
):
    """列出可用的语音列表"""
    if engine == "piper":
        # 静态定义的语音 + 检测本地已下载的
        local_voices = {v["id"] for v in piper.list_available_voices()}
        voices = []
        for v in PIPER_VOICES:
            if language and not v["language"].startswith(language):
                continue
            voices.append(VoiceInfo(
                id=v["id"],
                name=v["name"],
                language=v["language"],
                quality=v["quality"],
                gender=v["gender"],
                downloaded=v["id"] in local_voices,
                engine="piper",
            ))
        return VoicesResponse(engine="piper", voices=voices)

    else:  # edge-tts
        edge_voices = await edge.list_voices(language=language)
        voices = [
            VoiceInfo(
                id=v["id"],
                name=v["name"],
                language=v["locale"],
                gender=v["gender"],
                downloaded=True,  # Edge TTS 无需下载
                engine="edge-tts",
            )
            for v in edge_voices
        ]
        return VoicesResponse(engine="edge-tts", voices=voices)


@app.get("/voices/default")
async def get_default_voice(engine: str = Query("piper", regex=r"^(piper|edge-tts)$")):
    """获取默认语音配置"""
    if engine == "piper":
        return {
            "engine": "piper",
            "voice": "en_US-amy-medium",
            "name": "Amy (US, Medium)",
            "output_format": "wav",
        }
    else:
        return {
            "engine": "edge-tts",
            "voice": "en-US-AriaNeural",
            "name": "Aria (US, Female)",
            "output_format": "mp3",
        }


@app.post("/tts")
async def synthesize_speech(request: TTSRequest):
    """
    合成语音

    根据 engine 字段选择 TTS 引擎:
    - "piper":    使用 Piper 本地神经 TTS（完全离线）
    - "edge-tts": 使用 Microsoft Edge 免费在线 TTS（需网络）

    返回: 音频二进制（Content-Type: audio/wav 或 audio/mpeg）
    """
    if not request.text.strip():
        raise HTTPException(status_code=422, detail="Text cannot be empty")

    if len(request.text) > 5000:
        raise HTTPException(status_code=422, detail="Text too long (max 5000 chars)")

    try:
        # Edge TTS 原生输出 mp3，不需要 wav 转换
        if request.engine == "edge-tts" and request.output_format == "wav":
            request.output_format = "mp3"

        if request.engine == "piper":
            if not piper.is_available():
                raise HTTPException(
                    status_code=503,
                    detail="Piper is not available. Check that the piper binary is installed.",
                )

            audio_data = await piper.synthesize(
                text=request.text,
                voice_id=request.voice,
                speed=request.speed,
                output_format=request.output_format,
            )

        elif request.engine == "edge-tts":
            audio_data = await edge.synthesize(
                text=request.text,
                voice=request.voice,
                rate=request.rate,
                pitch=request.pitch,
                output_format=request.output_format,
            )

        else:
            raise HTTPException(status_code=400, detail=f"Unknown engine: {request.engine}")

        content_type = "audio/wav" if request.output_format == "wav" else "audio/mpeg"

        return Response(
            content=audio_data,
            media_type=content_type,
            headers={
                "X-TTS-Engine": request.engine,
                "X-TTS-Voice": request.voice,
                "X-TTS-Duration": str(len(audio_data)),
            },
        )

    except FileNotFoundError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except RuntimeError as e:
        logger.error("TTS synthesis failed: %s", str(e))
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        logger.exception("Unexpected TTS error")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tts/download", response_class=FileResponse)
async def download_voice_model(voice_id: str = Query(..., description="语音 ID")):
    """
    下载 Piper 语音模型

    从 HuggingFace 下载指定的 .onnx 模型和 .json 配置文件到本地 models 目录
    """
    # 查找语音定义
    voice_def = next((v for v in PIPER_VOICES if v["id"] == voice_id), None)
    if not voice_def:
        raise HTTPException(status_code=404, detail=f"Voice '{voice_id}' not found")

    # 检查是否已下载
    if piper.get_voice_path(voice_id):
        return {"status": "already_downloaded", "voice_id": voice_id}

    # 下载模型文件
    import httpx

    model_url = voice_def["model_url"]
    config_url = voice_def["config_url"]

    model_path = piper.models_dir / f"{voice_id}.onnx"
    config_path = piper.models_dir / f"{voice_id}.onnx.json"

    piper.models_dir.mkdir(parents=True, exist_ok=True)

    try:
        async with httpx.AsyncClient(timeout=300) as client:
            # 下载 .onnx 模型
            logger.info("Downloading model: %s", model_url)
            resp = await client.get(model_url)
            resp.raise_for_status()
            with open(model_path, "wb") as f:
                f.write(resp.content)
            logger.info("Model downloaded: %s (%d bytes)", model_path, len(resp.content))

            # 下载 .json 配置
            logger.info("Downloading config: %s", config_url)
            resp = await client.get(config_url)
            resp.raise_for_status()
            with open(config_path, "wb") as f:
                f.write(resp.content)
            logger.info("Config downloaded: %s (%d bytes)", config_path, len(resp.content))

        return {
            "status": "success",
            "voice_id": voice_id,
            "model_size": model_path.stat().st_size,
        }

    except Exception as e:
        # 清理部分下载的文件
        model_path.unlink(missing_ok=True)
        config_path.unlink(missing_ok=True)
        raise HTTPException(status_code=500, detail=f"Download failed: {str(e)}")


@app.get("/models")
async def list_downloaded_models():
    """列出本地已下载的 Piper 语音模型"""
    voices = piper.list_available_voices()
    return {
        "engine": "piper",
        "models_dir": models_dir,
        "voices": [
            {
                "id": v["id"],
                "path": v["path"],
                "has_config": v["has_config"],
            }
            for v in voices
        ],
    }
