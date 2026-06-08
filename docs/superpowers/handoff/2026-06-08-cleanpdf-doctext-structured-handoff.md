# DocText 구조+이미지 (DR2) — 핸드오프 (2026-06-08)

> 평탄 `DocText(paragraphs: List<String>)` → 구조 `DocText(blocks: List<DocBlock>)` 전환 완료.
> docx·hwpx·hwp 모두 **진짜 `<table>` + 인라인 이미지**로 추출·렌더.
> 브랜치 `feat/doctext-structured`(main 미병합). 스펙 `specs/2026-06-08-cleanpdf-doctext-structured-design.md` · 계획 `plans/2026-06-08-cleanpdf-doctext-structured.md`.

## 1. 한 줄 요약
한글/워드 문서를 텍스트만이 아니라 **문단·표·이미지 구조**로 읽어 오프라인 WebView에 표=`<table>`, 이미지=base64 `<img>` 인라인으로 보여준다. PDF 코어는 한 줄도 안 건드렸다(전 변경 `doc/` 한정).

## 2. 작동 (완료) ✅
- **구조 모델** — `DocBlock`(sealed): `Para(text)` / `Table(rows: List<List<String>>)` / `Image(mime, bytes)`(바이트 값 동등성). `DocText(blocks: List<DocBlock>)`.
- **공용 XML 파서** — `XmlBlocks.parse(parser, resolveImage)` 가 DOCX(`word/document.xml`)·HWPX(`Contents/section*.xml`)를 한 번 훑어 블록 생성. local-name 매칭(prefix 무관): `<p>`=Para, `<tbl>/<tr>/<tc>`=Table(셀=평탄 텍스트), 이미지 참조 속성 → `resolveImage(refId)` → `ImageFilter`.
- **이미지 필터** — `ImageFilter`: 매직바이트로 PNG/JPEG/GIF/BMP만 통과(WMF/EMF/OLE/unknown 거부), 개별 4MB·누적 16MB 캡. `classify→Outcome.{Ok/Oversized/Unsupported}`.
- **HTML 렌더** — `DocHtml.render(blocks)`: 문단=`<p>`(escape), 표=`<table><tr><td>`, 이미지=`<img src="data:mime;base64,…">`. `toHtml(DocText)`는 `render(text.blocks)`로 위임. `DocTextActivity`는 무변(`DocHtml.toHtml(result.text)` 그대로).
- **DOCX** — `DocxExtractor`: ZIP → `word/document.xml`을 XmlBlocks로, 이미지는 `word/_rels/document.xml.rels`(rId→media)로 해소. 계측 PASS(표·이미지·문단).
- **HWPX** — `HwpxExtractor`: ZIP → `section*.xml`(번호순)을 XmlBlocks로, 이미지는 `Contents/content.hpf`(id→href)로 해소(여러 후보 경로 시도). 계측 PASS.
- **HWP v5** — `HwpExtractor` + `HwpBlocks`: hwplib **객체모델**(HWPReader→HWPFile) 순회. `ControlTable`→Table, `ControlPicture`→`binData` 임베디드와 **등장순 페어링**→ImageFilter. (이전 평탄 `TextExtractor` 폐기.) **실제 fixture(.hwp, 표+이미지)로 계측 실제 실행 PASS.**

## 3. 핵심 결정 / 아키텍처
- **추출기 → `List<DocBlock>` → `DocHtml.render` → 오프라인 WebView**. 추출기는 컨테이너 해체 + 이미지 resolver만 담당, 본문 파싱은 공용 XmlBlocks(docx/hwpx) 또는 HwpBlocks(hwp)로 위임.
- **이미지 = 래스터만, 인라인.** 웹 렌더 불가능한 벡터(WMF/EMF)는 ImageFilter가 자동 차단. 용량캡으로 OOM/거대 base64 방지. 초과분은 `[큰 이미지 생략]` 문단으로 대체.
- **이미지 페어링은 등장순(v1 best-effort).** hwp는 `ControlPicture` 등장 순서로 `binData` 임베디드를 매핑(binItemID 정밀 매핑은 후속).
- **마이그레이션 안전성** — B1에서 한시적 `toResultStrings(lines)` wrapper로 모델을 먼저 갈아끼우고 빌드를 green 유지한 뒤, 포맷별 추출기를 하나씩 구조화. 전부 전환 후 E1에서 wrapper와 옛 평탄 파서(`XmlFlowText`) 제거.
- **PDF 8대 불변조건 무접촉** — fitz 미사용, 별도 액티비티, 별도 bg executor. DR2 변경 21파일 전부 `doc/` 패키지(+3 SmokeTest)로 확인(정적).

## 4. 파일
**신규(main)**: `doc/DocBlock.kt`, `doc/ImageFilter.kt`, `doc/XmlBlocks.kt`, `doc/HwpBlocks.kt`
**교체(main)**: `doc/DocText.kt`(blocks), `doc/DocTextExtractor.kt`(toResult(blocks)), `doc/DocHtml.kt`(render+위임), `doc/DocxExtractor.kt`, `doc/HwpxExtractor.kt`, `doc/HwpExtractor.kt`
**삭제(main)**: `doc/XmlFlowText.kt`
**신규/교체(test)**: `DocBlockTest`·`ImageFilterTest`·`XmlBlocksTest`·`DocHtmlRenderTest`(신규), `ToResultTest`(교체) / **삭제**: `XmlFlowTextTest`·`DocHtmlTest`
**교체(androidTest)**: `DocxExtractorSmokeTest`·`HwpxExtractorSmokeTest`·`HwpExtractorSmokeTest`
**무변**: `DocTextActivity.kt`, 라우팅/매니페스트/recents/strings, **PDF 코어 전체**.

## 5. 커밋 (브랜치 `feat/doctext-structured`)
- `ce3c0e2` chore: ignore private HWP test fixtures
- `2185943` feat: DocBlock 구조 모델
- `4766ce4` feat: ImageFilter
- `bad4532` feat: XmlBlocks
- `d35604e` feat: DocHtml.render
- `131cf03` refactor: DocText→DocBlock 모델 전환(toResultStrings 한시 wrapper)
- `3be70e1` feat: DocxExtractor 구조+이미지
- `621c807` feat: HwpxExtractor 구조+이미지
- `f1536de` feat: HwpExtractor 객체모델 전환(HwpBlocks)
- `44634c7` refactor: 평탄 경로(XmlFlowText/toResultStrings) 제거
- (+ docs: CLAUDE.md + 본 핸드오프)

## 6. 검증 (증거)
- **단위 88 PASS** (`./gradlew :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL, fresh).
- **계측 13 PASS** (`./gradlew :app:connectedDebugAndroidTest`) — PDF 렌더/스크롤줌/검색/하이라이트/선택/탐색/인텐트 회귀 0 + 문서 추출기 3종 + HWP 스모크(fixture 실제 실행).
- **PDF 무접촉(정적)** — `git diff --stat ce3c0e2..HEAD` 변경 21파일 전부 `doc/`·doc 테스트(SmokeTest 3). PDF 코어 0.
- **잔재 0** — `git grep "XmlFlowText\|toResultStrings"` 매치 없음.

## 7. 미검증 / 후속
- **실기 육안 미검증** — 표·이미지가 WebView에 실제 렌더되는지 스크린샷 없음. 추출→블록→HTML은 단위(`DocHtmlRenderTest`: Table→`<table>`, Image→`<img base64>`)+계측(추출기 블록 생성)으로 입증했고, WebView의 `<table>`/`<img>` 렌더는 표준이라 갈음. (실폰/에뮬에서 표+이미지 docx/hwpx/hwp를 열어 1회 육안 확인 권장.)
- **HWP 이미지 binItemID 정밀 매핑** — 현재 등장순 페어링(여러 이미지/표가 섞이면 어긋날 수 있음).
- **HWPX 실파일 이미지 attr/경로** — 합성 fixture로 green(`binaryItemIDRef`/href↔BinData). 실파일로 attr명·경로 확정 필요.
- **표 안 이미지** — v1 생략(XmlBlocks는 `tableDepth==0`일 때만 이미지 처리). 헤딩/스타일도 미반영(평탄 텍스트).
- **HWP fixture** — `app/src/androidTest/assets/sample.hwp`(개인문서, `.gitignore`로 미커밋). 다른 환경에선 표+이미지 .hwp를 같은 경로에 둬야 HWP 스모크가 SKIP 아닌 실행.

## 8. 계획 이탈 (실행 중 정정 — 의도된 개선)
- **A3 (XmlBlocks)**: 계획의 `getAttributeValue(null, "r:embed")`는 네임스페이스-aware 파서(KXmlParser)에서 prefix 붙은 속성을 못 찾아 **docx 이미지를 놓쳤을** 버그. → `IMAGE_REF_LOCAL = setOf("embed", "binaryItemIDRef")` + 속성 인덱스 루프로 **local-name 매칭**(주석의 "prefix 무관" 설계의도에 부합). 테스트 XML에 누락된 `xmlns:a` 선언 보강.
- **D1 (HwpBlocks)**: hwplib는 Java 라이브러리 → 프로퍼티 접근(`.rowList`)이 아니라 **getter 메서드**(`getRowList()`, `getNormalString()` 등) 사용. `getNormalString()`의 `UnsupportedEncodingException`은 `runCatching` 방어.
- **D1 (테스트)**: fixture(매장유산 발굴 허가 신청서.hwp) 구조상 본문 최상위 문단에 한글이 없고 **표 셀 안**에 있어, 한글 검증을 `hasKoreanPara || hasKoreanInTable`로 완화(의도 보존). `has table` 단언은 유지(표 추출 검증 불변).
