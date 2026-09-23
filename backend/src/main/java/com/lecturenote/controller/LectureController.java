package com.lecturenote.controller;

import com.lecturenote.domain.Lecture;
import com.lecturenote.dto.LectureResponseDto;
import com.lecturenote.dto.TranscriptSegmentDto;
import com.lecturenote.dto.UploadResponseDto;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.service.LectureService;
import com.lecturenote.service.SseEmitterService;
import com.lecturenote.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/lectures")
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;
    private final StorageService storageService;
    private final SseEmitterService sseEmitterService;
    private final LectureRepository lectureRepository;

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

    @GetMapping("/{id}/audio")
    public ResponseEntity<ResourceRegion> streamAudio(
            @PathVariable("id") Long id,
            @RequestHeader HttpHeaders headers) throws IOException {

        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Lecture not found: " + id));

        Resource resource = storageService.loadAsResource(lecture.getAudioPath());
        long contentLength = resource.contentLength();

        List<HttpRange> ranges = headers.getRange();
        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.parseMediaType("audio/mpeg"));

        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(1024 * 1024L, end - start + 1); // 1MB chunks

            ResourceRegion region = new ResourceRegion(resource, start, rangeLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(region);
        } else {
            long rangeLength = Math.min(1024 * 1024L, contentLength);
            ResourceRegion region = new ResourceRegion(resource, 0, rangeLength);
            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(region);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLecture(@PathVariable("id") Long id) {
        lectureService.deleteLecture(id);
        return ResponseEntity.noContent().build();
    }
}
