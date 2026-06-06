"""
Piper TTS 可用语音列表
来源: https://github.com/rhasspy/piper?tab=readme-ov-file#voices

每个语音条目包含:
  - id:         唯一标识
  - name:       显示名称
  - model_url:  .onnx 模型文件的下载 URL
  - config_url: .json 配置文件的下载 URL
  - language:   语言代码
  - quality:    质量等级 (high, medium, low)
"""

# =============================================================================
# Piper 英语语音
# =============================================================================
PIPER_VOICES = [
    # ---- 美式英语 (高音质) ----
    {
        "id": "en_US-amy-medium",
        "name": "Amy (US, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json",
        "language": "en-US",
        "quality": "medium",
        "gender": "female",
    },
    {
        "id": "en_US-amy-low",
        "name": "Amy (US, Low)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx.json",
        "language": "en-US",
        "quality": "low",
        "gender": "female",
    },
    {
        "id": "en_US-lessac-medium",
        "name": "Lessac (US, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
        "language": "en-US",
        "quality": "medium",
        "gender": "female",
    },
    {
        "id": "en_US-lessac-low",
        "name": "Lessac (US, Low)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/low/en_US-lessac-low.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/low/en_US-lessac-low.onnx.json",
        "language": "en-US",
        "quality": "low",
        "gender": "female",
    },
    {
        "id": "en_US-libritts_r-medium",
        "name": "LibriTTS-R (US, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts_r/medium/en_US-libritts_r-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts_r/medium/en_US-libritts_r-medium.onnx.json",
        "language": "en-US",
        "quality": "medium",
        "gender": "female",
    },
    {
        "id": "en_US-libritts-high",
        "name": "LibriTTS (US, High)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts/high/en_US-libritts-high.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts/high/en_US-libritts-high.onnx.json",
        "language": "en-US",
        "quality": "high",
        "gender": "female",
    },
    {
        "id": "en_US-ryan-medium",
        "name": "Ryan (US, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx.json",
        "language": "en-US",
        "quality": "medium",
        "gender": "male",
    },
    {
        "id": "en_US-ryan-low",
        "name": "Ryan (US, Low)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/low/en_US-ryan-low.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/low/en_US-ryan-low.onnx.json",
        "language": "en-US",
        "quality": "low",
        "gender": "male",
    },
    # ---- 英式英语 ----
    {
        "id": "en_GB-alan-medium",
        "name": "Alan (GB, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json",
        "language": "en-GB",
        "quality": "medium",
        "gender": "male",
    },
    {
        "id": "en_GB-alan-low",
        "name": "Alan (GB, Low)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/low/en_GB-alan-low.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/low/en_GB-alan-low.onnx.json",
        "language": "en-GB",
        "quality": "low",
        "gender": "male",
    },
    {
        "id": "en_GB-semaine-medium",
        "name": "Semaine (GB, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/semaine/medium/en_GB-semaine-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/semaine/medium/en_GB-semaine-medium.onnx.json",
        "language": "en-GB",
        "quality": "medium",
        "gender": "female",
    },
    {
        "id": "en_GB-vctk-medium",
        "name": "VCTK (GB, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/vctk/medium/en_GB-vctk-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/vctk/medium/en_GB-vctk-medium.onnx.json",
        "language": "en-GB",
        "quality": "medium",
        "gender": "mixed",
    },
    # ---- 中文 ----
    {
        "id": "zh_CN-huayan-medium",
        "name": "Huayan (CN, Medium)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json",
        "language": "zh-CN",
        "quality": "medium",
        "gender": "female",
    },
    {
        "id": "zh_CN-huayan-low",
        "name": "Huayan (CN, Low)",
        "model_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/low/zh_CN-huayan-low.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/low/zh_CN-huayan-low.onnx.json",
        "language": "zh-CN",
        "quality": "low",
        "gender": "female",
    },
]

# =============================================================================
# Edge TTS 语音（精选）
# 完整列表可通过 `edge-tts --list-voices` 获取
# =============================================================================
EDGE_TTS_VOICES = [
    # ---- 美式英语 ----
    {
        "id": "en-US-AriaNeural",
        "name": "Aria (US, Female)",
        "language": "en-US",
        "gender": "female",
    },
    {
        "id": "en-US-JennyNeural",
        "name": "Jenny (US, Female)",
        "language": "en-US",
        "gender": "female",
    },
    {
        "id": "en-US-GuyNeural",
        "name": "Guy (US, Male)",
        "language": "en-US",
        "gender": "male",
    },
    {
        "id": "en-US-DavisNeural",
        "name": "Davis (US, Male)",
        "language": "en-US",
        "gender": "male",
    },
    {
        "id": "en-US-AmberNeural",
        "name": "Amber (US, Female)",
        "language": "en-US",
        "gender": "female",
    },
    {
        "id": "en-US-AnaNeural",
        "name": "Ana (US, Female, Child)",
        "language": "en-US",
        "gender": "female",
    },
    # ---- 英式英语 ----
    {
        "id": "en-GB-SoniaNeural",
        "name": "Sonia (GB, Female)",
        "language": "en-GB",
        "gender": "female",
    },
    {
        "id": "en-GB-RyanNeural",
        "name": "Ryan (GB, Male)",
        "language": "en-GB",
        "gender": "male",
    },
    # ---- 中文 ----
    {
        "id": "zh-CN-XiaoxiaoNeural",
        "name": "晓晓 (CN, Female)",
        "language": "zh-CN",
        "gender": "female",
    },
    {
        "id": "zh-CN-YunxiNeural",
        "name": "云希 (CN, Male)",
        "language": "zh-CN",
        "gender": "male",
    },
    {
        "id": "zh-CN-YunjianNeural",
        "name": "云健 (CN, Male)",
        "language": "zh-CN",
        "gender": "male",
    },
]
