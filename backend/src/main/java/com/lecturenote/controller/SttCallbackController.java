package com.lecturenote.controller;

import com.lecturenote.config.AppProperties;
import com.lecturenote.dto.SttCallbackDto;
import com.lecturenote.service.LectureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

        if (!isValidToken(token)) {
            // 토큰 값은 로그에 남기지 않는다.
            log.warn("Unauthorized STT callback attempt (lectureId={})", callback.getLectureId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid callback authentication token"));
        }

        if (!lectureService.handleSttCallback(callback)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No active STT job for lecture " + callback.getLectureId()));
        }
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    /** 비밀값이 비어 있으면 모든 콜백을 거부한다(fail-closed). 비교는 상수 시간. */
    private boolean isValidToken(String token) {
        String expected = appProperties.getStt().getSharedSecret();
        if (expected == null || expected.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
