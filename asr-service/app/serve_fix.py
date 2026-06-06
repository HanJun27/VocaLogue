"""
========================================================================
Faster-Whisper ASR gRPC Server
========================================================================

鏋舵瀯:
  - 鍚姩鏃朵竴娆℃€у姞杞?WhisperModel 鍒板唴瀛橈紙鍗曚緥妯″紡锛?  - 鏀寔鍙屽悜娴佸紡 RPC (StreamRecognize): 娴佸紡闊抽 -> 娴佸紡缁撴灉
  - 鏀寔闈炴祦寮?RPC (RecognizeFile): 瀹屾暣闊抽 -> 瀹屾暣缁撴灉
  - 鏀寔 RPC: GetStatus / UpdateSettings

榛樿妯″瀷: large-v2
榛樿璁惧: cuda (鏈?GPU) / cpu (鏃?GPU)
========================================================================
"""
import logging
import grpc
import numpy as np
from concurrent import futures
from typing import Dict

from app.proto import asr_service_pb2
from app.proto import asr_service_pb2_grpc
from app.engine import WhisperEngine, RingBuffer
from app.stream_processor import ASRStreamProcessor
from app._convert import _convert_to_pcm

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("AsrServer")

# ============================================================
# gRPC 鏈嶅姟瀹炵幇
# ============================================================
class FasterWhisperASRServicer(asr_service_pb2_grpc.FasterWhisperASRServicer):
    def __init__(self):
        self.engine: WhisperEngine = None
        self.stream_processors: Dict[str, ASRStreamProcessor] = {}

    # ---------- 闈炴祦寮忚瘑鍒?----------
    def RecognizeFile(self, request, context):
        logger.info(f"RecognizeFile: {len(request.audio_data)} bytes, language={request.language}")
        if not self.engine or not self.engine.is_loaded:
            context.set_code(grpc.StatusCode.UNAVAILABLE)
            context.set_details("Model not loaded yet")
            return asr_service_pb2.RecognizeResult()

        audio_np = _convert_to_pcm(request.audio_data)
        language = request.language if request.language else None
        enable_vad = request.enable_vad

        text, segments, confidence = self.engine.transcribe_file(
            audio_np, language, enable_vad=enable_vad,
        )

        proto_segments = []
        for seg in segments:
            words = []
            for w in seg.words:
                words.append(asr_service_pb2.WordTimestamp(
                    word=w.word, start_time=w.start_time,
                    end_time=w.end_time, probability=w.probability,
                ))
            proto_segments.append(asr_service_pb2.Segment(
                id=seg.id, start_time=seg.start_time, end_time=seg.end_time,
                text=seg.text, confidence=seg.confidence, words=words,
            ))

        total_duration = segments[-1].end_time if segments else 0.0
        return asr_service_pb2.RecognizeResult(
            text=text,
            language=language or "auto",
            duration=total_duration,
            confidence=confidence,
            segments=proto_segments,
        )

    # ---------- 娴佸紡璇嗗埆 ----------
    def StreamRecognize(self, request_iterator, context):
        """
        鍙屽悜娴佸紡 RPC:
        - 瀹㈡埛绔祦寮忓彂閫?AudioChunk
        - 鏈嶅姟绔祦寮忚繑鍥?AsrResult
        """
        processor = None
        sample_rate = 16000

        try:
            for chunk in request_iterator:
                # 澶勭悊杩炴帴鍙栨秷
                if context.is_active() and not context.is_active():
                    break

                # 鍒濆鍖栧鐞嗗櫒锛堜粠绗竴涓?chunk 鑾峰彇 session_id 鍜屽弬鏁帮級
                if processor is None:
                    session_id = chunk.session_id or "default"
                    window_size_ms = 500
                    vad_threshold_ms = 500
                    language = None

                    processor = ASRStreamProcessor(
                        engine=self.engine,
                        sample_rate=sample_rate,
                        window_size_ms=window_size_ms,
                        vad_threshold_ms=vad_threshold_ms,
                        language=language,
                    )
                    self.stream_processors[session_id] = processor
                    logger.info(f"娴佸紡璇嗗埆寮€濮? session={session_id}")

                # 妫€鏌ユ槸鍚︿负缁撴潫淇″彿
                if chunk.is_end:
                    break

                # 澶勭悊闊抽鏁版嵁
                if chunk.audio_data:
                    results = processor.process_chunk(chunk.audio_data)
                    for r in results:
                        yield _build_asr_result_proto(r)

            # 鍙戦€佹渶缁堢粨鏋?            if processor:
                final_results = processor.finish()
                for r in final_results:
                    yield _build_asr_result_proto(r)
                processor.reset()
                logger.info(f"娴佸紡璇嗗埆缁撴潫, session={chunk.session_id if chunk else 'unknown'}")

        except Exception as e:
            logger.error(f"娴佸紡璇嗗埆鍑洪敊: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
        finally:
            # cleanup
            pass

    # ---------- 鏈嶅姟鐘舵€?----------
    def GetStatus(self, request, context):
        model_loaded = self.engine.is_loaded if self.engine else False
        current_model = self.engine.model_name if self.engine else "none"
        device = self.engine.device if self.engine else "cpu"

        return asr_service_pb2.StatusResponse(
            model_loaded=model_loaded,
            current_model=current_model,
            gpu_available=(device == "cuda"),
            device=device,
            message="Model ready" if model_loaded else "Model not loaded",
        )

    # ---------- 鏇存柊璁剧疆 ----------
    def UpdateSettings(self, request, context):
        model_name = request.model_name or "large-v2"
        device = request.device or "cuda"

        ct_map = {0: "int8", 1: "float16", 2: "int8_float16"}
        compute_type = ct_map.get(request.compute_type, "int8_float16")

        logger.info(f"UpdateSettings: model={model_name}, device={device}, compute_type={compute_type}")

        try:
            self.engine._load_model(model_name, device, compute_type, "./models")
            return asr_service_pb2.StatusResponse(
                model_loaded=True,
                current_model=model_name,
                gpu_available=(device == "cuda"),
                device=device,
                message=f"Model {model_name} loaded on {device}",
            )
        except Exception as e:
            logger.error(f"鍔犺浇妯″瀷澶辫触: {e}")
            return asr_service_pb2.StatusResponse(
                model_loaded=False,
                current_model=model_name,
                device=device,
                message=f"Failed to load model: {e}",
            )


# ============================================================
# gRPC 鏈嶅姟鍣?# ============================================================
def serve(port: int = 50051, model_name: str = "large-v2",
          device: str = "cuda", compute_type: str = "int8_float16",
          download_root: str = "./models"):
    """鍚姩 gRPC 鏈嶅姟鍣?""

    # 鍏堝姞杞芥ā鍨?    engine = WhisperEngine.create_instance(
        model_name=model_name,
        device=device,
        compute_type=compute_type,
        download_root=download_root,
    )

    # 鍒涘缓 gRPC 鏈嶅姟鍣?    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=10),
        options=[
            ("grpc.max_send_message_length", 50 * 1024 * 1024),
            ("grpc.max_receive_message_length", 50 * 1024 * 1024),
        ],
    )

    servicer = FasterWhisperASRServicer()
    servicer.engine = engine
    asr_service_pb2_grpc.add_FasterWhisperASRServicer_to_server(servicer, server)

    server.add_insecure_port(f"0.0.0.0:{port}")
    server.start()

    logger.info(f"=== Faster-Whisper ASR gRPC Server ===")
    logger.info(f"  Port: {port}")
    logger.info(f"  Model: {model_name}")
    logger.info(f"  Device: {device}")
    logger.info(f"  Compute Type: {compute_type}")
    logger.info(f"  Model loaded: {engine.is_loaded}")
    logger.info(f"======================================")

    server.wait_for_termination()


def _build_asr_result_proto(r):
    """鏋勫缓 AsrResult protobuf"""
    words = [
        asr_service_pb2.WordTimestamp(
            word=w.word, start_time=w.start_time,
            end_time=w.end_time, probability=w.probability,
        )
        for w in (r.word_timestamps or [])
    ]
    return asr_service_pb2.AsrResult(
        text=r.text,
        is_final=r.is_final,
        confidence=r.confidence,
        start_time=r.start_time,
        end_time=r.end_time,
        language=r.language,
        word_timestamps=words,
    )


# ============================================================
# CLI Entry
# ============================================================
if __name__ == "__main__":
    import argparse
    import os

    parser = argparse.ArgumentParser(description="Faster-Whisper ASR gRPC Server")
    parser.add_argument("--port", type=int, default=int(os.getenv("ASR_PORT", "50051")),
                        help="gRPC 鏈嶅姟绔彛 (榛樿: 50051)")
    parser.add_argument("--model", type=str, default=os.getenv("ASR_MODEL", "large-v2"),
                        help="Whisper 妯″瀷 (榛樿: large-v2)")
    parser.add_argument("--device", type=str, default=os.getenv("ASR_DEVICE", "cuda"),
                        help="鎺ㄧ悊璁惧: cuda / cpu (榛樿: cuda)")
    parser.add_argument("--compute-type", type=str,
                        default=os.getenv("ASR_COMPUTE_TYPE", "int8_float16"),
                        help="璁＄畻绫诲瀷: int8 / float16 / int8_float16 (榛樿: int8_float16)")
    parser.add_argument("--download-root", type=str,
                        default=os.getenv("ASR_DOWNLOAD_ROOT", "./models"),
                        help="妯″瀷涓嬭浇/瀛樺偍鐩綍 (榛樿: ./models)")

    args = parser.parse_args()

    # 鑷姩妫€娴?GPU
    device = args.device
    if device == "cuda":
        try:
            import torch
            if not torch.cuda.is_available():
                logger.warning("CUDA 涓嶅彲鐢紝鍥為€€鍒?CPU")
                device = "cpu"
        except ImportError:
            logger.warning("torch 涓嶅彲鐢紝鍥為€€鍒?CPU")
            device = "cpu"

    serve(
        port=args.port,
        model_name=args.model,
        device=device,
        compute_type=args.compute_type,
        download_root=args.download_root,
    )
