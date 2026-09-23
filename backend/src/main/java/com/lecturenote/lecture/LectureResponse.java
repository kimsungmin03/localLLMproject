package com.lecturenote.lecture;

import java.time.Instant;

public record LectureResponse(
        long id,
        String title,
        LectureStatus status,
        int progress,
        long durationMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static LectureResponse from(Lecture l) {
        return new LectureResponse(l.getId(), l.getTitle(), l.getStatus(), l.getProgress(), l.getDurationMs(),
                l.getErrorMessage(), l.getCreatedAt(), l.getUpdatedAt());
    }
}
