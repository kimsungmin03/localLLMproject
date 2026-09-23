package com.lecturenote.service;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.domain.Summary;
import com.lecturenote.domain.SummaryKind;
import com.lecturenote.domain.TranscriptSegment;
import com.lecturenote.dto.LectureResponseDto;
import com.lecturenote.dto.SttCallbackDto;
import com.lecturenote.dto.TranscriptSegmentDto;
import com.lecturenote.exception.NotFoundException;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.SummaryRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import com.lecturenote.service.stt.SttFailedException;
import com.lecturenote.service.stt.SttJobTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final LectureRepository lectureRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final SummaryRepository summaryRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEmitterService sseEmitterService;
    private final SttJobTracker sttJobTracker;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public LectureResponseDto createLecture(MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Audio file must not be empty");
        }
        String cleanTitle = (title != null && !title.isBlank()) ? title.trim() : "Untitled Lecture";

        // 1. Initial persistence to generate ID
        Lecture lecture = Lecture.builder()
                .title(cleanTitle)
                .audioPath("PENDING")
                .status(LectureStatus.UPLOADED)
                .progress(0)
                .build();
        lecture = lectureRepository.save(lecture);

        // 2. Save physical audio file
        String savedPath = storageService.storeAudioFile(file, lecture.getId());
        lecture.setAudioPath(savedPath);
        lectureRepository.save(lecture);

        // 3. Publish event to be processed after transaction commits safely
        eventPublisher.publishEvent(new com.lecturenote.dto.LectureCreatedEvent(lecture.getId(), savedPath));

        log.info("Created lecture {} - '{}' and enqueued for STT", lecture.getId(), cleanTitle);
        return LectureResponseDto.from(lecture);
    }

    @Transactional(readOnly = true)
    public List<LectureResponseDto> getAllLectures() {
        return lectureRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(LectureResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public LectureResponseDto getLecture(Long id) {
        return LectureResponseDto.from(findLecture(id));
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegmentDto> getTranscript(Long id) {
        return transcriptSegmentRepository.findByLectureIdOrderBySeqAsc(id)
                .stream()
                .map(TranscriptSegmentDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public String getFinalSummaryContent(Long id) {
        return summaryRepository.findFirstByLectureIdAndKindOrderByCreatedAtDesc(id, SummaryKind.FINAL)
                .map(Summary::getContent)
                .orElse("{}");
    }

    /** 처리 중(TRANSCRIBING/SUMMARIZING)인 강의는 큐 작업이 쓰고 있으므로 삭제하지 않는다(409). */
    @Transactional
    public void deleteLecture(Long id) {
        Lecture lecture = findLecture(id);
        if (lecture.getStatus() == LectureStatus.TRANSCRIBING || lecture.getStatus() == LectureStatus.SUMMARIZING) {
            throw new IllegalStateException("Lecture " + id + " is being processed");
        }

        // Delete physical file
        storageService.deleteAudioFile(lecture.getAudioPath());

        // Delete related DB records
        transcriptSegmentRepository.deleteByLecture(lecture);
        summaryRepository.deleteByLecture(lecture);
        lectureRepository.delete(lecture);

        log.info("Deleted lecture {} and associated resources", id);
    }

    /**
     * 워커 콜백을 큐 작업에 전달한다. 요약은 여기서 실행하지 않고 큐 스레드가 이어서 실행한다.
     *
     * @return 현재 대기 중인 STT 작업의 콜백이면 true. 타임아웃 이후 등 늦게 도착한 콜백이면 false.
     */
    public boolean handleSttCallback(SttCallbackDto callback) {
        Long lectureId = callback.getLectureId();
        String callbackStatus = callback.getStatus();
        if (lectureId == null || callbackStatus == null) {
            throw new IllegalArgumentException("lectureId and status are required");
        }
        SttJobTracker.Job job = sttJobTracker.find(lectureId).orElse(null);
        if (job == null) {
            log.info("Ignoring stale STT callback for lecture {}: status={}", lectureId, callbackStatus);
            return false;
        }
        job.touch();
        log.info("Handling STT callback for lecture {}: status={}, progress={}%",
                lectureId, callbackStatus, callback.getProgress());

        switch (callbackStatus.toUpperCase()) {
            case "PROGRESS" -> {
                // 전사 진행률은 전체의 0~49%로 표시한다(50%부터 요약).
                int raw = callback.getProgress() != null ? callback.getProgress() : 0;
                int overall = Math.min(49, Math.max(1, raw / 2));
                if (lectureRepository.updateProgress(lectureId, LectureStatus.TRANSCRIBING, overall,
                        positiveOrNull(callback.getDurationMs()), LocalDateTime.now()) > 0) {
                    sseEmitterService.sendEvent(lectureId, LectureStatus.TRANSCRIBING, overall, null);
                }
            }
            case "COMPLETED" -> {
                try {
                    Boolean saved = transactionTemplate.execute(tx -> saveTranscript(lectureId, callback));
                    if (!Boolean.TRUE.equals(saved)) {
                        return false;
                    }
                } catch (RuntimeException e) {
                    job.result().completeExceptionally(e);
                    throw e;
                }
                sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, 50, null);
                log.info("STT completed for lecture {}. Summarization continues on the job queue.", lectureId);
                job.result().complete(null);
            }
            case "FAILED" -> job.result().completeExceptionally(new SttFailedException(
                    callback.getErrorMessage() != null ? callback.getErrorMessage() : "STT transcription failed"));
            default -> throw new IllegalArgumentException("Unknown STT callback status: " + callbackStatus);
        }
        return true;
    }

    /** TRANSCRIBING 상태일 때만 세그먼트를 통째로 교체하고 SUMMARIZING(50%)으로 넘긴다. */
    private boolean saveTranscript(Long lectureId, SttCallbackDto callback) {
        LocalDateTime now = LocalDateTime.now();
        if (lectureRepository.transition(lectureId, LectureStatus.TRANSCRIBING, LectureStatus.SUMMARIZING, 50, now) == 0) {
            return false;
        }
        lectureRepository.updateProgress(lectureId, LectureStatus.SUMMARIZING, 50,
                positiveOrNull(callback.getDurationMs()), now);

        Lecture lecture = lectureRepository.getReferenceById(lectureId);
        transcriptSegmentRepository.deleteByLecture(lecture);
        if (callback.getSegments() != null && !callback.getSegments().isEmpty()) {
            List<TranscriptSegment> segments = new ArrayList<>();
            for (TranscriptSegmentDto s : callback.getSegments()) {
                segments.add(TranscriptSegment.builder()
                        .lecture(lecture)
                        .seq(s.getSeq())
                        .startMs(s.getStartMs())
                        .endMs(s.getEndMs())
                        .text(s.getText())
                        .build());
            }
            transcriptSegmentRepository.saveAll(segments);
            log.info("Saved {} transcript segments for lecture {}", segments.size(), lectureId);
        }
        return true;
    }

    private Lecture findLecture(Long id) {
        return lectureRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lecture not found: " + id));
    }

    private static Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }
}
