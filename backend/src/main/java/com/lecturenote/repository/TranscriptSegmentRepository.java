package com.lecturenote.repository;

import com.lecturenote.domain.Lecture;
import com.lecturenote.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {
    List<TranscriptSegment> findByLectureOrderBySeqAsc(Lecture lecture);
    List<TranscriptSegment> findByLectureIdOrderBySeqAsc(Long lectureId);
    void deleteByLecture(Lecture lecture);
}
