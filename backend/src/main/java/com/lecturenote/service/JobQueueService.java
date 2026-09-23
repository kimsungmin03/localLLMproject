package com.lecturenote.service;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.dto.LectureCreatedEvent;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.service.stt.SttClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobQueueService {

    private final SttClient sttClient;
    private final LectureRepository lectureRepository;
    private final SseEmitterService sseEmitterService;

    /**
     * Executes sequentially in single-threaded 'jobQueueExecutor' pool AFTER transaction is committed.
     * Guarantees 1 active AI workload at a time to prevent GPU VRAM overload and eliminates race conditions.
     */
    @Async("jobQueueExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLectureCreated(LectureCreatedEvent event) {
        Long lectureId = event.lectureId();
        String audioPath = event.audioPath();

        log.info("[JobQueue] Picking up lecture {} for transcription after transaction commit", lectureId);

        Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
        if (lecture == null) {
            log.error("[JobQueue] Lecture {} not found even after commit", lectureId);
            return;
        }

        try {
            lecture.setStatus(LectureStatus.TRANSCRIBING);
            lecture.setProgress(1);
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.TRANSCRIBING, 1, null);

            // Dispatch to STT Worker
            sttClient.requestTranscription(lectureId, audioPath);
            log.info("[JobQueue] STT Worker call dispatched for lecture {}", lectureId);

        } catch (Exception e) {
            log.error("[JobQueue] Error dispatching transcription for lecture {}: {}", lectureId, e.getMessage(), e);
            lecture.setStatus(LectureStatus.FAILED);
            lecture.setErrorMessage("STT dispatch failed: " + e.getMessage());
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.FAILED, lecture.getProgress(), lecture.getErrorMessage());
        }
    }
}
