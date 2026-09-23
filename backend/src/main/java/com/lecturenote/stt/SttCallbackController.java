package com.lecturenote.stt;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** X-STT-Token 검증은 {@link SttTokenInterceptor}가 한다. */
@RestController
public class SttCallbackController {

    private final SttCallbackService service;

    public SttCallbackController(SttCallbackService service) {
        this.service = service;
    }

    @PostMapping("/internal/stt/callback")
    public ResponseEntity<?> callback(@Valid @RequestBody SttCallback callback) {
        if (service.handle(callback)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "No active STT job for lecture " + callback.lectureId()));
    }
}
