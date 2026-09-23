package com.lecturenote.lecture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long lectureId;

    private int seq;
    private long startMs;
    private long endMs;

    @Column(nullable = false)
    private String text;

    protected TranscriptSegment() {}

    public Long getLectureId() { return lectureId; }
    public int getSeq() { return seq; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getText() { return text; }
}
