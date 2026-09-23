import React, { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { uploadLecture } from '../services/api';

export const UploadPage: React.FC = () => {
  const [title, setTitle] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

  const uploadMutation = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('파일을 선택해 주세요.');
      return uploadLecture(file, title);
    },
    onSuccess: (data) => {
      navigate(`/lectures/${data.id}`);
    },
  });

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      const selected = e.dataTransfer.files[0];
      setFile(selected);
      if (!title) {
        setTitle(selected.name.replace(/\.[^/.]+$/, ''));
      }
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      const selected = e.target.files[0];
      setFile(selected);
      if (!title) {
        setTitle(selected.name.replace(/\.[^/.]+$/, ''));
      }
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    uploadMutation.mutate();
  };

  return (
    <div style={{ maxWidth: '600px', margin: '32px auto', padding: '0 16px' }}>
      <h1 style={{ fontSize: '24px', fontWeight: 'bold', marginBottom: '8px' }}>강의 오디오 업로드</h1>
      <p style={{ color: '#6b7280', marginBottom: '24px' }}>
        강의 음성 파일(.mp3, .wav, .m4a 등)을 업로드하면 로컬 GPU에서 STT 및 요약이 시작됩니다.
      </p>

      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
        {/* Title Input */}
        <div>
          <label style={{ display: 'block', fontWeight: 600, marginBottom: '6px' }}>강의 제목</label>
          <input
            type="text"
            placeholder="예: 인공지능 개론 3주차"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            style={{
              width: '100%',
              padding: '10px 14px',
              borderRadius: '6px',
              border: '1px solid #d1d5db',
              fontSize: '15px',
              boxSizing: 'border-box',
            }}
          />
        </div>

        {/* Drag and Drop Zone */}
        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          onClick={() => fileInputRef.current?.click()}
          style={{
            border: isDragging ? '2px solid #2563eb' : '2px dashed #d1d5db',
            borderRadius: '8px',
            padding: '36px 20px',
            textAlign: 'center',
            cursor: 'pointer',
            backgroundColor: isDragging ? '#eff6ff' : '#f9fafb',
            transition: 'all 0.2s ease',
          }}
        >
          <input
            type="file"
            ref={fileInputRef}
            onChange={handleFileChange}
            accept="audio/*,.mp3,.wav,.m4a,.flac,.ogg,.webm"
            style={{ display: 'none' }}
          />
          {file ? (
            <div>
              <p style={{ fontWeight: 600, color: '#2563eb', margin: 0 }}>선택된 파일: {file.name}</p>
              <p style={{ fontSize: '13px', color: '#6b7280', margin: '4px 0 0' }}>
                {(file.size / (1024 * 1024)).toFixed(2)} MB (클릭하여 다른 파일 선택)
              </p>
            </div>
          ) : (
            <div>
              <p style={{ margin: 0, fontWeight: 600 }}>오디오 파일을 이곳에 드래그하거나 클릭하여 선택하세요</p>
              <p style={{ margin: '6px 0 0', fontSize: '13px', color: '#6b7280' }}>지원 포맷: MP3, WAV, M4A, WEBM 등</p>
            </div>
          )}
        </div>

        {uploadMutation.isError && (
          <p style={{ color: '#dc2626', fontSize: '14px', margin: 0 }}>
            오류: {(uploadMutation.error as Error).message}
          </p>
        )}

        <button
          type="submit"
          disabled={!file || uploadMutation.isPending}
          style={{
            backgroundColor: !file || uploadMutation.isPending ? '#9ca3af' : '#2563eb',
            color: '#fff',
            padding: '12px',
            borderRadius: '6px',
            border: 'none',
            fontSize: '16px',
            fontWeight: 600,
            cursor: !file || uploadMutation.isPending ? 'not-allowed' : 'pointer',
          }}
        >
          {uploadMutation.isPending ? '업로드 및 대기열 등록 중...' : '강의 분석 시작하기'}
        </button>
      </form>
    </div>
  );
};
