# GPU 加速实现文档

## 1. 概述

本项目使用 **Faster-Whisper**（基于 CTranslate2 的 OpenAI Whisper 高性能实现）进行本地语音识别（ASR）。Faster-Whisper 相比原始 PyTorch 实现的 Whisper，推理速度提升 4-5 倍，内存减少一半。

本文档说明 GPU 加速功能的完整实现链路，从前端设置开关到后端 ASR 服务调用。

## 2. 架构总览

```
前端设置页 (GPU开关)
    ↓ 保存配置
前端 App.vue (启动实时对话)
    ↓ POST /api/asr/settings/update
Java 后端 AsrSettingsController
    ↓ gRPC UpdateSettings
Python ASR Service (server.py)
    ↓ WhisperEngine.create_instance(device="cuda")
Faster-Whisper (CTranslate2 + cuBLAS)
    ↓
GPU 推理完成
```

## 3. 文件修改清单

### 3.1 前端

| 文件 | 修改内容 |
|------|---------|
| `frontend/src/config.ts` | 新增 `asrGpuEnabled` 配置项，默认 `false`（CPU 模式） |
| `frontend/src/components/SettingsPage.vue` | 音频设置区域新增「启用 GPU 加速」开关，保存时同步后端 |
| `frontend/src/api.ts` | 新增 `updateAsrSettings()` API 方法 |
| `frontend/src/App.vue` | 启动实时对话时按需调用 `updateAsrSettings()`，用 `lastAsrDevice` 缓存避免重复请求 |

### 3.2 Java 后端

| 文件 | 修改内容 |
|------|---------|
| `backend/.../AsrSettingsController.java` | `updateSettings()` 不再硬编码默认 `large-v2` 模型和 `compute_type`，前端未传入时不改变当前配置 |
| `backend/.../FasterWhisperGrpcClient.java` | `modelName` 为 null 时传空字符串防 protobuf NPE |

### 3.3 Python ASR 服务

| 文件 | 修改内容 |
|------|---------|
| `asr-service/app/server.py` | 实现 `UpdateSettings()` gRPC 方法；cuBLAS 缺失时自动回退 CPU 推理 |
| `asr-service/app/engine.py` | `create_instance()` 加线程锁防竞态；模型+设备相同时跳过重加载 |

### 3.4 启动脚本

| 文件 | 修改内容 |
|------|---------|
| `start.bat` | ASR 启动命令支持 `%ASR_DEVICE%` 环境变量（默认 `cpu`） |

## 4. 关键技术点

### 4.1 前端的设备缓存机制

GPU 切换调用 `gRPC UpdateSettings` 会触发模型重加载（耗时 5-30 秒）。为避免每次启动实时对话都阻塞，前端用 `lastAsrDevice` 缓存：

```typescript
let lastAsrDevice: string | null = null

async function startRealtimeConversation(): Promise<void> {
  const targetDevice = cfg.asrGpuEnabled ? 'cuda' : 'cpu'
  if (targetDevice !== lastAsrDevice) {
    await api.updateAsrSettings({ device: targetDevice })
    lastAsrDevice = targetDevice
  }
  // ...
}
```

### 4.2 Python 线程安全

```python
_load_lock: threading.Lock = threading.Lock()

@classmethod
def create_instance(cls, ...):
    with cls._load_lock:
        # 模型+设备相同时跳过
        # 否则重新加载
```

### 4.3 cuBLAS 缺失自动降级

```python
except Exception as e:
    if "cublas" in str(e).lower() and device == "cuda":
        # 回退到 CPU
        create_instance(model_name, "cpu", compute_type, download_root)
```

## 5. 使用方式

### 5.1 前置条件

- **NVIDIA GPU**（显存建议 ≥ 4GB，medium 模型约 1.5GB）
- **CUDA 12.x Toolkit**（支持 cuBLAS 12.x）
- 安装后验证：`nvcc --version` 和 `nvidia-smi`

### 5.2 操作步骤

1. 启动所有服务（`start.bat`）
2. 打开前端设置页
3. 在「音频设置」区域勾选 **「启用 GPU 加速」**
4. 点击 **「保存配置」**
5. 开始实时对话

### 5.3 验证方法

查看 ASR 服务日志，确认：

```
UpdateSettings: model=medium device=cuda compute_type=int8 vad=True
WhisperModel 加载完成: medium on cuda
UpdateSettings 完成: model_loaded=True device=cuda
```

## 6. 性能对比

| 模型 | 设备 | 10s 音频识别延迟 |
|------|------|----------------|
| medium | CPU (int8) | ~3-5s |
| medium | GPU CUDA (int8) | ~0.3-0.5s |
| large-v2 | GPU CUDA (int8_float16) | ~0.5-1s |

## 7. 故障排除

| 现象 | 原因 | 解决 |
|------|------|------|
| `cublas64_12.dll is not found` | 未安装 CUDA 12.x | 安装 CUDA Toolkit 12.x |
| 切换到 GPU 后 ASR 服务卡死 | 需要重启 Python 进程 | 重启 ASR 服务窗口 |
| GPU 开关打开但日志显示 `device=cpu` | cuBLAS 缺失自动降级 | 安装 CUDA 12.x |
| 模型加载慢 | 首次下载或 CPU 加载 | 等待下载完成，后续为秒级加载 |
