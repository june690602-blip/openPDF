# CleanPDF Viewer — Phase 1 완료 핸드오프 & Phase 2 백로그

> 작성일: 2026-06-05 (금) · 상태: **Phase 1 `main` 병합 완료** (`04602cb`)
> 스펙: `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md`
> Phase 0–1 계획: `docs/superpowers/plans/2026-06-05-cleanpdf-phase0-1.md`

이 문서의 목적: 다음 세션이 **Phase 2를 바로 시작**할 수 있도록 (1) 현재 상태, (2) 깨면 안 되는 아키텍처 불변조건, (3) 우선순위별 백로그를 한곳에 모은다.

---

## 1. Phase 1 상태 (완료)

`main` 에 연속 스크롤 + 핀치/더블탭 줌 + 임의 PDF 열기(SAF)까지 동작하는 PDF 뷰어가 올라가 있다.

- **렌더 파이프라인**: PDF(content:// 또는 asset) → 캐시 파일 복사 → MuPDF `fitz` 1.27.1(프리빌트 AAR) → 단일 렌더 스레드에서 페이지 비트맵 생성 → Bitmap LRU 캐시 → RecyclerView 한 행=한 페이지.
- **검증(증거 기반)**: 단위 18/18, 계측 2/2(`RenderSmokeTest` + `ScrollZoomSmokeTest`), `assembleDebug` 성공. 실기기(emulator-5554)에서 연속 스크롤·줌 재렌더·다른 PDF 선택→로드(스크린샷 증거) 확인, 크래시 0.
- **커밋 범위**: `3712137`(LRU 캐시) … `04602cb`(현재 HEAD). Phase 1 = 14커밋.

### 작동 중 ✅
- 연속 세로 스크롤(RecyclerView, 온디맨드 백그라운드 렌더), 가시 페이지만 렌더.
- 핀치 줌(라이브 프리뷰 → 손 떼면 정밀 재렌더) + 더블탭 줌(1↔2.5배), 최대 8배.
- 임의 PDF 열기: 툴바 오버플로 "PDF 열기" → SAF `OpenDocument`(application/pdf 필터) → `PdfSource.copyToCache` → 렌더. (샘플 `assets/sample.pdf` 는 첫 실행 시 자동 오픈 — dev 편의)
- 단위 테스트: `LruByteSizedCache`(5), `PageLayout`(5), `PdfValidation`(5), `RenderScale`(3).

---

## 2. ⚠️ 아키텍처 불변조건 (Phase 2에서 절대 깨지 말 것)

이 결정들은 **실제 크래시/OOM을 막기 위해** 내려졌다. 모르고 되돌리면 그 크래시가 재발한다. (대부분 코드 주석에도 적혀 있음.)

1. **단일 렌더 스레드** — 모든 `PdfDocument`/fitz 접근은 `PageRenderer` 의 단일 executor에서만 한다. fitz는 thread-safe 아님. (`pdf/PageRenderer.kt`)
2. **Cookie 미사용** — fitz 1.27.1엔 `AndroidDrawDevice.drawPage` Cookie 오버로드가 **없다**. `renderPage(index, scale)` 는 2-arg. 취소는 `Future.cancel(true)` + 렌더 전 `Thread.isInterrupted` 체크. **Cookie/`CancelableFuture` 재도입 금지.**
3. **`onReady` 는 렌더 스레드에서 호출** — `PageRenderer.submit` 콜백은 렌더 스레드에서 실행된다. UI나 메인-스레드 소유 상태(캐시)를 만지려면 `View.post {}` 로 메인에 재포스트해야 한다. (`PdfPageAdapter` 가 `holder.itemView.post {}` 사용)
4. **캐시 비트맵을 절대 `recycle()` 하지 않는다** — `BitmapCache` 는 `onEvict` 없이(=no-op) LRU 참조만 떨어뜨리고 GC에 맡긴다. 화면(또는 RecyclerView `mCachedViews`)에 붙어 있는 비트맵을 `recycle()` 하면 다음 드로우에서 `"trying to use a recycled bitmap"` 크래시. **`recycle()` 재도입 금지.** (`cache/BitmapCache.kt`)
5. **렌더 스케일 캡(메모리 상한)** — `RenderScale.forPage` 가 페이지 비트맵을 **≤32MB(ARGB_8888)** 로 제한한다. 안 그러면 8배 줌에서 한 페이지가 ~400MB → OOM/예산초과. 캡 이상은 업스케일(약간 흐림). 픽셀 선명한 고배율은 **타일링 필요(Phase 2 백로그 B-3).** (`pdf/RenderScale.kt`)
6. **줌 시 `cache.clear()` 안 함** — `PageKey` 에 `scaleMilli` 가 들어가 스케일별로 키가 다르다. 옛 스케일 비트맵은 그냥 캐시 미스(재렌더)되고 LRU가 예산으로 정리한다. (`PdfReaderView.commitZoom`)
7. **줌 시 행 너비 = `contentWidth`** — `MATCH_PARENT` 로 두면 `FIT_CENTER` 가 확대 비트맵을 뷰 너비로 줄여 **줌이 무력화**된다. 행 너비를 `contentWidth`(=뷰너비×zoom)로 잡아야 확대가 보인다. (`PdfPageAdapter.onBindViewHolder`)
8. **2번째 PDF 열 때 `openFile` 순서** — 새 렌더러를 **완전히 만든 뒤** 어댑터를 교체(`setDocument`)하고, **그 다음에** 옛 렌더러를 shutdown 한다. 먼저 shutdown하면 아직 붙어 있는 옛 어댑터가 종료된 executor에 submit → `RejectedExecutionException`. (`MainActivity.openFile`)
9. **`@Volatile private var renderer`** — bg 스레드에서 쓰고 메인(onDestroy)에서 읽음. (`MainActivity`)
10. **LRU 덮어쓰기 시 onEvict 호출** — `LruByteSizedCache.put` 은 같은 키 덮어쓸 때 옛 값에도 `onEvict` 를 부른다(범용 동작, 테스트됨). `BitmapCache` 는 onEvict를 안 넘기므로 영향 없음 — 단, 범용 캐시 의미는 유지.

> 한 줄 요약: **단일 스레드 + Cookie 없음 + recycle 없음 + 렌더 캡 + clear 없음** — 이 다섯이 깨지면 크래시/OOM이 돌아온다.

---

## 3. Phase 2 백로그 (우선순위별)

### A. 스펙 로드맵 다음 기능 = "파일 받기" (Phase 2 본 목표) — 스펙 §11

카톡 등 외부앱에서 받은 PDF를 바로 여는 흐름. **이미 만들어 둔 조각을 재사용**하면 빠르다.

- **A-1. VIEW 인텐트 받기** — `AndroidManifest` 에 `ACTION_VIEW` + PDF MIME 필터 추가(`application/pdf`, 카톡 대비 `application/octet-stream` 도 — opendwg 패턴 참고). `MainActivity` 가 `intent.data`(content://) → `PdfSource.copyToCache` → `openFile`.
- **A-2. SEND 인텐트 받기** — `ACTION_SEND` + `EXTRA_STREAM` 처리(`IntentCompat.getParcelableExtra`). 안드 11+ 패키지 가시성으로 VIEW가 안 닿는 경우의 우회 경로(opendwg Phase 11.2 핸드오프에 동일 이슈/해법 있음).
- **A-3. `isLikelyPdf` 게이트 연결** — `pdf/PdfValidation.isLikelyPdf(name, head)` 는 **이미 구현+단위테스트(5개) 완료**. octet-stream 등 비-PDF를 파싱 전에 차단하는 데 연결만 하면 됨.
- **A-4. 최근 파일** — 영속 리스트(`RecentFilesStore`, 불변 모델). 연 파일 기록 → 시작화면/메뉴에서 재열람. (스펙 §4 컴포넌트표)

> 활용 가능한 기존 자산: `PdfSource.copyToCache`(content://→캐시), `isLikelyPdf`(검증), `PdfOpenResult`(결과 모델, 아래 B-1에서 연결).

### B. Phase 1에서 연기된 기술 후속 (기능과 병행 또는 별도)

- **B-1. 친절한 에러 화면 (우선순위 높음)** — 현재 `MainActivity.loadFromUri` 는 `runCatching {}` 로 실패를 **조용히 삼킨다**(사용자 피드백 없음). `error_open` 문자열은 정의돼 있으나 미사용. **`PdfOpenResult`(Success/NeedsPassword/Error) 모델은 이미 있음** → 열기 실패/손상/암호 PDF 시 원인 + 돌아가기 안내 화면으로 연결. 암호 PDF는 `PdfDocument.needsPassword()`/`authenticate()`(이미 있음)로 비번 다이얼로그.
- **B-2. 비트맵 예산 튜닝 (우선순위 높음, 저사양 안전)** — 현재 96MB 고정(코드에 "tune later" 주석). **API 24/25는 비트맵이 Java 힙**(API 26+부터 네이티브)이라, 저사양 누가 기기에서 고배율 빠른 스크롤 시 OOM 위험. → 예산을 `ActivityManager.memoryClass`/`Runtime.maxMemory()` 비례로 스케일. (선택) 떼어진(detached) 비트맵만 골라 즉시 recycle하는 "attachment-aware recycle" 로 더 타이트하게. `largeHeap` 도 검토.
- **B-3. 타일링 (고배율 선명도)** — 현재 렌더 캡(32MB) 때문에 ~2.3배 이상은 업스케일(흐림). 진짜 선명한 고배율(도면 디테일)은 **보이는 뷰포트 영역만 타일로 고해상 렌더**하는 방식 필요. MuPDF 레퍼런스 뷰어(`mupdf-android-viewer`, AGPL) 위젯 참고. 큰 작업 — 별도 phase로 분리 권장.
- **B-4. 제스처 폴리시 (우선순위 낮음)** — (a) 핀치 시 **포커스 포인트 고정**(현재는 뷰 중심 기준 확대), (b) 확대 시 **가로 패닝**(현재 가로는 잘림), (c) 줌 커밋 시 `notifyDataSetChanged` 가 대형 문서에서 **스크롤 앵커를 잃을 수 있음**(현재 visible 페이지 위치 보존 안 함). 셋 다 Phase 1에서 의도적으로 연기.

### C. 그 외 스펙 로드맵 (Phase 3~7) — 참고

목차(outline)/썸네일/페이지점프(3), 검색(4), 텍스트 선택·복사(5), 다크모드+야간 반전(6), 출시 준비(R8/아이콘/스토어 listing KR·EN/개인정보 수집0/서명/**AGPL 고지**)(7). 상세는 스펙 §11.

> **출시 전 필수**: AGPL v3 → 소스 공개 의무. GitHub 공개 레포 + 앱 내 "오픈소스 라이선스" 화면(소스 링크 + AGPL 전문 + MuPDF 고지) + 스토어 설명 소스 링크. (자매앱 CleanCAD 패턴) 현재 openPDF는 **git 원격 없음** — 출시 준비 단계에서 세팅.

---

## 4. 빌드 / 테스트 / 실행 빠른 참조

- 빌드: `./gradlew :app:assembleDebug`
- 단위 테스트(JVM, 에뮬 불필요): `./gradlew :app:testDebugUnitTest`
- 계측 테스트(에뮬 필요, AVD `Medium_Phone_API_36.1`): `./gradlew :app:connectedDebugAndroidTest`
- 스택: Kotlin + Android Views, minSdk 24 / targetSdk 36, AGP 9.1.1 / Gradle 9.3.1, `com.artifex.mupdf:fitz:1.27.1`(maven.ghostscript.com). **NDK/JNI 직접 빌드 없음**(opendwg와 달리 MuPDF는 프리빌트 AAR).
- applicationId / 패키지: `io.github.june690602_blip.cleanpdf`
- 라이선스: **AGPL v3**(MuPDF 결합).
- ⚠️ 에뮬레이터 저장공간 부족 시: `adb -s emulator-5554 shell pm trim-caches 9999999999` 후 재시도. native가 아니라 순수 AAR이라 `install -r` 로 충분(opendwg의 .so 갱신 함정 없음).
- 루트 `*.png`(증거 스크린샷)와 `app/build/` 는 `.gitignore` 처리됨.

---

## 5. 컴포넌트 빠른 지도

| 파일 | 책임 |
|---|---|
| `pdf/PdfDocument.kt` | MuPDF `Document` 래퍼 (열기·페이지수·페이지크기·렌더·암호) |
| `pdf/PageRenderer.kt` | 단일 스레드 렌더, `submit(page,scale,onReady):Future` (취소 가능, Cookie 없음) |
| `pdf/RenderScale.kt` | 순수: 메모리 안전 렌더 스케일 캡(≤32MB) |
| `pdf/PageSize.kt` / `PdfValidation.kt` / `PdfOpenResult.kt` | 페이지크기 / `isLikelyPdf`(테스트됨) / 열기결과 모델(미연결) |
| `cache/LruByteSizedCache.kt` | 순수: 바이트예산 LRU (테스트됨) |
| `cache/BitmapCache.kt` | Bitmap LRU (recycle 안 함; 키=페이지+scaleMilli) |
| `view/PageLayout.kt` | 순수: 세로 스택 레이아웃 수학 (테스트됨) |
| `view/PdfPageAdapter.kt` | RecyclerView 어댑터, 비동기 렌더→캐시→표시, 재활용 시 취소 |
| `view/PdfReaderView.kt` | RecyclerView 서브클래스, 핀치/더블탭 줌, `commitZoom` |
| `io/PdfSource.kt` | content:// → 캐시 파일 복사 (인텐트/피커 공용) |
| `MainActivity.kt` | 툴바+메뉴, SAF 피커, 공용 `openFile`, 생명주기 |

---

**다음 세션 시작점**: 위 §3-A("파일 받기")가 스펙상 Phase 2 본 목표. §2 불변조건을 지키면서, B-1(에러 화면)·B-2(예산 튜닝)는 함께 끼워 넣기 좋음. 시작 전 이 문서 + 스펙 §5–6 + 불변조건(§2) 먼저 읽을 것.
