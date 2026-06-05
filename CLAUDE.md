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
- 계획서(완료): Phase 0–1 / Phase 2(intake) / Phase 3(navigation) / Phase 3.5(thumbnails) / Phase 4(search) / Phase 4.5(highlight) — `docs/superpowers/plans/`
- 핸드오프: Phase 1 / Phase 2 / Phase 3·3.5 / Phase 4 / **Phase 4.5** — `docs/superpowers/handoff/`
- **다음 작업 — Phase 5(텍스트 선택·복사)**: 계획 예정

## Status (2026-06-06) — Phase 5(텍스트 선택·복사) 완료, 브랜치 `feat/phase5-text-selection` (main 병합 대기)

**main HEAD: `4564499`(Phase 4.5 문서). Phase 5 코드는 브랜치 `feat/phase5-text-selection`(미병합).** 연속 스크롤 + 줌 + 임의 PDF 열기(SAF) + 카톡 VIEW/SEND 인입 + 에러/암호 화면 + 최근 파일 + 탐색(목차·페이지점프·번호점프·썸네일) + 전체 텍스트 검색(하이라이트+순차이동) + **텍스트 길게눌러 단어 선택·핸들 드래그·클립보드 복사**까지 동작.

### 작동 중 ✅
- **연속 세로 스크롤** — RecyclerView, 온디맨드 백그라운드 렌더, 가시 페이지만 렌더(±버퍼).
- **핀치 줌 + 더블탭 줌(1↔2.5배), 최대 8배** — 핀치 중 라이브 프리뷰, 손 떼면 정밀 재렌더.
- **임의 PDF 열기 (Phase 2)** — 오버플로 "PDF 열기" → SAF `OpenDocument` → `PdfSource.copyToCache` → 렌더. 샘플은 인입 인텐트 없을 때만 자동 오픈.
- **파일 받기 (Phase 2)** — 카톡 등 VIEW/SEND 수신(Manifest 필터 + `Intents.incomingUri` + `looksLikePdf` 게이트 + `copyToCache`). ⚠️ 안드16(S25+) 카톡 "열기"는 가시성 한계 → SEND 권장.
- **에러/암호 화면 (Phase 2)** — `PdfDocument.openResult`→`PdfOpenResult`(Success/NeedsPassword/Error). 손상·비-PDF → 에러 오버레이, 암호 PDF → 비번 다이얼로그.
- **최근 파일 (Phase 2)** — `RecentFilesStore`(SharedPreferences, 불변, newest-first·dedup·cap10).
- **탐색 — 목차/페이지점프 (Phase 3)** — 목차(`PdfDocument.loadOutline` via fitz `resolveLink`/`pageNumberFromLocation`, **렌더 스레드**에서 `loadOutlineBlocking`)·페이지 점프(`PdfReaderView.scrollToPage`)·페이지번호 입력 점프(순수 `PageJump`)·툴바 "N/전체" 인디케이터(`onPageChanged`).
- **썸네일 (Phase 3.5)** — 오버플로 "썸네일" → **AlertDialog 3열 그리드**(`ThumbnailAdapter` — 렌더러 공유 + 자체 작은 캐시 + recycle 금지) → 셀 탭 점프.
- **검색 (Phase 4)** — 오버플로 "검색" → 검색어 입력 → fitz `Page.search(needle, SEARCH_IGNORE_CASE): Quad[][]`(렌더 스레드 `PageRenderer.searchBlocking`)로 전체 페이지 검색, 히트별 quad 합집합 bbox를 `SearchHit(page,x0..y1)` 로. `maxHits=500` 상한.
- **검색 하이라이트 + 순차 이동 (Phase 4.5)** — 검색 시 결과 다이얼로그 대신 **하단 검색바**(◀ 현재/전체 ▶ + 닫기) + **형광 하이라이트 오버레이** + 첫 히트 자동 스크롤. `HighlightOverlayView`를 페이지 셀 `FrameLayout`에 얹음(**비트맵 캐시 미오염**, recycle 0). 순수 `SearchCursor`(wrapping next/prev) + 순수 `HighlightGeometry`(PDF점→셀픽셀, `FloatArray`). `PdfReaderView.scrollToHit`(lastLayout scale)·`setSearchHighlights`. 활성 hit=진한 주황, 나머지=연한 노랑. 좌표계 y-down(뒤집기 불필요, 실기 확인).
- **텍스트 선택·복사 (Phase 5)** — 길게 누르면 단어 스냅 선택 → 시작/끝 핸들 드래그로 범위 조절 → 하단 바 "N자 선택 / 복사 / 닫기"로 클립보드 복사. **순수 모델 엔진**: long-press 시 fitz `Page.toStructuredText().getBlocks()`로 페이지 1회 파싱 → 불변 `PageText`(문자 codepoint+bbox, android/fitz 타입 0) → 선택범위/하이라이트rect/복사문자열/핸들좌표는 전부 순수 Kotlin(`pdf/TextSelection`, JVM 단위테스트, 드래그 중 렌더스레드 왕복 0, 하이라이트=복사 WYSIWYG). 추출은 렌더 스레드(`PageRenderer.extractTextBlocking`→`PdfDocument.extractText`). 선택은 PDF점 저장→`onBind`에서 셀픽셀 재투영(줌 자동 추종, 실기 2.5배 정렬 확인). 오버레이 뷰(`view/SelectionOverlayView`, 비트맵/캐시 미오염, 캐시-히트 early-return **前** 적용). 핸들 grab 시 스크롤 가로채기(`onInterceptTouchEvent`), **핀치 중 미가로채기**(`!scaleDetector.isInProgress`). 좌표변환 `view/SelectionGeometry`(역변환 px→pt 포함). MainActivity는 검색과 동일한 controller-folded 패턴(begin/apply/drag/copy/close). **단일 페이지 범위**(교차페이지 미지원). 빈 영역 long-press=최근접 단어 스냅; 텍스트-없는 페이지만 "선택할 텍스트 없음" 토스트.
- **테스트** — 단위 58(기존43 + TextSelection12 + SelectionGeometry3), 계측 8(기존6 + TextExtraction + TextSelection). 기존 단위 43=LruByteSizedCache5+PageLayout5+PdfValidation5+RenderScale3+Intents4+RecentFilesLogic4+PageJump5+OutlineModel2+SearchHits2+SearchCursor5+HighlightGeometry3. ⚠️ `RecentFilesLogic` 은 org.json 때문에 Robolectric(`@Config sdk=34`) — 이후 순수 직렬화로 바꾸면 함정 제거 가능. ⚠️ `connectedDebugAndroidTest`는 실행 후 앱 APK를 **언인스톨**함 — 실기 수동검증은 그 전에(또는 `installDebug` 재설치 후).

### 진행 중 ⏳ — 다음 = Phase 6 (다크모드 + 야간 읽기)
- 스펙: 앱 크롬 다크(`values-night`) + **페이지 색 반전 토글**(어두운 곳 가독성) + 설정 화면.
- 이후 Phase 7 출시(**AGPL 고지** + GitHub 공개 레포 — 현재 원격 없음).
- **Phase 5 후속(미구현, 우선순위 낮음)**: 교차페이지 선택(현재 단일페이지), 핸들 드로어블 teardrop화, `MainActivity`(현 353줄)에 3번째 제스처 추가 시 `TextSelectionController` 추출, 텍스트-없는(스캔) PDF의 `selection_none` 토스트는 단위테스트로만 검증(실기 픽스처 없음).
- **미검증(실기/픽스처)**: 실제 카톡 SEND·VIEW(S25), 암호 PDF 다이얼로그(픽스처 없음), 목차 점프(목차 있는 실제 PDF 필요), 검색 하이라이트 줌-후 정렬(코드상 relayout 재계산 보장, 핀치 실기 미캡처).

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
- **adb `input text` + 한글 IME**: 에뮬 기본 키보드(Gboard)가 한글 조합 모드면 `input text "Page"` 가 자모로 변환됨(두벌식 g→ㅎ, e→ㄷ → "ㅎ드"). UI 자동화로 영문 입력 시 `adb shell ime disable <ime>` 로 IME 끄고 입력 후 `ime enable`+`ime set <ime>` 으로 복구(슬래시 포함 id라 `MSYS_NO_PATHCONV=1`). id 확인: `adb shell settings get secure default_input_method`. (Phase 4 검색 검증에서 발견.)
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
