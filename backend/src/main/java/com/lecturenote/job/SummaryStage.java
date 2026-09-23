package com.lecturenote.job;

/** STT 완료 후 실행되는 요약 단계. 큐 스레드에서 동기로 호출되며 실패 시 예외를 던진다. */
public interface SummaryStage {

    void summarize(long lectureId);
}
