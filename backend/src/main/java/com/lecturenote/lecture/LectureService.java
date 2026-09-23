package com.lecturenote.lecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.lecturenote.job.ProcessingQueue;
import com.lecturenote.sse.LectureEventPublisher;
import com.lecturenote.storage.AudioStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LectureService {

    private final LectureRepository lectures;
    private final TranscriptSegmentRepository segments;
    private final SummaryRepository summaries;
    private final AudioStorage storage;
    private final ProcessingQueue queue;
    private final LectureEventPublisher events;

    public LectureService(LectureRepository lectures, TranscriptSegmentRepository segments,
                          SummaryRepository summaries, AudioStorage storage, ProcessingQueue queue,
                          LectureEventPublisher events) {
        this.lectures = lectures;
        this.segments = segments;
        this.summaries = summaries;
        this.storage = storage;
        this.queue = queue;
        this.events = events;
    }

    public Lecture upload(MultipartFile file, String title) {
        String audioFile = storage.store(file);
        Lecture lecture;
        try {
            lecture = lectures.save(new Lecture(resolveTitle(title, file), audioFile));
        } catch (RuntimeException e) {
            storage.delete(audioFile);
            throw e;
        }
        if (!queue.submit(lecture.getId())) {
            lectures.fail(lecture.getId(), LectureStatus.ACTIVE, "Job queue is full", Instant.now());
        }
        return get(lecture.getId());
    }

    public List<Lecture> list() {
        return lectures.findAllByOrderByCreatedAtDesc();
    }

    public Lecture get(long id) {
        return lectures.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture " + id + " not found"));
    }

    public List<TranscriptSegment> transcript(long id) {
        get(id);
        return segments.findByLectureIdOrderBySeqAsc(id);
    }

    public JsonNode summary(long id) {
        get(id);
        return summaries.findFirstByLectureIdAndKindOrderByIdDesc(id, SummaryKind.FINAL)
                .map(Summary::getContent)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Summary not ready"));
    }

    public SseEmitter subscribe(long id) {
        return events.subscribe(get(id));
    }

    public Path audio(long id) {
        Path path = storage.resolve(get(id).getAudioFile());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file missing");
        }
        return path;
    }

    /** 처리 중(TRANSCRIBING/SUMMARIZING)인 강의는 409. 세그먼트·요약은 FK CASCADE로 함께 삭제된다. */
    public void delete(long id) {
        Lecture lecture = get(id);
        if (lectures.deleteIfStatusIn(id, LectureStatus.DELETABLE) == 0) {
            if (lectures.existsById(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lecture is being processed");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture " + id + " not found");
        }
        storage.delete(lecture.getAudioFile());
        events.close(id);
    }

    private static String resolveTitle(String title, MultipartFile file) {
        if (StringUtils.hasText(title)) {
            return title.strip();
        }
        String name = StringUtils.stripFilenameExtension(StringUtils.getFilename(file.getOriginalFilename()));
        return StringUtils.hasText(name) ? name : "Untitled";
    }
}
