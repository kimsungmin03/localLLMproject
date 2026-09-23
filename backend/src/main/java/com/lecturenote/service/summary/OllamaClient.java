package com.lecturenote.service.summary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lecturenote.config.AppProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private RestClient createClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(180).toMillis()); // 3 minutes timeout for LLM generation
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public String chat(String modelOverride, String systemPrompt, String userPrompt, boolean requireJson) {
        String baseUrl = appProperties.getOllama().getBaseUrl();
        String targetModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : appProperties.getOllama().getModel();
        int numCtx = Math.max(8192, appProperties.getOllama().getNumCtx());

        String endpoint = baseUrl + "/api/chat";
        log.info("Calling Ollama: model={}, num_ctx={}, jsonMode={}, promptLength={}",
                targetModel, numCtx, requireJson, userPrompt.length());

        Map<String, Object> options = Map.of(
                "num_ctx", numCtx,
                "temperature", 0.2
        );

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", targetModel);
        requestBody.put("messages", messages);
        requestBody.put("options", options);
        requestBody.put("stream", false);
        if (requireJson) {
            requestBody.put("format", "json");
        }

        try {
            OllamaChatResponse response = createClient().post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.error("Ollama returned error: {}", resp.getStatusCode());
                        throw new RuntimeException("Ollama returned HTTP error " + resp.getStatusCode());
                    })
                    .body(OllamaChatResponse.class);

            if (response != null && response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            throw new RuntimeException("Empty response received from Ollama");

        } catch (Exception e) {
            log.error("Failed to query Ollama at {}: {}", endpoint, e.getMessage());
            throw new RuntimeException("Ollama chat failed: " + e.getMessage(), e);
        }
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OllamaChatResponse {
        private String model;
        private Message message;
        private boolean done;

        @Getter
        @Setter
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Message {
            private String role;
            private String content;
        }
    }
}
