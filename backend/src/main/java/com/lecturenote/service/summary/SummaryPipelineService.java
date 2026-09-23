package com.lecturenote.service.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lecturenote.config.AppProperties;
import com.lecturenote.domain.*;
import com.lecturenote.dto.FinalSummaryDto;
import com.lecturenote.repository.LectureRepository;
import com.lecturenote.repository.SummaryRepository;
import com.lecturenote.repository.TranscriptSegmentRepository;
import com.lecturenote.service.SseEmitterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryPipelineService {

    private final LectureRepository lectureRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final SummaryRepository summaryRepository;
    private final TranscriptChunker transcriptChunker;
    private final OllamaClient ollamaClient;
    private final SseEmitterService sseEmitterService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * Runs Map-Reduce summarization pipeline sequentially in single-threaded 'jobQueueExecutor'
     * to prevent GPU VRAM collision with STT or concurrent LLM calls.
     */
    @Async("jobQueueExecutor")
    @Transactional
    public java.util.concurrent.CompletableFuture<Void> executeSummarization(Long lectureId, String modelOverride) {
        Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
        if (lecture == null) {
            log.error("[SummaryPipeline] Lecture {} not found", lectureId);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        String targetModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : appProperties.getOllama().getModel();

        log.info("[SummaryPipeline] Starting Map-Reduce summarization for lecture {} with model {}",
                lectureId, targetModel);

        try {
            lecture.setStatus(LectureStatus.SUMMARIZING);
            lecture.setProgress(50);
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, 50, null);

            List<TranscriptSegment> segments = transcriptSegmentRepository.findByLectureOrderBySeqAsc(lecture);
            if (segments.isEmpty()) {
                throw new IllegalStateException("No transcript segments found for lecture " + lectureId);
            }

            // Remove existing summaries if regenerating
            summaryRepository.deleteByLecture(lecture);

            // Chunk segments into ~8-10 minute blocks
            List<TranscriptChunker.TranscriptChunk> chunks = transcriptChunker.chunkSegments(segments);
            log.info("[SummaryPipeline] Lecture {} divided into {} chunk(s)", lectureId, chunks.size());

            // 1. MAP PHASE: Summarize each chunk
            List<String> chunkSummaries = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                TranscriptChunker.TranscriptChunk chunk = chunks.get(i);
                log.info("[SummaryPipeline] Processing Map chunk {}/{} ({}ms ~ {}ms)",
                        i + 1, chunks.size(), chunk.startMs(), chunk.endMs());

                String mapPrompt = buildMapPrompt(chunk);
                String mapResult = ollamaClient.chat(
                        targetModel,
                        "당신은 대학 강의 핵심 요약 전문가입니다. 제공된 강의 구간 텍스트를 분석하여 핵심 주제와 요점을 한국어 불릿포인트(3~5개)로 명확하게 요약하세요.",
                        mapPrompt,
                        false
                );

                Summary chunkSummaryEntity = Summary.builder()
                        .lecture(lecture)
                        .kind(SummaryKind.CHUNK)
                        .chunkIndex(chunk.chunkIndex())
                        .content(mapResult)
                        .model(targetModel)
                        .build();
                summaryRepository.save(chunkSummaryEntity);
                chunkSummaries.add(String.format("[구간 %d: %s ~ %s]\n%s",
                        i + 1, formatTime(chunk.startMs()), formatTime(chunk.endMs()), mapResult));

                // Progress: 50% -> 80%
                int mapProgress = 50 + (int) (((i + 1.0) / chunks.size()) * 30);
                lecture.setProgress(mapProgress);
                lectureRepository.save(lecture);
                sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, mapProgress, null);
            }

            // 2. REDUCE PHASE: Aggregate chunk summaries into final structured JSON
            log.info("[SummaryPipeline] Starting Reduce phase for lecture {}", lectureId);
            lecture.setProgress(85);
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.SUMMARIZING, 85, null);

            String reducePrompt = buildReducePrompt(chunkSummaries, chunks);
            String rawJsonResponse = ollamaClient.chat(
                    targetModel,
                    "당신은 강의 요약 및 시험 문제 출제 AI입니다. 반드시 지시된 JSON 스키마에 엄격히 맞춰 순수 JSON만 반환하세요.",
                    reducePrompt,
                    true
            );

            // 3. PARSE & RETRY LOGIC (1 retry attempt on parse failure)
            FinalSummaryDto finalSummaryDto = parseWithOneRetry(rawJsonResponse, targetModel, reducePrompt);

            String finalJson = objectMapper.writeValueAsString(finalSummaryDto);
            Summary finalSummary = Summary.builder()
                    .lecture(lecture)
                    .kind(SummaryKind.FINAL)
                    .chunkIndex(null)
                    .content(finalJson)
                    .model(targetModel)
                    .build();
            summaryRepository.save(finalSummary);

            // 4. DONE PHASE
            lecture.setStatus(LectureStatus.DONE);
            lecture.setProgress(100);
            lecture.setErrorMessage(null);
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.DONE, 100, null);

            log.info("[SummaryPipeline] Successfully completed summarization for lecture {}", lectureId);
            return java.util.concurrent.CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            log.error("[SummaryPipeline] Summarization failed for lecture {}: {}", lectureId, e.getMessage(), e);
            lecture.setStatus(LectureStatus.FAILED);
            lecture.setErrorMessage("Summarization failed: " + e.getMessage());
            lectureRepository.save(lecture);
            sseEmitterService.sendEvent(lectureId, LectureStatus.FAILED, lecture.getProgress(), lecture.getErrorMessage());
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    private FinalSummaryDto parseWithOneRetry(String initialResponse, String model, String basePrompt) {
        try {
            return extractAndParseJson(initialResponse);
        } catch (Exception firstErr) {
            log.warn("[SummaryPipeline] Initial JSON parsing failed: {}. Retrying once with correction prompt...", firstErr.getMessage());

            String retryUserPrompt = basePrompt + "\n\n[중요 주의사항]\n이전 응답이 올바른 JSON 파싱에 실패했습니다. 마크다운 따옴표, 줄바꿈 에러 없이 오직 유효한 JSON 형식으로만 다시 출력해 주세요.";
            String retryResponse = ollamaClient.chat(
                    model,
                    "반드시 유효한 표준 JSON 포맷으로만 응답해야 합니다. 마크다운 코드블록(```json) 없이 JSON 본문만 출력하세요.",
                    retryUserPrompt,
                    true
            );

            try {
                return extractAndParseJson(retryResponse);
            } catch (Exception secondErr) {
                log.error("[SummaryPipeline] Retry JSON parsing failed: {}", secondErr.getMessage());
                throw new RuntimeException("Failed to generate valid summary JSON after retry: " + secondErr.getMessage(), secondErr);
            }
        }
    }

    private FinalSummaryDto extractAndParseJson(String rawText) throws JsonProcessingException {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Empty JSON response from LLM");
        }
        String clean = rawText.trim();
        // Remove markdown code fences if model included them despite json mode
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        clean = clean.trim();

        int firstBrace = clean.indexOf('{');
        int lastBrace = clean.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            clean = clean.substring(firstBrace, lastBrace + 1);
        }

        return objectMapper.readValue(clean, FinalSummaryDto.class);
    }

    private String buildMapPrompt(TranscriptChunker.TranscriptChunk chunk) {
        return String.format("""
                [강의 구간: %s ~ %s]
                강의 스크립트:
                %s

                위 강의 내용을 분석하여:
                1. 이 구간에서 다룬 핵심 개념
                2. 주요 설명 내용
                을 한국어 3~5개 문장/불릿포인트로 요약해주세요.
                """, formatTime(chunk.startMs()), formatTime(chunk.endMs()), chunk.text());
    }

    private String buildReducePrompt(List<String> chunkSummaries, List<TranscriptChunker.TranscriptChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음은 전체 강의의 구간별 요약 내용입니다:\n\n");
        for (String s : chunkSummaries) {
            sb.append(s).append("\n\n");
        }

        long minStart = chunks.isEmpty() ? 0L : chunks.get(0).startMs();
        long maxEnd = chunks.isEmpty() ? 0L : chunks.get(chunks.size() - 1).endMs();

        sb.append(String.format("""
                위의 모든 구간 요약을 종합하여, 전체 강의를 체계적으로 정리한 JSON 데이터를 생성해주세요.
                강의 전체 시간 범위: %dms ~ %dms

                반드시 아래 JSON 스키마를 만족해야 합니다:
                {
                  "overview": "강의 전체를 아우르는 3~5문장의 명확하고 논리적인 핵심 요약",
                  "sections": [
                    {
                      "title": "섹션 소제목",
                      "startMs": 시작밀리초숫자,
                      "endMs": 종료밀리초숫자,
                      "points": ["해당 섹션의 핵심 포인트 1", "핵심 포인트 2"]
                    }
                  ],
                  "keywords": ["핵심키워드1", "핵심키워드2", "핵심키워드3", "핵심키워드4"],
                  "examQuestions": [
                    {
                      "q": "강의 내용에서 출제 가능한 시험 질문",
                      "a": "모범 답안 및 상세 해설"
                    }
                  ]
                }
                """, minStart, maxEnd));

        return sb.toString();
    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%02d:%02d", min, sec);
    }
}
