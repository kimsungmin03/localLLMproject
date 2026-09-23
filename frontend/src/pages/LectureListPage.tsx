import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { fetchLectures } from '../services/api';
import { Lecture, LectureStatus } from '../types/lecture';

function getStatusBadge(status: LectureStatus) {
  switch (status) {
    case 'UPLOADED':
      return <span style={{ color: '#6b7280', fontWeight: 600 }}>대기중 (UPLOADED)</span>;
    case 'TRANSCRIBING':
      return <span style={{ color: '#2563eb', fontWeight: 600 }}>전사중 (TRANSCRIBING)</span>;
    case 'SUMMARIZING':
      return <span style={{ color: '#d97706', fontWeight: 600 }}>요약중 (SUMMARIZING)</span>;
    case 'DONE':
      return <span style={{ color: '#16a34a', fontWeight: 600 }}>완료 (DONE)</span>;
    case 'FAILED':
      return <span style={{ color: '#dc2626', fontWeight: 600 }}>실패 (FAILED)</span>;
  }
}

export const LectureListPage: React.FC = () => {
  const { data: lectures, isLoading, error } = useQuery<Lecture[]>({
    queryKey: ['lectures'],
    queryFn: fetchLectures,
    refetchInterval: 5000, // Polling fallback alongside SSE
  });

  return (
    <div style={{ maxWidth: '900px', margin: '0 auto', padding: '24px 16px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
        <div>
          <h1 style={{ fontSize: '24px', fontWeight: 'bold', margin: 0 }}>강의 목록</h1>
          <p style={{ color: '#6b7280', margin: '4px 0 0' }}>온디바이스 AI로 전사 및 요약된 강의 목록입니다.</p>
        </div>
        <Link
          to="/upload"
          style={{
            backgroundColor: '#2563eb',
            color: '#fff',
            padding: '10px 18px',
            borderRadius: '6px',
            textDecoration: 'none',
            fontWeight: 600,
          }}
        >
          + 새 강의 업로드
        </Link>
      </div>

      {isLoading && <p>강의 목록을 불러오는 중...</p>}
      {error && <p style={{ color: '#dc2626' }}>목록 로딩 에러: {(error as Error).message}</p>}

      {lectures && lectures.length === 0 && (
        <div style={{ textAlign: 'center', padding: '48px', border: '1px dashed #d1d5db', borderRadius: '8px' }}>
          <p style={{ color: '#6b7280' }}>등록된 강의가 없습니다. 첫 강의를 업로드해보세요!</p>
          <Link to="/upload" style={{ color: '#2563eb', fontWeight: 600 }}>강의 파일 업로드하러 가기</Link>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
        {lectures?.map((lecture) => (
          <Link
            key={lecture.id}
            to={`/lectures/${lecture.id}`}
            style={{
              display: 'block',
              padding: '20px',
              border: '1px solid #e5e7eb',
              borderRadius: '8px',
              textDecoration: 'none',
              color: 'inherit',
              boxShadow: '0 1px 3px rgba(0,0,0,0.05)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <h2 style={{ fontSize: '18px', margin: '0 0 8px', fontWeight: 600 }}>{lecture.title}</h2>
              {getStatusBadge(lecture.status)}
            </div>

            {/* Progress bar */}
            <div style={{ margin: '12px 0 6px' }}>
              <div style={{ background: '#e5e7eb', borderRadius: '999px', height: '8px', overflow: 'hidden' }}>
                <div
                  style={{
                    background: lecture.status === 'FAILED' ? '#dc2626' : '#2563eb',
                    height: '100%',
                    width: `${lecture.progress}%`,
                    transition: 'width 0.3s ease',
                  }}
                />
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
                <span>진행률: {lecture.progress}%</span>
                <span>{new Date(lecture.createdAt).toLocaleString()}</span>
              </div>
            </div>

            {lecture.errorMessage && (
              <p style={{ color: '#dc2626', fontSize: '13px', margin: '6px 0 0' }}>오류: {lecture.errorMessage}</p>
            )}
          </Link>
        ))}
      </div>
    </div>
  );
};
