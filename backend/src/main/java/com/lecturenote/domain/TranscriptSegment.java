package com.lecturenote.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "transcript_segment",
    indexes = {
        @Index(name = "idx_transcript_lecture_seq", columnList = "lecture_id, seq")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Column(nullable = false)
    private Integer seq;

    @Column(name = "start_ms", nullable = false)
    private Long startMs;

    @Column(name = "end_ms", nullable = false)
    private Long endMs;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;
}
