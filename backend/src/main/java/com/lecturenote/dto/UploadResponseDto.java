package com.lecturenote.dto;

import com.lecturenote.domain.LectureStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UploadResponseDto {
    private Long id;
    private String title;
    private LectureStatus status;
    private String message;
}
