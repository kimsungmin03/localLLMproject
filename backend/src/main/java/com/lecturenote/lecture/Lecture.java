package com.lecturenote.lecture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Lecture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** storage 루트 기준 파일명 (경로 아님). */
    @Column(name = "audio_path", nullable = false)
    private String audioFile;

    private long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LectureStatus status;

    private int progress;

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Lecture() {}

    public Lecture(String title, String audioFile) {
        this.title = title;
        this.audioFile = audioFile;
        this.status = LectureStatus.UPLOADED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAudioFile() { return audioFile; }
    public long getDurationMs() { return durationMs; }
    public LectureStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
