# UI/UX·성능 극한 개선 — 2026-09-18

라이브러리와 뷰어의 화면 전환·오버레이·로딩 상태를 모션 토큰 기반으로 다시 만들고, 카드 렌더링과 저장 목록 초기 상태의 성능 문제를 정리했다. 시작 시 디스크를 훑던 크래시 로그 검사는 백그라운드로 옮겼다. 모든 변경은 기존 동작과 접근성 설명을 유지한다.

## 모션 기반

- `LibraryTheme.kt`에 `LibraryMotion` 토큰을 추가했다. Fast 140ms, Medium 240ms, Slow 360ms와 EaseOut/EaseInOut 커브를 공용으로 쓴다. 브러시는 상수로 캐시하고, `rememberLibraryColors()`가 색 세트를 안정적으로 기억한다.
- `LibraryScreen.kt`는 셸(Shell/Genre/Detail) 전환을 `AnimatedContent`의 `layerTransition`으로, 탭 전환을 `tabTransition`으로 처리한다. 하단 내비게이션 아이콘은 스프링 스케일로 반응한다.
- 여섯 개 오버레이(검색, 설정, 소스, 장르 등)를 모두 `AnimatedVisibility`로 통일했다.

## 라이브러리 화면

- 새 파일 `LoadingSkeleton.kt`: `rememberShimmerOpacity`, `SkeletonBlock`과 `HomeSkeleton`, `SavedSkeleton`, `CatalogGridSkeleton`, `DetailEpisodeSkeleton`을 제공한다.
- `LibraryState.kt`에 `savedLoaded` 플래그를 추가해 스냅샷 로드 전 빈 상태가 "저장된 작품 없음"으로 잘못 보이던 문제를 없앴다. 첫 스냅샷에서 `LibraryStateObservers.kt`가 플래그를 세운다.
- 홈 히어로 행의 `indexOf` 기반 O(n²) 순위 계산을 `itemsIndexed`로 교체했다. 카드마다 걸려 있던 `CompositingStrategy.Offscreen`을 제거해 목록 스크롤 비용을 줄였다.
- `SavedLibraryScreen`, `SearchScreen`, `SeriesDetailScreen`의 필터·개수·소스 라벨·오프라인 ID·즐겨찾기 ID를 `remember`로 유지한다. 목록에는 `animateItem()`을 적용했다.
- 실패 상태에 `RetryHome`/`RetryGenres`/`RetryDetail` 재시도 경로를 추가했다. `LibraryViewModel.retry(intent)`가 분기하며 구조 검사 복잡도 제한 안에 들어간다.

## 뷰어

- `ViewerChromeController.kt`: 상단·하단 바를 즉시 GONE 시키던 것을 슬라이드+페이드(표시 180ms, 숨김 140ms)로 바꿨다. 표시 시 3.5초 자동 숨김 타이머를 걸고, 설정 패널이 열리면 `setAutoHidePaused(true)`로 멈춘다. 표시 순간 `CLOCK_TICK` 햅틱이 울린다. 강조색 `GradientDrawable`은 `IdentityHashMap`으로 캐시하고, 같은 문자열·진행값은 다시 쓰지 않는다.
- `ViewerLoadingOverlay.kt`: 로딩 오버레이가 페이드 인/아웃하며, 완료 플래그로 늦은 콜백이 되살리지 못하게 했다.
- `ViewerScreenUi.kt`: 실패 카드 페이드/슬라이드, 더블탭 햅틱, `CARD_ANIMATION_MS` 정리. `ViewerReaderSettingsPanel.kt`는 슬라이드/페이드로 열고 닫는다.

## 시작 성능

- `CrashLog.kt`: `install()`이 시작 시 로그 디렉터리를 훑던 동작을 제거했다. `scanLastExit()`와 `pendingReport` StateFlow로 분리하고, `ViewerApplication`이 IO 디스패처에서 스캔을 시작하며 `MainActivity`가 결과를 수집한다.
- `AppUpdateDialog.kt`: 다운로드 진행률 표시를 추가했다.

## 검증

- `.\gradlew.bat :app:testDebugUnitTest` — 45개 스위트, 258개 테스트, 실패 0, 오류 0.
- `.\gradlew.bat verifyArchitectureQuality` — 409개 파일 통과 (파일 800줄, 클래스 400줄, 함수 60줄, 복잡도 15 제한).
- `.\gradlew.bat :app:assembleDebug` — 성공.
- 에뮬레이터(emulator-5556)에서 상세 화면이 회차 목록(219화~216화), 탭, 표지와 함께 표시됨을 uiautomator 덤프로 확인했다. 뷰어를 열어 `viewer-frame-presented` 노드를 확인하고, 화면 탭으로 크롬(뒤로/제목/양면/몰입/설정 + 1 / 149/책갈피/이전/회차/다음)이 나타났다가 약 5초 뒤 자동 숨김되는 것을 덤프 크기 변화(1,772 → 6,467 → 1,772바이트)로 확인했다. logcat crash 버퍼와 최근 로그에 FATAL이 없다.

## 산출물

- 수정 APK: `app/build/outputs/apk/debug/app-debug.apk`, 46,757,806바이트.
- SHA-256: `39496E35F4E6FF4DB06B8CFCA957B778377F4B710483F228ce8046410CD40F92`.

아래 경로는 로컬 검증 증거이며 Git 저장소에 포함하지 않는다.

- 화면 덤프: `.tmp-verify-home.xml`, `.tmp-verify-home2.xml`, `.tmp-verify-saved.xml`, `.tmp-verify-now.xml`(상세)
- 뷰어 덤프: `.tmp-verify-viewer.xml`, `.tmp-verify-chrome-on.xml`, `.tmp-verify-chrome-off.xml`

## 성능 실측 (2026-09-18 추가)

기존 검증용 에뮬레이터(PaseoNotifyQA, 5556)는 `-gpu` 없이 떠서 SwiftShader 소프트웨어 렌더링이었다. 그 환경에서는 시스템 Settings 앱도 스크롤 jank가 23.6%였으므로, 프로젝트 AVD(`MangaViewerApi35`)를 호스트 GPU(GTX 1060)로 5558 포트에 띄워 다시 측정했다. 빌드는 R8 release(`assembleRelease`)를 사용했다.

| 지표 | 결과 (release, host GPU) |
|---|---|
| 콜드 스타트 | 550ms 중앙값 (570/539/548/583/550ms) |
| 상세 회차 목록 스크롤 jank | 0.32~2.92%, 중앙값 약 1.6% (11회 샘플) |
| 대조군 Settings 스크롤 jank | 9.82% (동일 기기·절차) |
| UI 스레드 | p50 0.46ms / p99 1.13ms |
| RenderThread 대기 | p50 0.09ms |
| draw issue | p50 0.69ms / p99 1.82ms |
| GPU swap→완료 | p50 14.94ms (vsync 페이싱) |
| 메모리 | PSS 111,567KB, RSS 287,480KB |

남은 jank는 전부 `Slow issue draw commands`이고 UI 스레드 지연은 0이다. 단일 스와이프 버스트(35~38프레임)에서 jank 프레임은 1~2개이며 데드라인 초과가 0.87~3.64ms에 불과하다. 12회 빠른 스와이프(1.92%)와 3회 긴 드래그(1.73%)의 jank율이 비슷해 입력 주입 횟수와 무관함을 확인했다. 즉 잔여 jank는 앱 작업량이 아니라 에뮬레이터 GL 번역기 파이프라인 지터다. 상세 회차 카드의 `animateItem`은 스크롤 중 fade 레이어를 만들지 않도록 `fadeInSpec = null, fadeOutSpec = null`로 조정했다.

검증 명령: `.\gradlew.bat :app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` — 통과.
