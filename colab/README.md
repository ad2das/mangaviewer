# colab/ — 에뮬 테스트 루프 ↔ 코랩(A100) 연동

로컬 에뮬레이터에서 프로브를 돌리고 → 결과와 코드 상태를 코랩으로 보내면 →
코랩이 **빌드 + 유닛테스트 + 툴테스트 + A100 코더 모델 분석(진단/패치 제안)**을 수행하고 →
결과를 로컬로 되돌려주는 루프.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `mangaviewer_colab.ipynb` | 코랩 노트북 — 빌드/테스트/A100 분석/내보내기 |
| `push_inputs.ps1` | 로컬 — 최신 프로브 + git 상태 + focus 파일 → `colab-inbox` 브랜치 푸시 |
| `pull_results.ps1` | 로컬 — `colab-outbox` 브랜치 → `colab/results/<스탬프>/` 회수 |
| `bus/` · `staging/` · `results/` | 로컬 작업 상태 (gitignore됨) |

## 루프

```
[로컬 에뮬] 프로브 실행 (LongSessionScrollProbe 등)
   ↓  push_inputs.ps1
[GitHub] colab-inbox 브랜치 (inbox.zip = 프로브 + worktree diff + focus 파일 원본)
   ↓  코랩 노트북 실행 (A100)
[코랩] 빌드 → 유닛테스트 → 툴테스트 → A100 코더 분석 → results.tar.gz
   ↓  아웃박스 푸시(GITHUB_TOKEN) 또는 Drive/브라우저 다운로드
[GitHub] colab-outbox 브랜치
   ↓  pull_results.ps1
[로컬] colab/results/<스탬프>/extracted/ (analysis.md + proposal.patch)
```

## 코랩 셋업 (최초 1회)

1. 노트북 열기
   - 레포에 푸시돼 있으면: `https://colab.research.google.com/github/ad2das/mangaviewer/blob/main/colab/mangaviewer_colab.ipynb`
   - 아니면 로컬 `colab/mangaviewer_colab.ipynb` 파일을 코랩에 업로드
2. 런타임 → 런타임 유형 변경 → **A100 GPU** 선택
3. (선택) 좌측 🔑 → 시크릿에 `GITHUB_TOKEN` 추가
   - fine-grained PAT, `ad2das/mangaviewer` 레포지토리 `Contents: Read and write`
   - 없으면 아웃박스 푸시가 생략되고 Drive/다운로드로만 결과를 받음
4. **Ctrl+F9** (모두 실행)

## 로컬 사용

```powershell
# 1) 프로브 결과 + 코드 상태 올리기 (에뮬에서 최신 probe 디렉터리를 자동으로 당겨옴)
powershell -ExecutionPolicy Bypass -File colab\push_inputs.ps1 `
  -Objective "장시간 스크롤에서 stuck work record와 프레임 저하 원인 분석" `
  -Questions "RUNNING/RETRY_WAIT 레코드가 왜 은퇴하지 않는가?"

# 2) 코랩에서 노트북 실행 (A100)

# 3) 결과 회수 → colab\results\<스탬프>\
powershell -ExecutionPolicy Bypass -File colab\pull_results.ps1
```

`push_inputs.ps1` 옵션:

| 옵션 | 설명 |
| --- | --- |
| `-Objective` | 코더 모델에게 줄 목표 (기본: 최신 프로브 분석 + 개선 제안) |
| `-Questions` | 구체 질문 목록 |
| `-Focus` | 추가로 포함할 파일 경로 (기본: git 변경 파일 전체) |
| `-Serial` | adb 디바이스 (기본 `emulator-5554`) |
| `-NoDevice` | 에뮬 pull 없이 코드 상태만 올리기 |
| `-DryRun` | 푸시 없이 `colab\staging\`에 zip만 생성 |

## 참고 / 주의

- **CCU**: 빌드/테스트만 = CPU 런타임(무료). A100 구간만 CCU 소모 (A100 40GB ≈ 5.4 CCU/h). 끝나면 런타임 연결 해제.
- **모델 교체**: 노트북 설정 셀 `MODEL_ID`. 기본 `Qwen/Qwen2.5-Coder-32B-Instruct-AWQ` (A100 40GB용). 80GB면 fp16 모델도 가능.
- **google_services.xml**: gitignore + CI 시크릿 대상 — **공개 레포 브랜치에 올리지 않는다.** Firebase 로그인까지 되는 APK가 필요하면 코랩 파일창에 드래그(→ 셋업 셀이 자동 복사). 없어도 빌드는 성공하고 Firebase 기능만 비활성.
- **인박스/아웃박스 브랜치는 전용 버스** — 결과 확인 후 삭제해도 됨.
- **vLLM OOM** → 노트북에서 `MAX_MODEL_LEN` 낮추기 (32768 → 16384).
- **빌드 실패** → 코랩 `out/logs/gradle-build.log` (results.tar.gz에도 포함됨).
- `pull_results`에서 "colab-outbox 없음" → 노트북의 아웃박스 푸시 셀 상태(토큰 유무) 확인.
