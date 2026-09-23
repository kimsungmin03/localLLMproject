package com.lecturenote.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lecturenote.dto.SttCallbackDto;
import com.lecturenote.dto.TranscriptSegmentDto;
import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.domain.TranscriptSegment;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import com.lecturenote.service.stt.SttClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LectureControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Mock SttClient so tests don't require actual STT worker to be running
    @MockBean
    private SttClient sttClient;

    @MockBean
    private com.lecturenote.service.summary.SummaryPipelineService summaryPipelineService;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private TranscriptSegmentRepository transcriptSegmentRepository;

    @Test
    @DisplayName("Upload lecture audio file returns 202 Accepted")
    void testUploadLecture() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.mp3",
                "audio/mpeg",
                "dummy audio content bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/lectures")
                        .file(file)
                        .param("title", "Computer Science 101"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.title", is("Computer Science 101")))
                .andExpect(jsonPath("$.status", is("UPLOADED")));
    }

    @Test
    @DisplayName("STT callback with valid token updates lecture and stores segments")
    void testSttCallback() throws Exception {
        // 1. Create a lecture first
        MockMultipartFile file = new MockMultipartFile(
                "file", "speech.mp3", "audio/mpeg", "test bytes".getBytes()
        );
        String responseBody = mockMvc.perform(multipart("/api/lectures")
                        .file(file)
                        .param("title", "Physics 101"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        Long lectureId = objectMapper.readTree(responseBody).get("id").asLong();

        // 큐 작업이 워커를 호출한(TRANSCRIBING) 뒤에야 콜백이 유효하다.
        awaitStatus(lectureId, "TRANSCRIBING");

        // 2. Send callback from STT worker
        SttCallbackDto callback = SttCallbackDto.builder()
                .lectureId(lectureId)
                .status("COMPLETED")
                .progress(100)
                .durationMs(15000L)
                .segments(List.of(
                        TranscriptSegmentDto.builder()
                                .seq(0)
                                .startMs(0L)
                                .endMs(4000L)
                                .text("Welcome to Physics class.")
                                .build(),
                        TranscriptSegmentDto.builder()
                                .seq(1)
                                .startMs(4000L)
                                .endMs(8500L)
                                .text("Today we will learn Newton's laws.")
                                .build()
                ))
                .build();

        mockMvc.perform(post("/internal/stt/callback")
                        .header("X-STT-Token", "lecturenote-secret-token-change-in-prod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OK")));

        // 3. Verify transcript segments are accessible
        mockMvc.perform(get("/api/lectures/" + lectureId + "/transcript"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].text", is("Welcome to Physics class.")))
                .andExpect(jsonPath("$[1].text", is("Today we will learn Newton's laws.")));

        // 4. Verify lecture status transitioned to SUMMARIZING
        mockMvc.perform(get("/api/lectures/" + lectureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUMMARIZING")))
                .andExpect(jsonPath("$.progress", is(50)))
                .andExpect(jsonPath("$.durationMs", is(15000)));
    }

    @Test
    @DisplayName("STT callback with invalid token returns 401 Unauthorized")
    void testUnauthorizedCallback() throws Exception {
        SttCallbackDto callback = SttCallbackDto.builder()
                .lectureId(1L)
                .status("PROGRESS")
                .progress(20)
                .build();

        mockMvc.perform(post("/internal/stt/callback")
                        .header("X-STT-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Regenerate summary endpoint accepts model selection and enqueues task")
    void testRegenerateSummary() throws Exception {
        Lecture lecture = lectureRepository.save(Lecture.builder()
                .title("Done lecture").audioPath("done.mp3").status(LectureStatus.DONE).progress(100).build());
        transcriptSegmentRepository.save(TranscriptSegment.builder()
                .lecture(lecture).seq(0).startMs(0L).endMs(1000L).text("hello").build());
        long id = lecture.getId();

        mockMvc.perform(post("/api/lectures/" + id + "/summary:regenerate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"gemma3:4b\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("ACCEPTED")))
                .andExpect(jsonPath("$.lectureId", is((int) id)))
                .andExpect(jsonPath("$.model", is("gemma3:4b")));
        verify(summaryPipelineService, timeout(5000)).executeSummarization(id, "gemma3:4b");

        // 요약이 도는 동안(SUMMARIZING) 중복 재생성 요청은 409
        mockMvc.perform(post("/api/lectures/" + id + "/summary:regenerate"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Regenerate summary for unknown lecture returns 404")
    void testRegenerateSummaryNotFound() throws Exception {
        mockMvc.perform(post("/api/lectures/999999/summary:regenerate"))
                .andExpect(status().isNotFound());
    }

    private void awaitStatus(long lectureId, String expected) {
        await().atMost(Duration.ofSeconds(10)).until(() ->
                expected.equals(lectureRepository.findById(lectureId).map(l -> l.getStatus().name()).orElse(null)));
    }
}
