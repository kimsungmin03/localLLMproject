from typing import List, Optional
from pydantic import BaseModel, Field


class TranscribeRequest(BaseModel):
    lectureId: int = Field(..., description="ID of the lecture in backend DB")
    audioPath: str = Field(..., description="Path to audio file on shared storage")
    callbackUrl: str = Field(..., description="Endpoint URL to post transcription updates and results")
    language: Optional[str] = Field("ko", description="Spoken language in the audio (default ko)")


class Segment(BaseModel):
    seq: int
    startMs: int
    endMs: int
    text: str


class CallbackPayload(BaseModel):
    lectureId: int
    status: str = Field(..., description="PROGRESS, COMPLETED, or FAILED")
    progress: int = Field(0, ge=0, le=100)
    errorMessage: Optional[str] = None
    durationMs: Optional[int] = None
    segments: Optional[List[Segment]] = None


class AcceptedResponse(BaseModel):
    status: str = "ACCEPTED"
    lectureId: int
    message: str = "Transcription task queued"


class HealthResponse(BaseModel):
    status: str = "UP"
    device: str
    modelSize: str
    computeType: str
