# CleanPDF Viewer — Phase 5 핸드오프 (텍스트 선택·복사)

> 작성일: 2026-06-06 · 상태: **Phase 5 완료, 브랜치 `feat/phase5-text-selection`** (main 병합 대기)
> 선행: `2026-06-05-cleanpdf-phase1-handoff.md`(§2 불변조건), `2026-06-06-cleanpdf-phase4_5-handoff.md`
> 계획서: `plans/2026-06-06-cleanpdf-phase5-text-selection.md` · 스펙: `specs/2026-06-05-cleanpdf-viewer-design.md` §4/§5.4

스펙 §5.4(롱프레스 선택 → 핸들 드래그 → 클립보드 복사)를 **순수 모델 엔진**으로 구현.

---

## 1. 완료 상태
- **길게 누르기 → 단어 스냅 선택**: 누른 위치의 단어(공백 사이 비공백 런)를 선택, 시작/끝 핸들 + 하단 바 표시.
- **핸들 드래그로 범위 조절**: 시작/끝 핸들을 끌어 선택 확장/축소. 드래그 중 렌더스레드 왕복 0(순수 Kotlin).
- **클립보드 복사**: 하단 바 "N자 선택 / 복사 / 닫기". 복사 시 "복사됨" 토스트.
- **범위**: 단일 페이지(스펙 비요구·YAGNI로 교차페이지 미지원).

검증: 단위 **58/58**, 계측 **8/8**, 실기 6항목(아래 §6) 모두 통과, 크래시 0.

## 2. 핵심 결정 — 순수 모델 엔진 (fitz 위임 대신)
사용자 확정 3결정: ① 순수 모델 ② 단일 페이지 ③ 하단 바.

long-press 시 fitz로 페이지를 **1회만** 파싱(`Page.toStructuredText().getBlocks()`)해 불변 `PageText`(문자 codepoint+bbox(PDF점)+lineIndex, android/fitz 타입 0)를 추출. 이후 **모든 선택 로직은 순수 Kotlin**:
- `nearestCharIndex`(점→최근접 문자), `wordRangeAt`(단어 스냅), `selectionRects`(라인별 병합 rect), `selectedText`(문자열+줄바꿈), `handlePoints`(시작/끝 앵커).
- 전부 JVM 단위테스트(15개). 드래그 중 렌더스레드 왕복 없음 → 즉응. **하이라이트와 복사가 같은 char-range에서 나와 WYSIWYG**(보이는 대로 복사).
- fitz는 "파싱" 한 가지만 담당(`getBlocks`가 읽기순서 제공). 단어스냅/문자열조립은 우리가 소유.

대안(fitz `snapSelection`/`highlight`/`copy` 위임)은 코드는 적지만 선택 로직이 네이티브라 JVM 테스트 불가 + 드래그마다 렌더스레드 왕복이라 기각.

## 3. 파일 (신규/수정)
**순수(JVM 테스트):**
- `pdf/PageText.kt` — `PageChar`(codepoint,x0,y0,x1,y1,lineIndex) + `PageText`(pageIndex, chars).
- `pdf/TextSelection.kt` — 순수 선택 수학 5함수.
- `view/SelectionGeometry.kt` — PDF점↔셀픽셀(역변환 `toPdfPoint` 포함). `HighlightGeometry` 미러.

**UI(계측/실기):**
- `view/SelectionOverlayView.kt` — 반투명 rect + 핸들 2개 그림. 순수 프레젠테이션(상태 없음), `HighlightOverlayView`의 형제.
- `pdf/PdfDocument.kt`(+`extractText`) / `pdf/PageRenderer.kt`(+`extractTextBlocking`) — 렌더 스레드 추출.
- `view/PdfPageAdapter.kt` — 셀에 3번째 오버레이 추가, 선택을 PDF점 저장→onBind 재투영.
- `view/PdfReaderView.kt` — long-press→PDF점, 핸들 hit-test/drag 가로채기, `setSelection`/`clearSelection`.
- `MainActivity.kt` — 검색과 동일한 controller-folded 패턴(begin/apply/drag/copy/close + 새 문서 시 리셋).
- `res/layout/activity_main.xml`(+`selection_bar`), `res/values/strings.xml`(+5 문자열).

**테스트:** `test/.../TextSelectionTest.kt`(12), `test/.../SelectionGeometryTest.kt`(3), `androidTest/.../TextExtractionSmokeTest.kt`, `androidTest/.../TextSelectionSmokeTest.kt`.

## 4. 불변조건 준수 (Phase 1 §2)
- **단일 렌더 스레드**: 추출은 `PageRenderer.extractTextBlocking`. `PageText`는 값으로 UI 스레드 횡단.
- **오버레이 ≠ 비트맵**: 선택은 형제 오버레이 뷰. 비트맵/`BitmapCache` 미접촉(키 미오염, recycle 0).
- **캐시-히트 early-return 前 적용**: 선택 투영을 onBind의 `if(cached!=null){...return}` 前에 둠(Phase 4.5 교훈).
- **줌 자동 추종**: 선택을 PDF점으로 저장 → `commitZoom→relayout→notifyDataSetChanged→onBind`가 새 `contentWidth`로 재투영. 실기 2.5배에서 글자 정렬 유지 확인.
- **좌표계 y-down, 뒤집기 없음**(Phase 4.5와 동일).

## 5. 제스처 중재 (가장 까다로웠던 부분)
- `tapDetector.onLongPress` → 화면점을 셀→PDF점으로 변환 → `onLongPressPdf` 콜백.
- 선택 활성 시 `onInterceptTouchEvent`가 ACTION_DOWN이 핸들 grab 반경(24dp) 안이면 제스처를 가로채(`return true`) RecyclerView 스크롤과 안 싸움. 이후 MOVE/UP는 `onTouchEvent`가 핸들 드래그로 처리. **핸들 안 잡았을 땐 기존 scale/tap/super 그대로**(줌·스크롤·더블탭 무변).
- **핀치 가드(코드리뷰 발견·수정)**: 핀치 중엔 `scaleX/scaleY=liveScale`라 셀 좌표가 변환좌표와 어긋남 → `onInterceptTouchEvent`에 `&& !scaleDetector.isInProgress` 추가해 핀치 중 핸들 grab 차단(핀치와 핸들드래그는 상호배타라 UX 손실 없음).
- 핸들 hit-test/드래그 매핑은 `cell.left/top` + `SelectionGeometry`로 long-press와 동일 스케일 사용.

## 6. 실기 검증 (emulator-5554, sample.pdf)
1. ✅ "brown" 길게누르기 → 단어 전체 선택(**5자**) + 핸들 2 + 바 "5자 선택/복사/닫기".
2. ✅ 끝 핸들 우로 드래그 → "brown fox 0123456789"(**20자**)로 확장, 시작 핸들 고정, 카운트 갱신.
3. ✅ 복사 → "복사됨" 토스트(클립보드에 선택 텍스트).
4. ✅ 닫기 → 하이라이트+핸들+바 제거(줌은 유지).
5. ✅ 선택 중 2.5배 줌 → 하이라이트+핸들이 같은 글자에 정렬 유지.
6. ✅ 빈 영역 길게누르기 → 최근접 단어로 스냅(**10자**, 크래시 0). (텍스트-없는 페이지의 `selection_none` 토스트는 `wordRangeOnEmptyPageIsNull` 단위테스트로 검증 — 텍스트 샘플에선 재현 불가.)

> 주의: 선택 직후 같은 위치를 다시 길게누르면 **기존 핸들 grab 반경에 걸려 드래그로 해석**됨(정상). 새 단어를 선택하려면 닫기 후 누르거나 핸들에서 떨어진 곳을 누를 것.

## 7. 이번 세션 함정/메모
- **`connectedDebugAndroidTest`는 실행 후 앱 APK를 언인스톨**함 → 실기 수동검증 중 앱이 사라짐. 순서: 수동검증을 먼저(또는 `installDebug` 재설치), 전체 계측은 마지막.
- **`connectedDebugAndroidTest`는 `--tests` 미지원** → 클래스 필터는 `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
- **에뮬레이터 장기세션 중 사망** 1회 → `emulator -avd Medium_Phone_API_36.1` 재부팅 후 재개(설치 앱 유지). adb 경로: `~/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
- 단어스냅이 4자로 보였던 건 직전 선택의 핸들을 잘못 잡은 드래그 아티팩트였음(실제 데이터로 `wordRangeAt`을 돌린 일회용 프로브로 "brown" 정확 확인 후 프로브 삭제).

## 8. 후속 / 미구현 (우선순위 낮음)
- 교차페이지 선택(현재 단일 페이지). 셀 간 연속 좌표공간·핸들추적 복잡도 큼.
- CJK는 공백이 없어 "단어"=공백 사이 런 전체로 스냅됨(핸들로 축소 가능). v1 허용.
- 핸들 드로어블 teardrop화(현재 원형), 겹친 핸들 시 START 우선(끝 핸들 grab 불가) → end 우선 보정 TODO.
- `MainActivity`(353줄)에 3번째 제스처 기능 추가 시 `TextSelectionController` 추출 권장.

## 9. 다음 = Phase 6 (다크모드 + 야간 읽기)
앱 크롬 다크(`values-night`) + 페이지 색 반전 토글 + 설정 화면. 상세는 스펙 §5.7.
