CREATE TABLE lecture (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    audio_path    VARCHAR(500) NOT NULL, -- storage 루트 기준 파일명
    duration_ms   BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(30)  NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_lecture_status ON lecture(status);

CREATE TABLE transcript_segment (
    id         BIGSERIAL PRIMARY KEY,
    lecture_id BIGINT NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
    seq        INT    NOT NULL,
    start_ms   BIGINT NOT NULL,
    end_ms     BIGINT NOT NULL,
    text       TEXT   NOT NULL
);
CREATE INDEX idx_transcript_lecture_seq ON transcript_segment(lecture_id, seq);

CREATE TABLE summary (
    id          BIGSERIAL PRIMARY KEY,
    lecture_id  BIGINT       NOT NULL REFERENCES lecture(id) ON DELETE CASCADE,
    kind        VARCHAR(20)  NOT NULL, -- CHUNK, FINAL
    chunk_index INT,
    content     JSONB        NOT NULL,
    model       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_summary_lecture_kind ON summary(lecture_id, kind);
