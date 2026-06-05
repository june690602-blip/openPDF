# CleanPDF Viewer — Phase 4 핸드오프 (검색)

> 작성일: 2026-06-05 · 상태: **Phase 4(전체 텍스트 검색) `main` 병합 완료** (`0cf0c35`)
> 선행 핸드오프: `2026-06-05-cleanpdf-phase1-handoff.md`(§2 불변조건), `2026-06-05-cleanpdf-phase3-handoff.md`(§4 저용량-모델 교훈)
> 계획서: `plans/2026-06-05-cleanpdf-phase4-search.md`

이 문서: Phase 4(스펙 §5.3 검색) 완료 상태 + 새 컴포넌트/결정 + 이번 실행(opus 직접)에서 배운 점 + 다음(Phase 4.5).

---

## 1. 완료 상태
스펙 §5.3 검색의 "전체검색 → 히트수 → 페이지 점프"가 동작 → `main`. (하이라이트·순차 다음/이전은 Phase 4.5로 분리.)
- **검색 입력**: 오버플로 "검색" → AlertDialog(EditText "검색어" + 취소/검색).
- **전체 검색**: `PageRenderer.searchBlocking(needle)`(렌더 스레드 직렬화) → `PdfDocument.search(needle, maxHits=500)` → 각 페이지 `Page.search(needle, SEARCH_IGNORE_CASE): Quad[][]`, 히트별 quad 합집합 bbox를 `SearchHit(page, x0,y0,x1,y1)`(PDF 포인트).
- **결과**: 히트 없으면 "검색 결과 없음" 토스트, 있으면 AlertDialog(제목 "N건" = `search_count`, 항목 "M쪽" = 순수 `SearchHits.labels`).
- **점프**: 항목 탭 → `PdfReaderView.scrollToPage(hit.page)`(기존 검증 경로).

검증: 단위 35/35(+SearchHits 2), 계측 5/5(+SearchSmoke: "Page" 3히트 + 무매치 빈결과), `assembleDebug` 성공, 실기(emulator-5554) "검색→3건(1·2·3쪽)→3쪽 탭 Page 3 점프" + 크래시 0.

## 2. 새 컴포넌트 / 결정 (불변조건 준수 방식)
- `pdf/SearchHit.kt`: `data class SearchHit(page, x0,y0,x1,y1)`(PDF 포인트 bbox) + 순수 `object SearchHits.labels(List<SearchHit>): List<String>`("${page+1}쪽"). 단위테스트 2(`SearchHitsTest`).
- `pdf/PdfDocument.search(needle, maxHits=500)`: 빈 검색어 가드, 페이지별 `loadPage→search→destroy`, `hits != null` 가드(무매치 시 fitz 방어), quad 합집합 bbox, `maxHits` 상한. **fitz 접근이라 반드시 렌더 스레드.**
- `pdf/PageRenderer.searchBlocking(needle)`: `exec.submit{ doc.search(needle) }.get()` — 불변조건 §1(단일 렌더 스레드) 준수. outline/sizes blocking과 동일 패턴.
- `MainActivity.showSearch()/showSearchResults()`: 검색은 `bg.execute{ r.searchBlocking → runOnUiThread{ 결과 } }`(UI 비블로킹). 결과 다이얼로그는 기존 outline/goto 패턴 그대로.
- 문자열 4(search/search_hint/search_none/search_count) + 메뉴 항목 1(action_search).
- **렌더/캐시/줌/썸네일 클래스 0 변경** — 검색은 읽기 전용 fitz 접근만 추가.

## 3. fitz 검색 API (AAR 1.27.1 — javap로 직접 검증, 추론 아님)
- `Page.search(String, int): Quad[][]`(2-arg, native) / `Page.search(String): Quad[][]`(1-arg). 둘 다 존재.
- `StructuredText.SEARCH_IGNORE_CASE = 1`(그 외 EXACT=0, IGNORE_DIACRITICS=2, REGEXP=4, KEEP_LINES=8…).
- `Quad{ float ul_x…lr_y }` + `Quad.toRect(): Rect`. `Rect{ float x0,y0,x1,y1 }`(public 필드, PDF 포인트).

## 4. 이번 실행(opus 직접)에서 배운 점 / 조정
- **계획 "전체 파일 교체" → surgical 추가로 수행**: 계획서의 전체 교체는 명시적으로 *저용량 모델용 안전장치*(phase3 handoff §4). opus 직접 실행이라 기능 코드만 정확히 추가하고 기존 KDoc/불변조건 주석(특히 `showDocument`의 RejectedExecutionException 설명)을 보존. diff +127/-0(삭제 0).
- **검색 자동 계측이 검색을 직접 커버**: 샘플 PDF에 "CleanPDF - Page N" 텍스트가 있어 `SearchSmokeTest`가 "Page"(존재)+무매치(빈)를 자동 검증 — 목차(샘플에 없음)와 달리 디바이스 멀티스텝 탭에 덜 의존. (계획서가 예측한 강점.)
- **⚠️ 에뮬 한글 IME가 `adb input text` 깨뜨림**: Gboard 한글 조합 모드에서 "Page"→"ㅎ드". `ime disable`로 IME 끄고 입력 후 복구로 우회(CLAUDE.md Gotchas에 추가). 향후 영문 입력 UI 자동화에 재발 주의.
- **저장공간 부족 재발**: 설치/계측 전 `pm trim-caches` 필요(기존 gotcha 확인). 첫 설치 직후 스크린샷은 패키지 REPLACED 전환 타이밍이라 sleep 넉넉히.

## 5. 미검증 / 후속
- **검색 점프 시 툴바 인디케이터**: `scrollToPage`로 Page N 상단 정렬 시, 위에 걸친 N-1 페이지를 `onPageChanged`가 잡아 "N-1/전체"로 1 차이 표기될 수 있음(Phase 3 기존 근사 동작, 검색 무관). Phase 4.5 `scrollToHit`에서 페이지 내 y정렬 시 같이 다듬을 여지.
- **대용량 PDF 검색 체감**: `searchBlocking`이 bg→렌더 스레드라 UI 비블로킹 + `maxHits=500`이지만, 수백 페이지 PDF의 검색 시간/진행표시는 미검증(샘플 3p). 필요 시 진행 인디케이터.
- (Phase 1·2 미검증 여전) 실제 카톡 SEND·VIEW(S25), 암호 PDF 다이얼로그.

## 6. 다음 = Phase 4.5 (검색 하이라이트 + 순차 이동)
스펙 §5.3 나머지. 핵심:
- **순수 `SearchCursor`**(히트목록 + 현재 인덱스 + 다음/이전 wrapping, TDD) — UI 상태.
- **검색바**(하단): 이전/다음 버튼 + "현재/전체" 카운트.
- **하이라이트 오버레이**: `SearchHit` rect(PDF 포인트) → 페이지 픽셀 변환(렌더 scale·페이지 offset) → 페이지 셀 위 오버레이로 그림. **불변조건 주의**: 비트맵 합성은 캐시 오염(scale별 키) → 오버레이 뷰 권장, recycle 금지.
- **`scrollToHit(page, rectFracY)`**: 페이지 + 페이지 내 세로 위치로 스크롤.
시작 전 phase1 handoff §2 불변조건 + 이 핸드오프 §4 IME/surgical 교훈 읽을 것.
