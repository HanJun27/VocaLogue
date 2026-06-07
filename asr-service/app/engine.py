from faster_whisper import WhisperModel
from typing import Optional, Generator, List, Tuple
import numpy as np
import webrtcvad
import logging
import os
from dataclasses import dataclass, field
from collections import deque
import time

logger = logging.getLogger(__name__)

# ============================================================
# Ring Buffer — 环形缓冲区
# ============================================================
class RingBuffer:
    """
    环形缓冲区，用于缓存音频流。
    解决首字丢失和尾音截断问题：
    - 使用滑动窗口保持最近 N 秒的音频
    - 支持 pre-roll（在语音开始前保留一小段）
    """

    def __init__(self, buffer_size_seconds: float, sample_rate: int = 16000):
        self.sample_rate = sample_rate
        self.buffer_size = int(buffer_size_seconds * sample_rate * 2)  # 16bit = 2 bytes
        self.buffer = deque(maxlen=self.buffer_size)
        self._write_pos = 0

    def write(self, data: bytes):
        """写入音频数据"""
        for b in data:
            self.buffer.append(b)

    def read_all(self) -> bytes:
        """读取所有缓存数据"""
        return bytes(self.buffer)

    def read_recent(self, seconds: float) -> bytes:
        """读取最近 N 秒的音频数据"""
        num_bytes = int(seconds * self.sample_rate * 2)
        data = bytes(self.buffer)
        if len(data) > num_bytes:
            return data[-num_bytes:]
        return data

    def clear(self):
        """清空缓冲区"""
        self.buffer.clear()

    @property
    def size(self) -> int:
        return len(self.buffer)


# ============================================================
# VAD — 语音活动检测
# ============================================================
class VADProcessor:
    """
    语音活动检测处理器
    使用 WebRTC VAD 进行音轨分割 + faster-whisper vad_filter 进行静音过滤
    """

    # WebRTC VAD 模式: 0=普通, 1=低比特率, 2=高比特率, 3=激进
    VAD_MODE = 2
    FRAME_DURATION_MS = 30  # WebRTC VAD 帧长度（仅支持 10/20/30ms）

    def __init__(self, sample_rate: int = 16000, vad_threshold_ms: int = 500):
        self.sample_rate = sample_rate
        self.vad_threshold_ms = vad_threshold_ms  # 静音阈值，超过此时间视为语音结束
        self.vad = webrtcvad.Vad(self.VAD_MODE)
        self.frame_size = int(sample_rate * self.FRAME_DURATION_MS / 1000) * 2  # 字节

        self._speech_buffer = bytearray()
        self._silence_duration_ms = 0
        self._is_speaking = False
        self._segments: List[bytes] = []

    def process(self, audio_chunk: bytes) -> List[bytes]:
        """
        处理音频块，返回检测到的语音段。
        当检测到足够长的静音时，将之前的语音作为一个完整段返回。
        """
        speech_segments = []
        chunk_offset = 0

        while chunk_offset + self.frame_size <= len(audio_chunk):
            frame = audio_chunk[chunk_offset:chunk_offset + self.frame_size]
            chunk_offset += self.frame_size

            is_speech = self.vad.is_speech(frame, self.sample_rate)

            if is_speech:
                self._speech_buffer.extend(frame)
                self._silence_duration_ms = 0
                if not self._is_speaking:
                    self._is_speaking = True
                    logger.debug("VAD: 语音开始")
            else:
                if self._is_speaking:
                    self._silence_duration_ms += self.FRAME_DURATION_MS
                    self._speech_buffer.extend(frame)

                    if self._silence_duration_ms >= self.vad_threshold_ms:
                        # 语音段结束
                        segment = bytes(self._speech_buffer)
                        speech_segments.append(segment)
                        logger.debug(f"VAD: 语音段结束, 长度={len(segment)} bytes")
                        self._speech_buffer.clear()
                        self._is_speaking = False
                        self._silence_duration_ms = 0

        return speech_segments

    def flush(self) -> Optional[bytes]:
        """强制输出缓冲区中的剩余语音"""
        self._silence_duration_ms = 0
        if self._is_speaking and len(self._speech_buffer) > 0:
            segment = bytes(self._speech_buffer)
            self._speech_buffer.clear()
            self._is_speaking = False
            logger.debug(f"VAD: flush 剩余语音段, 长度={len(segment)} bytes")
            return segment
        return None

    def reset(self):
        """重置状态"""
        self._speech_buffer.clear()
        self._silence_duration_ms = 0
        self._is_speaking = False


# ============================================================
# 识别结果
# ============================================================
@dataclass
class WordTimestampInfo:
    word: str
    start_time: float
    end_time: float
    probability: float


@dataclass
class SegmentInfo:
    id: int
    start_time: float
    end_time: float
    text: str
    confidence: float
    words: List[WordTimestampInfo] = field(default_factory=list)


@dataclass
class AsrResultInfo:
    text: str
    is_final: bool
    confidence: float
    start_time: float
    end_time: float
    language: str
    word_timestamps: List[WordTimestampInfo] = field(default_factory=list)


# ============================================================
# WhisperModel — 常驻内存的单例
# ============================================================
class WhisperEngine:
    """
    faster-whisper 引擎 — 启动时一次性加载模型并常驻内存
    避免每次请求重复加载模型带来的巨大开销。

    支持模型: tiny, base, small, medium, large-v2, large-v3
    """

    _instance: Optional['WhisperEngine'] = None
    _model: Optional[WhisperModel] = None

    def __init__(self, model_name: str = "large-v2", device: str = "cuda",
                 compute_type: str = "int8_float16"):
        self.model_name = model_name
        self.device = device
        self.compute_type = compute_type
        self._loaded = False

    @classmethod
    def get_instance(cls) -> Optional['WhisperEngine']:
        return cls._instance

    @classmethod
    def create_instance(cls, model_name: str = "large-v2", device: str = "cuda",
                        compute_type: str = "int8_float16",
                        download_root: str = "./models") -> 'WhisperEngine':
        """创建单例并加载模型"""
        if cls._instance is not None:
            # 如果模型和设备都相同，跳过重新加载
            if (cls._instance.model_name == model_name
                    and cls._instance.device == device
                    and cls._instance.compute_type == compute_type
                    and cls._instance.is_loaded):
                logger.info("模型和设备未变化，跳过重新加载: model=%s device=%s",
                            model_name, device)
                return cls._instance
            logger.info("WhisperEngine 已存在，更新模型配置...")
            cls._instance._load_model(model_name, device, compute_type, download_root)
            return cls._instance

        cls._instance = cls(model_name, device, compute_type)
        cls._instance._load_model(model_name, device, compute_type, download_root)
        return cls._instance

    def _load_model(self, model_name: str, device: str, compute_type: str,
                    download_root: str):
        """加载模型到内存"""
        logger.info(f"Loading WhisperModel: model={model_name}, device={device}, "
                    f"compute_type={compute_type}, download_root={download_root}")

        self.model_name = model_name
        self.device = device
        self.compute_type = compute_type

        # 转换 compute_type 字符串
        ct_map = {
            "int8": "int8",
            "int8_float16": "int8_float16",
            "float16": "float16",
            "float32": "float32",
        }
        ct = ct_map.get(compute_type, "int8_float16")

        # 优先使用本地路径：如果 download_root/<model_name>/ 目录存在且包含 model.bin
        local_path = os.path.join(download_root, model_name)
        if os.path.isdir(local_path) and os.path.isfile(os.path.join(local_path, "model.bin")):
            model_path = local_path
            logger.info(f"使用本地模型路径: {model_path}")
            self._model = WhisperModel(
                model_path,
                device=device,
                compute_type=ct,
                local_files_only=True,
            )
        else:
            logger.info(f"本地模型 {local_path} 不存在，从 HuggingFace 下载...")
            self._model = WhisperModel(
                model_name,
                device=device,
                compute_type=ct,
                download_root=download_root,
                local_files_only=False,
            )

        self._loaded = True
        logger.info(f"WhisperModel 加载完成: {model_name} on {device}")

    @property
    def is_loaded(self) -> bool:
        return self._loaded and self._model is not None

    def transcribe_file(self, audio: np.ndarray, language: str = None,
                        enable_vad: bool = True,
                        vad_filter: bool = True) -> Tuple[str, List[SegmentInfo], float]:
        """
        转录音频数据（非流式）
        """
        if not self._model:
            raise RuntimeError("Model not loaded")

        vad_params = {"vad_filter": vad_filter} if enable_vad else {}

        segments_result, info = self._model.transcribe(
            audio=audio,
            language=language,
            beam_size=5,
            word_timestamps=True,
            **vad_params,
        )

        segments = []
        for seg in segments_result:
            words = []
            if seg.words:
                for w in seg.words:
                    words.append(WordTimestampInfo(
                        word=w.word,
                        start_time=w.start,
                        end_time=w.end,
                        probability=w.probability,
                    ))
            segments.append(SegmentInfo(
                id=len(segments),
                start_time=seg.start,
                end_time=seg.end,
                text=seg.text.strip(),
                confidence=seg.avg_logprob,
                words=words,
            ))

        full_text = " ".join(s.text for s in segments)
        confidence = sum(s.confidence for s in segments) / max(len(segments), 1)

        return full_text, segments, confidence

    def transcribe_stream(self, audio: np.ndarray, language: str = None,
                          enable_vad: bool = True) -> Generator[AsrResultInfo, None, None]:
        """
        转录音频数据（流式），逐个 segment 返回
        """
        if not self._model:
            raise RuntimeError("Model not loaded")

        vad_params = {}
        if enable_vad:
            vad_params = {
                "vad_filter": True,
                "vad_parameters": {"min_silence_duration_ms": 500},
            }

        segments_result, info = self._model.transcribe(
            audio=audio,
            language=language,
            beam_size=5,
            word_timestamps=True,
            **vad_params,
        )

        detected_language = info.language
        accumulated_text = ""

        for seg in segments_result:
            words = []
            if seg.words:
                for w in seg.words:
                    words.append(WordTimestampInfo(
                        word=w.word,
                        start_time=w.start,
                        end_time=w.end,
                        probability=w.probability,
                    ))

            accumulated_text += (" " if accumulated_text else "") + seg.text.strip()

            yield AsrResultInfo(
                text=accumulated_text,
                is_final=False,  # 非最后一段
                confidence=seg.avg_logprob,
                start_time=0.0,
                end_time=seg.end,
                language=detected_language,
                word_timestamps=words,
            )

        # 发出最终结果
        yield AsrResultInfo(
            text=accumulated_text,
            is_final=True,
            confidence=0.95,
            start_time=0.0,
            end_time=info.duration,
            language=detected_language,
        )
