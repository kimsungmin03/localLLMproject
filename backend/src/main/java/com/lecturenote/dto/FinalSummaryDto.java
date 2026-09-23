package com.lecturenote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinalSummaryDto {
    @Builder.Default
    private String overview = "";

    @Builder.Default
    private List<SectionDto> sections = new ArrayList<>();

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<ExamQuestionDto> examQuestions = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionDto {
        private String title;
        private Long startMs;
        private Long endMs;
        @Builder.Default
        private List<String> points = new ArrayList<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExamQuestionDto {
        private String q;
        private String a;
    }
}
