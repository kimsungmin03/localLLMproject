import { Lecture, TranscriptSegment, FinalSummary, UploadResponse } from '../types/lecture';

const API_BASE = '/api';

export async function fetchLectures(): Promise<Lecture[]> {
  const res = await fetch(`${API_BASE}/lectures`);
  if (!res.ok) throw new Error(`Failed to fetch lectures (${res.status})`);
  return res.json();
}

export async function fetchLecture(id: number): Promise<Lecture> {
  const res = await fetch(`${API_BASE}/lectures/${id}`);
  if (!res.ok) throw new Error(`Failed to fetch lecture ${id} (${res.status})`);
  return res.json();
}

export async function fetchTranscript(id: number): Promise<TranscriptSegment[]> {
  const res = await fetch(`${API_BASE}/lectures/${id}/transcript`);
  if (!res.ok) throw new Error(`Failed to fetch transcript (${res.status})`);
  return res.json();
}

export async function fetchSummary(id: number): Promise<FinalSummary> {
  const res = await fetch(`${API_BASE}/lectures/${id}/summary`);
  if (!res.ok) throw new Error(`Failed to fetch summary (${res.status})`);
  return res.json();
}

export async function uploadLecture(file: File, title: string): Promise<UploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  if (title.trim()) {
    formData.append('title', title.trim());
  }

  const res = await fetch(`${API_BASE}/lectures`, {
    method: 'POST',
    body: formData,
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Upload failed (${res.status}): ${errText}`);
  }

  return res.json();
}

export async function regenerateSummary(id: number, model?: string): Promise<void> {
  const res = await fetch(`${API_BASE}/lectures/${id}/summary:regenerate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(model ? { model } : {}),
  });

  if (!res.ok) throw new Error(`Regenerate summary failed (${res.status})`);
}

export async function deleteLecture(id: number): Promise<void> {
  const res = await fetch(`${API_BASE}/lectures/${id}`, {
    method: 'DELETE',
  });

  if (!res.ok) throw new Error(`Delete lecture failed (${res.status})`);
}

export function getAudioStreamUrl(id: number): string {
  return `${API_BASE}/lectures/${id}/audio`;
}

export function getEventsStreamUrl(id: number): string {
  return `${API_BASE}/lectures/${id}/events`;
}
