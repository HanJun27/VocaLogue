"""
Piper TTS 语音模型下载脚本

用法:
    python -m app.download_model en_US-amy-medium
    python -m app.download_model en_US-lessac-medium zh_CN-huayan-medium

将从 HuggingFace 下载指定的 .onnx 模型和 .json 配置文件
"""

import asyncio
import logging
import os
import sys
from pathlib import Path

import httpx

from .voices import PIPER_VOICES

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("DownloadModels")


async def download_voice(voice_id: str, models_dir: str = "/app/models/piper") -> bool:
    """下载单个语音模型"""
    # 查找语音定义
    voice_def = next((v for v in PIPER_VOICES if v["id"] == voice_id), None)
    if not voice_def:
        logger.error("Voice '%s' not found in voice list", voice_id)
        return False

    models_path = Path(models_dir)
    models_path.mkdir(parents=True, exist_ok=True)

    model_path = models_path / f"{voice_id}.onnx"
    config_path = models_path / f"{voice_id}.onnx.json"

    # 跳过已下载的
    if model_path.exists() and config_path.exists():
        logger.info("Voice '%s' already downloaded", voice_id)
        return True

    async with httpx.AsyncClient(timeout=600, follow_redirects=True) as client:
        # 下载模型
        if not model_path.exists():
            logger.info("Downloading model: %s", voice_def["model_url"])
            resp = await client.get(voice_def["model_url"])
            resp.raise_for_status()
            with open(model_path, "wb") as f:
                f.write(resp.content)
            logger.info(
                "  -> saved: %s (%d MB)",
                model_path, len(resp.content) / 1024 / 1024,
            )

        # 下载配置
        if not config_path.exists():
            logger.info("Downloading config: %s", voice_def["config_url"])
            resp = await client.get(voice_def["config_url"])
            resp.raise_for_status()
            with open(config_path, "wb") as f:
                f.write(resp.content)
            logger.info("  -> saved: %s", config_path)

    return True


async def main():
    model_dir = os.environ.get("TTS_MODELS_DIR", "/app/models/piper")
    voices = sys.argv[1:] if len(sys.argv) > 1 else ["en_US-amy-medium"]

    logger.info("Downloading %d voice(s) to %s", len(voices), model_dir)

    success = 0
    for voice_id in voices:
        if await download_voice(voice_id, model_dir):
            success += 1

    logger.info("Done: %d/%d voices downloaded successfully", success, len(voices))
    sys.exit(0 if success == len(voices) else 1)


if __name__ == "__main__":
    asyncio.run(main())
