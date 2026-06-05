# CleanPDF Viewer — Phase 4.5 핸드오프 (검색 하이라이트 + 순차 이동)

> 작성일: 2026-06-06 · 상태: **Phase 4.5 `main` 병합 완료** (코드 `2a81170`)
> 선행: `2026-06-05-cleanpdf-phase1-handoff.md`(§2 불변조건), `2026-06-05-cleanpdf-phase4-handoff.md`(§2 검색)
> 계획서: `plans/2026-06-05-cleanpdf-phase4_5-highlight.md`

Phase 4의 검색 결과 다이얼로그를 **검색바 + 하이라이트**로 진화 (스펙 §5.3 나머지).

---

## 1. 완료 상태
- **검색바(하단)**: ◀ "현재/전체" ▶ + 검색 닫기. 검색 시 첫 히트로 자동 스크롤.
- **하이라이트**: 모든 히트 형광(연노랑), 활성 히트 진한 주황. 글자 위 정렬.
- **다음/이전**: `SearchCursor` wrapping. 활성 이동 + `scrollToHit` + 카운트 갱신.
- **닫기**: 하이라이트 제거 + 바 숨김.

검증: 단위 43/43, 계측 6/6, 실기(emulator-5554: 검색→하이라이트+바 "1/3"→다음 "2/3" 활성 Page 2→닫기 제거), 크래시 0.

## 2. 새 컴포넌트 / 결정
- `pdf/SearchCursor.kt`: 순수 — `hits`+`index`, `next/prev` wrapping, `position`(1-based), `current`, `size`, empty-safe. 단위 5.
- `view/HighlightGeometry.kt`: 순수 — PDF점→셀픽셀. **`FloatArray[l,t,r,b]` 반환**(android 타입 회피 → JVM 단위 3). `scale = contentWidth/pageWidthPts`.
- `view/HighlightOverlayView.kt`: 셀 위 반투명 rect 그림(활성 강조). `onDraw`만, PDF 상태 없음.
- `view/PdfPageAdapter`(수정): 셀 `FrameLayout`에 오버레이 추가(ImageView 위). `setHighlights(hits, active)`. ⚠️ **하이라이트 적용은 캐시-히트 early-return 前**에 둠 — 안 그러면 캐시된 페이지에 하이라이트 누락(계획 버그, 실행 중 수정).
- `view/PdfReaderView`(수정): `lastLayout` 보관(relayout에서). `setSearchHighlights`/`clearSearchHighlights`/`scrollToHit`(lastLayout.contentWidth scale로 PDF점→픽셀, viewport 상단 1/4 위치).
- `MainActivity`(수정): 결과 다이얼로그(`showSearchResults`) **제거** → `openSearch`(바 표시+cursor 생성)/`stepSearch`(±1 wrap)/`applyCursor`(하이라이트+scrollToHit+카운트)/`closeSearch`. 미사용 `SearchHits` import 정리.
- 레이아웃: `activity_main.xml` FrameLayout 하단 `search_bar`(gone). 문자열 4(prev/next/close/position).

## 3. 불변조건 준수
- 하이라이트 = **오버레이 뷰**(비트맵/캐시 scale별 키 미오염, recycle 0). fitz 접근 추가 없음(Phase 4 `searchBlocking` 재사용 — 단일 렌더 스레드 유지).
- 줌 → `relayout` → `notifyDataSetChanged` → onBind가 새 `contentWidth`로 하이라이트 재계산(줌 추종).

## 4. 좌표계 (실증)
- fitz `Page.search` quad/Rect = page space **y-down(top-left)**. 렌더 `Matrix(scale)` 동일계.
- `pixel = pt * (contentWidth/pageWidthPts)`, x·y 동일 scale. **y 뒤집기 불필요** — 실기에서 하이라이트가 글자 위 정확 정렬 확인.

## 5. 이번 실행 메모
- **계획 버그(캐시-히트 하이라이트 누락)를 실행 중 수정**: onBind early-return 전으로 하이라이트 이동.
- **에뮬 재시작**: 세션 장기화로 에뮬레이터가 중간에 종료됨(`device not found`) → `emulator -avd Medium_Phone_API_36.1` 재부팅 후 재개. 코드 영향 없음.
- **adb 검색바 버튼 탭**: borderlessButton 영역이 좁아 추정 좌표가 빗나감 → `uiautomator dump`로 정확한 bounds 얻어 중심 탭(`search_next_btn [607,2263][838,2389]` 등). 향후 바 버튼 자동화는 uiautomator 권장.
- IME 한글조합 우회(phase4 §4)는 이번에도 적용.

## 6. 미검증 / 후속
- **줌-후 하이라이트 정렬**: 코드상 relayout 재계산으로 보장. 핀치 실기 캡처는 adb 멀티터치 제약으로 생략(더블탭 줌으로 후속 확인 가능).
- **대용량 PDF 검색바 성능**: 샘플 3p. 수백p·히트 다수 시 오버레이/스크롤 체감 미검증.

## 7. 다음 = Phase 5 (텍스트 선택·복사)
fitz 텍스트 추출 + 선택 핸들 UI. 하이라이트 오버레이/`HighlightGeometry` 좌표변환 패턴 재사용 여지.
