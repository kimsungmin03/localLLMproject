package com.lecturenote.stt;

import com.lecturenote.lecture.LectureRepository;
import com.lecturenote.lecture.LectureStatus;
import com.lecturenote.sse.LectureEventPublisher;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SttCallbackService {

    private static final Logger log = LoggerFactory.getLogger(SttCallbackService.class);

    private final SttJobTracker tracker;
    private final LectureRepository lectures;
    private final TranscriptWriter transcriptWriter;
    private final LectureEventPublisher events;

    public SttCallbackService(SttJobTracker tracker, LectureRepository lectures,
                              TranscriptWriter transcriptWriter, LectureEventPublisher events) {
        this.tracker = tracker;
        this.lectures = lectures;
        this.transcriptWriter = transcriptWriter;
        this.events = events;
    }

    /** @return 현재 대기 중인 작업의 콜백이면 true, 늦게 도착했거나 모르는 강의면 false */
    public boolean handle(SttCallback cb) {
        long id = cb.lectureId();
        var job = tracker.find(id).orElse(null);
        if (job == null || job.result().isDone()) {
            log.info("Ignoring stale STT callback: lecture={} status={}", id, cb.status());
            return false;
        }
        job.touch();
        switch (cb.status()) {
            case PROGRESS -> {
                // 100은 COMPLETED 전용. 진행 중에는 99까지만 반영한다.
                int progress = Math.min(99, cb.progress() == null ? 0 : cb.progress());
                if (lectures.updateProgress(id, LectureStatus.TRANSCRIBING, progress, cb.durationMs(), Instant.now()) > 0) {
                    events.publish(id);
                }
            }
            case COMPLETED -> {
                try {
                    List<SttCallback.Segment> segments = cb.segments() == null ? List.of() : cb.segments();
                    if (!transcriptWriter.replaceSegments(id, segments, cb.durationMs())) {
                        return false;
                    }
                    job.result().complete(null);
                } catch (RuntimeException e) {
                    job.result().completeExceptionally(e);
                    throw e;
                }
            }
            case FAILED -> job.result().completeExceptionally(new SttFailedException(
                    cb.errorMessage() == null || cb.errorMessage().isBlank() ? "STT failed" : cb.errorMessage()));
        }
        return true;
    }
}
