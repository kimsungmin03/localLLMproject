package com.lecturenote.dto;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class LectureResponseDto {
    private Long id;
    private String title;
    private Long durationMs;
    private LectureStatus status;
    private Integer progress;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static LectureResponseDto from(Lecture lecture) {
        return LectureResponseDto.builder()
                .id(lecture.getId())
                .title(lecture.getTitle())
                .durationMs(lecture.getDurationMs())
                .status(lecture.getStatus())
                .progress(lecture.getProgress())
                .errorMessage(lecture.getErrorMessage())
                .createdAt(lecture.getCreatedAt())
                .updatedAt(lecture.getUpdatedAt())
                .build();
    }
}
