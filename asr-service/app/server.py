import logging, grpc, numpy as np
from concurrent import futures
from app.proto import asr_service_pb2, asr_service_pb2_grpc
from app.engine import WhisperEngine
from app._convert import _convert_to_pcm

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("AsrServer")


class S(asr_service_pb2_grpc.FasterWhisperASRServicer):
    def __init__(self):
        self.engine = None
        self.stream_processors = {}

    def RecognizeFile(self, request, context):
        logger.info("RecognizeFile: %d bytes, lang=%s", len(request.audio_data), request.language)
        if not self.engine or not self.engine.is_loaded:
            context.set_code(grpc.StatusCode.UNAVAILABLE)
            context.set_details("not loaded")
            return asr_service_pb2.RecognizeResult()
        a = _convert_to_pcm(request.audio_data)
        t, sg, cf = self.engine.transcribe_file(a, request.language or None, request.enable_vad)
        d = sg[-1].end_time if sg else 0
        logger.info("done: text='%s' dur=%.2f", t, d)
        return asr_service_pb2.RecognizeResult(
            text=t, language=request.language or "auto", duration=d, confidence=cf
        )

    def GetStatus(self, request, context):
        return asr_service_pb2.StatusResponse(
            model_loaded=self.engine.is_loaded if self.engine else False
        )


def serve(port=50051, model_name="medium", device="cpu",
          compute_type="int8_float16", download_root="./models"):
    WhisperEngine.create_instance(model_name, device, compute_type, download_root)
    s = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    svc = S()
    svc.engine = WhisperEngine.get_instance()
    asr_service_pb2_grpc.add_FasterWhisperASRServicer_to_server(svc, s)
    s.add_insecure_port("0.0.0.0:%d" % port)
    s.start()
    logger.info("Server started: port=%d model=%s", port, model_name)
    s.wait_for_termination()


if __name__ == "__main__":
    import argparse, os
    a = argparse.ArgumentParser()
    a.add_argument("--port", type=int, default=int(os.getenv("ASR_PORT", "50051")))
    a.add_argument("--model", default=os.getenv("ASR_MODEL", "medium"))
    a.add_argument("--device", default=os.getenv("ASR_DEVICE", "cpu"))
    a.add_argument("--compute-type", default=os.getenv("ASR_COMPUTE_TYPE", "int8_float16"))
    a.add_argument("--download-root", default=os.getenv("ASR_DOWNLOAD_ROOT", "./models"))
    k = a.parse_args()
    d = k.device
    if d == "cuda":
        try:
            import torch
            d = "cuda" if torch.cuda.is_available() else "cpu"
        except:
            d = "cpu"
    serve(k.port, k.model, d, k.compute_type, k.download_root)
