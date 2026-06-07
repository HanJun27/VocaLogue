import logging, grpc, numpy as np, time
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

    # ==================== 非流式识别（已有） ====================

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

    # ==================== 流式识别（新增） ====================

    def StreamRecognize(self, request_iterator, context):
        """
        双向流式 RPC

        客户端流式发送 AudioChunk:
          - audio_data: WebM/Opus 音频块
          - is_end: 最后一块标记

        服务端流式返回 AsrResult:
          - 累积所有音频后，用 ffmpeg 一次性转为 PCM
          - 调用 transcribe_stream() 逐段 yield
          - 每段作为 interim，最后一段标记 is_final=true
        """
        logger.info("StreamRecognize: 开始接收流式音频")

        # 1. 累积所有音频块
        audio_buffer = bytearray()
        chunk_count = 0
        start_time = time.time()

        for chunk in request_iterator:
            audio_buffer.extend(chunk.audio_data)
            chunk_count += 1
            if chunk_count % 10 == 0:
                logger.debug("StreamRecognize: 已接收 %d 块, %d bytes",
                             chunk_count, len(audio_buffer))
            if chunk.is_end:
                logger.info("StreamRecognize: 收到结束标记, 共 %d 块, %d bytes",
                            chunk_count, len(audio_buffer))
                break

        if not audio_buffer or len(audio_buffer) < 1000:
            logger.warning("StreamRecognize: 音频数据太少 (%d bytes), 跳过", len(audio_buffer))
            yield asr_service_pb2.AsrResult(text="", is_final=True, confidence=0.0)
            return

        # 2. 转换为 PCM
        try:
            pcm = _convert_to_pcm(bytes(audio_buffer))
        except Exception as e:
            logger.error("StreamRecognize: PCM 转换失败: %s", e)
            yield asr_service_pb2.AsrResult(text="", is_final=True, confidence=0.0,
                                            language="en", start_time=0, end_time=0)
            return

        elapsed = time.time() - start_time
        logger.info("StreamRecognize: PCM 转换完成, %d samples, 耗时 %.2fs", len(pcm), elapsed)

        # 3. 调用 transcribe_stream 逐段返回
        try:
            accumulated_text = ""
            seg_count = 0
            last_is_final = False
            for result in self.engine.transcribe_stream(pcm, language="en", enable_vad=True):
                # 只发送增量文本
                new_text = result.text[len(accumulated_text):].strip()
                accumulated_text = result.text

                # final 段即使文本相同也一定要发出（否则前端收不到完成信号）
                if new_text or result.is_final:
                    seg_count += 1
                    # final 段用当前完整文本
                    text_to_send = accumulated_text if result.is_final else new_text
                    yield asr_service_pb2.AsrResult(
                        text=text_to_send,
                        is_final=result.is_final,
                        confidence=result.confidence,
                        start_time=result.start_time,
                        end_time=result.end_time,
                        language=result.language,
                    )
                    last_is_final = result.is_final
                    if seg_count % 5 == 0:
                        logger.info("StreamRecognize: 已输出 %d 段, 累计文本='%s'",
                                    seg_count, accumulated_text[:60])

            # 确保最终标记发出
            if not last_is_final and accumulated_text:
                logger.info("StreamRecognize: 补发最终标记")
                yield asr_service_pb2.AsrResult(text=accumulated_text, is_final=True,
                                                confidence=0.95, language="en",
                                                start_time=0, end_time=0)
            elif not accumulated_text:
                logger.warning("StreamRecognize: 识别结果为空")
                yield asr_service_pb2.AsrResult(text="", is_final=True, confidence=0.0,
                                                language="en", start_time=0, end_time=0)

            total_time = time.time() - start_time
            logger.info("StreamRecognize: 完成, %d 段, 文本='%s', 总耗时 %.2fs",
                        seg_count, accumulated_text[:60], total_time)

        except Exception as e:
            logger.error("StreamRecognize: 转录失败: %s", e)
            yield asr_service_pb2.AsrResult(text="", is_final=True, confidence=0.0)


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
