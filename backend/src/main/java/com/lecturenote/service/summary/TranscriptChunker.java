package com.lecturenote.service.summary;

import com.lecturenote.domain.TranscriptSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TranscriptChunker {

    // 8 minutes in milliseconds (480,000ms)
    private static final long TARGET_CHUNK_DURATION_MS = 8 * 60 * 1000L;
    // Maximum chunk boundary: 10 minutes (600,000ms)
    private static final long MAX_CHUNK_DURATION_MS = 10 * 60 * 1000L;

    public record TranscriptChunk(
            int chunkIndex,
            long startMs,
            long endMs,
            String text
    ) {}

    public List<TranscriptChunk> chunkSegments(List<TranscriptSegment> segments) {
        List<TranscriptChunk> chunks = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return chunks;
        }

        int chunkIndex = 0;
        long chunkStartMs = segments.get(0).getStartMs();
        long chunkEndMs = segments.get(0).getEndMs();
        StringBuilder chunkText = new StringBuilder();

        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment seg = segments.get(i);
            long currentDuration = seg.getEndMs() - chunkStartMs;

            if (chunkText.length() > 0) {
                chunkText.append(" ");
            }
            chunkText.append(seg.getText());
            chunkEndMs = seg.getEndMs();

            boolean isLast = (i == segments.size() - 1);
            // Split chunk if exceeded target duration and there are remaining segments
            if (!isLast && currentDuration >= TARGET_CHUNK_DURATION_MS) {
                chunks.add(new TranscriptChunk(chunkIndex++, chunkStartMs, chunkEndMs, chunkText.toString()));
                // Reset for next chunk
                TranscriptSegment nextSeg = segments.get(i + 1);
                chunkStartMs = nextSeg.getStartMs();
                chunkEndMs = nextSeg.getEndMs();
                chunkText.setLength(0);
            }
        }

        // Add remaining segment text if any
        if (chunkText.length() > 0) {
            chunks.add(new TranscriptChunk(chunkIndex, chunkStartMs, chunkEndMs, chunkText.toString()));
        }

        return chunks;
    }
}
