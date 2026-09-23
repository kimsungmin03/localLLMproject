export type LectureStatus = 'UPLOADED' | 'TRANSCRIBING' | 'SUMMARIZING' | 'DONE' | 'FAILED';

export interface Lecture {
  id: number;
  title: string;
  durationMs: number;
  status: LectureStatus;
  progress: number;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TranscriptSegment {
  seq: number;
  startMs: number;
  endMs: number;
  text: string;
}

export interface SummarySection {
  title: string;
  startMs: number;
  endMs: number;
  points: string[];
}

export interface ExamQuestion {
  q: string;
  a: string;
}

export interface FinalSummary {
  overview: string;
  sections: SummarySection[];
  keywords: string[];
  examQuestions: ExamQuestion[];
}

export interface SseEvent {
  lectureId: number;
  status: LectureStatus;
  progress: number;
  errorMessage?: string | null;
  timestamp: string;
}

export interface UploadResponse {
  id: number;
  title: string;
  status: LectureStatus;
  message: string;
}
