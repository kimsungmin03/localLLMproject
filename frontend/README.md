# LectureNote AI — Frontend 개발 가이드 (프런트엔드 팀원용)

프런트엔드 팀원의 UI/UX 개발을 위해 구성된 React + Vite + TypeScript 스타터 뼈대입니다.  
백엔드 REST API 명세와 SSE 실시간 이벤트 구독 훅, TypeScript 도메인 모델이 모두 세팅되어 있어 즉시 화면 커스터마이징 및 스타일링을 진행할 수 있습니다.

---

## 🛠️ 기술 스택 & 라이브러리
- **빌드/런타임**: Vite, React 18, TypeScript
- **라우팅**: `react-router-dom` v6
- **서버 상태 관리**: `@tanstack/react-query` v5
- **아이콘**: `lucide-react`

---

## 📁 디렉토리 구조
```
frontend/
├── src/
│   ├── types/
│   │   └── lecture.ts          # Lecture, TranscriptSegment, FinalSummary, SseEvent 타입 정의
│   ├── services/
│   │   └── api.ts              # 백엔드 REST API 함수 모음 (fetchLectures, uploadLecture 등)
│   ├── hooks/
│   │   └── useLectureEvents.ts # SSE (/api/lectures/:id/events) 실시간 구독 및 쿼리 캐시 자동 갱신 훅
│   ├── pages/
│   │   ├── LectureListPage.tsx   # 강의 목록 카드 및 진행률 바
│   │   ├── UploadPage.tsx        # 드래그앤드롭 업로드 폼
│   │   └── LectureDetailPage.tsx # 오디오 플레이어, 요약/전사/시험 3개 탭 뷰
│   ├── App.tsx                 # 네비게이션 헤더 및 라우터 정의
│   ├── main.tsx                # QueryClientProvider 및 앱 진입점
│   └── index.css               # 기본 스타일
├── package.json
├── tsconfig.json
├── vite.config.ts              # /api 프록시 설정 (http://localhost:8080)
└── README.md
```

---

## 🚀 빠른 시작
```bash
# 1. 의존성 설치
npm install

# 2. 개발 서버 시작 (http://localhost:5173)
npm run dev

# 3. 타입 체크 및 프로덕션 빌드 검증
npm run build
```

---

## 💡 주요 연동 포인트 & 구현 가이드

### 1. 실시간 SSE 이벤트 구독 (`useLectureEvents`)
강의 상세 페이지나 목록에서 상태/진행률 변경을 실시간 반영할 때 사용합니다:
```tsx
import { useLectureEvents } from '../hooks/useLectureEvents';

// 자동으로 SSE를 연결하고 LECTURE_STATUS 수신 시 React Query 캐시를 갱신합니다.
useLectureEvents(lectureId, (event) => {
  console.log('실시간 상태 수신:', event.status, event.progress);
});
```

### 2. 오디오-전사 타임스탬프 싱크 (Sync)
- 오디오 스트리밍 URL: `getAudioStreamUrl(lectureId)` (`/api/lectures/:id/audio`)
- 백엔드가 HTTP `Range` 헤더를 지원하므로 `<audio>` 태그에서 탐색바(Seek)를 자유롭게 이동할 수 있습니다.
- 특정 문장 클릭 시 해당 시점으로 이동:
  ```ts
  audioRef.current.currentTime = segment.startMs / 1000;
  ```
- 오디오 재생 중 현재 문장 하이라이트:
  `audio.addEventListener('timeupdate', () => setCurrentTimeMs(audio.currentTime * 1000))`를 통해 현재 시간이 `seg.startMs`와 `seg.endMs` 사이에 있을 때 스타일을 적용합니다.

### 3. 요약 모델 변경 및 재생성
- `regenerateSummary(lectureId, 'gemma3:4b')`를 호출하면 백엔드에서 해당 LLM으로 Map-Reduce 요약을 재실행합니다.
