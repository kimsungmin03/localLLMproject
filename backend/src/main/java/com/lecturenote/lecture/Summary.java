package com.lecturenote.lecture;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class Summary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long lectureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SummaryKind kind;

    private Integer chunkIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode content;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Summary() {}

    public Summary(long lectureId, SummaryKind kind, Integer chunkIndex, JsonNode content, String model) {
        this.lectureId = lectureId;
        this.kind = kind;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getLectureId() { return lectureId; }
    public SummaryKind getKind() { return kind; }
    public Integer getChunkIndex() { return chunkIndex; }
    public JsonNode getContent() { return content; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
}
