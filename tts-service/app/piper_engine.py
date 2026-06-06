"""
Piper TTS 引擎
使用 Piper 本地神经 TTS 生成语音（完全离线）
需要提前下载 .onnx 模型文件和 .json 配置文件
"""

import asyncio
import logging
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

logger = logging.getLogger("PiperEngine")


class PiperEngine:
    """Piper TTS 引擎封装"""

    def __init__(self, models_dir: str = "/app/models/piper"):
        self.models_dir = Path(models_dir)
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self._piper_bin = None
        self._init_error = None
        try:
            self._piper_bin = self._find_piper_binary()
        except RuntimeError as e:
            self._init_error = str(e)
            logger.warning("Piper engine: %s", e)

    def _find_piper_binary(self) -> str:
        """查找 piper 可执行文件"""
        # 1. 环境变量指定
        import os
        env_path = os.environ.get("PIPER_BIN")
        if env_path and Path(env_path).exists():
            return env_path

        # 2. 相对于当前脚本目录的路径（适用于直接手动启动）
        script_dir = Path(__file__).resolve().parent.parent
        relative_candidates = [
            script_dir / "piper" / "piper.exe",
            script_dir / "piper" / "piper",
            script_dir.parent / "piper" / "piper",
            script_dir.parent / "piper" / "piper.exe",
        ]
        for c in relative_candidates:
            if c.exists():
                return str(c)

        # 3. PATH 中查找
        try:
            result = subprocess.run(
                ["which", "piper"] if __import__("sys").platform != "win32" else ["where", "piper"],
                capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                return result.stdout.strip().split("\n")[0]
        except Exception:
            pass

        # 3. 常见的 Docker 安装路径
        candidates = [
            "/usr/local/bin/piper",
            "/usr/bin/piper",
            "/piper/piper",
            str(Path.cwd() / "piper"),
        ]
        for c in candidates:
            if Path(c).exists():
                return c

        raise RuntimeError(
            "Piper binary not found. "
            "Install from: https://github.com/rhasspy/piper/releases "
            "or set PIPER_BIN environment variable."
        )

    def is_available(self) -> bool:
        """检查 Piper 是否可用"""
        if self._piper_bin is None:
            return False
        try:
            result = subprocess.run(
                [self._piper_bin, "--help"],
                capture_output=True, text=True, timeout=5
            )
            return result.returncode == 0
        except Exception:
            return False

    def get_availability_error(self) -> str:
        """返回 Piper 不可用的原因"""
        if self._init_error:
            return self._init_error
        if self._piper_bin:
            return f"Piper binary found at {self._piper_bin} but failed to execute"
        return "Piper binary not available"

    def get_voice_path(self, voice_id: str) -> Optional[Path]:
        """获取语音模型的本地路径"""
        # 支持直接传入完整路径
        model_path = Path(voice_id)
        if model_path.suffix == ".onnx" and model_path.exists():
            return model_path

        # 从 models_dir 查找
        onnx_path = self.models_dir / f"{voice_id}.onnx"
        if onnx_path.exists():
            return onnx_path

        # 从 models_dir 的子目录查找
        for f in self.models_dir.rglob(f"{voice_id}.onnx"):
            return f

        return None

    def get_config_path(self, voice_id: str) -> Optional[Path]:
        """获取语音模型的配置文件路径"""
        model_path = Path(voice_id)
        if model_path.suffix == ".onnx":
            config_path = model_path.with_suffix(model_path.suffix + ".json")
            if config_path.exists():
                return config_path

        # 从 models_dir 查找
        json_path = self.models_dir / f"{voice_id}.onnx.json"
        if json_path.exists():
            return json_path

        # 从 models_dir 的子目录查找
        for f in self.models_dir.rglob(f"{voice_id}.onnx.json"):
            return f

        # 如果没有 .onnx.json，尝试 .json
        json_path = self.models_dir / f"{voice_id}.json"
        if json_path.exists():
            return json_path

        return None

    def list_available_voices(self) -> list[dict]:
        """列出本地已下载的语音模型"""
        voices = []
        for onnx_file in self.models_dir.rglob("*.onnx"):
            voice_id = onnx_file.stem  # e.g. en_US-amy-medium
            config_path = onnx_file.with_name(onnx_file.name + ".json")
            voices.append({
                "id": voice_id,
                "path": str(onnx_file),
                "has_config": config_path.exists(),
            })
        return voices

    async def synthesize(
        self,
        text: str,
        voice_id: str = "en_US-amy-medium",
        speed: float = 1.0,
        output_format: str = "wav",
    ) -> bytes:
        """
        使用 Piper 合成语音

        Args:
            text:          待合成文本
            voice_id:      语音 ID（如 en_US-amy-medium）
            speed:         语速倍率 (0.5 ~ 2.0)
            output_format: 输出格式 (wav / mp3)

        Returns:
            音频二进制数据
        """
        voice_path = self.get_voice_path(voice_id)
        if not voice_path:
            raise FileNotFoundError(
                f"Voice model '{voice_id}' not found in {self.models_dir}. "
                f"Please download it first: {voice_id}"
            )

        config_path = self.get_config_path(voice_id)

        # 创建临时输出文件
        suffix = ".wav" if output_format == "wav" else ".mp3"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            output_path = tmp.name

        try:
            # 构建 Piper 命令
            cmd = [
                self._piper_bin,
                "--model", str(voice_path),
                "--output-file", output_path,
                "--length-scale", str(1.0 / speed),
            ]

            if config_path:
                cmd.extend(["--config", str(config_path)])

            if output_format == "mp3":
                cmd.append("--output-format")
                cmd.append("mp3")

            logger.info("Running Piper: %s", " ".join(cmd))

            # 执行 Piper（通过 stdin 传入文本）
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

            try:
                stdout, stderr = await asyncio.wait_for(
                    proc.communicate(input=text.encode("utf-8")), timeout=60
                )
            except asyncio.TimeoutError:
                proc.kill()
                raise RuntimeError(f"Piper timed out after 60s for voice '{voice_id}'")

            if proc.returncode != 0:
                raise RuntimeError(
                    f"Piper failed (exit={proc.returncode}): {stderr.decode()}"
                )

            # 读取生成的音频文件
            with open(output_path, "rb") as f:
                audio_data = f.read()

            if not audio_data:
                raise RuntimeError("Piper produced empty audio output")

            logger.info(
                "Piper TTS OK: voice=%s text_len=%d audio_len=%d",
                voice_id, len(text), len(audio_data),
            )

            return audio_data

        finally:
            # 清理临时文件
            Path(output_path).unlink(missing_ok=True)
