package com.lecturenote.job;

import org.springframework.stereotype.Component;

/** 4단계(요약 파이프라인)에서 Ollama 구현으로 교체한다. */
@Component
public class NoopSummaryStage implements SummaryStage {

    @Override
    public void summarize(long lectureId) {
    }
}
