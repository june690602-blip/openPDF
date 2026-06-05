# CleanPDF Viewer — Phase 2 완료 핸드오프 & Phase 3 백로그

> 작성일: 2026-06-05 (금) · 상태: **Phase 2 `main` 병합 완료** (`d21fd63`)
> 스펙: `docs/superpowers/specs/2026-06-05-cleanpdf-viewer-design.md`
> Phase 2 계획: `docs/superpowers/plans/2026-06-05-cleanpdf-phase2-intake.md`
> Phase 1 핸드오프(불변조건 원문): `docs/superpowers/handoff/2026-06-05-cleanpdf-phase1-handoff.md`

이 문서: 다음 세션이 **Phase 3를 바로 시작**할 수 있도록 (1) Phase 2가 더한 것, (2) 미검증 항목, (3) Phase 3 백로그를 모은다.

---

## 1. Phase 2 상태 (완료) — "파일 받기 + 에러처리 + 최근파일"

`main` 에 카톡 등 외부앱에서 받은 PDF를 VIEW/SEND로 열고, 손상/비-PDF/암호를 친절히 처리하며, 최근 연 파일을 재열람하는 흐름이 올라가 있다.

- **커밋 범위**: `f418cf3`(Intents 셀렉터) … `d21fd63`(계측 스모크). Phase 2 = 7커밋, fast-forward 병합.
- **검증(증거 기반)**: 단위 26/26, 계측 3/3(`RenderSmokeTest`+`ScrollZoomSmokeTest`+`IntentIntakeSmokeTest`), `assembleDebug` 성공. 실기(emulator-5554):
  - VIEW 인텐트로 받은 PDF 열람(계측 + 수동: 앱 자체 파일 `copyToCache` → 렌더, `opened_*` 생성 확인).
  - SEND 인텐트(`EXTRA_STREAM`)로 받은 PDF 열람(수동: `copyToCache` → 렌더).
  - 비-PDF/읽기불가 → "PDF 파일이 아닙니다" 에러 오버레이, 크래시 0.
  - 최근 파일: 열람 시 SharedPreferences 기록 → 오버플로 "최근 파일" 다이얼로그 → 재열람(2번째 open, `RejectedExecutionException` 없음 = 불변조건 §2-8 유지).

### Phase 2가 더한 것
| 영역 | 내용 |
|---|---|
| 인텐트 인입 | `AndroidManifest` VIEW(content/file·pdf/x-pdf/octet-stream) + SEND 필터. `MainActivity.onCreate` 가 인입 URI 우선, 없으면 dev-샘플. |
| 순수 셀렉터 | `io/Intents.kt` — `incomingUri(action, viewData, sendStream)` (VIEW→data, SEND→stream). 단위테스트 4. |
| 비-PDF 차단 | `io/PdfSource.looksLikePdf(context, uri)` — 이름(.pdf)·매직헤더(%PDF) 로 `pdf/isLikelyPdf` 호출. |
| 결과형 열기 | `pdf/PdfDocument.openResult(path): PdfOpenResult`. `PdfOpenResult.NeedsPassword` 가 문서를 들고 다님(`data object`→`data class`). |
| 에러/암호 UI | `activity_main.xml` 에 `error_view` 오버레이. `MainActivity.showError/promptPassword`. |
| 최근 파일 | `store/RecentFilesStore.kt` — `RecentFile`(불변) + `RecentFilesLogic`(순수 add/dedup/cap/serialize) + SharedPreferences 래퍼. 단위테스트 4. |

---

## 2. ⚠️ 아키텍처 불변조건 — Phase 1 §2 그대로 유효 (Phase 2도 지킴)

Phase 1 핸드오프 §2의 10개 불변조건(단일 렌더 스레드 / Cookie 없음 / recycle 없음 / 렌더 캡 / clear 없음 / `openFile` shutdown 순서 등)을 **Phase 2는 깨지 않았다.** 특히:

- **§2-8 (openFile 순서)**: `showDocument(doc, file)` 가 "새 렌더러 완성 → `setDocument` → 옛 렌더러 shutdown" 순서 유지. 최근파일 재열람(=2번째 open)에서 `RejectedExecutionException` 없음 확인.
- **문서 열기 스레드**: `openResult`/`needsPassword`/`authenticate` 는 `MainActivity.bg`(단일 스레드)에서 호출되고, 이후 doc 은 `PageRenderer` 의 단일 executor로만 접근(순차 핸드오프, 동시접근 없음 — Phase 1 `open` 패턴과 동일).

> Phase 3에서 새 화면/스레드를 더할 때 위 불변조건 재확인 필수.

---

## 3. Phase 2 미검증 항목 (실기/픽스처 필요 — Phase 3 또는 사용자 확인)

에뮬레이터에서 자동화 가능한 경로는 모두 검증했다. 아래는 본질적으로 실기기 또는 별도 픽스처가 필요해 **런타임 미검증**:

1. **실제 카톡 VIEW/SEND (사용자 S25, 안드16)** — 인텐트 필터 해석은 `query-activities` 로 확인했고 코드 경로는 에뮬에서 동작. 단, S25 카톡 미리보기 "열기"는 패키지 가시성 한계로 SEND 경로 권장(opendwg Phase 11.2 동일). 실기 확인은 사용자 몫.
2. **암호 PDF 다이얼로그** — `promptPassword` 는 배선·컴파일됐으나 암호화 PDF 픽스처가 없어 런타임 미검증. Phase 3에서 pikepdf 등으로 암호화 PDF 만들어 계측테스트 추가 권장.
3. **최근항목 캐시삭제 토스트** — `showRecent` 의 `f.exists()==false` 분기(없어진 항목 제거+안내). 로직만, 런타임 미검증.

### 검증 시 함정 메모
- **scoped storage(API 36)**: 무권한 앱은 adb 가 `shell` 소유로 외부저장소에 푸시한 파일을 `file://` 로 못 읽는다. 실제 카톡/파일앱은 읽기권한 부여된 `content://` 를 주므로 동작. 수동 인텐트 검증은 **앱 자체 파일**(cache/외부파일 디렉토리)이나 계측테스트(`getExternalFilesDir`+`Uri.fromFile`)로.
- **`connectedDebugAndroidTest` 는 끝나며 앱을 언인스톨**한다 — 이후 수동 실기 검증은 `installDebug` 재설치 필요.
- **Robolectric 4.14.1 은 API 36 미지원** — `org.json` 쓰는 단위테스트는 `@RunWith(RobolectricTestRunner)` + `@Config(sdk=[34])` 필요.

---

## 4. Phase 3 백로그 (우선순위)

스펙 §11 로드맵 다음 단계.

### A. Phase 3 본 목표 = 탐색 (목차/썸네일/페이지 점프)
- **A-1. 목차(outline)** — `Document.loadOutline()`(fitz) → 트리 → 탭하면 해당 페이지로 점프. 단일 렌더 스레드에서 outline 로드.
- **A-2. 썸네일** — 사이드/그리드에 페이지 축소 렌더(저해상 스케일). 기존 `PageRenderer`/`BitmapCache` 재사용, 별도 저해상 키.
- **A-3. 페이지 점프** — "n/총N" 표시 + 페이지 번호 입력/슬라이더로 스크롤 위치 이동(`PageLayout` 의 오프셋 수학 재사용).

### B. 이후 로드맵 (Phase 4~7)
- 검색(4, `Page.search`), 텍스트 선택·복사(5), 다크모드+야간 반전(6), **출시 준비(7)** — R8/아이콘/스토어 listing(KR·EN)/개인정보(수집0)/서명/**AGPL 고지**.
- ⚠️ **출시 전 필수**: AGPL v3 소스 공개 의무 → GitHub 공개 레포(**현재 원격 없음**) + 앱 내 "오픈소스 라이선스" 화면(소스 링크 + AGPL 전문 + MuPDF 고지) + 스토어 설명 소스 링크. (자매앱 CleanCAD 패턴)

### C. Phase 1에서 연기된 기술 후속 (병행 가능)
- B-2 비트맵 예산 튜닝(저사양 OOM 안전), B-3 타일링(고배율 선명도), B-4 제스처 폴리시(포커스 핀치/가로 패닝/줌 앵커). 상세는 Phase 1 핸드오프 §3-B.

---

## 5. 컴포넌트 빠른 지도 (Phase 2 추가분 ★)

| 파일 | 책임 |
|---|---|
| `io/Intents.kt` ★ | 순수 VIEW/SEND 셀렉터 |
| `io/PdfSource.kt` | content://→캐시 복사 + `looksLikePdf` ★ |
| `pdf/PdfDocument.kt` | MuPDF 래퍼 + `openResult` ★(결과형 열기) |
| `pdf/PdfOpenResult.kt` | Success/NeedsPassword(doc)★/Error |
| `pdf/PdfValidation.kt` | `isLikelyPdf`(순수, 테스트됨) |
| `store/RecentFilesStore.kt` ★ | `RecentFile` + `RecentFilesLogic`(순수) + SharedPreferences |
| `MainActivity.kt` | 인텐트 분기·`openFile`/`showDocument(doc,file)`/`showError`/`promptPassword`/`showRecent` |
| `res/layout/activity_main.xml` | reader + `error_view` 오버레이 ★ |
| `res/menu/reader_menu.xml` | "PDF 열기" + "최근 파일" ★ |

(렌더 파이프라인 컴포넌트 — `PageRenderer`/`RenderScale`/`BitmapCache`/`PageLayout`/`PdfReaderView`/`PdfPageAdapter` — 은 Phase 1 핸드오프 §5 참조, Phase 2에서 무변경.)

---

**다음 세션 시작점**: 위 §4-A(탐색)가 스펙상 Phase 3. §2 불변조건 유지하며 §3 미검증 항목(특히 암호 PDF 계측)을 함께 끼워 넣기 좋음. 시작 전 이 문서 + 스펙 §11 먼저 읽을 것.
