package com.lecturenote.lecture;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/lectures")
public class LectureController {

    private final LectureService service;

    public LectureController(LectureService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LectureResponse> upload(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(required = false) @Size(max = 255) String title) {
        Lecture lecture = service.upload(file, title);
        return ResponseEntity.accepted()
                .location(URI.create("/api/lectures/" + lecture.getId()))
                .body(LectureResponse.from(lecture));
    }

    @GetMapping
    public List<LectureResponse> list() {
        return service.list().stream().map(LectureResponse::from).toList();
    }

    @GetMapping("/{id}")
    public LectureResponse get(@PathVariable long id) {
        return LectureResponse.from(service.get(id));
    }

    @GetMapping("/{id}/transcript")
    public List<SegmentResponse> transcript(@PathVariable long id) {
        return service.transcript(id).stream().map(SegmentResponse::from).toList();
    }

    @GetMapping("/{id}/summary")
    public JsonNode summary(@PathVariable long id) {
        return service.summary(id);
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        return service.subscribe(id);
    }

    /** Resource 를 반환하면 Spring MVC가 Range 요청을 206 으로 처리한다. */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable long id) {
        Path path = service.audio(id);
        Resource resource = new FileSystemResource(path);
        MediaType type = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
