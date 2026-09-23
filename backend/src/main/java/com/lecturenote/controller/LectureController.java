package com.lecturenote.controller;

import com.lecturenote.domain.Lecture;
import com.lecturenote.dto.LectureResponseDto;
import com.lecturenote.dto.RegenerateSummaryRequestDto;
import com.lecturenote.dto.TranscriptSegmentDto;
import com.lecturenote.dto.UploadResponseDto;
import com.lecturenote.exception.NotFoundException;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.service.JobQueueService;
import com.lecturenote.service.LectureService;
import com.lecturenote.service.SseEmitterService;
import com.lecturenote.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/lectures")
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;
    private final StorageService storageService;
    private final SseEmitterService sseEmitterService;
    private final LectureRepository lectureRepository;
    private final JobQueueService jobQueueService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponseDto> uploadLecture(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {

        log.info("Received lecture upload request: title={}, size={} bytes", title, file.getSize());
        LectureResponseDto created = lectureService.createLecture(file, title);

        UploadResponseDto response = UploadResponseDto.builder()
                .id(created.getId())
                .title(created.getTitle())
                .status(created.getStatus())
                .message("Lecture uploaded successfully and enqueued for transcription")
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<LectureResponseDto>> getAllLectures() {
        return ResponseEntity.ok(lectureService.getAllLectures());
    }

    @GetMapping("/{id}")
    public ResponseEntity<LectureResponseDto> getLecture(@PathVariable("id") Long id) {
        return ResponseEntity.ok(lectureService.getLecture(id));
    }

    @GetMapping("/{id}/transcript")
    public ResponseEntity<List<TranscriptSegmentDto>> getTranscript(@PathVariable("id") Long id) {
        return ResponseEntity.ok(lectureService.getTranscript(id));
    }

    @GetMapping(value = "/{id}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSummary(@PathVariable("id") Long id) {
        return ResponseEntity.ok(lectureService.getFinalSummaryContent(id));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable("id") Long id) {
        return sseEmitterService.subscribe(id);
    }

    /**
     * Resource 를 그대로 반환하면 Spring MVC가 Range 헤더를 해석해 206(부분)/200(전체)을 만든다.
     * (이전 구현은 Range 없는 요청에도 앞 1MB만 200으로 보내 파일이 잘렸다.)
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> streamAudio(@PathVariable("id") Long id) {
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lecture not found: " + id));

        Resource resource = storageService.loadAsResource(lecture.getAudioPath());
        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.parseMediaType("audio/mpeg"));
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    @PostMapping("/{id}/summary:regenerate")
    public ResponseEntity<?> regenerateSummary(
            @PathVariable("id") Long id,
            @RequestBody(required = false) RegenerateSummaryRequestDto request) {

        String model = (request != null) ? request.getModel() : null;
        log.info("Requesting summary regeneration for lecture {}: model={}", id, model);
        jobQueueService.enqueueRegeneration(id, model);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "ACCEPTED",
                        "lectureId", id,
                        "message", "Summary regeneration enqueued",
                        "model", model != null ? model : "default"
                ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLecture(@PathVariable("id") Long id) {
        lectureService.deleteLecture(id);
        return ResponseEntity.noContent().build();
    }
}
