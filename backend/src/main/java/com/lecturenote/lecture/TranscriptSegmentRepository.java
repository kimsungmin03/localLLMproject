package com.lecturenote.lecture;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByLectureIdOrderBySeqAsc(long lectureId);
}
