"""
Wav2Vec2 发音评测核心逻辑

参考 Echoic 的 PhonemeScoringService，移植为独立 Python 服务：
1. 加载 Wav2Vec2ForCTC 模型（facebook/wav2vec2-base-960h）
2. 用 phonemizer (espeak) 将参考文本转为音素序列
3. CTC forced alignment 对齐音素到音频
4. 计算每词/每音素的发音得分
"""

import io
import logging
import math
import re
import tempfile
from pathlib import Path
from typing import Optional

import numpy as np
import soundfile as sf
import torch
import torchaudio

logger = logging.getLogger(__name__)

# ── 模型单例 ──────────────────────────────────────────────

_MODEL = None
_PROCESSOR = None
_DEVICE = "cpu"

WAV2VEC2_MODEL_ID = "facebook/wav2vec2-base-960h"

# 音素器 espeak 语言代码映射
_ESPEAK_LANG_MAP = {
    "en": "en-us",
    "zh": "cmn",
    "ja": "ja",
    "fr": "fr-fr",
    "de": "de",
    "ko": "ko",
}


def _load_model(model_id: str = WAV2VEC2_MODEL_ID, device: str = "cpu"):
    """懒加载 wav2vec2 模型（全局单例）"""
    global _MODEL, _PROCESSOR, _DEVICE
    if _MODEL is not None and _PROCESSOR is not None:
        return _MODEL, _PROCESSOR

    from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

    logger.info("Loading wav2vec2 model: %s (device=%s)", model_id, device)
    _PROCESSOR = Wav2Vec2Processor.from_pretrained(model_id)
    _MODEL = Wav2Vec2ForCTC.from_pretrained(model_id).eval()
    _DEVICE = device

    if device != "cpu":
        _MODEL = _MODEL.to(device)

    logger.info("Model loaded successfully")
    return _MODEL, _PROCESSOR


# ── 音素化 ────────────────────────────────────────────────


def _phonemize_words(words: list[str], language: str = "en") -> list[str]:
    """用 espeak 将单词列表转为 IPA 音素字符串列表"""
    if not words:
        return []

    try:
        from phonemizer import phonemize

        espeak_lang = _ESPEAK_LANG_MAP.get(language, language)
        result = phonemize(
            words,
            language=espeak_lang,
            backend="espeak",
            strip=True,
            preserve_punctuation=False,
            with_stress=True,
        )
        return [str(p) for p in result]
    except Exception as e:
        logger.warning("Phonemization failed (language=%s): %s", language, e)
        return [""] * len(words)


# ── 文本处理辅助 ──────────────────────────────────────────


def _display_word(word: str) -> str:
    return re.sub(r"(^\W+|\W+$)", "", word)


def _reference_words(reference_text: str) -> list[str]:
    words = []
    for part in reference_text.split():
        display = _display_word(part)
        if display:
            words.append(display)
    return words


_STRESS_RE = re.compile(r"[ˈˌ]")


def _strip_stress(phonemes: str) -> str:
    return _STRESS_RE.sub("", phonemes)


# ── 音频加载 ──────────────────────────────────────────────


def _load_audio(audio_bytes: bytes, target_sr: int = 16000) -> torch.Tensor:
    """加载音频字节并转为单声道 16kHz Tensor"""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        with open(tmp_path, "wb") as f:
            f.write(audio_bytes)
        waveform, sample_rate = sf.read(tmp_path, always_2d=True)
    finally:
        Path(tmp_path).unlink(missing_ok=True)

    tensor = torch.from_numpy(waveform.T).to(torch.float32)
    if tensor.shape[0] > 1:
        tensor = tensor.mean(dim=0, keepdim=True)

    if sample_rate != target_sr:
        tensor = torchaudio.functional.resample(tensor, sample_rate, target_sr)

    return tensor


# ── 核心评测 ──────────────────────────────────────────────


def _calibrate(raw: float) -> float:
    """校准映射: 将 CTC 原始分数 (0-100) 映射到更直观的显示分数"""
    if raw <= 0.0:
        return 0.0
    return 100.0 * (raw / 100.0) ** 0.25


def evaluate_pronunciation(
    audio_bytes: bytes,
    reference_text: str,
    language: str = "en",
    device: str = "cpu",
    model_id: str = WAV2VEC2_MODEL_ID,
) -> dict:
    """
    发音评测主入口

    参数:
        audio_bytes: WAV/WebM 音频字节
        reference_text: 参考文本（用户应该说出的文本）
        language: 语言代码 (en/zh/ja/fr/de/ko)
        device: 运行设备 (cpu/cuda/mps)

    返回:
        {
            "accuracy_score": float,       # 发音准确度 (0-100)
            "fluency_score": float,        # 流利度 (0-100)
            "completeness_score": float,    # 完整度 (0-100)
            "overall_pronunciation_score": float,  # 综合发音分
            "word_scores": [               # 每词评分
                {
                    "word": str,
                    "accuracy_score": float,
                    "expected_phonemes": str,
                    "phoneme_scores": [float]
                }
            ]
        }
    """
    model, processor = _load_model(model_id, device)

    # 1. 提取参考词
    words = _reference_words(reference_text)
    if not words:
        return {
            "accuracy_score": 100.0,
            "fluency_score": 100.0,
            "completeness_score": 100.0,
            "overall_pronunciation_score": 100.0,
            "word_scores": [],
        }

    # 2. 音素化
    ipa_phonemes = _phonemize_words(words, language)

    # 3. 加载音频
    waveform = _load_audio(audio_bytes)
    if waveform.shape[-1] == 0:
        logger.warning("Empty audio received")
        return {
            "accuracy_score": 0.0,
            "fluency_score": 0.0,
            "completeness_score": 0.0,
            "overall_pronunciation_score": 0.0,
            "word_scores": [],
        }

    # 4. 获取 log_probs
    inputs = processor(
        waveform.squeeze(0).cpu().numpy(),
        sampling_rate=16000,
        return_tensors="pt",
    )
    batch = {k: v.to(device) for k, v in inputs.items()}
    if device != "cpu":
        model = model.to(device)

    with torch.inference_mode():
        logits = model(**batch).logits
    log_probs = torch.log_softmax(logits[0], dim=-1)  # [T, C]

    # 5. 音素 → token ID
    vocab = processor.tokenizer.get_vocab()
    all_tokens: list[int] = []
    word_lengths: list[int] = []

    for phoneme_str in ipa_phonemes:
        clean = _strip_stress(phoneme_str)
        tokens = [vocab[ch] for ch in clean if ch in vocab]
        all_tokens.extend(tokens)
        word_lengths.append(len(tokens))

    # 6. CTC forced alignment
    blank = processor.tokenizer.pad_token_id
    targets = torch.tensor([all_tokens], dtype=torch.int32)
    input_lengths = torch.tensor([log_probs.shape[0]], dtype=torch.int32)
    target_lengths = torch.tensor([len(all_tokens)], dtype=torch.int32)

    word_acc: list[float] = []
    per_word_ph_scores: list[list[float]] = []

    if all_tokens:
        try:
            aligned_tokens, scores = torchaudio.functional.forced_align(
                log_probs.unsqueeze(0).cpu(),
                targets,
                input_lengths=input_lengths,
                target_lengths=target_lengths,
                blank=blank,
            )
            token_spans = torchaudio.functional.merge_tokens(
                aligned_tokens[0], scores[0], blank=blank
            )

            flat_scores = [
                100.0 * min(1.0, math.exp(span.score)) for span in token_spans
            ]
            while len(flat_scores) < len(all_tokens):
                flat_scores.append(0.0)

            idx = 0
            for length in word_lengths:
                if length == 0:
                    word_acc.append(0.0)
                    per_word_ph_scores.append([])
                else:
                    ph = flat_scores[idx : idx + length]
                    word_acc.append(sum(ph) / len(ph))
                    per_word_ph_scores.append(ph)
                idx += length
        except Exception as e:
            logger.warning("forced_align failed: %s", e)
            word_acc = [0.0] * len(words)
            per_word_ph_scores = [[] for _ in words]
    else:
        word_acc = [0.0] * len(words)
        per_word_ph_scores = [[] for _ in words]

    # 7. 计算三维度分数
    accuracy_score = (
        sum(_calibrate(a) for a in word_acc) / len(word_acc) if word_acc else 100.0
    )

    SPOKEN_THRESHOLD = 15.0
    spoken_count = sum(1 for acc in word_acc if acc >= SPOKEN_THRESHOLD)
    completeness_score = (
        min(100.0, 100.0 * spoken_count / len(words)) if words else 100.0
    )

    # 流利度: 基于停顿和语速
    # 没有词时间戳时，从音频时长估算
    duration = waveform.shape[-1] / 16000.0
    fluency_score = 100.0
    if duration > 0 and words:
        wps = len(words) / max(duration, 0.1)
        rate_score = max(0.0, 100.0 - abs(wps - 2.5) / 2.5 * 50.0)
        fluency_score = max(0.0, min(100.0, rate_score))

    # 8. 构建词级得分
    word_scores = []
    for word, ipa, acc, ph_scores in zip(
        words, ipa_phonemes, word_acc, per_word_ph_scores
    ):
        word_scores.append(
            {
                "word": word,
                "accuracy_score": round(_calibrate(acc), 1),
                "expected_phonemes": ipa,
                "phoneme_scores": [round(_calibrate(p), 1) for p in ph_scores],
            }
        )

    overall = round((accuracy_score + fluency_score + completeness_score) / 3, 1)

    return {
        "accuracy_score": round(accuracy_score, 1),
        "fluency_score": round(fluency_score, 1),
        "completeness_score": round(completeness_score, 1),
        "overall_pronunciation_score": round(overall, 1),
        "word_scores": word_scores,
    }
