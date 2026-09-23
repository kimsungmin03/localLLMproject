package com.lecturenote.service.summary;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import com.lecturenote.domain.Summary;
import com.lecturenote.domain.SummaryKind;
import com.lecturenote.domain.TranscriptSegment;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.SummaryRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SummaryPipelineServiceTest {

    @Autowired
    private SummaryPipelineService summaryPipelineService;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private TranscriptSegmentRepository transcriptSegmentRepository;

    @Autowired
    private SummaryRepository summaryRepository;

    @MockBean
    private OllamaClient ollamaClient;

    @Test
    @DisplayName("Map-Reduce pipeline successfully processes segments, parses JSON, and transitions to DONE")
    void testExecuteSummarizationSuccess() throws Exception {
        // 1. Prepare lecture and segments
        Lecture lecture = lectureRepository.save(Lecture.builder()
                .title("Operating Systems")
                .audioPath("test.mp3")
                .status(LectureStatus.SUMMARIZING)
                .progress(50)
                .build());

        transcriptSegmentRepository.save(TranscriptSegment.builder()
                .lecture(lecture)
                .seq(0)
                .startMs(0L)
                .endMs(5000L)
                .text("Welcome to OS class. We will study processes and threads.")
                .build());

        // 2. Mock Ollama responses
        // Map response
        when(ollamaClient.chat(anyString(), anyString(), contains("강의 스크립트"), eq(false)))
                .thenReturn("- 프로세스와 스레드의 개념을 설명함\n- 멀티태스킹의 핵심 원리");

        // Reduce response with valid final JSON
        String mockFinalJson = """
                {
                  "overview": "운영체제 강의로서 프로세스와 스레드의 기본 개념을 학습합니다.",
                  "sections": [
                    {
                      "title": "프로세스와 스레드",
                      "startMs": 0,
                      "endMs": 5000,
                      "points": ["프로세스는 실행 중인 프로그램", "스레드는 실행 단위"]
                    }
                  ],
                  "keywords": ["운영체제", "프로세스", "스레드"],
                  "examQuestions": [
                    {
                      "q": "프로세스와 스레드의 차이점은 무엇인가?",
                      "a": "프로세스는 독립적인 메모리 공간을 가지며, 스레드는 프로세스 내 자원을 공유합니다."
                    }
                  ]
                }
                """;
        when(ollamaClient.chat(anyString(), anyString(), contains("위의 모든 구간 요약"), eq(true)))
                .thenReturn(mockFinalJson);

        // 3. Execute and wait for async completion
        summaryPipelineService.executeSummarization(lecture.getId(), "exaone3.5:2.4b").get(10, java.util.concurrent.TimeUnit.SECONDS);

        // 4. Verify lecture state
        Lecture updated = lectureRepository.findById(lecture.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(LectureStatus.DONE);
        assertThat(updated.getProgress()).isEqualTo(100);

        // 5. Verify stored summaries
        Optional<Summary> finalSummary = summaryRepository.findFirstByLectureAndKindOrderByCreatedAtDesc(lecture, SummaryKind.FINAL);
        assertThat(finalSummary).isPresent();
        assertThat(finalSummary.get().getContent()).contains("운영체제 강의로서 프로세스와 스레드의 기본 개념을 학습합니다.");
        assertThat(finalSummary.get().getModel()).isEqualTo("exaone3.5:2.4b");

        List<Summary> chunkSummaries = summaryRepository.findByLectureAndKindOrderByChunkIndexAsc(lecture, SummaryKind.CHUNK);
        assertThat(chunkSummaries).hasSize(1);
    }

    @Test
    @DisplayName("JSON parsing retry succeeds when initial response is malformed")
    void testJsonParsingRetrySuccess() throws Exception {
        Lecture lecture = lectureRepository.save(Lecture.builder()
                .title("Database Systems")
                .audioPath("test.mp3")
                .status(LectureStatus.SUMMARIZING)
                .progress(50)
                .build());

        transcriptSegmentRepository.save(TranscriptSegment.builder()
                .lecture(lecture)
                .seq(0)
                .startMs(0L)
                .endMs(4000L)
                .text("Relational databases store data in tables.")
                .build());

        when(ollamaClient.chat(anyString(), anyString(), contains("강의 스크립트"), eq(false)))
                .thenReturn("관계형 데이터베이스 소개");

        // First call returns broken json, second call (retry) returns valid json
        String brokenJson = "Here is your summary: { overview: missing quotes";
        String validJson = """
                {
                  "overview": "관계형 데이터베이스의 핵심 개념 요약",
                  "sections": [{"title": "RDBMS 개요", "startMs": 0, "endMs": 4000, "points": ["테이블 기반 저장"]}],
                  "keywords": ["RDBMS", "테이블"],
                  "examQuestions": [{"q": "RDBMS의 기본 구조는?", "a": "테이블 구조"}]
                }
                """;

        when(ollamaClient.chat(anyString(), anyString(), contains("위의 모든 구간 요약"), eq(true)))
                .thenReturn(brokenJson)
                .thenReturn(validJson);

        summaryPipelineService.executeSummarization(lecture.getId(), "gemma3:4b").get(10, java.util.concurrent.TimeUnit.SECONDS);

        Lecture updated = lectureRepository.findById(lecture.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(LectureStatus.DONE);
        assertThat(updated.getProgress()).isEqualTo(100);

        Optional<Summary> finalSummary = summaryRepository.findFirstByLectureAndKindOrderByCreatedAtDesc(lecture, SummaryKind.FINAL);
        assertThat(finalSummary).isPresent();
        assertThat(finalSummary.get().getContent()).contains("관계형 데이터베이스의 핵심 개념 요약");
    }
}
