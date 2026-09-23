package com.lecturenote.lecture;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    Optional<Summary> findFirstByLectureIdAndKindOrderByIdDesc(long lectureId, SummaryKind kind);
}
