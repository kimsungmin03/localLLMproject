package com.lecturenote.service;

import com.lecturenote.domain.LectureStatus;
import com.lecturenote.dto.SseEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class SseEmitterService {

    // Keep active emitters per lectureId
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private static final Long DEFAULT_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    public SseEmitter subscribe(Long lectureId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        emitters.computeIfAbsent(lectureId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(lectureId, emitter));
        emitter.onTimeout(() -> removeEmitter(lectureId, emitter));
        emitter.onError(e -> removeEmitter(lectureId, emitter));

        // Send initial connect ping
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "SSE connected for lecture " + lectureId)));
        } catch (IOException e) {
            removeEmitter(lectureId, emitter);
        }

        return emitter;
    }

    public void sendEvent(Long lectureId, LectureStatus status, Integer progress, String errorMessage) {
        List<SseEmitter> list = emitters.get(lectureId);
        if (list == null || list.isEmpty()) {
            return;
        }

        SseEventDto payload = SseEventDto.builder()
                .lectureId(lectureId)
                .status(status)
                .progress(progress != null ? progress : 0)
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now())
                .build();

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("LECTURE_STATUS")
                        .data(payload));
            } catch (IOException e) {
                log.warn("Failed to send SSE event to client for lecture {}: {}", lectureId, e.getMessage());
                removeEmitter(lectureId, emitter);
            }
        }
    }

    private void removeEmitter(Long lectureId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(lectureId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(lectureId);
            }
        }
    }
}
