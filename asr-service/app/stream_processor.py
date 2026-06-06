import logging
import numpy as np

from app.engine import (
    WhisperEngine, VADProcessor, RingBuffer,
    AsrResultInfo, WordTimestampInfo,
)

logger = logging.getLogger(__name__)


class ASRStreamProcessor:
    """
    ASR 流式处理器
    整合 RingBuffer + VAD + WhisperModel，实现实时语音识别。

    处理流程:
    1. RingBuffer 缓存音频流
    2. VAD 检测语音段
    3. VAD 段送入 WhisperModel 识别
    4. 返回流式识别结果
    """

    def __init__(self, engine: WhisperEngine, sample_rate: int = 16000,
                 window_size_ms: int = 500, vad_threshold_ms: int = 500,
                 language: str = None):
        self.engine = engine
        self.sample_rate = sample_rate
        self.vad = VADProcessor(sample_rate, vad_threshold_ms)
        self.ring_buffer = RingBuffer(
            buffer_size_seconds=max(window_size_ms / 500, 3.0),
            sample_rate=sample_rate,
        )

        # 将 window_size_ms 转换为采样点（16bit = 2 bytes）
        self.window_size_bytes = int(sample_rate * 2 * window_size_ms / 500)

        self.language = language
        self._accumulated_text = ""
        self._segment_id = 0

    def process_chunk(self, audio_bytes: bytes) -> list:
        """
        处理音频数据块。
        返回识别结果列表（可能为空列表）。
        """
        results = []

        # 写入环形缓冲区
        self.ring_buffer.write(audio_bytes)

        # VAD 检测
        segments = self.vad.process(audio_bytes)

        for segment in segments:
            try:
                audio_np = np.frombuffer(segment, dtype=np.int16).astype(np.float32) / 32768.0

                if len(audio_np) < self.sample_rate * 0.3:  # 忽略短于 0.3 秒的段
                    continue

                text, segments_info, confidence = self.engine.transcribe_file(
                    audio=audio_np,
                    language=self.language,
                    enable_vad=False,  # 已经过 VAD，不需要再次过滤
                    vad_filter=False,
                )

                if text:
                    self._accumulated_text += (" " if self._accumulated_text else "") + text
                    self._segment_id += 1

                    word_timestamps = []
                    for seg in segments_info:
                        for w in seg.words:
                            word_timestamps.append(WordTimestampInfo(
                                word=w.word,
                                start_time=w.start_time,
                                end_time=w.end_time,
                                probability=w.probability,
                            ))

                    results.append(AsrResultInfo(
                        text=self._accumulated_text,
                        is_final=False,
                        confidence=confidence,
                        start_time=0.0,
                        end_time=segments_info[-1].end_time if segments_info else 0.0,
                        language=self.language or "auto",
                        word_timestamps=word_timestamps,
                    ))

            except Exception as e:
                logger.error(f"识别 VAD 段时出错: {e}")

        return results

    def finish(self) -> list:
        """结束流式处理，返回最终结果"""
        results = []

        # Flush VAD 缓冲区中剩余语音
        remaining = self.vad.flush()
        if remaining:
            try:
                audio_np = np.frombuffer(remaining, dtype=np.int16).astype(np.float32) / 32768.0
                if len(audio_np) >= self.sample_rate * 0.3:
                    text, segments_info, confidence = self.engine.transcribe_file(
                        audio=audio_np,
                        language=self.language,
                        enable_vad=False,
                        vad_filter=False,
                    )
                    if text:
                        self._accumulated_text += (" " if self._accumulated_text else "") + text
            except Exception as e:
                logger.error(f"Flush 识别时出错: {e}")

        results.append(AsrResultInfo(
            text=self._accumulated_text,
            is_final=True,
            confidence=0.95,
            start_time=0.0,
            end_time=0.0,
            language=self.language or "auto",
        ))

        return results

    def reset(self):
        """重置处理器状态"""
        self.vad.reset()
        self.ring_buffer.clear()
        self._accumulated_text = ""
        self._segment_id = 0
