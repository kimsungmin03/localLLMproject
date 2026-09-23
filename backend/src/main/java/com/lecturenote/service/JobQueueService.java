package com.lecturenote.service;

import com.lecturenote.config.AppProperties;
import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.dto.LectureCreatedEvent;
import com.lecturenote.exception.NotFoundException;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import com.lecturenote.service.stt.SttClient;
import com.lecturenote.service.stt.SttJobTracker;
import com.lecturenote.service.summary.SummaryPipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 단일 스레드 작업 큐(jobQueueExecutor).
 * 워커는 /transcribe 에 202를 즉시 반환하므로, 큐 작업은 STT 완료·실패 콜백을 기다린 뒤 요약까지 끝내야 반환한다.
 * 그래야 STT·LLM 작업이 GPU에서 한 번에 하나만 돈다.
 */
@Slf4j
@Service
public class JobQueueService {

    private static final Set<LectureStatus> ACTIVE =
            EnumSet.of(LectureStatus.UPLOADED, LectureStatus.TRANSCRIBING, LectureStatus.SUMMARIZING);
    private static final Set<LectureStatus> IN_PROGRESS =
            EnumSet.of(LectureStatus.TRANSCRIBING, LectureStatus.SUMMARIZING);

    private final Executor jobQueueExecutor;
    private final SttClient sttClient;
    private final SttJobTracker tracker;
    private final LectureRepository lectureRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final SummaryPipelineService summaryPipelineService;
    private final SseEmitterService sseEmitterService;
    private final Duration inactivityTimeout;
    private final Duration maxDuration;

    public JobQueueService(@Qualifier("jobQueueExecutor") Executor jobQueueExecutor,
                           SttClient sttClient,
                           SttJobTracker tracker,
                           LectureRepository lectureRepository,
                           TranscriptSegmentRepository transcriptSegmentRepository,
                           SummaryPipelineService summaryPipelineService,
                           SseEmitterService sseEmitterService,
                           AppProperties appProperties) {
        this.jobQueueExecutor = jobQueueExecutor;
        this.sttClient = sttClient;
        this.tracker = tracker;
        this.lectureRepository = lectureRepository;
        this.transcriptSegmentRepository = transcriptSegmentRepository;
        this.summaryPipelineService = summaryPipelineService;
        this.sseEmitterService = sseEmitterService;
        this.inactivityTimeout = appProperties.getStt().getInactivityTimeout();
        this.maxDuration = appProperties.getStt().getMaxDuration();
    }

    /** 업로드 트랜잭션이 커밋된 뒤 큐에 넣는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLectureCreated(LectureCreatedEvent event) {
        submit(event.lectureId(), () -> processLecture(event.lectureId()));
    }

    /**
     * 요약 재생성. 전사가 있는 DONE/FAILED 강의만 받으며, 즉시 SUMMARIZING으로 바꿔 중복 요청·삭제를 막는다.
     */
    public void enqueueRegeneration(Long lectureId, String model) {
        if (!lectureRepository.existsById(lectureId)) {
            throw new NotFoundException("Lecture not found: " + lectureId);
        }
        if (!transcriptSegmentRepository.existsByLectureId(lectureId)) {
            throw new IllegalStateException("Lecture " + lectureId + " has no transcript to summarize");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean accepted = lectureRepository.transition(lectureId, LectureStatus.DONE, LectureStatus.SUMMARIZING, 50, now) > 0
                || lectureRepository.transition(lectureId, LectureStatus.FAILED, LectureStatus.SUMMARIZING, 50, now) > 0;
        if (!accepted) {
            throw new IllegalStateException("Lecture " + lectureId + " is being processed");
        }
        sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, 50, null);
        submit(lectureId, () -> summaryPipelineService.executeSummarization(lectureId, model));
    }

    /** 재시작 시: 처리 중이던 강의는 FAILED, 대기 중이던 강의는 다시 큐에 넣는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedLectures() {
        for (Lecture lecture : lectureRepository.findByStatusInOrderByIdAsc(ACTIVE)) {
            Long id = lecture.getId();
            if (lecture.getStatus() == LectureStatus.UPLOADED) {
                submit(id, () -> processLecture(id));
            } else {
                lectureRepository.fail(id, IN_PROGRESS, "Interrupted by server restart", LocalDateTime.now());
            }
        }
    }

    void processLecture(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
        if (lecture == null) {
            return;
        }
        // 워커 호출 전에 등록해야 202 응답보다 먼저 도착한 콜백도 받을 수 있다.
        SttJobTracker.Job job = tracker.start(lectureId);
        try {
            if (lectureRepository.transition(lectureId, LectureStatus.UPLOADED, LectureStatus.TRANSCRIBING, 1,
                    LocalDateTime.now()) == 0) {
                return;
            }
            sseEmitterService.sendEvent(lectureId, LectureStatus.TRANSCRIBING, 1, null);
            try {
                sttClient.requestTranscription(lectureId, lecture.getAudioPath());
            } catch (RuntimeException e) {
                fail(lectureId, "STT dispatch failed: " + e.getMessage());
                return;
            }
            awaitStt(job);
        } catch (TimeoutException e) {
            fail(lectureId, e.getMessage());
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            fail(lectureId, cause.getMessage() != null ? cause.getMessage() : "STT transcription failed");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(lectureId, "Processing interrupted");
            return;
        } finally {
            tracker.finish(job);
        }
        // COMPLETED 콜백이 세그먼트 저장과 SUMMARIZING 전이를 커밋한 상태다.
        summaryPipelineService.executeSummarization(lectureId, null);
    }

    /** 콜백이 올 때마다 무응답 타이머가 연장된다. 전체 상한(maxDuration)은 고정. */
    private void awaitStt(SttJobTracker.Job job) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + maxDuration.toNanos();
        while (true) {
            long now = System.nanoTime();
            long idleDeadline = job.lastActivityNanos() + inactivityTimeout.toNanos();
            long waitNanos = Math.min(deadline, idleDeadline) - now;
            if (waitNanos <= 0) {
                throw new TimeoutException(deadline - now <= 0
                        ? "STT exceeded max duration " + maxDuration
                        : "No STT callback for " + inactivityTimeout);
            }
            try {
                job.result().get(waitNanos, TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException ignored) {
                // 기다리는 동안 콜백이 왔으면 idleDeadline 이 늘어나 있다.
            }
        }
    }

    private void submit(Long lectureId, Runnable task) {
        try {
            jobQueueExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("[JobQueue] Job for lecture {} failed", lectureId, e);
                    fail(lectureId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) { // TaskRejectedException 포함
            fail(lectureId, "Job queue is full");
        }
    }

    private void fail(Long lectureId, String message) {
        log.warn("[JobQueue] Lecture {} failed: {}", lectureId, message);
        if (lectureRepository.fail(lectureId, ACTIVE, message, LocalDateTime.now()) > 0) {
            int progress = lectureRepository.findById(lectureId).map(Lecture::getProgress).orElse(0);
            sseEmitterService.sendEvent(lectureId, LectureStatus.FAILED, progress, message);
        }
    }
}
