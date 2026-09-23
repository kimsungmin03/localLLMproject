package com.lecturenote.service.summary;

import com.lecturenote.domain.TranscriptSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptChunkerTest {

    private final TranscriptChunker chunker = new TranscriptChunker();

    @Test
    @DisplayName("Short lecture (under 8 minutes) is kept as a single chunk")
    void testShortLectureSingleChunk() {
        List<TranscriptSegment> segments = List.of(
                TranscriptSegment.builder().seq(0).startMs(0L).endMs(60000L).text("First minute").build(),
                TranscriptSegment.builder().seq(1).startMs(60000L).endMs(120000L).text("Second minute").build(),
                TranscriptSegment.builder().seq(2).startMs(120000L).endMs(300000L).text("Fifth minute").build()
        );

        List<TranscriptChunker.TranscriptChunk> chunks = chunker.chunkSegments(segments);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).startMs()).isEqualTo(0L);
        assertThat(chunks.get(0).endMs()).isEqualTo(300000L);
        assertThat(chunks.get(0).text()).contains("First minute", "Fifth minute");
    }

    @Test
    @DisplayName("Long lecture (18 minutes) is divided into ~8-10 minute chunks")
    void testLongLectureChunks() {
        List<TranscriptSegment> segments = new ArrayList<>();
        // 18 segments, 1 minute each (60,000ms each) = 18 minutes total
        for (int i = 0; i < 18; i++) {
            segments.add(TranscriptSegment.builder()
                    .seq(i)
                    .startMs((long) i * 60000L)
                    .endMs((long) (i + 1) * 60000L)
                    .text("Minute " + (i + 1) + " speech content.")
                    .build());
        }

        List<TranscriptChunker.TranscriptChunk> chunks = chunker.chunkSegments(segments);

        // 8 minutes per chunk -> 8min + 8min + 2min = 3 chunks
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
        assertThat(chunks.get(2).chunkIndex()).isEqualTo(2);

        // Check time continuity
        assertThat(chunks.get(0).startMs()).isEqualTo(0L);
        assertThat(chunks.get(0).endMs()).isEqualTo(8 * 60000L);
        assertThat(chunks.get(1).startMs()).isEqualTo(8 * 60000L);
        assertThat(chunks.get(2).endMs()).isEqualTo(18 * 60000L);
    }

    @Test
    @DisplayName("Empty segments list returns empty chunks")
    void testEmptySegments() {
        List<TranscriptChunker.TranscriptChunk> chunks = chunker.chunkSegments(List.of());
        assertThat(chunks).isEmpty();
    }
}
