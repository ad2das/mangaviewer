# Android NTK 뷰어 콜드 성능 최적화 보고서

작성 기준 시각: 2026-07-18 02:40 KST

## 판정 요약

**최종 판정은 `NOT QUALIFIED`이다.** 프로덕션 경로와 자동화·계측 기반 및 최종 benchmark APK 빌드는 완료했지만, 현재 연결 가능한 환경은 Android 15/API 35, 90 Hz의 `sdk_gphone64_x86_64` 에뮬레이터뿐이다. 요구된 실제 Android 기기에서 무작위 웹툰 10개와 만화 10개를 모두 실행한 정식 결과가 없으며, 보관된 1+1 진단 실행도 실패했다. 따라서 “20개 모두 진짜 콜드 상태에서 즉시 표시되고 무중단 스크롤”을 달성했다고 주장할 수 없다.

| 구분 | 상태 | 근거 |
| --- | --- | --- |
| 프로덕션 코드 구현 | 완료 | 클릭 경계, strict rolling, 요청·화면 identity, generation별 retirement, HWUI commit 증명, 메모리·종료 처리 구현 |
| 단위 회귀 테스트 | PASS | 147 suite, 1,093 test, failure/error/skip 0 |
| 선택 API 35 계측 테스트 | PASS | strict rolling 시작·종료 1 test, 0.341 s |
| benchmark/release 빌드 | PASS | 최종 source의 release 기반 R8·resource shrink APK와 Macrobenchmark APK 생성 및 SHA-256 기록 |
| 에뮬레이터 진단 | FINAL APK FAIL (0/2) | fixed reproduction seed `4637290068602280461`; 웹툰/만화 first actual 7,065.3361/7,574.3809 ms, coverage/frame 실패. 에뮬레이터·1+1·10초 SLA이므로 진단 전용 |
| 실제 기기 무작위 10+10 | NOT RUN | 실제 기기 없음 |
| 최종 자격 | **NOT QUALIFIED** | 물리 기기, 정확히 10+10, 2,000 ms SLA, 20/20 PASS가 모두 필요 |

단위·계측 테스트는 요청 소유권과 생명주기 회귀를 검증한다. 이미지 콜드 SLA, 빈 영역, jank, 메모리, 서버 콘텐츠 유효성에 대한 20작품 합격 증거를 대신하지 않는다.

## 확인한 프로젝트 구조

### UI와 뷰어

- Compose가 아니다. 작품·회차 UI는 XML과 `RecyclerView` 기반이다. 회차 화면 진입과 클릭 경계는 [EpisodeActivity.java](../app/src/main/java/ml/melun/mangaview/activity/EpisodeActivity.java#L804), NTK 경량 레이아웃은 [activity_episode_ntk.xml](../app/src/main/res/layout/activity_episode_ntk.xml)에 있다.
- 실제 웹툰·만화 이미지는 [ReaderV2Activity.kt](../app/src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt#L72)가 [ReaderSurfaceView.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt#L855)를 생성해 그린다. `LazyColumn`, `AsyncImage`, Compose recomposition 문제는 이 경로에 해당하지 않는다.
- 웹툰과 만화 모두 같은 rolling surface 파이프라인을 사용한다. 만화의 auto-cut은 하나의 원본 source index가 한두 개의 display index로 매핑되며, 요청·검증은 display index가 아니라 원본 source identity를 유지한다. 관련 매핑은 [ReaderSession.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSession.kt#L986)와 [ReaderSession.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSession.kt#L12131)에 있다.

### 이미지 로더와 캐시

- 앱 전체의 기존 이미지 로더는 Glide 4.14.0이다. [CustomGlideModule.java](../app/src/main/java/ml/melun/mangaview/glide/CustomGlideModule.java#L22)는 source worker 4~8개와 192 MiB disk cache를 설정한다.
- 그러나 **정확한 NTK 콜드 뷰어 경로는 Glide 요청을 첫 이미지 소유자로 쓰지 않는다.** [ReaderImageCache.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt#L167), strict source transport, `ReaderSession`이 encoded source 파일·proof·decode·surface 전달을 소유한다. 기존 `ViewerWarmupManager`는 다른/레거시 경로를 위해 남아 있지만 exact NTK committed-click 경로는 prepared key나 준비된 bitmap을 조회하지 않는다.
- strict reader cache 디렉터리는 `reader_image_cache_v1`이고 목표/상한은 384/512 MiB이다. 캐시 key와 published body는 manifest, canonical asset, response identity, strong validator, encoded SHA proof로 결속한다. 콜드 판정은 캐시가 있어도 빨라지는지를 보지 않고 캐시가 0인 시작 상태만 합격 대상으로 삼는다.
- 프로세스 시작 직후 [ViewerColdStateSnapshot.java](../app/src/main/java/ml/melun/mangaview/runtime/ViewerColdStateSnapshot.java#L43)가 Glide/reader disk cache, structured content cache, memory entry, active request/decode, HTTP client 생성 여부를 fail-closed 방식으로 기록한다.

### 네트워크와 URL 생성

- Retrofit, Picasso, Coil은 사용하지 않는다. 네트워크 주 계층은 OkHttp 4.12.0 기반 [CustomHttpClient.java](../app/src/main/java/ml/melun/mangaview/mangaview/CustomHttpClient.java#L116)이며, 일부 NTK 경로에는 별도 QUIC fetcher가 존재한다.
- 일반 HTTP dispatcher는 8/host 4, 이미지 shared dispatcher는 16/host 8이다. strict rolling source는 별도의 bounded physical lane 8개를 갖지만 콜드 첫 wave에서는 실제 source 0과 1만 admit한다. 따라서 dispatcher 폭이 작품 전체 다운로드로 이어지지 않는다.
- OkHttp 기본 HTTP/2 + HTTP/1.1 협상을 유지하며 이미지 client는 connection pool을 공유한다. 같은 실행 안에서 이미 열린 소켓의 정상적인 재사용은 허용하지만, 테스트 전에 DNS/TLS/socket을 준비하지 않는다.
- 이미지 URL·header의 최종 정규화는 [Utils.java](../app/src/main/java/ml/melun/mangaview/Utils.java#L3236), authoritative manifest와 signed exact image API는 [NtkStrictEpisodeDiscoveryCoordinator.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt#L14), [NtkManifestAuthorityFactory.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkManifestAuthorityFactory.kt), [NtkStripContracts.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStripContracts.kt)에 있다.
- 회차 목록은 `EpisodeActivity`에서 불러오되, exact 이미지 manifest·ACK·image API는 committed episode click 뒤에만 시작한다. 문서 요청과 ACK prerequisite는 서로 겹치고, ACK proof → cookie import → unsigned exact request → renderer quiescence → signature → exact request의 identity 순서는 유지한다.

### 디코딩과 렌더링

- bitmap bounds를 먼저 읽고 실제 viewer 폭에 맞춰 power-of-two downsample을 적용한다. 관련 로직은 [ReaderImageCache.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt#L10891)와 [ReaderImageCache.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt#L11862)에 있다.
- 긴 원본은 파일/region/tile 경로로 처리하고, scroll 중 decode parallelism은 2, idle은 4로 제한한다. 화면에 전달된 bitmap/tile은 viewport window 밖에서 회수하고 `onTrimMemory`/`onLowMemory`에 대응한다.
- 첫 이미지 완료는 network response나 decode callback이 아니라 `ViewTreeObserver.registerFrameCommitCallback`을 통과한 hardware-accelerated HWUI frame으로 판정한다. 이는 SurfaceFlinger/compositor present 자체를 증명한다는 뜻이 아니다. [ReaderSurfaceView.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt#L5782)와 [ReaderV2Activity.kt](../app/src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt#L4878)가 callback 등록·관측 provenance와 generation, episode, manifest, source index, structure epoch, frame token, drawn/committed version, viewport coverage를 모두 확인한다. API 29 미만처럼 이 callback provenance를 만들 수 없는 환경은 strict actual event를 게시하지 않고 자격 판정에서 fail-closed한다.

## 프로덕션 변경과 개선 이유

| 영역 | 변경 전 위험 | 현재 동작 | 개선 이유 |
| --- | --- | --- | --- |
| 사용자 진입 경계 | press/화면 준비 단계에서 ACK·manifest·이미지 작업이 시작될 수 있음 | `ACTION_DOWN`은 metadata log만 남기고, `ACTION_UP` committed click에서 telemetry와 discovery를 연 뒤 즉시 Activity 전환 | 테스트 전 warm-up과 hidden preload를 구조적으로 차단 |
| Activity 전환 | prepared store/key 또는 첫 bitmap 준비 여부가 전환을 지연시킬 수 있음 | [openColdExactNtkViewer](../app/src/main/java/ml/melun/mangaview/Utils.java#L321)가 prepared key 없이 `ReaderV2Activity`를 즉시 연다 | 이미지 준비를 로딩 화면으로 숨기지 않음 |
| ACK guard fetch | versioned guard JS/WASM마다 legacy API의 영구 redirect를 먼저 거쳐 cold RTT가 추가됨 | committed click이 만든 격리 ACK flight 안에서 동일 origin·version의 closed-allowlist static target을 먼저 요청하고, 응답이 유효하지 않을 때만 legacy redirect discovery로 fail-closed fallback | 이미지 사전 요청 없이 proof 준비의 불필요한 redirect 왕복을 제거해 뒤따르는 signed API와 source 0/1 admission을 앞당길 수 있음 |
| trusted ACK 쿠키 | trusted challenge 뒤 confirmation 응답의 다른 `ad_ack`가 challenge 권한 쿠키를 덮어써 웹툰 exact API가 403을 반환 | confirmation transcript·키 바인딩 검증은 유지하되 viewer에 내보내는 쿠키와 만료는 `/api/ad/challenge` 발급본으로 고정하고 verifier가 confirmation 발급본을 거부 | 배포 웹 클라이언트의 trusted 분기와 동일한 권한을 사용하면서 로컬 proof는 fail-closed로 유지. 동일 작품에서 exact API 403→200 확인 |
| 웹툰 exact wire | 만화용 `requestKeyId`와 `x-ntk-*` header가 웹툰의 nv 기반 계약에도 섞일 수 있음 | 웹툰 body를 `workId, episodeId, token, nonce, proof` 다섯 필드와 `x-nv-session`으로 제한하고, 만화만 request-key 서명 wire 사용 | 서버 계약 불일치와 불필요한 403을 방지하며 정적 wire-contract test로 고정 |
| discovery HTTP 소유권 | document/exact image API의 물리 `Call`이 논리 flight와 분리되어 Activity 종료 뒤 응답·cookie를 게시할 수 있음 | [NtkStrictCallRegistry](../app/src/main/java/ml/melun/mangaview/mangaview/CustomHttpClient.java#L217)가 path와 viewer generation에 모든 strict OkHttp call을 결속한다. exact API는 registry 없는 호출을 거부하고 retirement는 admission을 닫은 뒤 active call을 원자적으로 detach한다 | 늦은 call 등록과 이전 generation 응답·cookie의 재게시를 차단 |
| discovery retirement | lifecycle callback이 publication actor나 Binder 취소를 기다리거나, retirement와 cookie merge가 경합할 수 있음 | [NtkStrictDiscoveryRetirementFence.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictDiscoveryRetirementFence.kt#L44)가 state/publication lock을 분리한다. retirement는 admission·local ACK terminal state를 동기적으로 닫고, 실제 `Call.cancel()`은 전용 single-thread dispatcher에서 수행하며 AIDL `cancel`은 `oneway`다 | 메인 스레드 retirement를 actor join/network/Binder에서 분리하면서 취소 이후 신규 작업을 fail-closed 처리 |
| viewer generation 선형화 | 새 viewer가 열릴 때 이전 generation의 짧은 cookie commit과 session 설치가 교차할 수 있음 | [ViewerTelemetry.viewerOpen](../app/src/main/java/ml/melun/mangaview/runtime/ViewerTelemetry.java#L53)을 동기화하고 이전 coordinator owner retirement 후 새 generation을 설치한다. cookie-map merge는 retirement와 같은 짧은 state fence 안에서만 commit한다 | 한 시점에 한 viewer generation만 전역 cookie/session publication 권한을 가짐 |
| 성공 flight 수명 | exact manifest 설치 직후 flight owner를 제거하면 같은 path 재진입이 이전 source lifetime과 겹치고 stale callback이 새 authority로 보일 수 있음 | 최종 `discovery_complete` ownership 검사 뒤에만 `completed=true`로 전환하고, 성공 flight는 Activity의 명시적 generation retirement까지 map에 유지한다. launch seal은 discovery generation과 proof digest까지 비교한다 | 성공 응답의 소유권을 source 종료까지 보존하고 같은 path의 이전/새 generation을 구분 |
| source/quarantine 세대 | path-only registry와 즉시 삭제가 같은 회차의 빠른 재진입에서 old close callback과 new session을 충돌시킬 수 있음 | exact source는 `(path, discoveryGeneration)`, quarantine은 `(path, discoveryGeneration, sessionId)` key를 사용한다. 교체된 entry는 lease-keyed `retiredEntries` tombstone으로 이동하고 close callback은 정확한 lease/generation만 해제한다 | 이전 generation drain과 새 generation source가 공존해도 잘못된 파일·bitmap·callback 적용을 차단 |
| document plan 예약 | path mutation lock 안에서 plan binding, executor prestart, 전체 source session 생성이 수행되어 lifecycle retirement가 긴 construction을 기다릴 수 있음 | [reserveDocumentPlan](../app/src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt#L453)은 첫 lock에서 짧은 spec만 캡처하고 bootstrap/session을 밖에서 만든 뒤, 마지막 lock에서 identity/evidence를 재검증해 pointer만 설치한다 | path lock의 임계 구간을 줄여 viewer 이탈·재진입이 thread/session construction에 막히지 않음 |
| executor 생성 실패 | 여러 strict lane 중간에서 prestart/constructor가 실패하면 앞서 만든 thread와 executor가 남을 수 있음 | [buildStrictBootstrapResources](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt#L80)가 각 resource를 즉시 cleanup holder에 등록하고 부분 실패를 역순 종료한다. session constructor 실패도 `abortConstructionFailure()`로 adopted resource를 정리한다 | 예외 경로의 daemon/thread/request owner 누수와 반복 실행 누적을 방지 |
| 첫 이미지 경쟁 | viewport 밖 요청이 첫 source와 경쟁 가능 | 첫 physical wave는 source 0/1만 admit | 첫 source network/decode 우선권 보장 |
| 스크롤 프리페치 | 고정/전체 fan-out 또는 반복 commit마다 request churn 가능 | 첫 actual HWUI commit 뒤 방향 기준 앞 3, 뒤 1의 bounded source window로 전환 | 흰 영역을 줄이면서 전체 작품 선다운로드 방지 |
| 방향 전환 | viewport와 commit producer 순서가 뒤섞이거나 같은 demand가 epoch를 증가시킬 수 있음 | [StrictRollingControlMailbox](../app/src/main/java/ml/melun/mangaview/reader/ReaderPipelinePolicy.kt#L154)가 event-time 순서를 직렬화하고 window는 latest-only, physical proof는 sticky로 보존. 같은 payload는 idempotent | stale callback, 취소 폭증, 무한 재요청 방지 |
| 역방향 디코딩 | viewport가 움직일 때 전달 bitmap을 즉시 hard-evict해 같은 원본을 왕복 중 반복 디코딩하고, 이미 window 밖인 작업도 decode 후 폐기 | access-order LRU를 정상 bitmap byte budget까지 보존하고 두 cold decode 진입점에서 bounded numeric window를 decode 전에 재검사. 완료 시점 race는 post guard가 계속 fail-closed 처리 | 순·역방향 재사용을 늘리고 불필요한 tile 할당을 줄이되 메모리 상한과 stale identity 검증 유지 |
| 잘못된 이미지 바인딩 | display index 재사용이나 늦은 callback이 다른 페이지에 적용될 수 있음 | immutable launch seal과 episode/manifest/canonical asset/source digest를 callback 적용 전에 재검증 | 다른 작품·페이지의 순간 표시를 fail-closed 처리 |
| actual 판정 | decode 완료나 root draw가 첫 이미지 완료로 오인될 수 있음 | API 29+ 등록·관측된 HWUI frame-commit callback, API 35 traversal proof, 완전 viewport coverage를 모두 요구하고 API<29 provenance 부재는 fail-closed | placeholder/빈 root commit을 PASS로 세지 않으며 compositor present로 과장하지 않음 |
| foreground commit | pause·회전·백그라운드 중 늦게 commit된 buffer가 복귀 화면의 actual proof로 남을 수 있음 | focus 획득 때만 foreground commit을 arm하고 focus 상실/pause 때 semantics와 이전 committed proof를 폐기 | background frame의 오인과 검정 resume 증거를 차단하고 복귀 후 새 실제 image commit만 인정 |
| page table identity | rolling `setPageCount`의 structure epoch가 0이면 실제 bitmap frame도 reject될 수 있음 | page count 설정·증감 때 traversal structure proof를 reset/extend | 실제 첫 frame의 identity-valid 판정 가능 |
| 디코딩 비용 | 원본 전체 크기 decode와 main thread 전달 비용 | viewport 폭 downsample, 긴 이미지 tile/region, bounded decode executor | peak bitmap과 GC, 긴 decode를 줄임 |
| 메모리 생명주기 | 화면 이탈 후 far-tail 작업·bitmap 참조가 남을 수 있음 | cancel/drain, viewport 밖 bitmap 회수, memory pressure trim, close 후 operation 0 증명 | OOM과 반복 실행 누적 방지 |
| strict source 종료 | 두 physical lane이 quarantine 디렉터리를 동시에 만들 때 TOCTOU로 lease가 남아 close barrier가 멈출 수 있음 | 동시 `mkdirs()` 재관찰, pre-spool/reject 포함 모든 실패에서 lease 종료, registry active operation 0과 release 성공 뒤에만 Closed commit | 화면 이탈·실패 후 요청/파일 owner 누수 방지 |
| 관측 | 평균 FPS 또는 network 완료만으로 정상 판단할 위험 | trace, JankStats, request/decode/bitmap counters, coverage, traversal, network connection, close drain 이벤트를 한 generation에 결속 | 작품별 실패를 재현 가능하게 판정 |
| trace 산출물 수명 | AndroidX의 고정 원격 `trace_output.pb`가 사례 종료 뒤 남으면 20회 실행 중 `/data`를 소진할 수 있음 | host artifact를 별도 case 경로에 보존한 뒤 exact output-path writer만 종료하고 고정 파일을 전·후 case에 제거한다. 광역 `killall`/`pkill`은 사용하지 않으며 정리 실패도 case FAIL이다 | 장시간 qualification의 저장공간 누적과 다음 case 오염을 방지 |

주요 상수와 admission 정책은 [ReaderPipelinePolicy.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderPipelinePolicy.kt#L261), 첫 두 source의 불변 시작 proof는 [NtkStrictSourceSession.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt#L158), strict source 시작은 [ReaderSession.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSession.kt#L892)에 있다.

## 금지 우회가 들어가지 않은 경계

1. [EpisodeActivity.java](../app/src/main/java/ml/melun/mangaview/activity/EpisodeActivity.java#L1318)의 `warmPressedNtkEpisode`는 network, ACK, manifest, image, decode, EGL 작업을 하지 않는다.
2. 동일 파일의 `enterPressedNtkEpisode`가 최초 content-demand 경계다. `ViewerTelemetry.viewerOpen` → cold rolling discovery → 즉시 Activity open 순서이고 `prepared=false`를 기록한다.
3. 뷰어 진입 전 [warmupInitialViewerTargets](../app/src/main/java/ml/melun/mangaview/activity/EpisodeActivity.java#L939)는 NTK user-demand path가 없으면 즉시 반환한다. library/continue/search navigation도 metadata-only임을 architecture test로 고정했다.
4. NTK Activity의 initial draw gate timeout은 0이다. 첫 이미지가 준비될 때까지 화면 전환이나 입력을 붙잡지 않는다. 이 때문에 진단 실패 시 검은/빈 화면 위험이 실제로 드러나며, 이를 placeholder로 PASS 처리하지 않는다.
5. cold rolling은 첫 actual 전 source 0/1만 허용한다. 전체 작품, 전체 bitmap, 무제한 요청은 허용하지 않는다. 첫 actual commit 뒤에만 방향성 3/1 window가 열린다.
6. Macrobenchmark는 `StartupMode.COLD`, iteration 1, `CompilationMode.Partial(..., warmupIterations = 0)`이다. Baseline Profile 사용 여부와 콘텐츠 캐시는 별개로 판정한다.
7. 같은 작품 warm reopen은 cold `measureRepeated` 밖에서 실행·기록한다. cold metric에 섞이지 않으며 최종 PASS는 cold 결과로만 계산한다.
8. 작품 ID, 테스트 계정, build type, test-run 감지로 이미지 경로·품질·prefetch 범위를 바꾸는 프로덕션 분기는 추가하지 않았다. 기존 strict architecture test는 main/reader/ACK source의 금지 call site를 정적 검사한다.
9. 테스트 script는 작품별로 app과 benchmark package에 `am force-stop` 및 `pm clear`를 실행한다. 앱 데이터가 없는 상태에서 동작하며 현재 NTK catalog 흐름에는 인증 token을 주입하지 않는다.
10. 이전 emulator-only/fixed-content/all-pages-before-input 자격 script는 [ntk_physical_qualification.ps1](../tools/ntk_physical_qualification.ps1)에서 즉시 실패하도록 퇴역했다. 정식 진입점은 [ntk_final_qualification.ps1](../tools/ntk_final_qualification.ps1) 하나다.

프로젝트에 레거시 warmup 코드와 native full-scene 준비 구현이 남아 있다는 사실 자체를 숨기지 않는다. 다만 exact NTK committed-click 경로는 이를 사용하지 않으며, canonical cold test가 pre-entry request/decode/content work를 발견하면 해당 작품을 실패시킨다.

## 계측 구현

### 앱 내부 trace와 frame 상태

- [ViewerTelemetry.java](../app/src/main/java/ml/melun/mangaview/runtime/ViewerTelemetry.java#L22): `ViewerOpen`, `ImageRequest`, `ImageDecode`, request/decode 수, bitmap bytes, response→HWUI frame commit, memory sampling, close drain.
- [ReaderSurfaceView.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt#L5797): `ViewerHwuiFrameCommit`과 hardware-accelerated HWUI frame-commit proof.
- [PerformanceMonitor.java](../app/src/main/java/ml/melun/mangaview/runtime/PerformanceMonitor.java#L23): JankStats frame 수, jank 수/비율, 최장 frame, 연속 jank와 작품/회차/방향/속도/viewport/request/decode/bitmap state.
- `ViewerItemBind`는 reader item/session 전달 구간을 trace한다. telemetry는 콘텐츠를 시작하거나 기다리게 하지 않고 이미 admit된 작업만 기록한다.

### Macrobenchmark

[macrobenchmark](../macrobenchmark)는 별도 `com.android.test` 모듈이다. [NtkColdViewerMacrobenchmark.kt](../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/NtkColdViewerMacrobenchmark.kt#L32)가 다음을 측정한다.

- `StartupTimingMetric`
- `FrameTimingMetric`
- `MemoryUsageMetric(Max/Last)`
- `TraceSectionMetric(ViewerOpen/ImageRequest/ImageDecode/ViewerHwuiFrameCommit)`
- [ViewerScrollTraceMetric.kt](../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/ViewerScrollTraceMetric.kt#L9)의 scroll 구간 process CPU, CPU %, main-thread 연속 running 최대값

UIAutomator는 launcher → 실제 작품 → 실제 회차 row → physical click을 사용한다. 고정 sleep으로 이미지 완료를 판정하지 않고 `actual:<episodePath>:<page>:<authority>` semantics가 HWUI commit 뒤 게시될 때까지 기다린다. 이후 중속 scroll, bottom까지 빠른 forward, top까지 reverse, 8회 방향 전환, 만화 추가 10회 page 전환, 회전, background/resume, viewer close를 실행한다.

### 랜덤 10+10 호스트 자동화

[ntk_cold_qualification.ps1](../tools/ntk_cold_qualification.ps1)는 다음을 수행한다.

- 정식 실행기는 암호학적 난수 seed만 생성한다. 기록된 양의 seed 재사용은 하위 실행기의 진단 모드에서만 허용되며 정식 PASS가 될 수 없다.
- host에서 `/api/works`와 `/api/manhwa-list`의 전체 pagination을 조회한다. 이미지 URL은 조회하지 않는다.
- `sha256(seed|type|workId)` lexical rank로 중복 없는 웹툰/만화 표본을 고른다. 작은 작품 필터나 재선정은 없다.
- 선택 작품의 실제 회차 metadata가 유효하지 않으면 임의 제외하지 않고 case 실패/중단 기록을 남긴다.
- 작품마다 app/benchmark force-stop과 pm clear, target UID process, service, running job/WorkManager, cache/client/request 상태를 확인한다.
- instrumentation, logcat, meminfo, gfxinfo, cpuinfo, activity/service/job dump, screenshot, Macrobenchmark JSON과 non-empty Perfetto trace를 case directory에 저장한다.
- first image, frame, coverage, identity, manifest traversal, duplicate/cancel/failure, request queue peak/balance, 방향 전환 cancel burst, PSS/bitmap/GC/retained growth, close drain을 fail-closed 평가한다.
- 한 작품이라도 실패하거나 artifact가 빠지면 non-zero로 종료한다.
- [ntk_cold_result.schema.json](../tools/ntk_cold_result.schema.json)과 [ntk_cold_report.ps1](../tools/ntk_cold_report.ps1)이 JSON shape와 cross-field invariant를 다시 계산한다.

정식 wrapper는 새 난수 seed, count 10+10, first-image SLA 2,000 ms, warm 결과 수집을 caller가 완화할 수 없게 고정한다. 에뮬레이터·가상기기 marker가 하나라도 있거나 양의 physical identity가 없으면 `passed=false`다. 작은 count, 다른 SLA, warm 비활성화 또는 기록 seed 재현 실행은 `diagnosticOnly=true`다.

## 병목 분석과 현재 증거

### 최종 APK 동일 시드 진단에서 확인한 병목

[최신 summary](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/summary.json), [자동 보고서](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/report.md)는 seed `4637290068602280461`, count 1+1, SLA 10,000 ms, warm 결과 별도, Android 15/API 35/90 Hz `sdk_gphone64_x86_64` 에뮬레이터에서 실행했다. 기록 seed 재현이므로 `FIXED_SEED_REPRODUCTION`, `diagnosticOnly=true`이고 **0/2 FAIL**이다. 정식 조건인 새 난수·실제 기기·정확히 10+10·2,000 ms가 아니다.

유형 | 작품/회차 | 이미지 metadata | 첫 actual draw | JankStats/최대 frame | HWUI callback interval | 빈 영역/invalid commit | 최대 PSS | 결과
--- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---
웹툰 | `726211/143500` | 20/20, 8,202,880 B, 평균 740x3907, 최대 740x5189, JPEG, 3 hosts | 7,065.3361 ms | 1.0121% / 536 ms | 21.5602 FPS, worst 259.7868 ms, 연속 256 | 12 / 2 | 280.56 MiB | FAIL
만화 | `24123/240338` | 30/30, 3,391,294 B, 752x1080, JPEG, `booktoki9.org` | 7,574.3809 ms | 0.3195% / 289 ms | 21.9336 FPS, worst 578.0574 ms, 연속 281 | 494 / 2 | 236.42 MiB | FAIL

| 구간 | 웹툰 | 만화 | 해석 |
| --- | ---: | ---: | --- |
| Viewer open → first image request | 5,241.053 ms | 약 5,924 ms | document, isolated ACK proof, exact API를 포함한 콜드 앞단 |
| First image request → response | 1,089.353 ms | 922.002 ms | HTTP/2 image transport |
| Response → decode 완료 | 51.725 ms | 32.590 ms | background decode/resize |
| Decode 완료 → actual HWUI commit | 17.067 ms | 34.225 ms | identity·coverage가 확인된 draw commit |
| Scroll main-thread running max | 39.972 ms | 9.5806 ms | 100 ms 이하지만 AndroidX CPU frame max는 1,072.3439/915.9924 ms로 frame 기준 실패 |

주 병목은 decode나 마지막 draw가 아니라 첫 image request 이전의 두 순차 서버 왕복이다. 웹툰은 ACK proof가 click 후 약 2.768 s, signed exact API가 약 5.215 s에 끝났고 이후 source 0의 1.089 s 요청이 이어졌다. 만화도 document/ACK/sign/exact API 뒤에 source 0이 시작됐다. document 응답은 canonical image URL을 제공하지 않고 exact API만 ordered canonical assets를 주므로 URL·host·확장자를 추측해 먼저 요청하는 우회는 구현하지 않았다.

trusted ACK 수정은 같은 seed/작품의 직접 전후 증거가 있다. [이전 웹툰 로그](../build/outputs/ntk-cold/20260718-015223-4637290068602280461/webtoon-01-726211/logcat.txt)는 `/api/webtoon-images`가 `403 ad_ack_required`, 이미지 0개로 끝났지만 [최신 로그](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/logcat.txt)는 같은 endpoint가 HTTP 200이고 20/20 traversal을 완료했다. challenge 발급 `ad_ack`를 viewer authority로 보존한 변경이 실제 APK에 반영됐음을 확인했다. 다만 첫 draw·coverage·frame 기준은 여전히 실패하므로 작품 PASS로 바꾸지 않았다.

만화 동일 작품의 직전 실행 대비 성공 decode는 135→131회, 누적 decode output은 438,566,400→425,571,840 B, post-window drops는 16→13이었다. 기대한 `decode_skip_outside_bounded_window`는 0회라 pre-decode gate가 이 fling의 moving-window race를 제거하지 못했다. 첫 draw는 7,332.4783→7,574.3809 ms, 빈 영역은 489→494, 최대 PSS는 231.59→236.42 MiB로 악화 또는 변동했고 max bitmap은 97,459,200 B로 같았다. 따라서 정적 회귀와 소폭의 decode 감소만 확인했으며 end-to-end 개선이나 합격은 주장하지 않는다. 웹툰도 20장에 성공 decode 87회, 누적 output 1,004,848,960 B와 post-window drop 21건을 기록해 반복 decode가 남은 스크롤 CPU·GC 병목임을 보여준다.

두 작품 모두 image/page/decode failure 0, wrong binding 0, duplicate download 0, cancellation 0, traversal 누락 0이고 종료 뒤 active request/decode와 foreign-generation work가 0이었다. 반면 coverage 결함, 긴 frame, 낮은 HWUI callback interval FPS, warm reopen 실패가 남았다. 각 case는 non-empty Perfetto 1개와 visual artifact 6/6을 보존했다. Linux kernel page cache, 통신사/CDN edge cache는 통제하지 못하며 결과는 CDN을 `UNKNOWN_UNCONTROLLED`로 기록한다.

## 보관된 진단 결과

### 최신 최종 APK 진단 artifact

- 웹툰: [case summary](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/case-summary.json), [cold proof](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/cold-proof.json), [Perfetto](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/benchmark/NtkColdViewerMacrobenchmark_coldViewerRandomWork_iter000_2026-07-17-17-35-13.perfetto-trace), [screenshots](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/screenshots), [logcat](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/webtoon-01-726211/logcat.txt).
- 만화: [case summary](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/manhwa-02-24123/case-summary.json), [cold proof](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/manhwa-02-24123/cold-proof.json), [Perfetto](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/manhwa-02-24123/benchmark/NtkColdViewerMacrobenchmark_coldViewerRandomWork_iter000_2026-07-17-17-38-34.perfetto-trace), [screenshots](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/manhwa-02-24123/screenshots), [logcat](../build/outputs/ntk-cold/20260718-023124-4637290068602280461/manhwa-02-24123/logcat.txt).
- 두 case 모두 `pm clear` 성공, 시작 snapshot의 memory cache 0, disk cache 0 file/0 B, content cache 0, active request/decode 0, HTTP client `not_created`, 클릭 전 image/decode/page-list/generation-0 work 0을 기록했다. 이는 앱 소유 cold 증거이지 성능 합격 증거가 아니다.

### 보관된 이전 source APK fresh-seed 10초 진단 실행

실행: [summary.json](../build/outputs/ntk-cold/20260717-211244-4591215914734652118/summary.json), [자동 보고서](../build/outputs/ntk-cold/20260717-211244-4591215914734652118/report.md), seed `4591215914734652118`, `FRESH_RANDOM`, count 1+1, SLA 10,000 ms, warm 결과 별도, Android 15/90 Hz 에뮬레이터. 정식 조건이 아니므로 `diagnosticOnly=true`이며 0/2 FAIL이다.

유형 | 작품 ID | 회차 ID | 이미지 수 | 첫 actual draw | Jank/최대 frame | HWUI callback interval | 최대 PSS | 주요 실패 | 결과
--- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- | ---
웹툰 | 68998127 | 1592449 | 0 / 미확정 | 미측정 | 미측정 | 미측정 | 미측정 | catalog 선택 회차가 실제 UI 회차 목록에 없음 | FAIL
만화 | 9223 | 1749604 | 47 / 47 | 6,721.6098 ms | 0.2874% / 238 ms | 21.6554 FPS | 183.23 MiB | HWUI callback slow interval 100%, background 복귀 actual image timeout, retained bitmap | FAIL

두 case 모두 `pm clear` 성공, target UID `10230`, 시작 전 target process/service/job/WorkManager 0, 앱 memory/disk/content cache 0, active request/decode 0, HTTP client `not_created`, 클릭 전 image/decode/page-list 0을 기록했다. 이는 앱 소유 cold 상태 증거이지 성능 합격 증거가 아니다. 만화 trace는 [Perfetto](../build/outputs/ntk-cold/20260717-211244-4591215914734652118/manhwa-02-9223/benchmark/NtkColdViewerMacrobenchmark_coldViewerRandomWork_iter000_2026-07-17-12-17-53.perfetto-trace), [visual artifacts](../build/outputs/ntk-cold/20260717-211244-4591215914734652118/manhwa-02-9223/screenshots), [instrumentation](../build/outputs/ntk-cold/20260717-211244-4591215914734652118/manhwa-02-9223/instrumentation.txt)에 있다.

### 이전 10초 진단 실행

실행: [summary.json](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/summary.json), [자동 보고서](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/report.md), seed `2320062379880660749`, count 1+1, SLA 10,000 ms, warm reopen 없음, 에뮬레이터. 이 설정은 정식 판정 조건과 다르므로 진단 전용이며 0/2 FAIL이다.

유형 | 작품 ID | 회차 ID | 이미지 수 | 첫 actual draw | Jank 비율 | 최대 frame | 빈 영역 | 이미지 오류 | 최대 PSS | 결과
--- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---
웹툰 | 801277 | 1166659 | 0 / 미확정 | 미측정; 10초 timeout | 미측정 | 미측정 | 미측정 | 0이지만 coverage 없음 | 미측정 | FAIL
만화 | 25883 | 309911 | 2 / authoritative 5 | 미측정; 10초 timeout | 0.0%¹ | 6.0 ms¹ | 미측정 | 0이지만 traversal 미완료 | 미측정 | FAIL

¹ 실제 first actual/traversal 전 단 2 frame의 값이므로 jank 합격 증거로 사용할 수 없다.

웹툰 trace: [Perfetto](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/webtoon-01-801277/benchmark/NtkColdViewerMacrobenchmark_coldViewerRandomWork_iter000_2026-07-17-10-22-05.perfetto-trace), [case summary](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/webtoon-01-801277/case-summary.json), [logcat](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/webtoon-01-801277/logcat.txt).

만화 trace: [Perfetto](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/manhwa-02-25883/benchmark/NtkColdViewerMacrobenchmark_coldViewerRandomWork_iter000_2026-07-17-10-23-00.perfetto-trace), [case summary](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/manhwa-02-25883/case-summary.json), [logcat](../build/outputs/ntk-cold/20260717-191931-2320062379880660749/manhwa-02-25883/logcat.txt).

### 2초 SLA 진단 실행

[summary.json](../build/outputs/ntk-cold/20260717-190531-2320062379880660749/summary.json)도 같은 seed와 작품을 사용했고 0/2 FAIL이다. 두 작품 모두 2,000 ms 안에 actual HWUI-committed image draw가 게시되지 않았다. 각 case 시작의 앱 snapshot은 memory cache 0, disk cache file/bytes 0, content cache 0, active request/decode 0, HTTP client `not_created`, pre-entry content work 0을 기록했다.

이것은 앱 소유 cache cold 증거에는 유용하지만 실제 기기 20작품 결과가 아니다. host는 작품마다 target app/benchmark package에 force-stop과 pm clear를 실행했고 target app process는 pre-run activity process dump에서 보이지 않았다. 별도의 오래 남은 `ml.melun.mangaview.test` process는 target APK와 UID가 다른 계측 package이며, 정식 최신 runner는 target UID·secondary process를 명시적으로 검사한다.

### 정식 20작품 표

아래는 결과를 채워 넣을 형식이며 **모두 미실행**이다. 값이 없는 칸을 0으로 만들지 않는다.

유형 | 순번 | 작품 ID | 이미지 수 | 첫 이미지 draw | Jank 비율 | 최대 frame | 빈 영역 | 이미지 오류 | 최대 PSS | 결과
--- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---
웹툰 | 1 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 2 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 3 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 4 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 5 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 6 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 7 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 8 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 9 | 미실행 | — | — | — | — | — | — | — | NOT RUN
웹툰 | 10 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 1 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 2 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 3 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 4 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 5 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 6 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 7 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 8 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 9 | 미실행 | — | — | — | — | — | — | — | NOT RUN
만화 | 10 | 미실행 | — | — | — | — | — | — | — | NOT RUN

## 콜드 상태 증명 범위

정식 runner가 작품별로 증명·기록하는 항목은 다음과 같다.

| 조건 | 증명 방식 |
| --- | --- |
| 앱 process cold | `am force-stop`, `pm clear`, `pidof`, target UID/secondary process dump |
| 이미지 memory/disk cache cold | process-start `ViewerColdStateSnapshot`, reader/Glide cache file count와 bytes |
| content/page cache cold | structured cache와 in-memory content entry 0 |
| 대상 이미지 사전 요청 없음 | generation 0/pre-entry request·decode·page-list count 0 |
| 진행 중 work 없음 | activity service, jobscheduler/WorkManager, active request/decode count |
| HTTP client 신규 | process-start `client=not_created`, session client instance count |
| socket 재사용 | protocol, hashed connection ID, `connectionReused` 기록. 같은 cold 실행 안의 정상 재사용과 이전 process 재사용을 구분 |
| CDN cache | `UNKNOWN_UNCONTROLLED`로 명시 |

정식 합격에는 각 case의 raw dump, cold-state event, first request/response/decode/draw timestamp, Perfetto, Macrobenchmark JSON, screenshot/video-equivalent visual artifacts가 모두 있어야 한다. artifact pull 실패나 0-byte trace도 실패다.

## 테스트 결과

### 단위/계측

- [단위 결과 디렉터리](../app/build/test-results/testDebugUnitTest): 147 suite, 1,093 test, failure 0, error 0, skipped 0. 최종 명령 `./gradlew.bat --no-daemon --no-build-cache :app:testDebugUnitTest :macrobenchmark:compileBenchmarkKotlin :app:assembleBenchmark :macrobenchmark:assembleBenchmark`는 `BUILD SUCCESSFUL in 2m 23s`였다.
- 주요 회귀 범위: metadata-only click, StrictFresh ACK ownership/order와 challenge-authoritative cookie, 웹툰 exact wire, flight-owned physical call의 late-register/cancel-once, non-blocking retirement, 성공 flight lifetime, 같은 path의 exact/quarantine generation 공존, two-phase plan reservation, 부분 executor construction cleanup, first source 0/1, rolling demand idempotence, reverse LRU/pre-decode window, foreground commit lifecycle, event-time mailbox, surface structure/commit validation, source close re-arm과 quarantine lease.
- [NtkStrictCallRegistryTest.java](../app/src/test/java/ml/melun/mangaview/mangaview/NtkStrictCallRegistryTest.java), [NtkStrictDiscoveryRetirementFenceTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkStrictDiscoveryRetirementFenceTest.kt), [NtkSamePathGenerationRetirementContractTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkSamePathGenerationRetirementContractTest.kt), [NtkQuarantineSourceOwnershipGenerationTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkQuarantineSourceOwnershipGenerationTest.kt), [NtkExecutionBootstrapTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkExecutionBootstrapTest.kt)이 신규 retirement/generation/failure 경계를 고정한다.
- `pwsh -NoProfile -File tools/ntk_cold_qualification_contract_test.ps1`도 PASS했다. 이는 seed/count/SLA/physical-device/artifact fail-closed 계약 검사이며 실제 작품 성능 측정을 대체하지 않는다.
- [API 35 선택 계측 결과 디렉터리](../app/build/outputs/androidTest-results/connected/debug): `TEST-MangaViewerApi35(AVD) - 15-_app-.xml`, `NtkStrictSourceSessionRollingStartInstrumentedTest`, 1 test, 0 failure, 0 error, 0 skip, test 0.341 s.
- 마지막 계측 회귀의 원인은 두 physical lane의 quarantine directory 생성 race에서 한 lane의 pre-call lease가 남는 것이었다. [ReaderImageCache.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt#L2963)와 [NtkStrictSourceSession.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt#L876), [NtkStrictSourceSession.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt#L1368)를 수정했으며 timeout이나 assertion을 완화하지 않았다.

### 최종 benchmark/release 빌드 산출물

- 최종 통합 명령은 113 tasks(13 executed, 100 up-to-date)로 완료됐다. app benchmark는 release 기반 R8/resource shrink, `debuggable=false`이며 trusted ACK, exact wire, decode/window, foreground commit 변경을 포함한다.
- [app benchmark APK](../app/build/outputs/apk/benchmark/mangaViewer_2112261718-benchmark.apk): 14,530,563 bytes, SHA-256 `135bee962f659081603cdc0b3b32d4ad696301328e3a74a9168ea4d58e2e70ee`.
- [Macrobenchmark APK](../macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk): 39,990,696 bytes, SHA-256 `13533986cb1fca400441f3589655d0e59fbfc9130ddca19483e00a93e1ce0330`.

이들은 최종 source 산출물이지만 **빌드·단위 테스트 PASS는 물리 기기 20/20 성능 PASS가 아니다.** 실제 기기에서 canonical runner의 모든 cold case가 통과하기 전까지 APK의 자격은 `NOT QUALIFIED`다.

## 변경 파일

아래는 성능 작업에 직접 연결된 파일이다. 기존 아키텍처를 전면 교체하지 않고 episode click → strict discovery → rolling source/decode → `ReaderSurfaceView` 흐름을 보강했다.

| 범위 | 파일 | 역할 |
| --- | --- | --- |
| 빌드/variant | [settings.gradle](../settings.gradle), [app/build.gradle](../app/build.gradle), [benchmark manifest](../app/src/benchmark/AndroidManifest.xml), [macrobenchmark/build.gradle](../macrobenchmark/build.gradle) | `:macrobenchmark`, release형 benchmark, profileable, R8/shrink, tracing/JankStats 의존성 |
| 진입 UI | [EpisodeActivity.java](../app/src/main/java/ml/melun/mangaview/activity/EpisodeActivity.java), [EpisodeAdapter.java](../app/src/main/java/ml/melun/mangaview/adapter/EpisodeAdapter.java), [EpisodeRowView.java](../app/src/main/java/ml/melun/mangaview/ui/EpisodeRowView.java), [activity_episode_ntk.xml](../app/src/main/res/layout/activity_episode_ntk.xml) | metadata-only press, committed-click cold 진입, stable 회차 identity |
| launch handoff | [Utils.java](../app/src/main/java/ml/melun/mangaview/Utils.java), [ReaderLaunchPayloadStore.java](../app/src/main/java/ml/melun/mangaview/activity/ReaderLaunchPayloadStore.java) | prepared image 없이 exact episode identity만 즉시 전달 |
| viewer/surface | [ReaderV2Activity.kt](../app/src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt), [ReaderSession.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSession.kt), [ReaderSurfaceView.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt), [ReaderPipelinePolicy.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderPipelinePolicy.kt) | rolling window, display/source mapping, decode delivery, HWUI commit·coverage·traversal 증명 |
| strict discovery/source | [NtkStrictEpisodeDiscoveryCoordinator.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt), [NtkStrictDiscoveryRetirementFence.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictDiscoveryRetirementFence.kt), [NtkStrictSourceSession.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt), [NtkStrictSourcePolicy.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourcePolicy.kt), [NtkStrictSourceTransport.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceTransport.kt), [NtkSourceSpoolRegistry.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt), [NtkStrictSourceOwnershipRegistry.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStrictSourceOwnershipRegistry.kt), [NtkQuarantineSourceOwnershipRegistry.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkQuarantineSourceOwnershipRegistry.kt) | flight-owned call/ACK retirement, 성공 owner lifetime, source 0/1 첫 wave, direction demand, generation별 quarantine/exact ownership·tombstone, two-phase plan install, close barrier |
| identity/manifest | [NtkStripContracts.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkStripContracts.kt), [NtkManifestEvidenceParser.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkManifestEvidenceParser.kt), [NtkManifestAuthorityFactory.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkManifestAuthorityFactory.kt) | episode/manifest/request/response/canonical source seal |
| image/cache/decode | [ReaderImageCache.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt), [ReaderPagePipeline.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderPagePipeline.kt), [ReaderPipelineExecutors.kt](../app/src/main/java/ml/melun/mangaview/reader/ReaderPipelineExecutors.kt), [NtkBodyLeaseDispatcher.kt](../app/src/main/java/ml/melun/mangaview/reader/NtkBodyLeaseDispatcher.kt) | exact source spool/cache proof, downsample/tile, bounded executor, lease release |
| ACK process | [AndroidManifest.xml](../app/src/main/AndroidManifest.xml), [AIDL](../app/src/main/aidl/ml/melun/mangaview/ntkack), [ntkack package](../app/src/main/java/ml/melun/mangaview/ntkack) | 별도 `:ntk_ack` process, challenge-authoritative viewer grant, confirmation transcript proof, quiescence/signature, local terminal state 후 ordered `oneway` cancellation |
| HTTP | [CustomHttpClient.java](../app/src/main/java/ml/melun/mangaview/mangaview/CustomHttpClient.java), [NtkQuicFetcher.java](../app/src/main/java/ml/melun/mangaview/activity/NtkQuicFetcher.java) | bounded dispatcher, exact one-call API, path/viewer-generation call registry, detached off-main cancellation, connection telemetry, HTTP/2/QUIC transport |
| lifecycle/telemetry | [MainApplication.java](../app/src/main/java/ml/melun/mangaview/MainApplication.java), [ReaderV2Activity.kt](../app/src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt), [ViewerTelemetry.java](../app/src/main/java/ml/melun/mangaview/runtime/ViewerTelemetry.java), [PerformanceMonitor.java](../app/src/main/java/ml/melun/mangaview/runtime/PerformanceMonitor.java), [ViewerColdStateSnapshot.java](../app/src/main/java/ml/melun/mangaview/runtime/ViewerColdStateSnapshot.java), [PerfTrace.java](../app/src/main/java/ml/melun/mangaview/runtime/PerfTrace.java) | onDestroy/adjacent transition의 exact generation retirement, viewerOpen 선형화, trim/cancel, trace, JankStats, cold proof, memory/request/decode counters |
| 자동화 | [NtkColdViewerMacrobenchmark.kt](../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/NtkColdViewerMacrobenchmark.kt), [ViewerScrollTraceMetric.kt](../macrobenchmark/src/main/java/ml/melun/mangaview/macrobenchmark/ViewerScrollTraceMetric.kt), [ntk_cold_qualification.ps1](../tools/ntk_cold_qualification.ps1), [ntk_final_qualification.ps1](../tools/ntk_final_qualification.ps1), [ntk_cold_report.ps1](../tools/ntk_cold_report.ps1), [result schema](../tools/ntk_cold_result.schema.json), [Perfetto config](../tools/ntk_perfetto.textproto) | 실제 UI 10+10 orchestration, cold reset, metrics/artifacts, non-zero failure |
| 회귀 테스트 | [NtkStrictFreshArchitectureTest.java](../app/src/test/java/ml/melun/mangaview/mangaview/NtkStrictFreshArchitectureTest.java), [NtkStrictWebtoonWireContractTest.java](../app/src/test/java/ml/melun/mangaview/mangaview/NtkStrictWebtoonWireContractTest.java), [trusted ACK tests](../app/src/test/java/ml/melun/mangaview/ntkack/NtkAckTrustedGrantValidatorTest.kt), [NtkStrictReverseDecodeRetentionTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkStrictReverseDecodeRetentionTest.kt), [ReaderForegroundCommitLifecycleTest.kt](../app/src/test/java/ml/melun/mangaview/activity/ReaderForegroundCommitLifecycleTest.kt), [NtkStrictDiscoveryRetirementFenceTest.kt](../app/src/test/java/ml/melun/mangaview/reader/NtkStrictDiscoveryRetirementFenceTest.kt), [rolling instrumentation](../app/src/androidTest/java/ml/melun/mangaview/reader/NtkStrictSourceSessionRollingStartInstrumentedTest.kt), [qualification contract](../tools/ntk_cold_qualification_contract_test.ps1) | 금지 pre-click, exact wire/grant, reverse decode/window, foreground commit, retirement·generation 격리, 0/1 wave, host fail-closed contract |

`app/src/main/cpp/**`와 native SurfaceControl/full-scene 구성도 저장소에 있지만 exact cold canonical 경로는 전체 작품 준비가 필요한 native full-scene을 첫 진입 경로로 사용하지 않는다. 해당 native 구성의 개별 테스트 성공을 이번 20작품 자격으로 계산하지 않는다.

## 재현 방법

### 요구 환경

- PowerShell 7.2 이상, Android SDK/`adb`, JDK/Gradle wrapper
- USB 디버깅이 가능한 **실제 Android 기기**, release/benchmark APK, debugger 미연결
- Wi-Fi/제한 네트워크 등 네트워크 유형을 실행 로그에 남길 수 있는 환경
- 현재 catalog는 로그인 token을 요구하지 않으며 runner는 인증 상태를 주입하지 않는다. 이후 인증이 필요해지면 token 저장소만 별도 주입하고 image/page/reader cache는 계속 0이어야 한다.

### 빌드와 회귀 테스트

```powershell
.\gradlew.bat --no-daemon --no-build-cache :app:testDebugUnitTest
pwsh -NoProfile -File .\tools\ntk_cold_qualification_contract_test.ps1
.\gradlew.bat --no-daemon --no-build-cache :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

마지막 source 수정 뒤 APK hash를 기록한다.

```powershell
Get-FileHash -Algorithm SHA256 `
  .\app\build\outputs\apk\benchmark\mangaViewer_2112261718-benchmark.apk, `
  .\macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk
```

### APK 설치

canonical runner는 기본적으로 두 APK를 설치한다. 수동 설치가 필요하면 다음과 같다.

```powershell
adb -s <PHYSICAL_SERIAL> install -r .\app\build\outputs\apk\benchmark\mangaViewer_2112261718-benchmark.apk
adb -s <PHYSICAL_SERIAL> install -r .\macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk
```

### 정식 무작위 10+10

`Seed=0`은 매 실행 새 암호학적 seed를 만들고 결과에 기록한다. 정식 wrapper는 양수 seed를 거부하므로 고정된 우수 작품으로 자격을 우회할 수 없다.

```powershell
pwsh -NoProfile -File .\tools\ntk_final_qualification.ps1 `
  -AppApkPath .\app\build\outputs\apk\benchmark\mangaViewer_2112261718-benchmark.apk `
  -BenchmarkApkPath .\macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk `
  -DeviceSerial <PHYSICAL_SERIAL> `
  -Seed 0 `
  -StandalonePerfetto
```

결과는 `build\outputs\ntk-cold\<yyyyMMdd-HHmmss>-<seed>\` 아래에 생성된다. `selection.json`, `summary.json`, `report.md`와 20개 case directory가 있어야 한다. 전체 PASS가 아니면 process exit code는 non-zero다.

기록 seed 재실행은 lower-level runner에서만 허용되고 항상 `FIXED_SEED_REPRODUCTION`, `diagnosticOnly=true`, `passed=false`다.

```powershell
pwsh -NoProfile -File .\tools\ntk_cold_qualification.ps1 `
  -AppApkPath .\app\build\outputs\apk\benchmark\mangaViewer_2112261718-benchmark.apk `
  -BenchmarkApkPath .\macrobenchmark\build\outputs\apk\benchmark\macrobenchmark-benchmark.apk `
  -DeviceSerial <DEVICE_SERIAL> `
  -Seed <RECORDED_POSITIVE_SEED> `
  -CountPerType 10 `
  -FirstImageSlaMs 2000 `
  -IncludeWarmReopen:$true
```

### 개별 cold 상태 확인

```powershell
adb -s <PHYSICAL_SERIAL> shell am force-stop ml.melun.mangaview
adb -s <PHYSICAL_SERIAL> shell pm clear ml.melun.mangaview
adb -s <PHYSICAL_SERIAL> shell pidof ml.melun.mangaview
adb -s <PHYSICAL_SERIAL> shell dumpsys activity processes
adb -s <PHYSICAL_SERIAL> shell dumpsys meminfo ml.melun.mangaview
adb -s <PHYSICAL_SERIAL> shell dumpsys gfxinfo ml.melun.mangaview reset
```

직접 Perfetto를 수집해야 할 때의 config는 [ntk_perfetto.textproto](../tools/ntk_perfetto.textproto)다. canonical `-StandalonePerfetto`가 config 설치, 시작/중지, pull과 non-empty 검사를 처리한다. 수동 흐름은 다음과 같다.

```powershell
adb -s <PHYSICAL_SERIAL> push .\tools\ntk_perfetto.textproto /data/local/tmp/ntk_perfetto.textproto
adb -s <PHYSICAL_SERIAL> shell perfetto --txt `
  -c /data/local/tmp/ntk_perfetto.textproto `
  -o /data/misc/perfetto-traces/ntk-cold.perfetto-trace
adb -s <PHYSICAL_SERIAL> pull /data/misc/perfetto-traces/ntk-cold.perfetto-trace .\build\outputs\ntk-cold\manual.perfetto-trace
```

보고서만 다시 생성할 때는 다음을 사용한다. schema 또는 cross-field invariant가 맞지 않으면 실패한다.

```powershell
pwsh -NoProfile -File .\tools\ntk_cold_report.ps1 `
  -SummaryPath .\build\outputs\ntk-cold\<run>\summary.json `
  -OutputPath .\build\outputs\ntk-cold\<run>\report.md
```

## 완료까지 남은 조건

1. 실제 기기에서 fresh seed, 정확히 웹툰 10개 + 만화 10개, SLA 2,000 ms, warm 결과 별도 수집으로 canonical runner를 실행한다.
2. 20개 case 각각 first actual page 0, initial blank 0, viewport/runway defect 0, wrong binding 0, duplicate/failure 0, bounded cancellation, complete forward/reverse traversal, jank/100 ms/main-thread/PSS/GC/close 기준을 모두 충족해야 한다.
3. 생성된 20개 Perfetto/Macrobenchmark/visual/raw adb artifact를 보고서와 함께 보존한다.
4. 실패 작품은 작은 작품으로 바꾸지 않는다. 삭제·접근 불가가 명확할 때만 원 선택과 교체 사유를 함께 기록한다.

이 조건이 충족되기 전에는 코드 구현이나 일부 테스트 성공과 무관하게 최종 상태는 계속 **NOT QUALIFIED**다.
