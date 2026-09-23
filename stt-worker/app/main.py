import uvicorn
from fastapi import FastAPI, BackgroundTasks, status
from fastapi.middleware.cors import CORSMiddleware

from app.config import (
    STT_HOST,
    STT_PORT,
    STT_MODEL_SIZE,
    STT_DEVICE,
    STT_COMPUTE_TYPE,
)
from app.schemas import TranscribeRequest, AcceptedResponse, HealthResponse
from app.transcription import process_transcription

app = FastAPI(
    title="LectureNote AI - STT Worker",
    description="Faster-Whisper on-device transcription worker with HTTP callbacks",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse(
        status="UP",
        device=STT_DEVICE,
        modelSize=STT_MODEL_SIZE,
        computeType=STT_COMPUTE_TYPE,
    )


@app.post("/transcribe", status_code=status.HTTP_202_ACCEPTED, response_model=AcceptedResponse)
async def transcribe(request: TranscribeRequest, background_tasks: BackgroundTasks):
    background_tasks.add_task(process_transcription, request)
    return AcceptedResponse(
        status="ACCEPTED",
        lectureId=request.lectureId,
        message=f"Transcription job queued for lecture {request.lectureId}",
    )


if __name__ == "__main__":
    uvicorn.run("app.main:app", host=STT_HOST, port=STT_PORT, reload=False)
