"""
Pronunciation Evaluation Service — FastAPI 微服务

基于 wav2vec2 + phonemizer 的英语发音评测服务。
被 LingoAI Spring Boot 后端通过 HTTP 调用。

端点:
  POST /evaluate — 评测发音
  GET  /health   — 健康检查
"""

import logging
import os

import uvicorn
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware

from .phoneme_scorer import evaluate_pronunciation

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Pronunciation Evaluation Service",
    description="wav2vec2-based English pronunciation scoring service",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/evaluate")
async def evaluate(
    file: UploadFile = File(..., description="用户录音音频文件 (wav/webm/opus)"),
    reference_text: str = Form(..., description="参考文本（用户想说的文本）"),
    language: str = Form("en", description="语言代码: en/zh/ja/fr/de/ko"),
):
    """
    发音评测端点

    接收用户录音和参考文本，返回音素级发音评分。
    """
    if not reference_text.strip():
        raise HTTPException(status_code=400, detail="reference_text 不能为空")

    audio_bytes = await file.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="音频文件为空")

    device = os.getenv("PRONUNCIATION_DEVICE", "cpu")
    logger.info(
        "Evaluating: ref_text='%s', language=%s, audio_bytes=%d, device=%s",
        reference_text[:50],
        language,
        len(audio_bytes),
        device,
    )

    try:
        result = evaluate_pronunciation(
            audio_bytes=audio_bytes,
            reference_text=reference_text,
            language=language,
            device=device,
        )
        logger.info(
            "Evaluation complete: accuracy=%.1f, fluency=%.1f, completeness=%.1f",
            result["accuracy_score"],
            result["fluency_score"],
            result["completeness_score"],
        )
        return result
    except Exception as e:
        logger.error("Evaluation failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8002"))
    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=True)
