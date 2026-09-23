package com.lecturenote.job;

import com.lecturenote.config.AppProperties;
import com.lecturenote.lecture.Lecture;
import com.lecturenote.lecture.LectureRepository;
import com.lecturenote.lecture.LectureStatus;
import com.lecturenote.sse.LectureEventPublisher;
import com.lecturenote.stt.SttJobTracker;
import com.lecturenote.stt.SttWorkerClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 강의 하나를 끝까지 처리한다: STT 요청 → 완료/실패 콜백 대기 → 요약 → DONE.
 * 큐의 단일 스레드에서 호출되며, 이 메서드가 끝나야 다음 강의가 시작된다(GPU 동시 사용 방지).
 */
@Component
public class LectureProcessor {

    private static final Logger log = LoggerFactory.getLogger(LectureProcessor.class);

    private final LectureRepository lectures;
    private final SttWorkerClient worker;
    private final SttJobTracker tracker;
    private final SummaryStage summaryStage;
    private final LectureEventPublisher events;
    private final Duration inactivityTimeout;
    private final Duration maxDuration;

    public LectureProcessor(LectureRepository lectures, SttWorkerClient worker, SttJobTracker tracker,
                            SummaryStage summaryStage, LectureEventPublisher events, AppProperties props) {
        this.lectures = lectures;
        this.worker = worker;
        this.tracker = tracker;
        this.summaryStage = summaryStage;
        this.events = events;
        this.inactivityTimeout = props.stt().inactivityTimeout();
        this.maxDuration = props.stt().maxDuration();
    }

    public void process(long lectureId) {
        try {
            if (transcribe(lectureId) && transition(lectureId, LectureStatus.TRANSCRIBING, LectureStatus.SUMMARIZING, 0)) {
                summaryStage.summarize(lectureId);
                transition(lectureId, LectureStatus.SUMMARIZING, LectureStatus.DONE, 100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(lectureId, "Processing interrupted");
        } catch (Exception e) {
            log.error("Lecture {} processing failed", lectureId, e);
            fail(lectureId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** @return STT가 성공적으로 끝났으면 true, 처리할 필요가 없는 강의(삭제·상태 불일치)면 false */
    private boolean transcribe(long lectureId) throws InterruptedException, ExecutionException {
        Lecture lecture = lectures.findById(lectureId).orElse(null);
        if (lecture == null) {
            return false;
        }
        // 워커 호출 전에 등록해야 202 응답보다 먼저 온 콜백도 받을 수 있다.
        SttJobTracker.Job job = tracker.start(lectureId);
        try {
            if (!transition(lectureId, LectureStatus.UPLOADED, LectureStatus.TRANSCRIBING, 0)) {
                return false;
            }
            try {
                worker.requestTranscription(lectureId, lecture.getAudioFile());
            } catch (RestClientException e) {
                fail(lectureId, "STT worker request failed: " + e.getMessage());
                return false;
            }
            await(job);
            return true;
        } catch (TimeoutException e) {
            fail(lectureId, e.getMessage());
            return false;
        } catch (ExecutionException e) {
            fail(lectureId, e.getCause() == null ? "STT failed" : e.getCause().getMessage());
            return false;
        } finally {
            tracker.finish(job);
        }
    }

    /** 콜백이 올 때마다 무응답 타이머가 연장된다. 전체 상한(maxDuration)은 고정. */
    private void await(SttJobTracker.Job job) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + maxDuration.toNanos();
        while (true) {
            long now = System.nanoTime();
            long idleDeadline = job.lastActivityNanos() + inactivityTimeout.toNanos();
            long waitNanos = Math.min(deadline, idleDeadline) - now;
            if (waitNanos <= 0) {
                if (deadline - now <= 0) {
                    throw new TimeoutException("STT exceeded max duration " + maxDuration);
                }
                throw new TimeoutException("No STT callback for " + inactivityTimeout);
            }
            try {
                job.result().get(waitNanos, TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException ignored) {
                // 기다리는 동안 콜백이 왔으면 idleDeadline 이 늘어나 있다.
            }
        }
    }

    private boolean transition(long id, LectureStatus from, LectureStatus to, int progress) {
        boolean changed = lectures.transition(id, from, to, progress, Instant.now()) > 0;
        if (changed) {
            events.publish(id);
        }
        return changed;
    }

    private void fail(long id, String message) {
        log.warn("Lecture {} failed: {}", id, message);
        if (lectures.fail(id, LectureStatus.ACTIVE, message, Instant.now()) > 0) {
            events.publish(id);
        }
    }
}
