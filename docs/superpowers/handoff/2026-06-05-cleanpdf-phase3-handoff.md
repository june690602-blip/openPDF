# CleanPDF Viewer — Phase 3 + 3.5 핸드오프 (탐색 완료)

> 작성일: 2026-06-05 · 상태: **Phase 3(목차·페이지점프·번호점프) + Phase 3.5(썸네일) `main` 병합 완료** (`bd9e535`)
> 선행 핸드오프: `2026-06-05-cleanpdf-phase1-handoff.md`(§2 불변조건), `2026-06-05-cleanpdf-phase2-handoff.md`
> 계획서: `plans/2026-06-05-cleanpdf-phase3-navigation.md`, `plans/2026-06-05-cleanpdf-phase3_5-thumbnails.md`

이 문서: 탐색(스펙 Phase 3 "탐색") 완료 상태 + 새로 들어온 컴포넌트/결정 + **저용량-모델 실행에서 배운 점** + 다음(Phase 4).

---

## 1. 완료 상태
스펙 §11 Phase 3 "탐색"의 4조각이 모두 동작 → `main`.
- **목차(outline)**: 오버플로 "목차" → `PdfDocument.loadOutline()`(fitz `Document.loadOutline()` → 각 `Outline` 을 `resolveLink`→`pageNumberFromLocation` 로 0-based 페이지 변환, 순수 `OutlineModel.flatten` 으로 평탄화) → 다이얼로그(레벨 들여쓰기) → 항목 탭 시 `scrollToPage`.
- **페이지 점프**: `PdfReaderView.scrollToPage(index)`(`LinearLayoutManager.scrollToPositionWithOffset`).
- **현재 페이지 표시**: `PdfReaderView.onPageChanged(cur,total)` 콜백 → 툴바 subtitle "N / 전체".
- **페이지번호 입력 점프**: 오버플로 "페이지 이동" → 입력 → 순수 `PageJump.parse(input,total)`(클램프/검증) → `scrollToPage`.
- **썸네일(Phase 3.5)**: 오버플로 "썸네일" → AlertDialog + 3열 `GridLayoutManager` RecyclerView(`ThumbnailAdapter`) → 페이지 미리보기 렌더 → 셀 탭 시 `scrollToPage` + dismiss.

검증: 단위 33/33, 계측 4/4, 빌드 성공, 실기 "1/3" 인디케이터·썸네일 그리드 렌더 확인, 크래시 0.

## 2. 새 컴포넌트 / 결정 (불변조건 준수 방식)
- `pdf/OutlineModel.kt`: `PdfOutlineItem(title,page,level)`, `RawOutline(title,page,children)`, 순수 `flatten`(단위테스트 2). fitz 트리 변환은 `PdfDocument` 내부의 thin 코드.
- `pdf/PdfDocument.loadOutline()` + `pdf/PageRenderer.loadOutlineBlocking()`: **outline 도 fitz 접근이라 반드시 렌더 스레드**(`loadOutlineBlocking` = `exec.submit{...}.get()`). 불변조건 §2-1 준수.
- `view/PageJump.kt`: 순수 번호 파싱(단위테스트 5).
- `view/PdfReaderView`: `scrollToPage`/`pageCount`/`onPageChanged` 추가(줌·캐시·렌더 로직은 그대로).
- `view/ThumbnailAdapter.kt`: `PdfPageAdapter` 패턴 **그대로 복제** — 제출→`itemView.post`→캐시, `onViewRecycled` 취소+null, **`recycle()` 0건**(불변조건 §2-4). 메인 뷰어 렌더러를 공유(단일 스레드 직렬화), 자체 작은 `BitmapCache(32MB)`. `showThumbnails()` 가 매 호출마다 **현재 renderer/currentSizes** 로 새로 구성 → 종료된 executor submit 방지(불변조건 §2-8 정신).
- UI 선택: 썸네일을 **AlertDialog**로(풀스크린 오버레이/뒤로가기 처리 회피) → 레이아웃 변경 0, 약한 모델 안전.

## 3. fitz API (AAR 1.27.1 검증 — 추론 금지, 이대로 사용)
- 목차: `Document.loadOutline(): Outline[]?`(없으면 null), `Outline{ String title; String uri; Outline[] down }`, `Document.resolveLink(Outline): Location`, `Document.pageNumberFromLocation(Location): Int`(절대 0-based, 미해결 -1). `Location{ int chapter; int page }`.
- 검색(Phase 4용 미리 확인): `Page.search(needle: String): Quad[][]`(히트 배열, 각 히트=Quad[]) / `search(needle, flags: Int)`. 플래그: `StructuredText.SEARCH_IGNORE_CASE` 등. `Quad{ float ul_x,ul_y,ur_x,ur_y,ll_x,ll_y,lr_x,lr_y }` + `toRect(): Rect`. `Rect{ float x0,y0,x1,y1 }`(PDF 포인트).

## 4. ⚠️ 저용량-모델 실행에서 배운 점 (중요 — 다음 실행에 반영)
Phase 3·3.5를 **sonnet subagent**(태스크별 새 컨텍스트) + opus 컨트롤러 검증으로 실행한 관찰:
- **순수/전체-파일 교체 태스크는 완벽**: 코드를 그대로 받아 쓰고 RED→GREEN→commit 깔끔. 계획서의 "전체-파일 교체 + 순수 JVM 테스트" 설계가 약한 모델에 결정적으로 잘 맞음.
- **멀티스텝 디바이스 UI 검증 스텝이 약점**: 오버플로 메뉴→다이얼로그→셀 탭을 스크린샷 좌표로 자동화하는 스텝에서 subagent가 **예산 소진**(Phase 2 picker, Phase 3.5 썸네일 모두 동일 패턴). 코드는 정확·빌드 성공했고, 컨트롤러가 들어가 스크린샷 확인+커밋+회귀검증으로 마무리.
- **시사점(계획서 작성 규칙)**: ① 디바이스 UI "탭 자동화" 검증은 약한 모델이 못 끝낼 수 있으니, 그 스텝은 **컨트롤러/큰 모델이 맡거나** 계획에서 "빌드+설치+스크린샷 1장까지만 필수, 탭 점프는 수동/회귀로" 로 가볍게. ② 좌표 의존 탭을 최소화. ③ 나머지(코드/순수테스트)는 지금 방식 유지.

## 5. 미검증 / 후속
- **목차 점프 실기**: 샘플 PDF엔 목차가 없어 "목차 없음" 토스트까지만 자동 확인. 목차 있는 실제 PDF로 점프는 수동(실기).
- **썸네일 탭→점프**: 그리드 렌더는 실기 확인. 탭→점프는 `onPick→scrollToPage`(이미 검증된 경로)라 저위험이나 실기 직접 확인은 수동 체크리스트.
- (Phase 1·2 미검증 항목 여전) 실제 카톡 SEND·VIEW(S25), 암호 PDF 다이얼로그.

## 6. 다음 = Phase 4 (검색)
계획: `plans/2026-06-05-cleanpdf-phase4-search.md`. 핵심: 순수 `SearchCursor`(히트목록+현재위치+다음/이전 wrapping, TDD) + `PdfDocument.search`(렌더 스레드, `Page.search`) + 찾기 입력/카운트/다음·이전 + 히트 페이지로 점프. **하이라이트 오버레이는 Phase 4.5로 분리**(렌더 어댑터 침습 최소화, §4 교훈 반영). 시작 전 §2 불변조건 + 이 핸드오프 §4 먼저 읽을 것.
