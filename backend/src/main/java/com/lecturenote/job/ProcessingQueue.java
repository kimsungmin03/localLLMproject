package com.lecturenote.job;

import com.lecturenote.config.AppProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 단일 스레드 작업 큐. 각 작업은 STT 콜백과 요약이 끝날 때까지 스레드를 점유하므로
 * 워커가 202를 즉시 반환해도 GPU 작업은 한 번에 하나만 돈다.
 */
@Component
public class ProcessingQueue implements DisposableBean {

    private final ExecutorService executor;
    private final LectureProcessor processor;

    public ProcessingQueue(LectureProcessor processor, AppProperties props) {
        this.processor = processor;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(props.queue().capacity()),
                r -> {
                    Thread t = new Thread(r, "lecture-job");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** @return 큐가 가득 차 받지 못하면 false */
    public boolean submit(long lectureId) {
        try {
            executor.execute(() -> processor.process(lectureId));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
