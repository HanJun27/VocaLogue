import logging
import numpy as np
import subprocess
import tempfile
import os as _os

logger = logging.getLogger("AsrServer")

def _convert_to_pcm(audio_bytes: bytes) -> np.ndarray:
    """
    将 WebM/Opus 音频字节转换为 float32 PCM (16kHz mono)

    使用 ffmpeg 解码，失败时抛出异常（不再使用 raw PCM fallback，
    因为将 Opus 字节当 int16 PCM 处理会产生噪音，VAD 会全部过滤掉）
    """
    if not audio_bytes:
        raise ValueError("audio_bytes is empty")

    try:
        with tempfile.NamedTemporaryFile(suffix=".webm", delete=False) as tmp_in:
            tmp_in.write(audio_bytes)
            tmp_in_path = tmp_in.name

        tmp_out_path = tmp_in_path + ".raw"

        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", tmp_in_path,
            "-f", "s16le", "-acodec", "pcm_s16le",
            "-ar", "16000", "-ac", "1",
            tmp_out_path,
        ]

        proc = subprocess.run(cmd, capture_output=True, timeout=30)

        # 检查 ffmpeg 是否成功
        if proc.returncode != 0:
            stderr_msg = proc.stderr.decode("utf-8", errors="replace").strip()
            logger.error("ffmpeg 返回非零: rc=%d, stderr=%s", proc.returncode, stderr_msg[:500])
            raise RuntimeError(f"ffmpeg exited with code {proc.returncode}: {stderr_msg[:200]}")

        # 检查输出文件是否存在且非空
        if not _os.path.exists(tmp_out_path) or _os.path.getsize(tmp_out_path) == 0:
            logger.error("ffmpeg 输出文件为空或不存在: %s", tmp_out_path)
            raise RuntimeError("ffmpeg produced empty output")

        with open(tmp_out_path, "rb") as f:
            raw_data = f.read()

        audio_np = np.frombuffer(raw_data, dtype=np.int16).astype(np.float32) / 32768.0

        # 清理临时文件
        _os.unlink(tmp_in_path)
        _os.unlink(tmp_out_path)

        if len(audio_np) < 160:  # 小于 10ms 通常无意义
            logger.warning("PCM 音频过短: %d samples (%.1fms)", len(audio_np), len(audio_np) / 16.0)
            return np.array([], dtype=np.float32)

        return audio_np

    except FileNotFoundError:
        logger.error("ffmpeg 未安装或不在 PATH 中，无法转换音频")
        raise RuntimeError("ffmpeg not found in PATH")
    except subprocess.TimeoutExpired as e:
        logger.error("ffmpeg 转换超时 (30s): 输入 %d bytes", len(audio_bytes))
        # 尝试清理
        if '_os' in dir() and _os.path.exists(tmp_in_path):
            try: _os.unlink(tmp_in_path)
            except: pass
        raise RuntimeError(f"ffmpeg timed out after 30s, input was {len(audio_bytes)} bytes") from e
    except Exception as e:
        logger.error("PCM 转换失败: %s (输入 %d bytes)", e, len(audio_bytes))
        # 尝试清理
        try:
            if '_os' in globals() and 'tmp_in_path' in dir() and _os.path.exists(tmp_in_path):
                _os.unlink(tmp_in_path)
            if 'tmp_out_path' in dir() and _os.path.exists(tmp_out_path):
                _os.unlink(tmp_out_path)
        except:
            pass
        raise RuntimeError(f"PCM conversion failed: {e}") from e
