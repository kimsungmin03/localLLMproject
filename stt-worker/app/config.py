import os
import sys
from pathlib import Path
from dotenv import load_dotenv

# Load root .env if present, otherwise stt-worker/.env
root_env = Path(__file__).resolve().parent.parent.parent / ".env"
local_env = Path(__file__).resolve().parent.parent / ".env"

if root_env.exists():
    load_dotenv(root_env)
elif local_env.exists():
    load_dotenv(local_env)

# Try adding NVIDIA CUDA/cuDNN DLL paths if available in Python environment
if sys.platform == "win32":
    site_packages = Path(sys.prefix) / "Lib" / "site-packages"
    nvidia_dirs = [
        site_packages / "nvidia" / "cublas" / "bin",
        site_packages / "nvidia" / "cudnn" / "bin",
        site_packages / "torch" / "lib",
    ]
    for d in nvidia_dirs:
        if d.exists():
            try:
                os.add_dll_directory(str(d))
            except Exception:
                pass

STT_HOST = os.getenv("STT_HOST", "0.0.0.0")
STT_PORT = int(os.getenv("STT_PORT", "8000"))
STT_MODEL_SIZE = os.getenv("STT_MODEL_SIZE", "large-v3-turbo")
STT_DEVICE = os.getenv("STT_DEVICE", "cuda")
STT_COMPUTE_TYPE = os.getenv("STT_COMPUTE_TYPE", "float16")
STT_SHARED_SECRET = os.getenv("STT_SHARED_SECRET", "lecturenote-secret-token-change-in-prod")
