# CleanPDF Viewer

Ad-free, free Android PDF viewer for construction-site users (계약서·견적서·도면 from 카톡 등).
Open source, **AGPL v3** (linking MuPDF makes the whole app AGPL — source stays public).
(GitHub repo name will be `openPDF`; the app's display name is "CleanPDF Viewer".)
Sibling app: **CleanCAD Viewer** (`C:\dev\opendwg`) — same "ad-free, free" pattern for DWG; reuse its proven solutions (intent handling, release flow).

## Stack
- Kotlin, Android Views (XML layouts). minSdk 24, compile/targetSdk 36.
- AGP 9.1.1, Gradle 9.3.1 (built-in Kotlin).
- PDF engine: **MuPDF (Artifex) `fitz` 1.27.1** — prebuilt AAR from `maven.ghostscript.com` (`com.artifex.mupdf:fitz:1.27.1`). **No NDK/JNI build** (unlike opendwg's LibreDWG) — fitz ships `.so` + Java API; we add Kotlin UI on top.
- Rendering pipeline: **PDF → cache file copy → fitz Document → single-thread PageRenderer → Bitmap LRU cache → RecyclerView (one page per row) → custom PdfReaderView (continuous scroll + pinch zoom)**.
- Package / applicationId: `io.github.june690602_blip.cleanpdf`

## Key docs (read these first to get oriented)
- Design spec — all decisions: `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md`
- 계획서(완료): Phase 0–1 / Phase 2(intake) / Phase 3(navigation) / Phase 3.5(thumbnails) — `docs/superpowers/plans/`
- 핸드오프: Phase 1 / Phase 2 / **Phase 3·3.5** — `docs/superpowers/handoff/`
- **다음 작업 — Phase 4(검색) 계획**: `docs/superpowers/plans/2026-06-05-cleanpdf-phase4-search.md`

## Status (2026-06-05) — Phase 3.5 완료, `main` 병합됨

**현재 `main` HEAD: Phase 3.5 = `bd9e535`.** 연속 스크롤 + 줌 + 임의 PDF 열기(SAF) + 카톡 VIEW/SEND 인입 + 에러/암호 화면 + 최근 파일 + **탐색(목차·페이지점프·번호점프·썸네일)**까지 동작.

### 작동 중 ✅
- **연속 세로 스크롤** — RecyclerView, 온디맨드 백그라운드 렌더, 가시 페이지만 렌더(±버퍼).
- **핀치 줌 + 더블탭 줌(1↔2.5배), 최대 8배** — 핀치 중 라이브 프리뷰, 손 떼면 정밀 재렌더.
- **임의 PDF 열기 (Phase 2)** — 오버플로 "PDF 열기" → SAF `OpenDocument` → `PdfSource.copyToCache` → 렌더. 샘플은 인입 인텐트 없을 때만 자동 오픈.
- **파일 받기 (Phase 2)** — 카톡 등 VIEW/SEND 수신(Manifest 필터 + `Intents.incomingUri` + `looksLikePdf` 게이트 + `copyToCache`). ⚠️ 안드16(S25+) 카톡 "열기"는 가시성 한계 → SEND 권장.
- **에러/암호 화면 (Phase 2)** — `PdfDocument.openResult`→`PdfOpenResult`(Success/NeedsPassword/Error). 손상·비-PDF → 에러 오버레이, 암호 PDF → 비번 다이얼로그.
- **최근 파일 (Phase 2)** — `RecentFilesStore`(SharedPreferences, 불변, newest-first·dedup·cap10).
- **탐색 — 목차/페이지점프 (Phase 3)** — 목차(`PdfDocument.loadOutline` via fitz `resolveLink`/`pageNumberFromLocation`, **렌더 스레드**에서 `loadOutlineBlocking`)·페이지 점프(`PdfReaderView.scrollToPage`)·페이지번호 입력 점프(순수 `PageJump`)·툴바 "N/전체" 인디케이터(`onPageChanged`).
- **썸네일 (Phase 3.5)** — 오버플로 "썸네일" → **AlertDialog 3열 그리드**(`ThumbnailAdapter` — 렌더러 공유 + 자체 작은 캐시 + recycle 금지) → 셀 탭 점프.
- **테스트** — 단위 33(LruByteSizedCache5+PageLayout5+PdfValidation5+RenderScale3+Intents4+RecentFilesLogic4+PageJump5+OutlineModel2), 계측 4(Render+ScrollZoom+IntentIntake+Navigation). ⚠️ `RecentFilesLogic` 은 org.json 때문에 Robolectric(`@Config sdk=34`) — 이후 순수 직렬화로 바꾸면 함정 제거 가능.

### 진행 중 ⏳ — 다음 = Phase 4 (검색)
- 스펙 §5.3: 찾기 바 → 전체검색(fitz `Page.search(needle): Quad[][]`) → 다음/이전 → 히트수. 계획: `docs/superpowers/plans/2026-06-05-cleanpdf-phase4-search.md`. **하이라이트 오버레이는 Phase 4.5로 분리**(렌더 파이프라인 침습 최소화).
- 이후 Phase 5 선택·복사 / Phase 6 다크·야간반전 / Phase 7 출시(**AGPL 고지** + GitHub 공개 레포 — 현재 원격 없음).
- **미검증(실기/픽스처)**: 실제 카톡 SEND·VIEW(S25), 암호 PDF 다이얼로그(픽스처 없음), 목차 점프(목차 있는 실제 PDF 필요).

## ⚠️ 아키텍처 불변조건 (깨면 크래시/OOM 재발 — 절대 되돌리지 말 것)
> 상세 근거: 핸드오프 문서 §2. 대부분 코드 주석에도 있음.

1. **단일 렌더 스레드** — 모든 `PdfDocument`/fitz 접근은 `PageRenderer` 의 단일 executor에서만 (fitz는 thread-safe 아님).
2. **Cookie 미사용** — fitz 1.27.1엔 Cookie 오버로드 없음. `renderPage(index, scale)` 2-arg. 취소 = `Future.cancel(true)` + `isInterrupted`. Cookie 재도입 금지.
3. **`onReady` 는 렌더 스레드 호출** — UI/캐시 만지려면 `View.post {}` 로 메인 재포스트.
4. **캐시 비트맵 `recycle()` 안 함** — `BitmapCache` 는 onEvict 없이 참조만 떨어뜨리고 GC에 맡김. 붙어있는 비트맵 recycle → "trying to use a recycled bitmap" 크래시. recycle 재도입 금지.
5. **렌더 스케일 캡** — `RenderScale.forPage` 가 비트맵 ≤32MB로 제한(OOM 방지). 캡 이상은 업스케일. 선명한 고배율 = 타일링(미래).
6. **줌 시 `cache.clear()` 안 함** — `PageKey` 에 `scaleMilli` 포함(스케일별 키). clear 불필요.
7. **줌 시 행 너비 = `contentWidth`** — `MATCH_PARENT` 면 FIT_CENTER가 줌 무력화.
8. **`openFile` 순서** — 새 렌더러 완성 → 어댑터 교체 → **그 다음** 옛 렌더러 shutdown (먼저 하면 `RejectedExecutionException`).

## Build & test
- (서브모듈 없음 — 클론 후 바로 빌드 가능.)
- 빌드: `./gradlew :app:assembleDebug`
- 단위 테스트(JVM): `./gradlew :app:testDebugUnitTest`
- 계측 테스트(에뮬 필요, AVD `Medium_Phone_API_36.1` x86_64): `./gradlew :app:connectedDebugAndroidTest`
- 실행: `./gradlew :app:installDebug && adb -s emulator-5554 shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity`

## Gotchas
- **에뮬레이터 저장공간 부족**: `adb -s emulator-5554 shell pm trim-caches 9999999999` 후 재시도. (native가 아니라 순수 AAR이라 `install -r` 로 충분 — opendwg의 .so 갱신 함정 없음.)
- **Windows/git-bash 경로**: `adb shell <device-path>` 가 MSYS 경로변환에 망가지면 `MSYS_NO_PATHCONV=1` 사용. `$null`/`$env:` 는 PowerShell, bash 명령은 Bash 툴로.
- **LF→CRLF git 경고**: 무해. 무시.
- 루트 `*.png`(증거 스크린샷)와 `app/build/` 는 `.gitignore` 처리됨.

## Conventions
- 불변 Kotlin data class 도메인 모델; 작고 집중된 파일 다수(고응집·저결합).
- 순수 로직(캐시·레이아웃·검증·렌더스케일)은 JVM 단위테스트; 렌더/UI는 계측 스모크.
- 커밋 메시지: `<type>: <description>` (feat/fix/refactor/docs/test/chore).
- 증거 기반 완료: Phase 종료 시 "빌드 exit 0 + 테스트 통과 수 + 실기 동작"을 증거로.
- License: AGPL v3 — MuPDF 결합으로 앱 전체 AGPL. 소스 공개 영속. (출시 시 앱 내 라이선스 고지 + 스토어 소스 링크 필수.)
- ⚠️ git 원격 아직 없음. 출시 준비 단계에서 GitHub 공개 레포 세팅.
