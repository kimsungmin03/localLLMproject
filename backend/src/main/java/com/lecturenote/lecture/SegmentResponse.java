package com.lecturenote.lecture;

public record SegmentResponse(int seq, long startMs, long endMs, String text) {

    public static SegmentResponse from(TranscriptSegment s) {
        return new SegmentResponse(s.getSeq(), s.getStartMs(), s.getEndMs(), s.getText());
    }
}
