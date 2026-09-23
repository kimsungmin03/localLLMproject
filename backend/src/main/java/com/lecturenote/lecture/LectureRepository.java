package com.lecturenote.lecture;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 전이는 모두 "현재 상태 조건부" 벌크 업데이트로 한다.
 * 큐 스레드·콜백 스레드·DELETE 요청이 경합해도 허용되지 않은 전이는 0건 갱신으로 끝난다.
 */
public interface LectureRepository extends JpaRepository<Lecture, Long> {

    List<Lecture> findAllByOrderByCreatedAtDesc();

    List<Lecture> findByStatusInOrderByIdAsc(Collection<LectureStatus> statuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.status = :to, l.progress = :progress, l.errorMessage = null, l.updatedAt = :now
            where l.id = :id and l.status = :from""")
    int transition(long id, LectureStatus from, LectureStatus to, int progress, Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.progress = :progress, l.durationMs = coalesce(:durationMs, l.durationMs), l.updatedAt = :now
            where l.id = :id and l.status = :status""")
    int updateProgress(long id, LectureStatus status, int progress, Long durationMs, Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.status = com.lecturenote.lecture.LectureStatus.FAILED,
                l.errorMessage = :message, l.updatedAt = :now
            where l.id = :id and l.status in :from""")
    int fail(long id, Collection<LectureStatus> from, String message, Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from Lecture l where l.id = :id and l.status in :statuses")
    int deleteIfStatusIn(long id, Collection<LectureStatus> statuses);
}
