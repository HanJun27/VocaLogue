import logging
import numpy as np
import subprocess
import tempfile
import os as _os

logger = logging.getLogger("AsrServer")

def _convert_to_pcm(audio_bytes: bytes) -> np.ndarray:
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
        subprocess.run(cmd, check=True, capture_output=True, timeout=30)
        with open(tmp_out_path, "rb") as f:
            raw_data = f.read()
        audio_np = np.frombuffer(raw_data, dtype=np.int16).astype(np.float32) / 32768.0
        _os.unlink(tmp_in_path)
        if _os.path.exists(tmp_out_path):
            _os.unlink(tmp_out_path)
        return audio_np
    except FileNotFoundError:
        logger.warning("ffmpeg not found, using raw PCM fallback")
    except Exception:
        logger.warning("ffmpeg failed, using raw PCM fallback")
    if len(audio_bytes) % 2 != 0:
        audio_bytes = audio_bytes + b"\x00"
    if len(audio_bytes) < 320:
        audio_bytes = audio_bytes * (320 // len(audio_bytes) + 1)
    return np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32) / 32768.0
