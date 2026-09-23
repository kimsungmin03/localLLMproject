package com.lecturenote.repository;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.Summary;
import com.lecturenote.domain.SummaryKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface SummaryRepository extends JpaRepository<Summary, Long> {
    Optional<Summary> findFirstByLectureAndKindOrderByCreatedAtDesc(Lecture lecture, SummaryKind kind);
    Optional<Summary> findFirstByLectureIdAndKindOrderByCreatedAtDesc(Long lectureId, SummaryKind kind);
    List<Summary> findByLectureAndKindOrderByChunkIndexAsc(Lecture lecture, SummaryKind kind);
    @Transactional
    void deleteByLecture(Lecture lecture);
}
