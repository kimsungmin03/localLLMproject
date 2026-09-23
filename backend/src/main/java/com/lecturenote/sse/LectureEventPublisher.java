package com.lecturenote.sse;

import com.lecturenote.config.AppProperties;
import com.lecturenote.lecture.Lecture;
import com.lecturenote.lecture.LectureRepository;
import com.lecturenote.lecture.LectureResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 강의별 SSE 구독자. 이벤트 이름은 "status", 데이터는 LectureResponse. 종료 상태가 되면 스트림을 닫는다. */
@Component
public class LectureEventPublisher {

    public static final String EVENT_NAME = "status";

    private final ConcurrentMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final LectureRepository lectures;
    private final long timeoutMs;

    public LectureEventPublisher(LectureRepository lectures, AppProperties props) {
        this.lectures = lectures;
        this.timeoutMs = props.sse().timeout().toMillis();
    }

    public SseEmitter subscribe(Lecture lecture) {
        long id = lecture.getId();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        if (lecture.getStatus().isTerminal()) {
            send(id, emitter, lecture);
            emitter.complete();
            return emitter;
        }
        emitters.computeIfAbsent(id, k -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(id, emitter));
        emitter.onTimeout(() -> remove(id, emitter));
        emitter.onError(e -> remove(id, emitter));
        send(id, emitter, lecture);
        return emitter;
    }

    /** DB의 현재 상태를 구독자에게 보낸다. 상태 변경이 커밋된 뒤에 호출해야 한다. */
    public void publish(long lectureId) {
        Set<SseEmitter> subs = emitters.get(lectureId);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        lectures.findById(lectureId).ifPresentOrElse(lecture -> {
            subs.forEach(e -> send(lectureId, e, lecture));
            if (lecture.getStatus().isTerminal()) {
                close(lectureId);
            }
        }, () -> close(lectureId));
    }

    public void close(long lectureId) {
        Set<SseEmitter> subs = emitters.remove(lectureId);
        if (subs != null) {
            subs.forEach(SseEmitter::complete);
        }
    }

    private void send(long id, SseEmitter emitter, Lecture lecture) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(LectureResponse.from(lecture)));
        } catch (IOException | IllegalStateException e) {
            remove(id, emitter);
        }
    }

    private void remove(long id, SseEmitter emitter) {
        emitters.computeIfPresent(id, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
