import asyncio
import logging
from pathlib import Path
from typing import List, Optional
import httpx
import av

from app.config import (
    STT_MODEL_SIZE,
    STT_DEVICE,
    STT_COMPUTE_TYPE,
    STT_SHARED_SECRET,
)
from app.schemas import TranscribeRequest, CallbackPayload, Segment

logger = logging.getLogger("stt-worker")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")


class WhisperEngine:
    _instance = None
    _model = None

    @classmethod
    def get_model(cls, model_size: str = None, device: str = None, compute_type: str = None):
        if cls._model is None:
            size = model_size or STT_MODEL_SIZE
            dev = device or STT_DEVICE
            ctype = compute_type or STT_COMPUTE_TYPE
            from faster_whisper import WhisperModel

            logger.info(f"Loading faster-whisper model: size={size}, device={dev}, compute_type={ctype}")
            try:
                cls._model = WhisperModel(size, device=dev, compute_type=ctype)
                logger.info("faster-whisper model loaded successfully.")
            except Exception as e:
                logger.warning(f"Failed to load on {dev} with {ctype}: {e}. Retrying with CPU fallback...")
                cls._model = WhisperModel(size, device="cpu", compute_type="int8")
                logger.info("faster-whisper fallback model (CPU int8) loaded successfully.")
        return cls._model


def get_audio_duration_ms(audio_path: str) -> int:
    """Extract audio duration in milliseconds using PyAV."""
    try:
        with av.open(audio_path) as container:
            if container.duration is not None:
                return int((container.duration / av.time_base) * 1000)
            # Fallback to audio stream duration
            for stream in container.streams.audio:
                if stream.duration is not None:
                    return int((stream.duration * stream.time_base) * 1000)
    except Exception as e:
        logger.warning(f"Failed to inspect audio duration via av: {e}")
    return 0


async def send_callback(callback_url: str, payload: CallbackPayload):
    """Send transcription callback to Spring Boot backend."""
    headers = {
        "X-STT-Token": STT_SHARED_SECRET,
        "Content-Type": "application/json",
    }
    logger.info(
        f"Sending callback to {callback_url} (lectureId={payload.lectureId}, status={payload.status}, progress={payload.progress}%)"
    )
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(callback_url, json=payload.model_dump(), headers=headers)
            logger.info(f"Callback response: {resp.status_code}")
    except Exception as e:
        logger.error(f"Failed to send callback to {callback_url}: {e}")


def _run_transcription_sync(audio_path: str, language: str):
    """Synchronous CPU/GPU bound transcription generator."""
    model = WhisperEngine.get_model()
    # vad_filter=True, language=language
    segments_iter, info = model.transcribe(
        audio_path,
        language=language,
        vad_filter=True,
        beam_size=5,
    )
    return segments_iter, info


async def process_transcription(request: TranscribeRequest):
    """Background task for transcribing audio and delivering callbacks."""
    lecture_id = request.lectureId
    audio_path = request.audioPath
    callback_url = request.callbackUrl
    language = request.language or "ko"

    file_path = Path(audio_path)
    if not file_path.exists():
        err_msg = f"Audio file not found at path: {audio_path}"
        logger.error(err_msg)
        await send_callback(
            callback_url,
            CallbackPayload(lectureId=lecture_id, status="FAILED", progress=0, errorMessage=err_msg),
        )
        return

    duration_ms = get_audio_duration_ms(str(file_path))
    logger.info(f"Processing lecture {lecture_id}, audio duration: {duration_ms} ms")

    # Initial progress report
    await send_callback(
        callback_url,
        CallbackPayload(lectureId=lecture_id, status="PROGRESS", progress=1, durationMs=duration_ms),
    )

    try:
        # Run transcription in a separate thread so asyncio event loop isn't blocked
        loop = asyncio.get_running_loop()

        def do_transcribe():
            model = WhisperEngine.get_model()
            segments_generator, info = model.transcribe(
                str(file_path),
                language=language,
                vad_filter=True,
            )
            # Evaluate generator to collect all segments while tracking progress
            total_duration_sec = info.duration if info.duration and info.duration > 0 else (duration_ms / 1000.0)
            collected = []
            last_reported_progress = 1
            seq = 0

            for seg in segments_generator:
                start_ms = int(seg.start * 1000)
                end_ms = int(seg.end * 1000)
                text = seg.text.strip()
                if text:
                    collected.append(Segment(seq=seq, startMs=start_ms, endMs=end_ms, text=text))
                    seq += 1

                # Progress estimation
                if total_duration_sec > 0:
                    current_progress = min(99, max(1, int((seg.end / total_duration_sec) * 100)))
                    if current_progress - last_reported_progress >= 5:
                        last_reported_progress = current_progress
                        # schedule callback to async loop
                        asyncio.run_coroutine_threadsafe(
                            send_callback(
                                callback_url,
                                CallbackPayload(
                                    lectureId=lecture_id,
                                    status="PROGRESS",
                                    progress=current_progress,
                                    durationMs=int(total_duration_sec * 1000),
                                ),
                            ),
                            loop,
                        )

            computed_duration_ms = int(total_duration_sec * 1000) if total_duration_sec > 0 else duration_ms
            return collected, computed_duration_ms

        segments, final_duration_ms = await loop.run_in_executor(None, do_transcribe)

        logger.info(f"Transcription completed for lecture {lecture_id}: {len(segments)} segments")
        await send_callback(
            callback_url,
            CallbackPayload(
                lectureId=lecture_id,
                status="COMPLETED",
                progress=100,
                durationMs=final_duration_ms,
                segments=segments,
            ),
        )

    except Exception as e:
        logger.exception(f"Error during transcription for lecture {lecture_id}: {e}")
        await send_callback(
            callback_url,
            CallbackPayload(
                lectureId=lecture_id,
                status="FAILED",
                progress=0,
                errorMessage=str(e),
            ),
        )
