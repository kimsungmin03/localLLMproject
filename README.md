# LectureNote AI 🎓🎙️

> **온디바이스 STT 기반 강의 요약 웹 서비스**  
> 외부 상용 API 없이 로컬 PC(NVIDIA GPU) 환경에서 `faster-whisper`로 음성을 전사하고, `Ollama` 로컬 LLM으로 강의 내용을 구조화 요약 및 시험 문제를 생성합니다.

---

## 📌 주요 특징
- **100% 로컬 프라이빗 추론**: 외부 클라우드 API를 사용하지 않고 음성 파일과 텍스트를 로컬 PC 내부에서만 처리합니다.
- **빠른 전사(STT)**: `faster-whisper` (CUDA, float16) 및 Silero VAD를 통한 효율적인 한국어 강의 전사.
- **Map-Reduce 요약 파이프라인**: 8~10분 청크 요약 후 최종 결합을 통해 긴 강의도 문맥 유실 없이 핵심 요약, 타임스탬프별 소제목, 키워드, 시험 문제를 생성합니다.
- **실시간 진행률 피드백**: 업로드부터 전사, 요약까지 Server-Sent Events(SSE)로 진행 상황을 브라우저에 실시간 반영합니다.
- **오디오 - 전사 싱크 플레이어**: 타임스탬프 기반 탐색 및 현재 재생 위치 문장 실시간 하이라이팅을 지원합니다.
- **GPU 자원 보호**: 단일 작업 큐를 적용하여 8GB VRAM(NVIDIA RTX 3070 등) 환경에서도 OOM 없이 안정적으로 동작합니다.

---

## 🛠️ 기술 스택

| 영역 | 기술 스택 | 설명 |
|---|---|---|
| **Frontend** | React 18, Vite, TypeScript, Tailwind CSS, TanStack Query | 모던 SPA 프론트엔드 및 오디오 싱크 UI |
| **Backend** | Spring Boot 3, Java 21, Gradle, Spring Data JPA, Spring AI | 메인 비즈니스 서버, 작업 큐, SSE, Ollama 연동 |
| **STT Worker** | Python 3.13, FastAPI, faster-whisper, CTranslate2 | CUDA 가속 기반 고속 음성 전사 백그라운드 워커 |
| **LLM Engine** | Ollama (`exaone3.5:2.4b` / `gemma3:4b` 등) | 온디바이스 로컬 요약 및 구조화 JSON 생성 |
| **Database** | PostgreSQL 16 | Docker Compose 기반 강의, 전사, 요약 데이터 관리 |

---

## 📂 프로젝트 구조

```
lecturenote-ai/
├── frontend/             # React + Vite + TypeScript 클라이언트
├── backend/              # Spring Boot 3 (Java 21) 메인 애플리케이션
├── stt-worker/           # FastAPI + faster-whisper 전사 워커
├── storage/              # 공유 오디오 파일 저장소 (.gitignore 대상)
│   └── audio/
├── docs/                 # 아키텍처 및 상세 기술 문서
│   └── ARCHITECTURE.md
├── docker-compose.yml    # PostgreSQL 16 컨테이너 정의
├── .env.example          # 환경 설정 템플릿
├── .gitignore
└── README.md
```

---

## ⚡ 빠른 시작 (Quick Start)

### 1. 사전 요구사항
- **GPU**: NVIDIA GPU (RTX 3070 8GB 권장, CUDA 12+ 지원 드라이버)
- **Docker**: Docker Desktop (PostgreSQL 컨테이너 실행용)
- **Java**: JDK 21
- **Node.js**: Node 20+ 및 npm
- **Python**: Python 3.11 ~ 3.13
- **Ollama**: 로컬 실행 중 (`ollama run exaone3.5:2.4b` 등)

### 2. 인프라 실행 (PostgreSQL)
```bash
docker compose up -d postgres
```

### 3. 환경 변수 설정
```bash
cp .env.example .env
```

### 4. STT Worker 실행
```bash
cd stt-worker
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
# source .venv/bin/activate

pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 5. Backend 실행
```bash
cd backend
./gradlew bootRun
```

### 6. Frontend 실행
```bash
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`으로 접속합니다.

---

## 📖 문서
시스템 구조, 비동기 콜백 프로토콜, DB 스키마, API 엔드포인트 명세는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)를 참고하세요.

---

## 🤝 협업 및 기여 규칙
- 커밋 메시지는 **Conventional Commits** 규격을 준수합니다. (예: `feat(stt-worker): implement transcription callback`)
- 오디오 파일, 가중치 모델 파일, `.env`는 버전 관리에서 제외됩니다.
