"""
Edge TTS 引擎
使用 Microsoft Edge 的在线 TTS 服务（免费，无需 API Key，需要网络）
"""

import asyncio
import io
import logging
import tempfile
from pathlib import Path
from typing import Optional

import edge_tts

logger = logging.getLogger("EdgeTTSEngine")


class EdgeTTSEngine:
    """Edge TTS 引擎封装"""

    async def list_voices(self, language: Optional[str] = None) -> list[dict]:
        """
        列出可用的 Edge TTS 语音

        Args:
            language: 按语言过滤（如 "en-US", "zh-CN"）

        Returns:
            语音列表
        """
        voices = await edge_tts.list_voices()
        result = []
        for v in voices:
            if language and not v["Name"].startswith(language.replace("-", "-")):
                continue
            result.append({
                "id": v["Name"],
                "name": v["FriendlyName"] or v["ShortName"],
                "locale": v["Locale"],
                "gender": v["Gender"],
                "content_categories": v.get("ContentCategories", []),
                "voice_personalities": v.get("VoicePersonalities", []),
            })
        return result

    async def synthesize(
        self,
        text: str,
        voice: str = "en-US-AriaNeural",
        rate: str = "+0%",
        pitch: str = "+0Hz",
        output_format: str = "mp3",
    ) -> bytes:
        """
        使用 Edge TTS 合成语音

        Args:
            text:          待合成文本
            voice:         语音名称（如 en-US-AriaNeural）
            rate:          语速调节（如 "+10%", "-20%"）
            pitch:         音调调节（如 "+10Hz", "-5Hz"）
            output_format: 输出格式 (mp3 / wav / ogg / opus / webm / aac / flac)

        Returns:
            音频二进制数据
        """
        # Edge TTS 始终输出 mp3，如需其他格式需额外转换
        # 这里直接使用 mp3

        # 构建 Communicate 对象
        communicate = edge_tts.Communicate(
            text=text,
            voice=voice,
            rate=rate,
            pitch=pitch,
        )

        # 收集音频流
        audio_chunks = []
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_chunks.append(chunk["data"])

        if not audio_chunks:
            raise RuntimeError("Edge TTS produced empty audio output")

        audio_data = b"".join(audio_chunks)

        logger.info(
            "Edge TTS OK: voice=%s text_len=%d audio_len=%d",
            voice, len(text), len(audio_data),
        )

        # 如需 wav 格式，需要额外转换（使用 ffmpeg）
        if output_format == "wav":
            audio_data = await self._convert_to_wav(audio_data)

        return audio_data

    async def _convert_to_wav(self, mp3_data: bytes) -> bytes:
        """将 mp3 转换为 wav（需要 ffmpeg）"""
        import subprocess

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as tmp_in:
            tmp_in.write(mp3_data)
            tmp_in_path = tmp_in.name

        tmp_out_path = tmp_in_path.replace(".mp3", ".wav")

        try:
            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-y",
                "-i", tmp_in_path,
                "-acodec", "pcm_s16le",
                "-ar", "22050",
                "-ac", "1",
                tmp_out_path,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            try:
                _, stderr = await asyncio.wait_for(proc.communicate(), timeout=30)
            except asyncio.TimeoutError:
                proc.kill()
                logger.warning("ffmpeg conversion timed out, returning original mp3")
                return mp3_data

            if proc.returncode != 0:
                logger.warning("ffmpeg conversion failed: %s", stderr.decode()[:200])
                return mp3_data  # fallback: return original mp3

            with open(tmp_out_path, "rb") as f:
                wav_data = f.read()
            return wav_data

        finally:
            Path(tmp_in_path).unlink(missing_ok=True)
            Path(tmp_out_path).unlink(missing_ok=True)
