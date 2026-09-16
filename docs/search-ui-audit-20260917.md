# 검색·UI 안정성 검증 — 2026-09-17

NTK에서 `생존`으로 검색했을 때 `생존게임`(`/manhwa/3648`)이 빠지던 문제를 수정했다. WFWF와 굿툰의 후속 검색 페이지 누락도 수정했다. 검색 결과는 상세 화면과 분리해서 유지하고, 요청 실패·빈 결과·진행 중 상태를 구분한다.

이 문서는 테스트한 범위와 남은 한계를 함께 기록한다. 외부 사이트의 모든 검색어·모든 시점에서 무결함이라고 판정한 것은 아니다.

## 실사이트 대조

Android API 35 에뮬레이터에서 앱의 실제 `AppGraph`와 네트워크 경로로 요청했다. HTML 원문, 요청 URL, 페이지 커서, 작품 ID, 소요 시간, 오류를 저장했다. 앱의 Jsoup 파서와 별개인 Python/BeautifulSoup 파서로 원문을 다시 읽고, 사이트에 따로 요청한 목록과도 작품 ID 집합을 비교했다. 숫자만 같아도 통과시키지 않았다.

| 사이트 | 검색 조건 | 앱 고유 작품 수 | 결과 |
|---|---|---:|---|
| NTK | 생존, 전체 | 45 | 원문·별도 조회 모두 ID 차이 0; 생존게임 포함 |
| NTK | 생존, 만화 | 4 | 생존게임 포함, 웹툰 혼입 없음; UI에서 확인 |
| NTK | 사랑, 전체 | 1,180 | 전체 26개 원본 페이지, ID 차이 0 |
| NTK | 비가, 작가 | 250 | 끝 페이지까지 ID 차이 0 |
| WFWF | 생존 | 43 | 2페이지, ID 차이 0 |
| WFWF | 사랑 | 676 | 19페이지, ID 차이 0 |
| 뉴엑스툰 | 생존 | 383 | 17페이지, 원문·별도 조회 ID 차이 0 |
| 뉴엑스툰 | 비가, 사이트 통합 검색 | 15 | 재시도 후 원문·별도 조회 ID 차이 0 |
| 뉴엑스툰 | 사랑 | 2,680 | 117페이지 완료; 사이트 표시 총수와 일치, 원문 ID 차이 0 |
| 굿툰 | 생존 | 32 | 원문·별도 조회 ID 차이 0 |
| 굿툰 | 비가, 사이트 통합 검색 | 12 | 원문·별도 조회 ID 차이 0 |
| 굿툰 | 사랑 | 280 | 보완 조회 후 원문 및 별도 6회 수집의 합집합과 ID 차이 0 |

네 사이트 모두 `화산귀환`, 앞뒤 공백, 존재하지 않는 검색어도 검사했다. NTK/WFWF에는 `원피스` 만화 필터와 `화산귀환` 웹툰 필터를 추가했다. WFWF는 제목 검색만, 뉴엑스툰·굿툰은 사이트 통합 검색으로 안내한다. 지원하지 않는 작가 전용 필터를 지원하는 것처럼 표시하지 않는다.

### 요청 제한과 변동하는 페이지 순서의 복구

- 뉴엑스툰: 이전 실행의 시간 초과·HTTP 429 실패를 보존한 채 수정 후 다시 검사했다. 최종 `사랑` 조회는 117페이지를 완료했으며 서버 표시 총수 2,680과 일치했다. 12개 페이지에서 서버가 지정한 25~27초 대기를 지킨 뒤 같은 페이지로 복귀했다. 성공/실패 응답을 모두 보존했고 별도 BeautifulSoup 파서의 원문 ID 집합과 앱 결과가 일치했다. 이번 대량 조회는 같은 원문 및 사이트 총수로 검증했으며, 별도 전체 재조회까지 했다고 주장하지 않는다.
- 굿툰: 동일 검색의 페이지 경계가 계속 바뀌어 중복과 누락이 생겼다. 별도 직접 요청으로 여섯 번 전체 페이지를 수집한 합집합은 280개였다. 수정 앱의 첫 번째 다섯 페이지에서는 267개가 모였고, 자동 보완 조회로 빠진 13개를 더 찾아 280개를 완성했다. 앱·원문·별도 수집 합집합의 ID 차이가 모두 0이다.
- 서버의 모든 미래 응답을 보장할 수는 없다. 계속 결과가 변하거나 요청 제한이 길어지는 경우에도 확인하지 못한 상태를 결과 0개 또는 전체 완료로 기록하지 않고, 기존 결과와 재시도 위치를 유지한다.

## 변경 사항

- NTK: 웹툰·만화를 별도로 검색하고 각 페이지 커서를 유지한다. 소설 등이 앞 페이지를 채워 만화가 밀려나던 문제와 첫 페이지에서 끝나던 문제를 함께 해결했다.
- WFWF: 실제 통합 검색 `/sh`를 사용하고 `pg`를 따라간다. 만화 검색을 전체 카탈로그 스캔에 의존하지 않는다. 중간 페이지를 종류 필터로 모두 제외해도 다음 페이지를 유지한다.
- 굿툰: `q`를 유지하면서 `pg`를 따라간다. 중복된 페이지 경계 때문에 카드 슬롯 수보다 고유 작품 수가 적으면 보완 조회를 수행한다. 네 번째 전체 확인까지 해도 차이가 남으면 기존 결과와 커서를 유지하고 재시도 안내를 표시한다. 보완 요청 실패·취소는 원자적으로 처리해 아직 표시하지 않은 작품을 수집 완료로 계산하지 않는다.
- 뉴엑스툰: 검색어 2~100자와 실제 검색 HTML을 검증한다. 문서 요청을 직렬화하고 요청 사이에 2.5초 간격을 둔다. 일시적인 네트워크 오류는 최대 세 번 시도한다. HTTP 429의 `Retry-After`는 초·HTTP 날짜 형식을 모두 처리하며, 긴 대기를 3초로 줄이던 오류를 제거했다. 최근 유효 문서는 제한된 캐시에 재사용하고 점검 HTML은 캐시하지 않는다.
- 공통: 작품 ID로 중복을 제거하고 각 시도에 30초 제한을 둔다. 요청 제한·시간 초과는 기존 작품과 정확한 커서를 보존한 채 최대 두 번 자동 재시도하며, 대기 시간을 표시한다. 새 검색은 대기와 이전 요청을 취소한다. 대기 표시 갱신은 작품 메타데이터 요청을 다시 쌓지 않는다. 페이지 반복·무진행 조회도 제한한다.
- UI: 선택 상태가 보이는 필터, 실제 작품 종류 배지, 로딩 자리 표시, 별도 오류·빈 결과 화면, 48dp 지우기 영역, 키보드 처리, 상세 화면·탭 이동 후 검색 결과와 스크롤 유지, 라이트/다크 테마 확인.
- 큰 글씨: 130% 글씨에서 발견한 검색 버튼 글자 잘림을 수정해 버튼이 글자 너비에 맞게 늘어나도록 했다.

## 자동 검사

| 검사 | 결과 |
|---|---|
| app 단위 테스트 | 246 통과 |
| source-ntk 단위 테스트 | 187 통과 |
| source-wfwf 단위 테스트 | 86 통과 |
| source-newxtoon 단위 테스트 | 34 통과 |
| source-goodtoon 단위 테스트 | 65 통과 |
| UI 기기 테스트 | 3개 통과: 네 사이트 검색, 입력 오류/빈 결과/회복, NTK 필터·상세·탭·지우기 |
| 뷰어 기기 테스트 | 24개 통과: GPU 자원 해제·재연결, 드래그, 확대/축소, 설정·백그라운드 복귀, 오프라인 렌더링 |
| 130% 글씨 추가 검증 | 버튼 수정 후 기기 검사 1개 통과; 라이트/다크 화면에서 글자 잘림 해소 확인 |
| viewer / engine-v2 단위 테스트 | 22 / 186 통과 |
| APK 빌드, Android lint, 구조 검사 | 모두 통과; 검사 제외나 규칙 완화 없음 |

`verifyArchitectureQuality`에서 남았던 14개 위반을 모두 수정했다. 화면 구성·설정, Surface 연결, 핀치 입력, 쿠키 저장, 선택/설정 동작을 각각 담당하는 타입과 함수로 나눴다. 브라우저 생성은 메인 스레드를 확인하는 공통 팩터리 한 곳으로 모았다. 브라우저 상태 확인의 반복 Handler 작업은 취소 가능한 코루틴으로 바꿨으며, 종료 후 예약된 캡처도 취소한다. 원본의 검사 규칙은 변경하지 않았다.

관련 단위 테스트 826개가 모두 통과했다. 이전 14개 실패 기록과 원본 16개 실패 기록은 변경 경위를 확인할 수 있도록 보존한다. 최종 실행은 구조 검사를 포함한 lint와 APK 빌드까지 통과했다.

## 재현 및 증거

아래 `artifacts/` 경로는 검증 환경에 보관한 로컬 증거이며 Git 저장소에는 포함하지 않는다. APK는 문서 하단의 고파일 링크에서 내려받을 수 있다.

- [수정 후 최종 ID 대조 결과](../artifacts/search-audit-20260917/comparison-repaired.json)
- [굿툰 별도 6회 수집과 대조](../artifacts/goodtoon-reconciliation-comparison.json), [굿툰 수정 후 기기 검사](../artifacts/goodtoon-repaired-live.log)
- [뉴엑스툰 117페이지 완료 검사](../artifacts/newxtoon-repaired-live.log), [뷰어 기기 검사 24개](../artifacts/followup-reader-device.log)
- [처음 수행한 전체 실사이트 검사](../artifacts/search-audit-final-device.log)
- [뉴엑스툰 간격을 둔 검사](../artifacts/newxtoon-native-paced.log), [비가 재시도](../artifacts/newxtoon-native-author-retry.log)
- [수정 후 기본 글씨 UI 검사](../artifacts/followup-search-ui-device.log), [수정 후 130% 글씨 검사](../artifacts/followup-largefont-device.log)
- [수정 후 단위 테스트 합계](../artifacts/followup-unit-summary.json), [전체 관련 모듈 빌드](../artifacts/search-final-build.log), [최종 앱 빌드·lint](../artifacts/search-release-checks.log)
- [수정 후 전체 검사](../artifacts/followup-full-checks.log), [최종 구조 검사·단위 테스트·lint·APK](../artifacts/followup-final-verification.log)
- [수정 전 구조 검사](../artifacts/architecture-current.log), [원본 구조 검사](../artifacts/architecture-baseline.log)
- [라이트 화면](../artifacts/followup-search-ui/ntk-survival-comic-light.png), [다크 화면](../artifacts/followup-search-ui/ntk-survival-comic-dark.png), [큰 글씨 라이트](../artifacts/followup-largefont-light.png), [큰 글씨 다크](../artifacts/followup-largefont-dark.png)

관련 단위 테스트:

```powershell
.\gradlew.bat :app:testDebugUnitTest :source-ntk:testDebugUnitTest :source-wfwf:test :source-newxtoon:test :source-goodtoon:test
```

실사이트 기기 검사는 `FourSourceSearchAuditDeviceTest`, 화면 검사는 `SearchUiRegressionDeviceTest`에 있다. 실사이트 검사는 외부 응답 상태에 따라 실패할 수 있으며 이를 실패로 기록한다. `searchAuditLabel`, `searchAuditCases`, `searchAuditPauseMillis` 인자로 보존 위치·검사 범위·요청 간격을 지정할 수 있다. 지정 범위는 각 실행의 `configuration.json`에 남는다.

독립 조회는 `tools/audit_search_reference.py`, 원문 및 별도 조회와의 비교는 `tools/compare_search_audit.py`를 사용한다. 비교 도구에 여러 `--native` 디렉터리를 주면 같은 사례에 대해 나중 실행을 사용하며, 실행하지 않은 사례는 이전 실패 기록까지 유지한다.

수정 APK: [MangaViewer-search-stability-20260917.apk — 고파일 다운로드](https://gofile.io/d/abKdPw9B), 46,166,758바이트. SHA-256: `8800141AAE1F325DC673127D24A9838162A06B8601E77FB864481DBAD58E30FF`.

굿툰 보완 수집까지 포함한 최종 대조는 다음과 같이 재현한다.

```powershell
python tools/compare_search_audit.py --native artifacts/search-audit-20260917/verified-native artifacts/search-audit-20260917/paced-native artifacts/search-audit-20260917/author-native artifacts/search-audit-20260917/repaired-native --reference artifacts/search-audit-20260917/reference --goodtoon-repair-captures artifacts/goodtoon-repair-probe --output artifacts/search-audit-20260917/comparison-repaired.json
```
