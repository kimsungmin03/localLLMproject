package com.lecturenote.stt;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TranscriptWriter {

    private final JdbcTemplate jdbc;

    public TranscriptWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * TRANSCRIBING 상태일 때만 세그먼트를 통째로 교체한다(중복 COMPLETED 콜백에도 결과가 같다).
     * @return 상태가 맞지 않아 저장하지 않았으면 false
     */
    @Transactional
    public boolean replaceSegments(long lectureId, List<SttCallback.Segment> segments, Long durationMs) {
        int updated = jdbc.update("""
                update lecture set progress = 100, duration_ms = coalesce(?, duration_ms), updated_at = ?
                where id = ? and status = 'TRANSCRIBING'""",
                durationMs, Timestamp.from(Instant.now()), lectureId);
        if (updated == 0) {
            return false;
        }
        jdbc.update("delete from transcript_segment where lecture_id = ?", lectureId);
        jdbc.batchUpdate(
                "insert into transcript_segment (lecture_id, seq, start_ms, end_ms, text) values (?, ?, ?, ?, ?)",
                segments, 500, (ps, s) -> {
                    ps.setLong(1, lectureId);
                    ps.setInt(2, s.seq());
                    ps.setLong(3, s.startMs());
                    ps.setLong(4, s.endMs());
                    ps.setString(5, s.text());
                });
        return true;
    }
}
