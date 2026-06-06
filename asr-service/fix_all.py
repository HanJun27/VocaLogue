#!/usr/bin/env python3
"""Fix server.py and _convert.py encoding issues"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.chdir(os.path.dirname(os.path.abspath(__file__)))

# 1. Write _convert.py
with open("app/_convert.py", "w", encoding="utf-8") as f:
    f.write('''import logging
import numpy as np
import subprocess
import tempfile
import os as _os

logger = logging.getLogger("AsrServer")

def _convert_to_pcm(audio_bytes: bytes) -> np.ndarray:
    """Convert any audio format to PCM float32 16kHz mono using ffmpeg"""
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
    except Exception:
        logger.warning("ffmpeg unavailable, trying as raw PCM")
        return np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32) / 32768.0
''')
print("_convert.py written OK")

# 2. Write server.py
with open("app/server.py", "w", encoding="utf-8") as f:
    f.write('''import logging, grpc, numpy as np
from concurrent import futures
from app.proto import asr_service_pb2
from app.proto import asr_service_pb2_grpc
from app.engine import WhisperEngine
from app._convert import _convert_to_pcm

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("AsrServer")

class FasterWhisperASRServicer(asr_service_pb2_grpc.FasterWhisperASRServicer):
    def __init__(self):
        self.engine = None
        self.stream_processors = {}

    def RecognizeFile(self, request, context):
        logger.info("RecognizeFile: %d bytes, lang=%s", len(request.audio_data), request.language)
        if not self.engine or not self.engine.is_loaded:
            context.set_code(grpc.StatusCode.UNAVAILABLE)
            context.set_details("Model not loaded")
            return asr_service_pb2.RecognizeResult()
        audio_np = _convert_to_pcm(request.audio_data)
        text, segments, confidence = self.engine.transcribe_file(audio_np, request.language or None, request.enable_vad)
        segs = []
        for s in segments:
            segs.append(asr_service_pb2.Segment(id=s.id, start_time=s.start_time, end_time=s.end_time, text=s.text, confidence=s.confidence))
        dur = segments[-1].end_time if segments else 0.0
        logger.info("RecognizeFile done: text='%s', dur=%.2fs", text, dur)
        return asr_service_pb2.RecognizeResult(text=text, language=request.language or "auto", duration=dur, confidence=confidence, segments=segs)

    def StreamRecognize(self, request_iterator, context):
        from app.stream_processor import ASRStreamProcessor
        proc = None
        try:
            for c in request_iterator:
                if not context.is_active(): break
                if proc is None:
                    proc = ASRStreamProcessor(engine=self.engine, sample_rate=16000)
                    self.stream_processors[c.session_id or "d"] = proc
                if c.is_end: break
                if c.audio_data:
                    for r in proc.process_chunk(c.audio_data):
                        yield _build_proto(r)
            if proc:
                for r in proc.finish():
                    yield _build_proto(r)
                proc.reset()
        except Exception as e:
            logger.error("StreamError: %s", e)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))

    def GetStatus(self, request, context):
        loaded = self.engine.is_loaded if self.engine else False
        return asr_service_pb2.StatusResponse(
            model_loaded=loaded,
            current_model=self.engine.model_name if self.engine else "none",
            gpu_available=(self.engine.device == "cuda") if self.engine else False,
            device=self.engine.device if self.engine else "cpu",
            message="OK" if loaded else "not loaded")

    def UpdateSettings(self, request, context):
        ct = {0:"int8",1:"float16",2:"int8_float16"}.get(request.compute_type,"int8_float16")
        try:
            self.engine._load_model(request.model_name or "large-v2", request.device or "cuda", ct, "./models")
            return asr_service_pb2.StatusResponse(model_loaded=True, current_model=request.model_name)
        except Exception as e:
            return asr_service_pb2.StatusResponse(model_loaded=False, message=str(e))

def _build_proto(r):
    return asr_service_pb2.AsrResult(text=r.text, is_final=r.is_final, confidence=r.confidence, start_time=r.start_time, end_time=r.end_time, language=r.language)

def serve(port=50051, model_name="medium", device="cpu", compute_type="int8_float16", download_root="./models"):
    WhisperEngine.create_instance(model_name, device, compute_type, download_root)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10), options=[("grpc.max_send_message_length",50*1024*1024),("grpc.max_receive_message_length",50*1024*1024)])
    svc = FasterWhisperASRServicer()
    svc.engine = WhisperEngine.get_instance()
    asr_service_pb2_grpc.add_FasterWhisperASRServicer_to_server(svc, server)
    server.add_insecure_port("0.0.0.0:%d" % port)
    server.start()
    logger.info("Server started: port=%d model=%s device=%s", port, model_name, device)
    server.wait_for_termination()

if __name__ == "__main__":
    import argparse, os
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=int(os.getenv("ASR_PORT","50051")))
    p.add_argument("--model", default=os.getenv("ASR_MODEL","medium"))
    p.add_argument("--device", default=os.getenv("ASR_DEVICE","cpu"))
    p.add_argument("--compute-type", default=os.getenv("ASR_COMPUTE_TYPE","int8_float16"))
    p.add_argument("--download-root", default=os.getenv("ASR_DOWNLOAD_ROOT","./models"))
    a = p.parse_args()
    d = a.device
    if d == "cuda":
        try: import torch; d = "cuda" if torch.cuda.is_available() else "cpu"
        except: d = "cpu"
    serve(a.port, a.model, d, a.compute_type, a.download_root)
''')
print("server.py written OK")

# Verify imports
from app._convert import _convert_to_pcm
print("_convert_to_pcm imported OK")
