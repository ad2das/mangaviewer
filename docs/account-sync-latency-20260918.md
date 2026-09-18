# 구글 계정 기록 동기화 지연 제거 — 2026-09-18

계정 기록 동기화가 체감상 수 초 걸리던 원인을 제거했다. 로컬 변경은 이제 마지막 변경 후 150ms 안에 한 번의 Firestore 트랜잭션으로 반영된다.

## 원인

1. 모든 변경 뒤 `delay(1_200L)` 고정 디바운스가 있었다.
2. 세션 시작 시 legacy 마이그레이션이 동기화 경로를 막았다. 서버 문서 재조회 1회에 더해, 미해결 작품마다 회차 목록을 최대 10초 타임아웃으로 순차 조회했다.
3. 자기 쓰기가 스냅샷 리스너를 통해 되돌아와 트랜잭션을 한 번 더 실행했다.

## 변경

- `AccountSyncSession`: 고정 1.2초 지연을 트레일링 코얼레스로 교체했다. 마지막 wake 후 150ms(`COALESCE_QUIET_MS`) 동안 조용하면 동기화를 시작하고, 연속 편집이 이어져도 최대 600ms(`COALESCE_MAX_MS`)에서 시작한다.
- `FirebaseLibraryRemote`: legacy 마이그레이션을 `observe()` 시점의 백그라운드 작업으로 분리하고, 미해결 작품 회차 조회를 `async`로 병렬화했다. 카탈로그가 준비되면 wake를 보내 다음 교환에서 반영된다.
- 스냅샷 리스너가 `hasPendingWrites()`이거나 마지막으로 쓴 문자열과 같으면 wake를 보내지 않는다(에코 제거).

## 검증

- `.\gradlew.bat :app:testDebugUnitTest verifyArchitectureQuality :app:assembleDebug` — 성공. 전체 259개 테스트, 실패 0.
- `AccountSyncSessionTest` 5개 통과. 추가: `rapidLocalChangesCoalesceIntoOneExchange` — 3회 연속 변경이 1회 교환으로 합쳐짐.
- 에뮬레이터(emulator-5558)에 디버그 APK 설치·실행, 크래시 버퍼 비어 있음.
