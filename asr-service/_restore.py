import os
os.chdir(os.path.dirname(os.path.abspath(__file__)))

with open("app/server.py", "w", encoding="utf-8") as f:
    f.write('''import logging, grpc, numpy as np
from concurrent import futures
from app._convert import _convert_to_pcm
from app.proto import asr_service_pb2, asr_service_pb2_grpc
from app.engine import WhisperEngine

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')
logger = logging.getLogger('AsrServer')

class S(asr_service_pb2_grpc.FasterWhisperASRServicer):
    def __init__(self):
        self.engine = None

    def RecognizeFile(self, request, context):
        if not self.engine or not self.engine.is_loaded:
            context.set_code(grpc.StatusCode.UNAVAILABLE)
            return asr_service_pb2.RecognizeResult()
        n = _convert_to_pcm(request.audio_data)
        t, sg, cf = self.engine.transcribe_file(n, request.language or None, request.enable_vad)
        return asr_service_pb2.RecognizeResult(
            text=t, language=request.language or 'auto',
            duration=sg[-1].end_time if sg else 0, confidence=cf)

    def GetStatus(self, request, context):
        ld = self.engine.is_loaded if self.engine else False
        return asr_service_pb2.StatusResponse(
            model_loaded=ld, current_model=self.engine.model_name if self.engine else 'none',
            device=self.engine.device if self.engine else 'cpu',
            message='ready' if ld else 'not loaded')

    def UpdateSettings(self, request, context):
        return asr_service_pb2.StatusResponse(model_loaded=True)

def serve(port=50051, model_name='medium', device='cpu',
          compute_type='int8_float16', download_root='./models'):
    WhisperEngine.create_instance(model_name, device, compute_type, download_root)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    svc = S()
    svc.engine = WhisperEngine.get_instance()
    asr_service_pb2_grpc.add_FasterWhisperASRServicer_to_server(svc, server)
    server.add_insecure_port('0.0.0.0:%d' % port)
    server.start()
    logger.info('ASR Server started: port=%d model=%s device=%s', port, model_name, device)
    server.wait_for_termination()

if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('--port', type=int, default=int(os.getenv('ASR_PORT', '50051')))
    p.add_argument('--model', default=os.getenv('ASR_MODEL', 'medium'))
    p.add_argument('--device', default=os.getenv('ASR_DEVICE', 'cpu'))
    p.add_argument('--compute-type', default=os.getenv('ASR_COMPUTE_TYPE', 'int8_float16'))
    p.add_argument('--download-root', default=os.getenv('ASR_DOWNLOAD_ROOT', './models'))
    a = p.parse_args()
    d = a.device
    if d == 'cuda':
        try:
            import torch
            d = 'cuda' if torch.cuda.is_available() else 'cpu'
        except:
            d = 'cpu'
    serve(a.port, a.model, d, a.compute_type, a.download_root)
''')
print('server.py restored OK')
