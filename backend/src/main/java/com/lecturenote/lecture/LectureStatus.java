package com.lecturenote.lecture;

import java.util.EnumSet;
import java.util.Set;

public enum LectureStatus {
    UPLOADED, TRANSCRIBING, SUMMARIZING, DONE, FAILED;

    public static final Set<LectureStatus> ACTIVE = EnumSet.of(UPLOADED, TRANSCRIBING, SUMMARIZING);
    public static final Set<LectureStatus> IN_PROGRESS = EnumSet.of(TRANSCRIBING, SUMMARIZING);
    public static final Set<LectureStatus> DELETABLE = EnumSet.of(UPLOADED, DONE, FAILED);

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
