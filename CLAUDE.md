# CleanPDF Viewer

Ad-free, free Android PDF viewer for construction-site users (계약서·견적서·도면 from 카톡 등).
Open source, **AGPL v3** (linking MuPDF makes the whole app AGPL — source stays public).
(GitHub repo name will be `openPDF`; the app's display name is "CleanPDF Viewer".)
Sibling app: **CleanCAD Viewer** (`C:\dev\opendwg`) — same "ad-free, free" pattern for DWG; reuse its proven solutions (intent handling, release flow).

## Stack
- Kotlin, Android Views (XML layouts). minSdk 24, compile/targetSdk 36.
- AGP 9.1.1, Gradle 9.3.1 (built-in Kotlin).
- PDF engine: **MuPDF (Artifex) `fitz` 1.27.1** — prebuilt AAR from `maven.ghostscript.com` (`com.artifex.mupdf:fitz:1.27.1`). **No NDK/JNI build** (unlike opendwg's LibreDWG) — fitz ships `.so` + Java API; we add Kotlin UI on top.
- Rendering pipeline: **PDF → cache file copy → fitz Document → single-thread PageRenderer → Bitmap LRU cache → custom Matrix `PdfReaderView`**. Single canvas; pages laid out in document space (PDF points) via `PageWorld`, drawn through one `Matrix` (doc→screen); pinch/pan/fling/double-tap = matrix ops + invalidate; visible pages re-rendered at the settled scale for sharpness. (opendwg `render/DrawingView` 패턴. 이전 RecyclerView+페이지비트맵 구조는 부드러운 줌아웃이 안 돼 폐기.)
- 문서(한글·워드) 읽기: **DOCX/HWP/HWPX → 구조(문단·표·이미지)** 추출 → 별도 `DocTextActivity`(오프라인 WebView). DOCX/HWPX=안드 내장 XmlPullParser(0 dep, 공용 `XmlBlocks`), HWP=**hwplib 1.1.10** 객체모델(Apache-2.0, AGPL 호환). 표=`<table>`·이미지=base64 인라인(래스터만, 용량캡). PDF 코어와 격리.
- Package / applicationId: `io.github.june690602_blip.cleanpdf`

## Key docs (read these first to get oriented)
- Design spec — all decisions: `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md`
- 계획서(완료): Phase 0–1 / Phase 2(intake) / Phase 3(navigation) / Phase 3.5(thumbnails) / Phase 4(search) / Phase 4.5(highlight) — `docs/superpowers/plans/`
- 핸드오프: Phase 1 / Phase 2 / Phase 3·3.5 / Phase 4 / **Phase 4.5** — `docs/superpowers/handoff/`
- **DocText(한글·워드 텍스트 읽기, 2026-06-06)** — 스펙 `specs/2026-06-06-cleanpdf-doctext-design.md` · 계획 `plans/2026-06-06-cleanpdf-doctext.md` · 핸드오프 `handoff/2026-06-06-cleanpdf-doctext-handoff.md`
- **DocText 구조+이미지(DR2, 2026-06-08)** — 스펙 `specs/2026-06-08-cleanpdf-doctext-structured-design.md` · 계획 `plans/2026-06-08-cleanpdf-doctext-structured.md` · 핸드오프 `handoff/2026-06-08-cleanpdf-doctext-structured-handoff.md`

## Status (2026-06-09) — PDF 줌/팬 **Matrix 재구성** 완료. 브랜치 `fix/statusbar-and-pdf-only` (main 미병합). 현재 **PDF 전용**.

reader를 RecyclerView+페이지비트맵에서 **opendwg `DrawingView`식 단일 Matrix 캔버스**로 재구성 — 줌인/줌아웃/팬/플링이 모두 매끄럽게(실폰 SM-G996N 확인). 워드/한글(DocText)은 조잡해서 진입만 비활성화(`doc/` 코드·hwplib 의존성·`DocTextActivity` 보존; 복구 = `MainActivity.route` 1줄 + manifest 문서 MIME 되돌리기). 공개 API가 전부 PDF점 좌표라 렌더 재구성에도 MainActivity·검색·선택 파이프라인은 무변경.

### 작동 중 ✅
- **Matrix 렌더/스크롤/줌/팬** — 페이지를 doc공간(PDF점, `PageWorld`)에 세로 스택, 단일 `Matrix`(doc→screen)로 그림(`PdfReaderView.onDraw` = `canvas.concat(matrix)` 후 비트맵 draw). 핀치 `postScale`(+두손가락 팬 `postTranslate`)·1손가락 팬·`OverScroller` 플링·더블탭(fit↔2.5x 애니)·clamp(스케일 [fit, 8×]·팬 콘텐츠 내). 배율이 멈추면(~120ms) 보이는 페이지를 `RenderScale.forPage(pageWpt*scale)` 배율로 재렌더(선명); 줌 중엔 캐시 비트맵을 matrix로 리샘플(부드러움, 재렌더 X).
- **임의 PDF 열기 / 파일 받기(카톡 VIEW·SEND) / 에러·암호 / 최근파일** — Phase 2 그대로.
- **탐색(목차·페이지점프)·썸네일·검색** — 그대로. 검색 하이라이트는 이제 `onDraw`에서 matrix로 그림(doc좌표 rect, 활성=주황/나머지=노랑). `scrollToHit`=matrix 세로이동. (썸네일 그리드는 여전히 별도 RecyclerView `ThumbnailAdapter`.)
- **텍스트 선택·복사** — long-press 단어선택 → 시작/끝 핸들 드래그 + **long-press 후 손 안 떼고 드래그로 범위 확장** + **다른 곳 탭으로 해제**. 선택 rect/핸들은 `onDraw`에서 matrix로(핸들은 화면 고정크기). 좌표는 역행렬 screen→doc→PDF점. 순수 `pdf/TextSelection`·`PageText`는 그대로(단일 페이지 범위).
- **UI 크롬 (2026-06-09)** — 제목 = 연 PDF **파일명**, **검은 바 + 흰 글씨**. 본문 **탭 → 툴바 숨김/표시**(immersive; 높이변경엔 줌 유지). **잡을 수 있는 스크롤바**(우측, 굵고 짧은 손잡이, 스크롤할 때만 표시·자동 fade, 드래그로 위치 점프). 하단 **"현재 / 전체" 페이지 인디케이터**(페이지 바뀌면 잠깐 떴다 사라짐).
- **테스트** — JVM 단위 0실패(`PageWorldTest` 추가; `PageLayoutTest`/`HighlightGeometryTest`/`SelectionGeometryTest` 제거), 계측 13/13(에뮬). 죽은 파일 삭제: `PdfPageAdapter`·`HighlightOverlayView`·`SelectionOverlayView`·`PageLayout`·`HighlightGeometry`·`SelectionGeometry`. ⚠️ `connectedDebugAndroidTest`는 실행 후 앱 APK를 **언인스톨**함 — 실기 수동검증은 그 전에(또는 `installDebug` 재설치).

### 진행 중 ⏳
- 이 브랜치 main 병합. 이후 다크모드/페이지반전(Phase 6), Phase 7 출시(**AGPL 고지** + GitHub 공개 레포 — 현재 원격 없음).
- 후속(우선순위 낮음): 교차페이지 선택, 고배율 타일링(현재 `RenderScale` 32MB 캡 이상은 업스케일), 가로 스크롤바(현재 없음 — 핀치 팬으로 충분), DocText 재활성화 여부 결정.
- 미검증: 실제 카톡 SEND·VIEW(S25), 암호 PDF 다이얼로그(픽스처 없음), 목차 점프(목차 있는 실제 PDF 필요).

## ⚠️ 아키텍처 불변조건 (깨면 크래시/OOM/줌 결함 재발 — 절대 되돌리지 말 것)

1. **단일 렌더 스레드** — 모든 `PdfDocument`/fitz 접근은 `PageRenderer` 의 단일 executor에서만 (fitz는 thread-safe 아님).
2. **Cookie 미사용** — fitz 1.27.1엔 Cookie 오버로드 없음. `renderPage(index, scale)` 2-arg. 취소 = `Future.cancel(true)` + `isInterrupted`. Cookie 재도입 금지.
3. **`onReady` 는 렌더 스레드 호출** — UI/캐시 만지려면 `View.post {}` 로 메인 재포스트.
4. **캐시 비트맵 `recycle()` 안 함** — `BitmapCache` 는 참조만 떨어뜨리고 GC에 맡김. recycle → "trying to use a recycled bitmap" 크래시. recycle 재도입 금지.
5. **렌더 스케일 캡** — `RenderScale.forPage` 가 비트맵 ≤32MB로 제한(OOM 방지). 캡 이상은 업스케일. 선명한 고배율 = 타일링(미래).
6. **줌 시 `cache.clear()` 안 함** — `PageKey` 에 `scaleMilli` 포함(스케일별 키). 배율별로 캐시·재렌더.
7. **줌/팬/스크롤은 `PdfReaderView.matrix` 한 곳** — 단일 `Matrix`(doc→screen)가 유일한 줌/스크롤 상태. `onDraw` 는 `canvas.concat(matrix)` 로 페이지 비트맵을 그림. 라이브 줌은 캐시 비트맵을 matrix로 리샘플(재렌더 X), 멈추면 재렌더. **RecyclerView·뷰 자체 `scaleX` 스케일·오버레이 자식뷰로 되돌리지 말 것** — 줌아웃 검은공간 쪼그라듦·핀치 떨림·커밋 깜빡임이 그 구조의 태생적 결함이었음(2026-06-09 폐기).
8. **공개 API = PDF점 좌표** — `setSelection`/`SearchHit`/`onLongPressPdf`/`scrollToPage` 등은 전부 PDF점. screen↔PDF점 변환은 뷰 내부 matrix(`PageWorld.pdfToDoc` + `matrix`)에서만. MainActivity·검색·선택 파이프라인은 렌더 방식과 무관(그래서 RecyclerView→Matrix 재구성에도 무변경).

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
