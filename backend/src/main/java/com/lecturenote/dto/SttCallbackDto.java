package com.lecturenote.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SttCallbackDto {
    private Long lectureId;
    private String status; // PROGRESS, COMPLETED, FAILED
    private Integer progress;
    private String errorMessage;
    private Long durationMs;
    private List<TranscriptSegmentDto> segments;
}
