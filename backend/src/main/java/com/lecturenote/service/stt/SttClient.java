package com.lecturenote.service.stt;

import com.lecturenote.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SttClient {

    private final AppProperties appProperties;
    private final RestClient restClient = RestClient.create();

    public void requestTranscription(Long lectureId, String audioPath) {
        String workerUrl = appProperties.getStt().getWorkerUrl();
        String callbackUrl = appProperties.getStt().getCallbackUrl();
        String endpoint = workerUrl + "/transcribe";

        Map<String, Object> payload = Map.of(
                "lectureId", lectureId,
                "audioPath", audioPath,
                "callbackUrl", callbackUrl,
                "language", "ko"
        );

        log.info("Requesting transcription from STT Worker: {} for lectureId={}", endpoint, lectureId);

        try {
            var response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.error("STT Worker returned error status: {}", resp.getStatusCode());
                        throw new RuntimeException("STT Worker transcription request failed with HTTP " + resp.getStatusCode());
                    })
                    .toBodilessEntity();

            log.info("STT Worker accepted task for lectureId={}: status={}", lectureId, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to connect to STT Worker at {}: {}", endpoint, e.getMessage());
            throw new RuntimeException("STT Worker call failed: " + e.getMessage(), e);
        }
    }
}
