package com.lecturenote.service;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.domain.Summary;
import com.lecturenote.domain.SummaryKind;
import com.lecturenote.domain.TranscriptSegment;
import com.lecturenote.dto.LectureResponseDto;
import com.lecturenote.dto.SttCallbackDto;
import com.lecturenote.dto.TranscriptSegmentDto;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.SummaryRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final SseEmitterService sseEmitterService;

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
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found: " + id));
        return LectureResponseDto.from(lecture);
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

    @Transactional
    public void deleteLecture(Long id) {
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found: " + id));

        // Delete physical file
        storageService.deleteAudioFile(lecture.getAudioPath());

        // Delete related DB records
        transcriptSegmentRepository.deleteByLecture(lecture);
        summaryRepository.deleteByLecture(lecture);
        lectureRepository.delete(lecture);

        log.info("Deleted lecture {} and associated resources", id);
    }

    @Transactional
    public void handleSttCallback(SttCallbackDto callback) {
        Long lectureId = callback.getLectureId();
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found: " + lectureId));

        String callbackStatus = callback.getStatus();
        log.info("Handling STT callback for lecture {}: status={}, progress={}%",
                lectureId, callbackStatus, callback.getProgress());

        if ("PROGRESS".equalsIgnoreCase(callbackStatus)) {
            lecture.setStatus(LectureStatus.TRANSCRIBING);
            if (callback.getProgress() != null) {
                // Map transcription progress into 0~50% of overall lecture processing
                int overallProgress = Math.min(50, Math.max(1, callback.getProgress() / 2));
                lecture.setProgress(overallProgress);
            }
            if (callback.getDurationMs() != null && callback.getDurationMs() > 0) {
                lecture.setDurationMs(callback.getDurationMs());
            }
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.TRANSCRIBING, lecture.getProgress(), null);

        } else if ("COMPLETED".equalsIgnoreCase(callbackStatus)) {
            if (callback.getDurationMs() != null && callback.getDurationMs() > 0) {
                lecture.setDurationMs(callback.getDurationMs());
            }

            // Save transcript segments
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

            // Move to SUMMARIZING stage (50% progress)
            lecture.setStatus(LectureStatus.SUMMARIZING);
            lecture.setProgress(50);
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, 50, null);

            log.info("STT completed for lecture {}. Ready for summarization pipeline.", lectureId);

        } else if ("FAILED".equalsIgnoreCase(callbackStatus)) {
            lecture.setStatus(LectureStatus.FAILED);
            lecture.setErrorMessage(callback.getErrorMessage() != null ? callback.getErrorMessage() : "STT transcription failed");
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.FAILED, lecture.getProgress(), lecture.getErrorMessage());
            log.error("STT failed for lecture {}: {}", lectureId, lecture.getErrorMessage());
        }
    }
}
