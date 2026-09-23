package com.lecturenote.stt;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 큐 스레드가 기다리는 STT 작업과 콜백을 연결한다. 등록되지 않은 강의의 콜백은 무시된다. */
@Component
public class SttJobTracker {

    private final ConcurrentMap<Long, Job> jobs = new ConcurrentHashMap<>();

    public Job start(long lectureId) {
        Job job = new Job(lectureId);
        jobs.put(lectureId, job);
        return job;
    }

    public Optional<Job> find(long lectureId) {
        return Optional.ofNullable(jobs.get(lectureId));
    }

    public void finish(Job job) {
        jobs.remove(job.lectureId(), job);
    }

    public static final class Job {
        private final long lectureId;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());

        Job(long lectureId) {
            this.lectureId = lectureId;
        }

        public long lectureId() { return lectureId; }
        public CompletableFuture<Void> result() { return result; }
        public long lastActivityNanos() { return lastActivityNanos.get(); }
        public void touch() { lastActivityNanos.set(System.nanoTime()); }
    }
}
