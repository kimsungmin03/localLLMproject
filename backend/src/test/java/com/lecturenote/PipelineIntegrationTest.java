package com.lecturenote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.service.stt.SttClient;
import com.lecturenote.service.summary.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 업로드 → 큐 → STT 콜백 → 요약 → DONE 전체 흐름. STT 워커와 Ollama만 목으로 대체한다.
 * 다른 테스트 컨텍스트와 H2 DB를 공유하지 않도록 별도 DB 이름을 쓴다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pipeline;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "app.storage.audio-dir=build/test-audio-pipeline",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PipelineIntegrationTest {

    private static final String TOKEN = "lecturenote-secret-token-change-in-prod";
    private static final String FINAL_JSON = """
            {"overview": "요약 개요", "sections": [{"title": "s1", "startMs": 0, "endMs": 6000, "points": ["p"]}],
             "keywords": ["k"], "examQuestions": [{"q": "q?", "a": "a"}]}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LectureRepository lectureRepository;

    @MockBean
    SttClient sttClient;

    @MockBean
    OllamaClient ollamaClient;

    @BeforeEach
    void setUp() {
        reset(sttClient, ollamaClient);
        when(ollamaClient.chat(anyString(), anyString(), contains("강의 스크립트"), eq(false))).thenReturn("- 구간 요약");
        when(ollamaClient.chat(anyString(), anyString(), contains("위의 모든 구간 요약"), eq(true))).thenReturn(FINAL_JSON);
    }

    @Test
    void uploadTranscribeSummarizeDone() throws Exception {
        long id = upload("lecture.mp3", new byte[] {1, 2, 3});
        awaitStatus(id, LectureStatus.TRANSCRIBING);
        verify(sttClient).requestTranscription(eq(id), anyString());

        callback(Map.of("lectureId", id, "status", "PROGRESS", "progress", 40, "durationMs", 6000))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/lectures/" + id)).andExpect(jsonPath("$.progress", is(20)));

        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "durationMs", 6000,
                "segments", List.of(
                        Map.of("seq", 0, "startMs", 0, "endMs", 3000, "text", "첫 문장"),
                        Map.of("seq", 1, "startMs", 3000, "endMs", 6000, "text", "둘째 문장"))))
                .andExpect(status().isOk());

        // 이전 구현은 콜백 트랜잭션 커밋 전에 요약이 시작돼 세그먼트를 못 읽고 FAILED 될 수 있었다.
        awaitStatus(id, LectureStatus.DONE);
        mockMvc.perform(get("/api/lectures/" + id + "/transcript")).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/lectures/" + id + "/summary")).andExpect(jsonPath("$.overview", is("요약 개요")));

        // 종료 후 늦게 도착한 PROGRESS 는 상태를 되돌리지 못한다.
        callback(Map.of("lectureId", id, "status", "PROGRESS", "progress", 95)).andExpect(status().isConflict());
        assertThat(lectureRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LectureStatus.DONE);
    }

    @Test
    void jobsRunOneAtATime() throws Exception {
        long first = upload("a.mp3", new byte[] {1});
        long second = upload("b.mp3", new byte[] {2});

        awaitStatus(first, LectureStatus.TRANSCRIBING);
        // 첫 작업이 202를 받았어도 콜백·요약이 끝나기 전에는 두 번째를 워커에 보내지 않는다.
        verify(sttClient, after(1500).never()).requestTranscription(eq(second), anyString());
        assertThat(lectureRepository.findById(second).orElseThrow().getStatus()).isEqualTo(LectureStatus.UPLOADED);

        completeWithOneSegment(first);
        awaitStatus(first, LectureStatus.DONE);

        verify(sttClient, timeout(5000)).requestTranscription(eq(second), anyString());
        completeWithOneSegment(second);
        awaitStatus(second, LectureStatus.DONE);
    }

    @Test
    void sttFailureCallbackFailsLecture() throws Exception {
        long id = upload("fail.wav", new byte[] {1});
        awaitStatus(id, LectureStatus.TRANSCRIBING);

        callback(Map.of("lectureId", id, "status", "FAILED", "progress", 0, "errorMessage", "CUDA out of memory"))
                .andExpect(status().isOk());
        awaitStatus(id, LectureStatus.FAILED);
        assertThat(lectureRepository.findById(id).orElseThrow().getErrorMessage()).isEqualTo("CUDA out of memory");
    }

    @Test
    void noCallbackTimesOut() throws Exception {
        long id = upload("silent.wav", new byte[] {1});
        awaitStatus(id, LectureStatus.FAILED);
        assertThat(lectureRepository.findById(id).orElseThrow().getErrorMessage()).contains("No STT callback");

        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100, "segments", List.of()))
                .andExpect(status().isConflict());
    }

    @Test
    void callbackWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/internal/stt/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lectureId\":1,\"status\":\"PROGRESS\",\"progress\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteWhileProcessingIsRejected() throws Exception {
        long id = upload("busy.mp3", new byte[] {1});
        awaitStatus(id, LectureStatus.TRANSCRIBING);
        mockMvc.perform(delete("/api/lectures/" + id)).andExpect(status().isConflict());

        completeWithOneSegment(id);
        awaitStatus(id, LectureStatus.DONE);
        mockMvc.perform(delete("/api/lectures/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/lectures/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void audioIsServedWholeOrByRange() throws Exception {
        byte[] content = new byte[1_500_000]; // 이전 구현이 잘라내던 1MB 보다 크게
        Arrays.fill(content, (byte) 7);
        content[content.length - 1] = 9;
        long id = upload("long.mp3", content);
        awaitStatus(id, LectureStatus.TRANSCRIBING);
        completeWithOneSegment(id);
        awaitStatus(id, LectureStatus.DONE);

        byte[] whole = mockMvc.perform(get("/api/lectures/" + id + "/audio"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(whole).hasSize(content.length);
        assertThat(whole[whole.length - 1]).isEqualTo((byte) 9);

        mockMvc.perform(get("/api/lectures/" + id + "/audio").header(HttpHeaders.RANGE, "bytes=1499990-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1499990-1499999/1500000"));
    }

    private void completeWithOneSegment(long id) throws Exception {
        callback(Map.of("lectureId", id, "status", "COMPLETED", "progress", 100,
                "segments", List.of(Map.of("seq", 0, "startMs", 0, "endMs", 1000, "text", "문장"))))
                .andExpect(status().isOk());
    }

    private long upload(String filename, byte[] content) throws Exception {
        String body = mockMvc.perform(multipart("/api/lectures")
                        .file(new MockMultipartFile("file", filename, "audio/mpeg", content)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions callback(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/internal/stt/callback")
                .header("X-STT-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void awaitStatus(long id, LectureStatus expected) {
        await().atMost(Duration.ofSeconds(10)).until(() ->
                lectureRepository.findById(id).map(l -> l.getStatus() == expected).orElse(false));
    }
}
