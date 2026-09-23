package com.lecturenote.stt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 워커 → 백엔드 콜백 페이로드 (stt-worker/app/schemas.py CallbackPayload). */
public record SttCallback(
        @NotNull Long lectureId,
        @NotNull Status status,
        @Min(0) @Max(100) Integer progress,
        String errorMessage,
        @Min(0) Long durationMs,
        List<@Valid @NotNull Segment> segments) {

    public enum Status { PROGRESS, COMPLETED, FAILED }

    public record Segment(
            @Min(0) int seq,
            @Min(0) long startMs,
            @Min(0) long endMs,
            @NotNull String text) {}
}
