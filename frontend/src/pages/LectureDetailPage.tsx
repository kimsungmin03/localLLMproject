import React, { useState, useRef, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  fetchLecture,
  fetchTranscript,
  fetchSummary,
  regenerateSummary,
  getAudioStreamUrl,
} from '../services/api';
import { useLectureEvents } from '../hooks/useLectureEvents';
import { Lecture, TranscriptSegment, FinalSummary } from '../types/lecture';

export const LectureDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const lectureId = Number(id);
  const queryClient = useQueryClient();

  const [activeTab, setActiveTab] = useState<'summary' | 'transcript' | 'exam'>('summary');
  const [currentTimeMs, setCurrentTimeMs] = useState<number>(0);
  const [selectedModel, setSelectedModel] = useState<string>('exaone3.5:2.4b');
  const audioRef = useRef<HTMLAudioElement>(null);

  // 1. SSE Real-time events subscription
  useLectureEvents(lectureId);

  // 2. Fetch queries
  const { data: lecture, isLoading: isLectureLoading } = useQuery<Lecture>({
    queryKey: ['lecture', lectureId],
    queryFn: () => fetchLecture(lectureId),
    enabled: !!lectureId,
  });

  const { data: transcript } = useQuery<TranscriptSegment[]>({
    queryKey: ['transcript', lectureId],
    queryFn: () => fetchTranscript(lectureId),
    enabled: !!lectureId && (lecture?.status === 'SUMMARIZING' || lecture?.status === 'DONE'),
  });

  const { data: summary } = useQuery<FinalSummary>({
    queryKey: ['summary', lectureId],
    queryFn: () => fetchSummary(lectureId),
    enabled: !!lectureId && lecture?.status === 'DONE',
  });

  // 3. Regenerate summary mutation
  const regenMutation = useMutation({
    mutationFn: () => regenerateSummary(lectureId, selectedModel),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['lecture', lectureId] });
      queryClient.invalidateQueries({ queryKey: ['summary', lectureId] });
    },
  });

  // Seek audio to specific millisecond
  const seekTo = (ms: number) => {
    if (audioRef.current) {
      audioRef.current.currentTime = ms / 1000;
      audioRef.current.play().catch(() => {});
    }
  };

  // Track playback time for transcript syncing
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const handleTimeUpdate = () => {
      setCurrentTimeMs(audio.currentTime * 1000);
    };

    audio.addEventListener('timeupdate', handleTimeUpdate);
    return () => audio.removeEventListener('timeupdate', handleTimeUpdate);
  }, []);

  if (isLectureLoading) return <div style={{ padding: '24px' }}>강의 정보를 불러오는 중...</div>;
  if (!lecture) return <div style={{ padding: '24px' }}>강의를 찾을 수 없습니다.</div>;

  return (
    <div style={{ maxWidth: '1000px', margin: '0 auto', padding: '24px 16px' }}>
      {/* Header */}
      <div style={{ marginBottom: '20px' }}>
        <h1 style={{ fontSize: '26px', fontWeight: 'bold', margin: '0 0 8px' }}>{lecture.title}</h1>
        <div style={{ display: 'flex', gap: '16px', alignItems: 'center', fontSize: '14px', color: '#6b7280' }}>
          <span>상태: <strong>{lecture.status}</strong></span>
          <span>진행률: <strong>{lecture.progress}%</strong></span>
          <span>등록일: {new Date(lecture.createdAt).toLocaleString()}</span>
        </div>
      </div>

      {/* Progress alert if not finished */}
      {lecture.status !== 'DONE' && lecture.status !== 'FAILED' && (
        <div style={{ background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '8px', padding: '16px', marginBottom: '20px' }}>
          <p style={{ margin: '0 0 8px', fontWeight: 600, color: '#1e40af' }}>
            온디바이스 AI 분석 진행 중 ({lecture.status}: {lecture.progress}%)
          </p>
          <div style={{ background: '#dbeafe', borderRadius: '999px', height: '10px', overflow: 'hidden' }}>
            <div style={{ background: '#2563eb', height: '100%', width: `${lecture.progress}%`, transition: 'width 0.3s' }} />
          </div>
        </div>
      )}

      {/* Audio Player (Range streaming) */}
      <div style={{ background: '#f9fafb', border: '1px solid #e5e7eb', borderRadius: '8px', padding: '16px', marginBottom: '24px' }}>
        <audio
          ref={audioRef}
          controls
          src={getAudioStreamUrl(lectureId)}
          style={{ width: '100%' }}
        />
      </div>

      {/* Tab Navigation */}
      <div style={{ display: 'flex', borderBottom: '2px solid #e5e7eb', marginBottom: '20px' }}>
        <button
          onClick={() => setActiveTab('summary')}
          style={{
            padding: '12px 20px',
            border: 'none',
            background: 'none',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '16px',
            borderBottom: activeTab === 'summary' ? '2px solid #2563eb' : 'none',
            color: activeTab === 'summary' ? '#2563eb' : '#6b7280',
          }}
        >
          요약 (Summary)
        </button>
        <button
          onClick={() => setActiveTab('transcript')}
          style={{
            padding: '12px 20px',
            border: 'none',
            background: 'none',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '16px',
            borderBottom: activeTab === 'transcript' ? '2px solid #2563eb' : 'none',
            color: activeTab === 'transcript' ? '#2563eb' : '#6b7280',
          }}
        >
          전사 텍스트 (Transcript)
        </button>
        <button
          onClick={() => setActiveTab('exam')}
          style={{
            padding: '12px 20px',
            border: 'none',
            background: 'none',
            cursor: 'pointer',
            fontWeight: 600,
            fontSize: '16px',
            borderBottom: activeTab === 'exam' ? '2px solid #2563eb' : 'none',
            color: activeTab === 'exam' ? '#2563eb' : '#6b7280',
          }}
        >
          시험문제 (Exam)
        </button>
      </div>

      {/* Tab Contents */}
      {/* 1. Summary Tab */}
      {activeTab === 'summary' && (
        <div>
          {lecture.status !== 'DONE' ? (
            <p style={{ color: '#6b7280' }}>요약이 생성되는 중입니다. 완료되면 자동으로 표시됩니다.</p>
          ) : summary ? (
            <div>
              {/* Regenerate controls */}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginBottom: '16px' }}>
                <select
                  value={selectedModel}
                  onChange={(e) => setSelectedModel(e.target.value)}
                  style={{ padding: '6px 10px', borderRadius: '4px', border: '1px solid #d1d5db' }}
                >
                  <option value="exaone3.5:2.4b">EXAONE 3.5 (2.4B)</option>
                  <option value="gemma3:4b">Gemma 3 (4B)</option>
                </select>
                <button
                  onClick={() => regenMutation.mutate()}
                  disabled={regenMutation.isPending}
                  style={{
                    backgroundColor: '#4b5563',
                    color: '#fff',
                    padding: '6px 12px',
                    borderRadius: '4px',
                    border: 'none',
                    cursor: 'pointer',
                  }}
                >
                  {regenMutation.isPending ? '재생성 중...' : '요약 재생성'}
                </button>
              </div>

              {/* Overview */}
              <div style={{ marginBottom: '24px' }}>
                <h3 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '8px' }}>핵심 요약</h3>
                <p style={{ lineHeight: 1.6, color: '#374151', background: '#f3f4f6', padding: '16px', borderRadius: '8px' }}>
                  {summary.overview}
                </p>
              </div>

              {/* Sections */}
              <div style={{ marginBottom: '24px' }}>
                <h3 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '12px' }}>섹션별 내용</h3>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  {summary.sections?.map((sec, idx) => (
                    <div
                      key={idx}
                      onClick={() => seekTo(sec.startMs)}
                      style={{
                        padding: '14px',
                        border: '1px solid #e5e7eb',
                        borderRadius: '6px',
                        cursor: 'pointer',
                        transition: 'background 0.2s',
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '6px' }}>
                        <strong style={{ color: '#1d4ed8' }}>{sec.title}</strong>
                        <span style={{ fontSize: '12px', color: '#6b7280' }}>
                          {(sec.startMs / 1000 / 60).toFixed(0)}분 이동 ➔
                        </span>
                      </div>
                      <ul style={{ margin: 0, paddingLeft: '20px', color: '#4b5563' }}>
                        {sec.points?.map((pt, pIdx) => <li key={pIdx}>{pt}</li>)}
                      </ul>
                    </div>
                  ))}
                </div>
              </div>

              {/* Keywords */}
              <div>
                <h3 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '8px' }}>핵심 키워드</h3>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  {summary.keywords?.map((kw, idx) => (
                    <span
                      key={idx}
                      style={{
                        backgroundColor: '#e0e7ff',
                        color: '#4338ca',
                        padding: '4px 10px',
                        borderRadius: '999px',
                        fontSize: '13px',
                        fontWeight: 500,
                      }}
                    >
                      #{kw}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          ) : null}
        </div>
      )}

      {/* 2. Transcript Tab */}
      {activeTab === 'transcript' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {!transcript || transcript.length === 0 ? (
            <p style={{ color: '#6b7280' }}>전사된 세그먼트가 없습니다.</p>
          ) : (
            transcript.map((seg) => {
              const isCurrent = currentTimeMs >= seg.startMs && currentTimeMs <= seg.endMs;
              return (
                <div
                  key={seg.seq}
                  onClick={() => seekTo(seg.startMs)}
                  style={{
                    padding: '10px 14px',
                    borderRadius: '6px',
                    cursor: 'pointer',
                    backgroundColor: isCurrent ? '#fef08a' : 'transparent',
                    borderLeft: isCurrent ? '4px solid #eab308' : '4px solid transparent',
                    transition: 'background-color 0.15s',
                  }}
                >
                  <span style={{ fontSize: '12px', color: '#6b7280', marginRight: '10px' }}>
                    [{(seg.startMs / 1000).toFixed(1)}s]
                  </span>
                  <span style={{ fontWeight: isCurrent ? 600 : 400 }}>{seg.text}</span>
                </div>
              );
            })
          )}
        </div>
      )}

      {/* 3. Exam Tab */}
      {activeTab === 'exam' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {!summary?.examQuestions || summary.examQuestions.length === 0 ? (
            <p style={{ color: '#6b7280' }}>요약 완료 후 시험문제가 표시됩니다.</p>
          ) : (
            summary.examQuestions.map((eq, idx) => (
              <div key={idx} style={{ padding: '16px', border: '1px solid #e5e7eb', borderRadius: '8px', background: '#fafafa' }}>
                <p style={{ margin: '0 0 8px', fontWeight: 600, color: '#111827' }}>
                  Q{idx + 1}. {eq.q}
                </p>
                <div style={{ background: '#fff', border: '1px solid #f3f4f6', padding: '12px', borderRadius: '6px', color: '#374151' }}>
                  <strong>정답 및 해설:</strong> {eq.a}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};
