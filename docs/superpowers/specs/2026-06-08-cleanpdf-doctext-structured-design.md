# CleanPDF Viewer — DocText 구조+이미지 렌더링 (DR2) 설계 문서

> 작성일: 2026-06-08 (월) · 상태: 승인 대기 → 승인 시 writing-plans 로 구현계획 전개
> 브랜치: `feat/doctext-structured` (main `a265e94`에서 분기, main 직접 커밋 금지)
> 선행: DocText 1차 `specs/2026-06-06-cleanpdf-doctext-design.md`, 핸드오프 `handoff/2026-06-06-cleanpdf-doctext-handoff.md`
> PoC(이미 검증): hwplib 객체모델로 .hwp의 표(행/열/셀텍스트)와 이미지(실제 PNG/BMP 바이트+포맷명) 추출 가능 확인.

## 1. 목적 & 동기

**한 줄: 평탄한 텍스트 덤프를 "문단·표·이미지" 구조 렌더링으로 올려, 한글·워드 문서가 "원본처럼" 읽히게 한다.**

1차 DocText는 "텍스트만"이라 한글 파일이 글자 나열로만 보여 원본 구조를 알 수 없다는 사용자 피드백이 나왔다.
"원본 레이아웃 100% 재현"은 무료/오프라인 제약상 비현실적(특히 HWP는 FOSS 렌더러 전무)이므로, **가성비
지점인 "구조(표) + 이미지"** 까지 끌어올린다. 표는 진짜 표로, 그림은 인라인으로 — 텍스트+표+그림이
흐르는 문서가 된다(체감 충실도 대폭 상승, 레이아웃 정밀배치·도형·수식은 제외).

성공 기준:
- docx·hwpx·hwp **세 포맷 모두** 표가 진짜 `<table>`로, 래스터 이미지가 인라인으로 보인다.
- 오프라인 100%·무료(FOSS)·광고 0 유지. PDF 코어·인텐트 라우팅·찾기·줌·선택 무영향.
- 못 그리는 형식(WMF/EMF)·초대형 이미지는 안전하게 생략(크래시/OOM 0).

## 2. 확정 제약 (변경 금지 — 1차와 동일)
오프라인 · FOSS만 · 무료 · 대상 DOCX/HWP/HWPX. (추가 상용 SDK 금지.)

## 3. 브레인스토밍 결정 요약
| 축 | 결정 |
|---|---|
| 모델 | 평탄 `List<String>` → 구조 `List<DocBlock>`(Para/Table/Image) |
| 범위(.hwp) | **세 포맷 전부 구조화**(.hwp는 hwplib 객체모델, PoC로 가능 확인) |
| 스타일 | inline 굵게/제목/글머리표 **v1 제외**(문단·표·이미지만) |
| 이미지 포맷 | 래스터(PNG/JPEG/GIF/BMP)만 인라인. **WMF/EMF/OLE 생략**(자리표시 없음) |
| 이미지 용량 | 개별 ~4MB·총 ~16MB 캡. 초과분은 **"[큰 이미지 생략]" 자리표시** |
| 배치 | 문서 흐름상 등장 위치에 인라인(base64 data URI) |

## 4. 데이터 흐름

```
지금: 추출기 → List<String>(평탄, 표=탭/줄) → DocText(paragraphs) → DocHtml(<pre> 하나)
변경: 추출기 → List<DocBlock>(구조)          → DocText(blocks)    → DocHtml(<p>/<table>/<img>)
```
DocTextActivity·라우팅·매니페스트·recents·찾기·줌은 **무변**(여전히 `DocHtml.toHtml`→WebView).

## 5. 모델 (`doc/DocBlock.kt`, `DocText` 교체)

```kotlin
sealed interface DocBlock {
    data class Para(val text: String) : DocBlock                  // 문단/줄 (plain text)
    data class Table(val rows: List<List<String>>) : DocBlock     // 셀 = 평탄 텍스트
    data class Image(val mime: String, val bytes: ByteArray) : DocBlock  // 래스터만
}
data class DocText(val blocks: List<DocBlock>)   // 기존 paragraphs:List<String> 대체
```
- 표 셀은 plain text(셀 안 여러 문단/중첩표는 텍스트로 합침). 셀 안 이미지는 v1에서 텍스트로 단순화.
- `ExtractResult`(Success/Empty/Failure) 불변. **Empty 판정** = 의미있는 블록 0(텍스트 없는 Para/표/이미지 모두 없음).

## 6. 포맷별 추출 (평탄 추출기 → 구조 추출기로 교체)

### 6.1 공용 `doc/XmlBlocks.kt` (순수, XmlFlowText 대체)
docx `word/document.xml` · hwpx `Contents/section*.xml`를 local-name 매칭으로 파싱 → 순서대로 `Para`/`Table`/이미지 emit.
- 이미지는 **`resolveImage: (refId: String) -> ByteArray?` 람다를 주입**받아, 참조 만나면 호출해 바이트를 가져와 `Image`로. (파서는 순수 유지 — 테스트는 가짜 resolver 주입.)
- `<w:p>`/`<hp:p>`=Para(런 연결, tab/break), `<w:tbl>`/`<hp:tbl>`=Table(행/셀), 이미지 참조: docx `<a:blip r:embed="rId">`, hwpx `<hp:pic>`/binItemIDRef.

### 6.2 추출기
| 추출기 | 방식 |
|---|---|
| `DocxExtractor` | zip → `word/_rels/document.xml.rels`(rId→`word/media/x`) 맵 → resolver로 `XmlBlocks.parse(document.xml)` |
| `HwpxExtractor` | zip → `Contents/content.hpf`(binItemIDRef→`BinData/x`) 맵 → resolver로 section들 누적 |
| `HwpExtractor` | **hwplib 객체모델**: `HWPReader.fromInputStream` → BodyText.sectionList → Paragraph(getNormalString=Para) · `ControlTable`(getRowList→Row.getCellList→Cell.getParagraphList=Table, 셀 재귀) · `ControlPicture`→`ShapeComponentPicture.binItemID`→`HWPFile.binData.embeddedBinaryDataList`(EmbeddedBinaryData.getData=bytes, getName=포맷) =Image |

> **PoC 확정 API/주의**: hwplib 패키지 `kr.dogfoot.hwplib.object.*` 의 `object`는 Kotlin 예약어 → **import 백틱 이스케이프 필수**(`kr.dogfoot.hwplib.\`object\`.…`). 표/이미지 접근 경로는 PoC로 실제 검증됨.

### 6.3 이미지 필터 `doc/ImageFilter.kt` (순수)
- **매직바이트 → mime**: PNG(`89504E47`)·JPEG(`FFD8FF`)·GIF(`474946`)·BMP(`424D`) 통과.
- **포맷 탈락**(WMF/EMF/OLE/unknown): `null` 반환 → 호출측이 **조용히 생략**(자리표시 없음 — 결정 b).
- **용량 캡**: 유효 래스터라도 개별 > ~4MB 또는 누적 > ~16MB면 → 호출측이 **자리표시 Para "[큰 이미지 생략]"** (결정 c).
- (HWP는 `EmbeddedBinaryData.name`의 확장자도 힌트로 쓸 수 있으나 **매직바이트가 1차 판정**.)

## 7. HTML 렌더 (`doc/DocHtml.kt` 교체)
- 블록 순회 → `Para`=`<p style="white-space:pre-wrap;word-wrap:break-word">`(escape), `Table`=`<table>`(테두리·셀 패딩 CSS, 셀 escape), `Image`=`<img src="data:{mime};base64,{…}" style="max-width:100%;height:auto">`.
- `<head>`에 charset/viewport + 표/본문 기본 CSS(가독 폰트·줄간격·테이블 border-collapse). 본문은 일반 흐름(`<body>`), 1차의 단일 `<pre>` 제거.
- 오프라인·보안 동일: `loadDataWithBaseURL(null, html, "text/html","utf-8", null)`, JS off. 찾기(`findAllAsync`)·핀치줌·선택복사 그대로.

## 8. 파일 (신규/교체/영향)
- **신규**: `doc/DocBlock.kt`, `doc/XmlBlocks.kt`, `doc/ImageFilter.kt`.
- **교체**: `doc/DocText.kt`(blocks), `doc/DocHtml.kt`(블록 렌더), `doc/DocxExtractor.kt`·`doc/HwpxExtractor.kt`·`doc/HwpExtractor.kt`(구조+이미지). `doc/XmlFlowText.kt`·`sectionIndex`는 `XmlBlocks`로 흡수/대체.
- **영향 적음**: `DocTextActivity`(여전히 toHtml→WebView, 무변 예상), `DocTextExtractor`/`Extractors`(인터페이스·팩토리 유지). 라우팅/매니페스트/recents/strings **무변**.
- **삭제될 테스트**: 평탄 동작 가정한 `XmlFlowTextTest`·기존 extractor 스모크는 블록 기준으로 재작성.

## 9. 에러 처리 (1차 유지 + 추가)
- 추출 실패=`Failure`(친절한 에러), 텍스트·표·이미지 전무=`Empty`. 1차 문자열·화면 재사용.
- 개별 이미지 디코드/필터 실패는 **그 이미지만 생략**(전체 추출 실패로 번지지 않게 per-image runCatching).

## 10. 테스트 전략
- **JVM 단위**: `XmlBlocks.parse`(Para/Table/이미지참조 순서·표 그리드·가짜 resolver로 Image 주입), `DocHtml.toHtml`(진짜 `<table>`·`<img data:>`·escape·캡 자리표시), `ImageFilter`(PNG/JPG/GIF/BMP 통과·WMF/EMF/unknown 차단·개별/누적 용량캡). XML 파서는 `android.util.Xml` 때문에 Robolectric(기존과 동일).
- **계측 스모크**: docx/hwpx를 **이미지 포함**해 in-test zip 구성 → `Image` 블록 + 표 블록 확인. hwp 구조는 실제 픽스처 skip-if-absent(개인정보 .hwp 미커밋, 1차 방식).
- **실기**: 표+이미지 있는 실제 .hwp/.docx/.hwpx 기기 렌더(스크린샷). PDF 회귀 0 확인(전체 계측).
- 증거 기반 완료: 각 Phase "빌드 exit 0 + 테스트 수 + 실기" 기록.

## 11. 리스크 & 선검증
| 리스크 | 대응 |
|---|---|
| hwplib 객체모델 표/이미지 접근 | **PoC 완료**(표 6×4 등, PNG/BMP 바이트 확인) |
| `object` 키워드 충돌 | import 백틱(스펙 §6.2) |
| HWP의 WMF/EMF·OLE 이미지 | ImageFilter가 매직으로 차단(렌더 가능 포맷만) |
| 대형 이미지 base64 OOM | 개별 4MB·총 16MB 캡 + 자리표시 |
| 이미지↔본문 위치 매핑(HWP binItemID) | ControlPicture.ShapeComponentPicture.binItemID → DocInfo BinData → embedded "BINxxxx" 매핑(미세 어긋남 시 등장 문단 위치에 best-effort) |
| HWPX 이미지 참조 경로(`content.hpf` id↔`BinData/`) | 실제 .hwpx로 매핑 검증(1차 element-name 검증과 동일 방식); 불명확 시 해당 이미지만 생략 |
| 표 안 이미지/중첩표 | v1은 셀=텍스트(이미지는 흐름상 단순화), 중첩표는 best-effort |

## 12. 비범위 (YAGNI)
inline 굵게/색/제목레벨·글머리표·정확한 좌표 배치/페이지·도형/차트/그래프·수식·WMF/EMF 렌더·셀 병합 정밀 재현·머리말/꼬리말/각주.

## 13. 실행 계획 (Phase 골격)
> 상세는 writing-plans. DOCX 수직 슬라이스로 모델 전환을 먼저 끝내고 포맷을 늘린다.

| Phase | 목표 | 완료 증거 |
|---|---|---|
| **S-A** | `DocBlock` 모델 + `ImageFilter`(순수) + `DocHtml` 블록 렌더 + `XmlBlocks`(순수, resolver 주입) | 단위테스트 통과 |
| **S-B** | `DocxExtractor` 구조+이미지(rels resolver) | .docx 표·이미지 렌더(계측) |
| **S-C** | `HwpxExtractor` 구조+이미지(content.hpf resolver) | .hwpx 표·이미지 렌더 |
| **S-D** | `HwpExtractor` 객체모델 전환(표·이미지) | .hwp 표·이미지 렌더(실기) |
| **S-E** | 마감: 실기 3포맷·PDF 회귀·문서 갱신 | 증거 + 핸드오프 |

각 Phase "작동하는 앱" 유지. 사용자 검증 후 다음.
