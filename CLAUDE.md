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
- Phase 0–1 plan (완료): `docs/superpowers/plans/2026-06-05-cleanpdf-phase0-1.md`
- **Phase 1 핸드오프 + Phase 2 백로그**: `docs/superpowers/handoff/2026-06-05-cleanpdf-phase1-handoff.md`
- Phase 2 plan (다음 작업): `docs/superpowers/plans/2026-06-05-cleanpdf-phase2-intake.md`

## Status (2026-06-05) — Phase 2 완료, `main` 병합됨

**현재 `main` HEAD: Phase 2 코드 = `d21fd63`.** 연속 스크롤 + 핀치/더블탭 줌 + 임의 PDF 열기(SAF) + 카톡 등 VIEW/SEND 인텐트로 받기 + 에러/암호 화면 + 최근 파일까지 동작.

### 작동 중 ✅
- **연속 세로 스크롤** — RecyclerView, 온디맨드 백그라운드 렌더, 가시 페이지만 렌더(±버퍼).
- **핀치 줌 + 더블탭 줌(1↔2.5배), 최대 8배** — 핀치 중 라이브 프리뷰(scaleX/Y), 손 떼면 정밀 재렌더.
- **임의 PDF 열기** — 툴바 오버플로 "PDF 열기" → SAF `OpenDocument`(application/pdf) → `PdfSource.copyToCache` → 렌더. 샘플 `assets/sample.pdf` 는 인입 인텐트가 없을 때만 첫 실행 자동 오픈(dev 편의).
- **파일 받기 (Phase 2)** — 카톡 등 외부앱 "열기(VIEW)" + 시스템 공유시트 "공유(SEND)" 수신. `AndroidManifest` VIEW/SEND 필터(application/pdf·x-pdf·octet-stream — 카톡 octet-stream 대비). `Intents.incomingUri`(순수 셀렉터, 단위테스트) → `MainActivity.onCreate` 분기(`IntentCompat.getParcelableExtra` 로 SEND `EXTRA_STREAM`) → `PdfSource.looksLikePdf`(`isLikelyPdf` 게이트, 비-PDF 파싱 전 차단) → `copyToCache`(content:// 핸들 만료 회피) → 열람. ⚠️ 안드16(S25+) 카톡 미리보기 "열기"는 패키지 가시성 한계 → SEND 경로 권장(opendwg Phase 11.2 동일 이슈).
- **에러/암호 화면 (Phase 2)** — `PdfDocument.openResult` → `PdfOpenResult`(Success/NeedsPassword/Error). 손상·비-PDF → 에러 오버레이(`error_view`), 암호 PDF → 비번 다이얼로그(`needsPassword()/authenticate()`, 오답 시 재입력). 기존의 조용한 `runCatching` 실패 삼킴 제거.
- **최근 파일 (Phase 2)** — `RecentFilesStore`(SharedPreferences, 불변 `RecentFile`, newest-first·dedup·cap=10). 열람 시 기록, 오버플로 "최근 파일" → 다이얼로그에서 재열람(캐시에서 사라진 항목은 안내 후 목록서 제거).
- **테스트** — 단위 26(`LruByteSizedCache`5 + `PageLayout`5 + `PdfValidation`5 + `RenderScale`3 + `Intents`4 + `RecentFilesLogic`4), 계측 3(`RenderSmokeTest` + `ScrollZoomSmokeTest` + `IntentIntakeSmokeTest`). 실기(emulator) VIEW/SEND 인입·비-PDF 에러·최근 재열람 검증, 크래시 0. ⚠️ `RecentFilesLogic` 은 org.json 때문에 Robolectric 러너 사용(`@Config(sdk=[34])` — Robolectric 4.14.1 이 API 36 미지원).

### 진행 중 ⏳ — 다음 세션 = Phase 3 (목차/썸네일/페이지 점프)
- 스펙 §11 로드맵 다음 기능: outline(목차)·썸네일·페이지 점프. 이후 검색(4)·텍스트 선택(5)·다크/야간 반전(6)·출시 준비(7, **AGPL 고지 필수** + GitHub 공개 레포 — 현재 원격 없음).
- **미검증(실기/픽스처 필요, 다음 세션/사용자 확인)**: 실제 카톡 SEND·VIEW(사용자 S25 기기), 암호 PDF 비번 다이얼로그(암호화 PDF 픽스처 없어 런타임 미검증 — 코드는 배선·컴파일됨), 최근항목 캐시삭제 토스트(`f.exists()` 분기, 로직만).
- 상세: `docs/superpowers/handoff/2026-06-05-cleanpdf-phase2-handoff.md`.

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
