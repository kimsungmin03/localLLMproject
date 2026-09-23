package com.lecturenote.repository;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.LectureStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 상태 전이는 "현재 상태 조건부" 벌크 업데이트로 한다.
 * 큐 스레드·콜백·삭제 요청이 경합해도 허용되지 않은 전이는 0건 갱신으로 끝난다.
 */
@Repository
public interface LectureRepository extends JpaRepository<Lecture, Long> {
    List<Lecture> findAllByOrderByCreatedAtDesc();

    List<Lecture> findByStatusInOrderByIdAsc(Collection<LectureStatus> statuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.status = :to, l.progress = :progress, l.errorMessage = null, l.updatedAt = :now
            where l.id = :id and l.status = :from""")
    int transition(Long id, LectureStatus from, LectureStatus to, int progress, LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.progress = :progress, l.durationMs = coalesce(:durationMs, l.durationMs), l.updatedAt = :now
            where l.id = :id and l.status = :status""")
    int updateProgress(Long id, LectureStatus status, int progress, Long durationMs, LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Lecture l set l.status = com.lecturenote.domain.LectureStatus.FAILED,
                l.errorMessage = :message, l.updatedAt = :now
            where l.id = :id and l.status in :from""")
    int fail(Long id, Collection<LectureStatus> from, String message, LocalDateTime now);
}
