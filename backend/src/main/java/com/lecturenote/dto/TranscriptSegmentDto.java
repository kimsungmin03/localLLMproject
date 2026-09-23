package com.lecturenote.dto;

import com.lecturenote.domain.TranscriptSegment;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptSegmentDto {
    private Integer seq;
    private Long startMs;
    private Long endMs;
    private String text;

    public static TranscriptSegmentDto from(TranscriptSegment segment) {
        return TranscriptSegmentDto.builder()
                .seq(segment.getSeq())
                .startMs(segment.getStartMs())
                .endMs(segment.getEndMs())
                .text(segment.getText())
                .build();
    }
}
