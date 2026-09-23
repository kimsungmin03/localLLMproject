package com.lecturenote.job;

import com.lecturenote.lecture.Lecture;
import com.lecturenote.lecture.LectureRepository;
import com.lecturenote.lecture.LectureStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 재시작 시: 처리 중이던 강의는 FAILED, 대기 중이던 강의는 다시 큐에 넣는다. */
@Component
public class JobRecovery {

    private static final Logger log = LoggerFactory.getLogger(JobRecovery.class);

    private final LectureRepository lectures;
    private final ProcessingQueue queue;

    public JobRecovery(LectureRepository lectures, ProcessingQueue queue) {
        this.lectures = lectures;
        this.queue = queue;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<Lecture> pending = lectures.findByStatusInOrderByIdAsc(LectureStatus.ACTIVE);
        for (Lecture l : pending) {
            if (l.getStatus() == LectureStatus.UPLOADED) {
                if (!queue.submit(l.getId())) {
                    lectures.fail(l.getId(), LectureStatus.ACTIVE, "Job queue is full", Instant.now());
                }
            } else {
                lectures.fail(l.getId(), LectureStatus.IN_PROGRESS, "Interrupted by server restart", Instant.now());
            }
        }
        if (!pending.isEmpty()) {
            log.info("Recovered {} unfinished lectures", pending.size());
        }
    }
}
