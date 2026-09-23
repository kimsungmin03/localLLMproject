# LectureNote AI 🎓🎙️

> **온디바이스 STT 기반 강의 요약 웹 서비스**  
> 외부 상용 API 없이 로컬 PC(NVIDIA GPU) 환경에서 `faster-whisper`로 음성을 전사하고, `Ollama` 로컬 LLM으로 강의 내용을 구조화 요약 및 시험 문제를 생성하는 모노레포 프로젝트입니다.

---

## 👥 3인 협업 역할 분담

| 역할 | 담당자 | 주요 개발 범위 |
|---|---|---|
| **PM (본인)** | **AI 엔진 & 인프라 / 파이프라인 연계** | • `stt-worker` (FastAPI + faster-whisper CUDA 최적화, 콜백 발송)<br>• 로컬 환경 구축 (`docker-compose`, Ollama 설정/모델 튜닝)<br>• Map-Reduce 프롬프트 엔지니어링 및 JSON 스키마 검증/재시도<br>• 모노레포 구조 세팅 및 전체 진행 단계(0~6단계) 조율 |
| **백엔드 팀원** | **Spring Boot 코어 비즈니스 로직** | • DB 스키마 설계 및 JPA 엔티티/리포지토리 (`Lecture`, `Transcript`, `Summary`)<br>• 단일 스레드 작업 큐(GPU VRAM 보호 대기열) 및 콜백 보안 토큰 처리<br>• Spring AI 및 Ollama 연동 비즈니스 통합<br>• 클라이언트 REST API, HTTP Range 오디오 스트리밍, SSE 실시간 전송 |
| **프런트엔드 팀원** | **React SPA 화면 및 UX** | • 라우팅, 업로드 폼(드래그앤드롭), SSE 이벤트 연동 및 실시간 프로그레스 바<br>• 오디오 플레이어 UI 및 오디오-자막 동기화(타임스탬프 클릭 시 이동, 현재 문장 하이라이트)<br>• 요약/시험문제 탭 뷰 렌더링 및 스타일링 |

---

## 🏛️ 시스템 아키텍처

```
[React SPA :5173] ──────── REST / SSE ────────▶ [Spring Boot :8080] ──▶ [PostgreSQL :5432]
      │                                                ▲         │
      │                                                │ 콜백    │ 요약 (Ollama 호출)
      ▼                                                │         ▼
   STT 요청 (비동기) ────────────────────────▶ [STT Worker :8000]   [Ollama Engine :11434]
                                                       │
                                                공유 볼륨: ./storage/audio
```

- **GPU VRAM 보호**: 작업 큐를 **단일 스레드(`SingleGPUQueue`)**로 제한하여 동시 추론으로 인한 8GB VRAM(NVIDIA RTX 3070 등) OOM을 원천 차단합니다.
- **Map-Reduce 요약**: 전사 세그먼트를 약 8~10분 단위로 청킹하여 1차 요약(Map) 후, 최종 JSON 규격으로 결합(Reduce)합니다.
- **JSON 복구 재시도**: 로컬 LLM의 JSON 출력 오류 시 1회 교정 프롬프트로 자동 재시도합니다.

---

## 📂 디렉토리 구조

```
lecturenote-ai/
├── frontend/             # React + Vite + TypeScript (프런트엔드 팀원 작업 영역)
├── backend/              # Spring Boot 3 (Java 21) (백엔드 팀원 작업 영역)
├── stt-worker/           # FastAPI + faster-whisper (PM 작업 영역)
├── storage/              # 공유 오디오 파일 볼륨 (.gitignore)
│   └── audio/
├── docs/                 # 아키텍처 및 상세 기술 문서
│   └── ARCHITECTURE.md
├── .github/workflows/    # GitHub Actions CI 파이프라인
│   └── ci.yml
├── docker-compose.yml    # PostgreSQL 16 컨테이너 정의
├── .env.example          # 환경 설정 템플릿
├── .gitignore
└── README.md
```

---

## ⚡ 빠른 시작 가이드 (Quick Start)

### 1. 사전 요구사항 점검
- **GPU**: NVIDIA GPU (RTX 3070 8GB 권장, CUDA 12+ 드라이버)
- **Docker**: Docker Desktop (PostgreSQL 실행용)
- **Java**: JDK 21
- **Python**: Python 3.11 ~ 3.13
- **Node.js**: Node 20+ 및 npm
- **Ollama**: 로컬 실행 중 (`ollama pull exaone3.5:2.4b` 및 `ollama pull gemma3:4b`)

---

### 2. 인프라 실행 (PostgreSQL)
```bash
docker compose up -d postgres
```

---

### 3. STT Worker 실행 (`:8000`)
```bash
cd stt-worker
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate

pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```
> **단독 테스트 실행**: `python test_worker.py` (Mock 서버와 샘플 음성으로 CUDA 및 콜백 자동 검증)

---

### 4. Backend 실행 (`:8080`)
```bash
cd backend
# Windows:
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
.\gradlew.bat bootRun

# Linux/macOS:
# ./gradlew bootRun
```
> **테스트 실행**: `.\gradlew.bat test` (H2 인메모리 기반으로 Docker 없이도 전체 테스트 통과)

---

### 5. Frontend 실행 (`:5173`)
```bash
cd frontend
npm install
npm run dev
```
브라우저에서 `http://localhost:5173`으로 접속합니다.

---

## 📡 API 명세 요약

| HTTP Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/lectures` | 오디오 파일 업로드 (`multipart/form-data`) ➔ 202 Accepted |
| `GET` | `/api/lectures` | 전체 강의 목록 및 진행 상태 조회 |
| `GET` | `/api/lectures/{id}` | 특정 강의 상세 정보 (status, progress) |
| `GET` | `/api/lectures/{id}/transcript` | 타임스탬프 전사 세그먼트 목록 조회 |
| `GET` | `/api/lectures/{id}/summary` | 최종 요약 JSON 조회 |
| `GET` | `/api/lectures/{id}/events` | SSE 실시간 진행률 및 상태 스트리밍 |
| `GET` | `/api/lectures/{id}/audio` | HTTP Range 헤더 기반 오디오 부분 스트리밍 |
| `POST` | `/api/lectures/{id}/summary:regenerate` | 선택 모델(`model`)로 요약 재실행 |
| `DELETE` | `/api/lectures/{id}` | 강의 및 오디오 파일 삭제 |
| `POST` | `/internal/stt/callback` | STT 워커 ➔ 백엔드 콜백 (`X-STT-Token` 인증) |

---

## 🧪 CI (GitHub Actions)
- **Backend**: Java 21 환경에서 Gradle 단위/통합 테스트 자동 실행
- **Frontend**: TypeScript 컴파일 및 Vite 프로덕션 빌드 무결성 자동 검증
- **STT Worker**: Python 구문 컴파일 및 의존성 설치 검증
