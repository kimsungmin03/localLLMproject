import React from 'react';
import { Routes, Route, Link } from 'react-router-dom';
import { LectureListPage } from './pages/LectureListPage';
import { UploadPage } from './pages/UploadPage';
import { LectureDetailPage } from './pages/LectureDetailPage';

export const App: React.FC = () => {
  return (
    <div>
      {/* Top Navbar */}
      <header
        style={{
          borderBottom: '1px solid #e5e7eb',
          padding: '16px 24px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          backgroundColor: '#ffffff',
          position: 'sticky',
          top: 0,
          zIndex: 10,
        }}
      >
        <Link to="/" style={{ textDecoration: 'none', color: '#111827', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ fontSize: '20px', fontWeight: 'bold' }}>🎓 LectureNote AI</span>
          <span style={{ fontSize: '12px', background: '#e0f2fe', color: '#0369a1', padding: '2px 8px', borderRadius: '999px', fontWeight: 600 }}>
            On-Device AI
          </span>
        </Link>
        <nav style={{ display: 'flex', gap: '16px' }}>
          <Link to="/" style={{ textDecoration: 'none', color: '#4b5563', fontWeight: 500 }}>
            강의 목록
          </Link>
          <Link to="/upload" style={{ textDecoration: 'none', color: '#2563eb', fontWeight: 600 }}>
            + 업로드
          </Link>
        </nav>
      </header>

      {/* Main Content */}
      <main>
        <Routes>
          <Route path="/" element={<LectureListPage />} />
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/lectures/:id" element={<LectureDetailPage />} />
        </Routes>
      </main>
    </div>
  );
};
