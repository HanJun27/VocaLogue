"""
Proto 代码生成脚本
生成 Python gRPC 代码到 app/proto/ 目录
"""
import subprocess
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent  # VocaLogue/
PROTO_DIR = ROOT_DIR / "proto"
OUTPUT_DIR = Path(__file__).resolve().parent / "app" / "proto"


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    proto_file = PROTO_DIR / "asr_service.proto"

    if not proto_file.exists():
        print(f"错误: proto 文件不存在: {proto_file}")
        sys.exit(1)

    cmd = [
        sys.executable, "-m", "grpc_tools.protoc",
        f"-I{PROTO_DIR}",
        f"--python_out={OUTPUT_DIR}",
        f"--grpc_python_out={OUTPUT_DIR}",
        str(proto_file),
    ]
    print(f"Running: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
    print(f"Generated gRPC code in {OUTPUT_DIR}")

    # 修复导入：生成的 _pb2_grpc.py 使用扁平导入，在包内需要改为相对导入
    _fix_grpc_import(OUTPUT_DIR / "asr_service_pb2_grpc.py")


def _fix_grpc_import(file_path: Path):
    """将 asr_service_pb2_grpc.py 中的扁平导入改为相对导入"""
    if not file_path.exists():
        return
    content = file_path.read_text(encoding="utf-8")
    old = "import asr_service_pb2 as asr__service__pb2"
    new = "from . import asr_service_pb2 as asr__service__pb2"
    if old in content:
        content = content.replace(old, new)
        file_path.write_text(content, encoding="utf-8")
        print(f"Fixed import in {file_path.name}")
    else:
        print(f"No fix needed for {file_path.name}")


if __name__ == "__main__":
    main()
