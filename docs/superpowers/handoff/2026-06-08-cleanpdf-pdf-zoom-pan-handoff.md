# CleanPDF — PDF 확대/이동(줌·팬) 개선 핸드오프 (2026-06-08)

> **방향 전환**: 워드/한글(DocText) 기능은 **조잡해서 기각** → **PDF 전용**으로 집중하기로 함.
> 사용자 요청 4가지 중 **A(상태바·워드한글)=완료**, **B(확대/이동)=진행 중(점프 미해결)**.
> 브랜치: `fix/statusbar-and-pdf-only` (main `230a5cf`에서 분기). main 미병합.
> 실기: 삼성 SM-G996N(Android 15), adb serial `R3CR60N1Q3X`. `cleanpdf-sample.hwp`가 폰 Download에 남아있음(개인파일, 지워도 됨).

---

## 1. 사용자 요청 (원문 취지)
1. **상태바 겹침** — 앱 제목이 상태바와 겹침 → 고치기. ✅ 완료
2. **확대 후 드래그(팬)** — 확대하면 페이지가 화면보다 넓어지는데 좌우로 못 움직임 → 자유 이동. ⏳ 미완성
3. **부드러운 확대** — 확대 중 글자 안 보이다가 손 떼면 "짠" 나타남 → 부드럽게. ⏳ 1차만(점프 잔존)
4. **워드/한글 기각** — DocText 너무 조잡 → PDF 전용. ✅ 완료(진입 비활성화)

## 2. 완료 (A) — 커밋 `f306971`
- **상태바 겹침**: Android 15 edge-to-edge 강제인데 inset 미처리였음. `MainActivity.onCreate`에 `WindowCompat.setDecorFitsSystemWindows(window, false)` + `applySystemBarInsets()` — 툴바에 status bar top padding, 하단 검색·선택 바에 navbar bottom padding. `activity_main.xml` 툴바를 `wrap_content`+`minHeight=actionBarSize`로(top padding 시 타이틀 안 짤리게). **실기 확인됨**(사용자 "이제 안 겹친다").
- **워드/한글 비활성화(PDF 전용)**:
  - `MainActivity.route()`: `DOCX/HWP/HWPX` → `DocTextActivity` 진입 대신 `showError(error_unsupported)`. (KDoc에 복구법 명시)
  - `AndroidManifest.xml`: VIEW·SEND 필터에서 문서 MIME 6종 + `application/zip` 제거 → 공유목록에 PDF만. `pdf`/`x-pdf`/`octet-stream`만 남김.
  - **doc/ 패키지·DocTextActivity·hwplib 의존성은 보존**(미사용). 복구 = route 1줄 + manifest 문서 MIME 되돌리기.

## 3. 진행 중 (B) — ③ 1차 커밋 `3d7940c`, ② 미착수
### 한 일 (③ 부분)
- **빈화면 제거**: `PdfPageAdapter.onBindViewHolder` — 같은 페이지를 다시 바인드(줌 변경)하면 이전 비트맵 유지(`PageVH.boundPage` 추적). FIT_CENTER로 새 셀 크기에 늘어나 보이다가 새 스케일 렌더되면 교체. → "빈화면 후 짠" 해소.
- **손가락 중심 확대**: `PdfReaderView` `scaleDetector`에 `onScaleBegin`{ `pivotX=focusX; pivotY=focusY` } 추가.
- **실기 결과(사용자)**: ✅ 확대 중 글자 보임, ✅ 손가락 사이 중심으로 커짐. ❌ **손 떼는 순간 다른 데로 순간이동 + 세로로 안 한 스크롤처럼 튕김**. 핀치 중 글자 미세 "떨림".

### 미해결의 근본 원인 (중요)
현재 줌 아키텍처:
- `zoom` 필드 → `PageLayout.compute(zoom)` → 셀(페이지) **layoutParams width = contentWidth = fitWidthPx*zoom**, height 비례. RecyclerView(세로 LinearLayoutManager) 연속 스크롤. `RenderScale.forPage`로 셀 크기에 맞춰 고해상 재렌더(선명).
- 핀치 중: `scaleX/scaleY = liveScale` (뷰 전체 변환, focal pivot) — **시각적 확대만**, 스크롤/레이아웃 불변.
- `onScaleEnd` → `commitZoom(zoom*liveScale)`: `scaleX=1` 리셋 + `zoom` 갱신 + `relayout()`(`notifyDataSetChanged`).

**점프/튕김 = commit 시 "보던 지점 고정(anchor)"이 없음.** 핀치 중 시각상태(focal 중심 scaleX 확대)와 commit 후 상태(셀이 실제로 zoom배 커지고 RecyclerView 스크롤 위치는 이전 그대로)가 **다른 좌표계**라, 손 떼는 순간 어긋남.
- 세로: 셀 높이가 바뀌었는데 스크롤 위치 재계산 안 함 → 튕김.
- 가로: **가로 이동(팬) 시스템 자체가 없음**(RecyclerView 세로 전용, 셀 left 정렬, 넘친 가로는 클립). focal 가로 확대분이 commit 후 사라짐 → 가로 점프. → **②(팬)과 ③(위치고정)이 한 몸**.

## 4. 다음 세션 할 일 — ②+③ 통합 (권장 설계)
**목표 UX**: 확대해도 보던 곳 유지(anchor) + 확대 상태에서 상하좌우 자유 이동(팬). 검색·글자선택·형광펜 유지.

**방식: 현 RecyclerView 구조 보강(방향①)** — 같은 좌표 시스템으로:
1. **가로 오프셋 `panX`** 도입 (0 ~ `contentWidth - viewportWidth`). 보이는 자식 셀 전체에 `translationX = -panX` 적용(`addOnChildAttachStateChangeListener`로 새 자식에도, `onScrolled`에서 갱신).
2. **팬 제스처**: `zoom>1` & 1손가락 드래그 & `!scaleDetector.isInProgress` & `draggingHandle==null` 일 때 — `tapDetector`에 `onScroll` 추가해 가로는 `panX += distanceX`(clamp)·`applyPanX()`, 세로는 RecyclerView 기본 스크롤에 맡김(return false).
3. **commit 위치고정(anchor)**: `onScaleBegin`에서 focal 저장 → `commitZoom(newZoom, focalX, focalY)`:
   - 핀치 전 focal 아래 콘텐츠의 가로/세로 비율 계산: `child=findChildViewUnder(fx,fy)`, `page=getChildAdapterPosition(child)`, `relY=(fy-child.top)/child.height`, `relX=(panX+fx)/oldContentWidth`.
   - `zoom=newZoom; relayout()` 후: 가로 `panX = relX*newContentWidth - fx`(clamp); 세로 `scrollToPositionWithOffset(page, (fy - relY*newPageHeight).toInt())`. 그 뒤 `applyPanX()`.
   - (relayout→scrollToPositionWithOffset는 다음 레이아웃 패스라 타이밍 주의 — `post{}` 필요할 수 있음. 실기 반복으로 맞출 것.)
4. **떨림**: focal pivot을 `onScaleBegin`에 1회 고정(현행)으로 충분한지, 핀치 중 translation 보정이 필요한지 실기로 판단.

**좌표계 영향 점검**: 검색 하이라이트·텍스트 선택·핸들 히트테스트는 모두 셀 픽셀 좌표(`HighlightGeometry`/`SelectionGeometry`, contentWidth 기준)라 zoom은 자동 추종. 단 **가로 `panX` 도입 시 화면→셀 좌표 변환에 panX 보정**이 필요(`onLongPress`의 `e.x - child.left`, `handleUnder`, `onTouchEvent` 핸들 드래그). child.left가 translationX로 이동하므로 대체로 따라가지만, 실기로 선택/핸들 정확도 확인 필수.

**대안(방향②, 비권장)**: 확대/팬을 통째로 matrix 변환 레이어로. 팬은 쉬워지나 (a)선명도 위해 고배율 재렌더 별도 필요, (b)검색/선택/하이라이트 좌표 전면 재작업. 리스크 큼.

## 5. 핵심 파일
- `view/PdfReaderView.kt` — 줌/제스처/스크롤(여기가 주 무대). `commitZoom`·`scaleDetector`·`onTouchEvent`·`relayout`.
- `view/PdfPageAdapter.kt` — 셀 바인딩/렌더/비트맵 유지(`boundPage`)·오버레이.
- `view/PageLayout.kt` — `compute(sizes, fitWidthPx, gapPx, zoom)` → tops/heights/contentWidth.
- `view/SelectionGeometry.kt`·`HighlightGeometry.kt` — 셀픽셀↔PDF점(panX 보정 검토 대상).
- `MainActivity.kt` — insets·route(A 완료분).

## 6. 빌드·실기
- 빌드+실폰 설치: `cd /c/dev/openPDF && ANDROID_SERIAL=R3CR60N1Q3X ./gradlew :app:installDebug`
- 앱 실행: `MSYS_NO_PATHCONV=1 ~/AppData/Local/Android/Sdk/platform-tools/adb.exe -s R3CR60N1Q3X shell am start -n io.github.june690602_blip.cleanpdf/.MainActivity` (실행 전 `input keyevent KEYCODE_WAKEUP`, 잠금은 사용자가 해제)
- 스크린샷: `adb -s R3CR60N1Q3X shell screencap -p /sdcard/s.png && adb -s R3CR60N1Q3X pull /sdcard/s.png ./s.png` (루트 *.png는 .gitignore)
- ⚠️ 단위테스트는 PDF 줌/팬에 무력(계측·실기가 핵심). 변경 후 `assembleDebug` green + 실기 확인.

## 7. 마무리 메모
- A(상태바·PDF전용)는 완성·실기검증됨 — 원하면 먼저 main 병합 가능(`fix/statusbar-and-pdf-only`의 `f306971`까지). ③ WIP(`3d7940c`)는 점프 잔존이라 ②완성 후 함께 병합 권장.
- CLAUDE.md Status는 아직 DR2(워드/한글) 완료 상태로 적혀 있음 — PDF 전용 전환은 이 작업 완료 후 CLAUDE.md 갱신 필요(워드/한글 "비활성화" 반영).
