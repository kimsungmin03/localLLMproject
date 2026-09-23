package com.lecturenote.dto;

import com.lecturenote.domain.LectureStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SseEventDto {
    private Long lectureId;
    private LectureStatus status;
    private Integer progress;
    private String errorMessage;
    private LocalDateTime timestamp;
}
