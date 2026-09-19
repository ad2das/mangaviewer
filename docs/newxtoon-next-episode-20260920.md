# Newxtoon next-episode pre-attach + clearance storm fix — 2026-09-20

## Problem (Kovak, 2026-09-19)

- 뉴엑스툰 로딩이 너무 느림.
- 스크롤 끝에 다다를 때쯤 다음화가 안 붙어 있음 — 로딩 없이 붙어 있어야 함.
- 현재화 자원경쟁 없이, 뷰어 스크롤 버벅거림 없이 구현.

## Changes

- `engine-v2` `EngineSessionRuntime`: reader 입력이 챕터 끝 6페이지 안으로 들어오면
  다음화 헤드 4페이지를 `NEXT_EPISODE` 우선순위로 프리페치한다
  (`BOUNDARY_APPROACH_PAGES = 6`, `NEXT_EPISODE_HEAD_PAGES = 4`). 현재화의
  `NEXT_IMAGE` 작업이 항상 앞서므로 현재화 디코드와 경쟁하지 않는다. 뷰어 진입·이동은
  앵커 에피소드를 요구하고, 실패한 에피소드는 3초 후 재시도한다.
- `NewxtoonClearance`: 한 번 성공한 solved 뷰를 20초간 재사용해 요청마다
  부수는 스톰을 없앴고, 실패한 챌린지 래더는 60초 쿨다운으로 후퇴한다. replay가
  `403 (cf-mitigated: challenge)`이면 persisted clearance를 stale 처리해 같은
  쿠키로 루프하지 않는다.
- `NewxtoonClearanceTransport`: solve 후에는 replay를 먼저 시도한다.
- 리플레이 브라우저를 `NewxtoonReplayView`, 챌린지 페이지를
  `NewxtoonChallengePage`로 분리해 `NewxtoonClearance`를 400줄 아키텍처 게이트
  안으로 되돌렸다.

## Device verification (emulator-5556)

- 콜드 회차 목록: `app_episode_catalog_cache_v1`, `app_home_catalog_cache_v1`,
  `files/newxtoon_documents` 삭제 후 상세 → 회차 탭 전환 +1.0초 시점에 7개 행과
  `제7화`(이어보기) 렌더 확인.
- 콜드 클리어런스 로그(스톰 없음):
  `replay view ready (no challenge page)` →
  `replay answered 403 ... mitigated=challenge` →
  `replay refused by the edge; clearance marked stale` →
  `solve: starting webview challenge attempt=1` 1회 → 목록 렌더.
- 다음화 붙이기: 뷰어 진입 직후 `NtkPageWork: page-start priority=NEXT_EPISODE` 55건,
  이후 `page-done cached=true`; 경계 화면에서 로딩 표시 없이 다음 페이지 콘텐츠가
  연속 렌더됨(스크린샷 확인).
- 뷰어 버벅거림: 뷰어는 자체 GL SurfaceView로 그려서 HWUI gfxinfo 카운터가 스크롤
  중 증가하지 않고, `dumpsys SurfaceFlinger --latency`도 이 에뮬레이터에서 빈 테이블을
  돌려준다(이전 성능 단계에서 문서화된 에뮬레이터 측정 한계). 빠른 스와이프 60회+
  동안 시각적 끊김은 없었다.

## Verification commands

- `.\gradlew.bat :app:testDebugUnitTest :source-newxtoon:test verifyArchitectureQuality :app:assembleDebug`
  → green (테스트 전체, 아키텍처 게이트 418파일)
- 콜드 목록: 캐시 삭제 후 `am start` → 카드 탭 → 회차 탭 +1.0s 스크린샷
- 프리페치 로그: `adb logcat | grep NtkPageWork`

## Commit

`9115e5355` — pushed to `origin/main`.
