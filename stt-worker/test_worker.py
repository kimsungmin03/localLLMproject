import asyncio
import math
import os
import struct
import subprocess
import sys
import time
import wave
from pathlib import Path
import httpx
import uvicorn
from fastapi import FastAPI, HTTPException, Request

received_callbacks = []
CALLBACK_TOKEN = "lecturenote-secret-token-change-in-prod"

mock_app = FastAPI()

@mock_app.post("/internal/stt/callback")
async def mock_callback(request: Request):
    token = request.headers.get("X-STT-Token")
    if token != CALLBACK_TOKEN:
        raise HTTPException(status_code=401, detail="Unauthorized")
    body = await request.json()
    received_callbacks.append(body)
    print(f"  [Mock Callback Received] Status: {body.get('status')}, Progress: {body.get('progress')}%, Duration: {body.get('durationMs')}ms, Segments: {len(body.get('segments') or [])}")
    return {"status": "OK"}


def generate_sample_wav(filename: str, duration_sec: float = 4.0, sample_rate: int = 16000):
    """Generate a valid 16kHz mono WAV file for testing speech pipeline."""
    path = Path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    n_samples = int(duration_sec * sample_rate)
    
    with wave.open(str(path), "w") as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit
        wav_file.setframerate(sample_rate)
        
        frames = []
        for i in range(n_samples):
            t = float(i) / sample_rate
            # Harmonic signal with modulation
            amp = 0.6 * (1 + math.sin(2 * math.pi * 1.5 * t))
            val = int(amp * 16000 * (0.6 * math.sin(2 * math.pi * 350 * t) + 0.4 * math.sin(2 * math.pi * 700 * t)))
            frames.append(struct.pack("<h", max(-32767, min(32767, val))))
        
        wav_file.writeframes(b"".join(frames))
    print(f"[Sample Audio] Created {path} ({duration_sec}s, {sample_rate}Hz)")
    return path


async def main():
    print("=== STT Worker Standalone End-to-End Test ===")
    sample_wav = generate_sample_wav("sample_test_speech.wav", duration_sec=4.0)

    # 1. Start Mock Callback server on port 8999
    config = uvicorn.Config(mock_app, host="127.0.0.1", port=8999, log_level="warning")
    mock_server = uvicorn.Server(config)
    mock_task = asyncio.create_task(mock_server.serve())
    await asyncio.sleep(1)

    # 2. Launch STT Worker subprocess with STT_MODEL_SIZE=tiny for fast test verification
    worker_env = os.environ.copy()
    worker_env["STT_PORT"] = "8000"
    worker_env["STT_MODEL_SIZE"] = "tiny"
    worker_env["STT_DEVICE"] = "cuda"
    worker_env["STT_COMPUTE_TYPE"] = "float16"
    worker_env["STT_SHARED_SECRET"] = CALLBACK_TOKEN

    python_exe = sys.executable
    print(f"[Worker] Launching STT Worker on port 8000 with model 'tiny' (CUDA float16)...")
    worker_process = subprocess.Popen(
        [python_exe, "-m", "uvicorn", "app.main:app", "--port", "8000"],
        env=worker_env,
        cwd=str(Path(__file__).parent),
    )

    try:
        # Wait for worker /health endpoint
        ready = False
        for _ in range(15):
            await asyncio.sleep(1)
            try:
                async with httpx.AsyncClient(timeout=2.0) as client:
                    resp = await client.get("http://127.0.0.1:8000/health")
                    if resp.status_code == 200:
                        print(f"[Worker Health] UP! Response: {resp.json()}")
                        ready = True
                        break
            except Exception:
                pass

        if not ready:
            raise RuntimeError("STT Worker failed to become ready on http://127.0.0.1:8000/health")

        # 3. Call /transcribe
        transcribe_payload = {
            "lectureId": 42,
            "audioPath": str(sample_wav.resolve()),
            "callbackUrl": "http://127.0.0.1:8999/internal/stt/callback",
            "language": "ko",
        }
        print(f"[Request] POST /transcribe -> lectureId={transcribe_payload['lectureId']}")
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post("http://127.0.0.1:8000/transcribe", json=transcribe_payload)
            print(f"[Response] Status: {resp.status_code}, Body: {resp.json()}")
            assert resp.status_code == 202, f"Expected 202, got {resp.status_code}"

        # 4. Wait for callbacks
        print("[Callbacks] Waiting for callbacks from STT Worker...")
        start_wait = time.time()
        completed = False
        while time.time() - start_wait < 45:
            for cb in received_callbacks:
                if cb.get("status") in ("COMPLETED", "FAILED"):
                    completed = True
                    break
            if completed:
                break
            await asyncio.sleep(1)

        print(f"[Result] Total callbacks received: {len(received_callbacks)}")
        assert len(received_callbacks) > 0, "No callbacks received!"
        last_cb = received_callbacks[-1]
        print(f"[Result] Final Callback: status={last_cb.get('status')}, progress={last_cb.get('progress')}%")
        assert last_cb.get("status") == "COMPLETED", f"Expected COMPLETED, got {last_cb}"
        print("=== Test PASSED successfully! ===")

    finally:
        # Terminate worker process
        print("[Cleanup] Terminating STT Worker process...")
        worker_process.terminate()
        try:
            worker_process.wait(timeout=5)
        except Exception:
            worker_process.kill()

        # Stop mock server
        mock_server.should_exit = True
        await asyncio.sleep(0.5)

        # Remove temp audio
        if sample_wav.exists():
            sample_wav.unlink()
            print("[Cleanup] Removed sample audio file.")


if __name__ == "__main__":
    asyncio.run(main())
