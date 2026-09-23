package com.lecturenote.controller;

import com.lecturenote.config.AppProperties;
import com.lecturenote.dto.SttCallbackDto;
import com.lecturenote.service.LectureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal/stt")
@RequiredArgsConstructor
public class SttCallbackController {

    private final AppProperties appProperties;
    private final LectureService lectureService;

    @PostMapping("/callback")
    public ResponseEntity<?> handleCallback(
            @RequestHeader(value = "X-STT-Token", required = false) String token,
            @RequestBody SttCallbackDto callback) {

        String expectedToken = appProperties.getStt().getSharedSecret();
        if (expectedToken != null && !expectedToken.isBlank() && !expectedToken.equals(token)) {
            log.warn("Unauthorized STT callback attempt with invalid token: {}", token);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid callback authentication token"));
        }

        lectureService.handleSttCallback(callback);
        return ResponseEntity.ok(Map.of("status", "OK"));
    }
}
