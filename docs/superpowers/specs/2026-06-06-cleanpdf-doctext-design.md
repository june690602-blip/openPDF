# CleanPDF Viewer — DocText (한글·워드 텍스트 읽기) 설계 문서

> 작성일: 2026-06-06 (토) · 상태: 승인 대기 → 승인 시 writing-plans 로 구현계획 전개
> 브랜치: `feat/doctext-reader` (main `6c05712`에서 분기, main 직접 커밋 금지)
> 선행: 뷰어 설계 `specs/2026-06-05-cleanpdf-viewer-design.md`, Phase 5 핸드오프 `handoff/2026-06-06-cleanpdf-phase5-handoff.md`(§2 불변조건)
> PoC 결론(재검증 불필요): hwplib 1.1.10 안드 에뮬 한글 추출 성공, hwpx/docx는 ZIP+XML(XmlPullParser, 0 dep)

## 1. 목적 & 동기

**한 줄: PDF 코어는 그대로 두고, 인텐트 진입점에 포맷 라우터를 끼워 한글·워드 문서를 같은 앱에서 "텍스트로 읽기"만 지원한다.**

현장(건설 등) 사용자가 카톡 등으로 받는 건 PDF만이 아니다. `.hwp`/`.hwpx`(한글)·`.docx`(워드)
도 흔하다. 정확한 레이아웃·표·이미지는 필요 없고 **내용(텍스트)만 읽히면 충분**하다. PDF 뷰어는
손대지 않고, 한 앱에서 이 세 포맷이 같이 열리게 한다.

성공 기준:
- 카톡 등에서 받은 `.docx`/`.hwp`/`.hwpx`를 "열기/공유"로 바로 텍스트 열람
- 표는 텍스트로 평탄화되어 읽힘(계약서·견적서의 표 정보 보존)
- 텍스트 **선택·복사·찾기·글자크기 조절** 동작
- 오프라인 100%(서버 업로드/온라인 변환 0), 광고 0, 데이터 수집 0
- 못 읽는 파일은 깨진 화면 대신 **친절한 에러**

## 2. 확정 제약 (변경 금지)

| 제약 | 내용 |
|---|---|
| 오프라인 | 온라인 변환/서버 업로드 금지. 모든 파싱은 기기 내. |
| 충실도 | **텍스트만** 읽히면 OK. 표·이미지·정확한 레이아웃 불필요. 표는 텍스트로 평탄화. |
| 무료 | 상용 SDK 금지, **FOSS만**. |
| 대상 | **DOCX · HWP · HWPX 세 포맷 모두.** |

## 3. 브레인스토밍 결정 요약

| 축 | 결정 | 비고 |
|---|---|---|
| 렌더 위젯 | **WebView** (오프라인, JS off, baseURL null) | 선택·복사·찾기·글자줌 내장으로 거의 공짜 |
| 화면 구조 | **별도 `DocTextActivity`** | MainActivity = 인텐트 단일 진입 + 포맷 라우터 + PDF 전용. PDF 불변조건과 격리 |
| 실패 폴백 | **친절한 에러 화면만** | `Empty`(텍스트 없음) vs `Failure`(못 읽음) 구분. "다른 앱으로 열기"는 보류 |
| 뷰어 크롬 | **찾기 + 글자줌 포함** | WebView 기본(스크롤·선택·복사)에 찾기바 + textZoom |
| 표 처리 | **셀=탭(\t), 행=줄바꿈(\n)** | 읽는 순서 유지 + 어느 정도 정렬감 |

## 4. 아키텍처 & 데이터 흐름

```
인텐트(VIEW/SEND) / SAF "열기" / 최근파일
   │  MainActivity = 단일 진입점 + 포맷 라우터
   ▼
DocFormat.detect(name, magic)  →  PDF │ DOCX │ HWP │ HWPX │ UNKNOWN
   │                                │              │            │
   PDF────────┐         DOCX/HWP/HWPX─┐         UNKNOWN─┐
   ▼          ▼                       ▼                 ▼
 기존 PDF 경로  copyToCache(공용)  →  startActivity(DocTextActivity, file, format)   친절한 에러
   (불변)                              │
                                       ▼
                          [bg 스레드] DocTextExtractor.extract(file)
                                       │   → ExtractResult: Success(DocText) │ Empty │ Failure
                                       ▼
                          DocHtml.toHtml(DocText)   (escape + <pre>)
                                       ▼
                          WebView (JS off, baseURL null, 오프라인)
                          + 찾기바(findAllAsync) + 글자줌(textZoom) + 선택·복사(기본)
```

**불변조건 격리 원칙**: 문서 경로는 fitz/`PdfDocument`/`PageRenderer`/`BitmapCache`를 일절 사용하지
않으며 별도 액티비티에 산다. 따라서 PDF의 8개 아키텍처 불변조건(단일 렌더스레드·Cookie 미사용·
캐시 비트맵 recycle 금지 등)은 이 작업으로 깨질 수 없다. 추출은 `DocTextActivity` 자체의 단일 bg
executor에서 실행하고, 결과는 불변 `DocText` 값으로 UI 스레드를 횡단한다.

## 5. 컴포넌트 설계 (작은 파일 다수 · 순수/IO/UI 분리)

각 단위는 "무엇을 하나 / 어떻게 쓰나 / 무엇에 의존하나"가 명확해야 한다.

### 5.1 순수 로직 (JVM 단위테스트)

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `doc/DocFormat.kt` | `enum {PDF,DOCX,HWP,HWPX,UNKNOWN}` + 순수 `detect(name, head)`: 확장자 1차, `%PDF-` 매직 2차. 확장자 없는 ZIP/OLE는 단독 판정 불가 → `UNKNOWN` 반환(정밀판정은 `DocProbe`로 위임) | — |
| `doc/DocText.kt` | 추출 결과 불변 모델 `DocText(paragraphs: List<String>)` + `ExtractResult` sealed(`Success`/`Empty`/`Failure`) | — |
| `doc/DocxXml.kt` | document.xml 스트림 → `List<String>`. `<w:p>`=문단, `<w:t>`=텍스트, `<w:tab>`=\t, `<w:br>/<w:cr>`=줄바꿈, `<w:tbl>/<w:tr>/<w:tc>`=표(셀\t·행\n) | XmlPullParser |
| `doc/HwpxXml.kt` | section*.xml(OWPML) 스트림 → `List<String>`. `<hp:p>`=문단, `<hp:t>`=텍스트, 표(`<hp:tbl>/<hp:tr>/<hp:tc>`) 동일 평탄화 | XmlPullParser |
| `doc/DocHtml.kt` | `DocText` → HTML 문자열. `& < > " '` escape(스크립트/태그 주입 차단) + `<pre>`로 줄바꿈·탭 보존, UTF-8 meta | — |

> XmlPullParser는 인터페이스(`org.xmlpull.v1`)에 의존하고, 파서 인스턴스를 **인자로 주입**받아 순수
> 함수로 테스트한다(테스트는 kxml2 `testImplementation` 또는 기존 `RecentFilesLogic`처럼 Robolectric —
> 구체 선택은 plan에서). 즉 "ZIP에서 꺼내기"(IO)와 "XML→문단"(순수)을 파일/함수 경계로 가른다.

### 5.2 추출기 (IO — 계측 스모크)

`doc/DocTextExtractor.kt` — 인터페이스 `fun extract(file: File): ExtractResult` + `forFormat(format): DocTextExtractor` 팩토리.

| 컴포넌트 | 방식 | 의존 |
|---|---|---|
| `doc/DocProbe.kt` | `detect`가 UNKNOWN인데 매직이 ZIP/OLE면 컨테이너 엿보기로 정밀판정: ZIP 엔트리(`word/document.xml`→DOCX, `Contents/`·`mimetype`→HWPX), OLE 서명(→HWP). ZIP 경로는 java.util.zip로 JVM 테스트 가능 | java.util.zip |
| `doc/DocxExtractor.kt` | `ZipFile`에서 `word/document.xml` 엔트리 → `DocxXml.parse(reader)` | `DocxXml`, java.util.zip |
| `doc/HwpxExtractor.kt` | `ZipFile`에서 `Contents/section*.xml`(번호순 다중) → `HwpxXml.parse` 누적 | `HwpxXml`, java.util.zip |
| `doc/HwpExtractor.kt` | hwplib: `HWPReader.fromInputStream(is)` → `TextExtractor.extract(hwpFile, TextExtractMethod.InsertControlTextBetweenParagraphText)` → 줄 분해 | `kr.dogfoot:hwplib` |

빈/공백뿐이면 `Empty`, 예외면 `Failure(reason)` 반환(불변조건: 추출기는 throw 대신 결과로 보고).

### 5.3 UI (계측/실기)

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `DocTextActivity.kt` | file+format 수신 → bg 추출 → `ExtractResult` 분기 → HTML→WebView 로드. 찾기바·글자줌·에러 화면 배선. 툴바 제목=파일명 | 추출기, `DocHtml` |
| `res/layout/activity_doc_text.xml` | 툴바 + WebView + 찾기바(기존 `search_bar` 미러) + error_view | — |

### 5.4 공용/수정 (기존 PDF 코어)

| 대상 | 변경 |
|---|---|
| `io/PdfSource.copyToCache` | 포맷 중립 헬퍼로 일반화(이미 사실상 중립; 기본 파일명만 정리). PDF·문서 양쪽 사용. 기존 `looksLikePdf` 게이트는 `DocFormat.detect`로 대체(PDF 매직 검사는 detect 안으로 흡수) |
| `MainActivity.loadFromUri` | `looksLikePdf` 게이트 → `DocFormat.detect` 분기로 교체. PDF=기존 경로, 문서=copyToCache 후 `DocTextActivity` 기동, UNKNOWN=에러 |
| `MainActivity` SAF | `openDoc.launch(arrayOf(...))` MIME 목록에 docx/hwp/hwpx 추가. 결과 URI도 동일 분기 |
| `store/RecentFilesStore` | `RecentFile`에 `format: String` 추가(직렬화 키 `f`). 재오픈 시 타입으로 PDF/문서 라우팅. 구버전 항목(format 없음)=PDF로 간주(하위호환) |
| `AndroidManifest.xml` | VIEW/SEND 필터에 docx/hwp/hwpx MIME 추가(octet-stream·pdf 유지) |
| `app/build.gradle.kts` | `kr.dogfoot:hwplib:1.1.10` 의존성 추가 |

## 6. 포맷 감지 (`DocFormat.detect`)

**규칙: 확장자가 1차, 컨테이너 엿보기가 2차.** 거의 모든 카톡 파일은 확장자가 정상이라 1차로 끝난다.
확장자 + `%PDF-`는 순수 `DocFormat.detect`가, 확장자 없는 ZIP/OLE 컨테이너 정밀판정(아래 표 하단 2행)은
IO `DocProbe`가 맡는다.

매직바이트:
- PDF: `25 50 44 46 2D` (`%PDF-`)
- ZIP(docx·hwpx 공통): `50 4B 03 04` (`PK..`)
- OLE 복합문서(hwp v5·구 .doc 공통): `D0 CF 11 E0 A1 B1 1A E1`

분류 로직:

| 입력 | 판정 |
|---|---|
| 이름 `.pdf` 또는 `%PDF-` | PDF |
| 이름 `.docx` | DOCX |
| 이름 `.hwpx` | HWPX |
| 이름 `.hwp` | HWP |
| 확장자 없음 + ZIP 매직 | ZIP 엔트리 엿보기: `word/document.xml`→DOCX, `mimetype`=`application/hwp+zip` 또는 `Contents/section0.xml`→HWPX, 아니면 UNKNOWN |
| 확장자 없음 + OLE 매직 | OLE 내 "HWP Document File" 서명 있으면 HWP, 없으면(구 .doc/.xls) **UNKNOWN** |
| 그 외 | UNKNOWN |

> **OLE 매직 충돌 주의**: hwp v5와 구 `.doc`는 매직이 동일하다. 확장자 또는 OLE 내부 서명으로만 구분
> 가능. 확장자 없는 OLE는 보수적으로 서명 확인 후 아니면 UNKNOWN(미지원) 처리.

## 7. 추출기 명세

- **DOCX**: ZIP → `word/document.xml`(본문만; 머리말/꼬리말/각주는 v1 제외) → XmlPullParser로
  읽기순서대로 `<w:p>` 단위 문단, `<w:t>` 텍스트, `<w:tab>`→\t, `<w:br>`/`<w:cr>`→줄바꿈. 표는
  `<w:tbl>` 안 `<w:tr>`=행, `<w:tc>`=셀 → 셀 텍스트를 \t로 잇고 행 끝에 \n.
- **HWPX**: ZIP → `Contents/section0.xml, section1.xml …`을 **번호순**으로 누적 파싱. OWPML
  네임스페이스(`hp:`) `<hp:p>` 문단, `<hp:t>` 텍스트, 표(`<hp:tbl>/<hp:tr>/<hp:tc>`) 동일 평탄화.
  `header.xml`/`settings.xml`/그림·이진객체는 무시.
- **HWP(v5 바이너리)**: hwplib에 위임. `TextExtractMethod.InsertControlTextBetweenParagraphText`로
  문단 사이 컨트롤(표 등) 텍스트까지 포함. 반환 문자열을 줄(`\n`) 기준으로 `List<String>`화.
- **공통**: 결과가 비었거나 공백뿐 → `Empty`. 파싱 예외 → `Failure`.

## 8. 뷰어 (`DocTextActivity`)

- **WebView 설정**: `settings.javaScriptEnabled = false`, `loadDataWithBaseURL(null, html, "text/html",
  "utf-8", null)`(네트워크 0), `settings.setSupportZoom(true)` + `builtInZoomControls = true` +
  `displayZoomControls = false`(핀치 글자줌), 텍스트 선택 기본 on. `allowFileAccess=false` 등 보안 기본 잠금.
- **HTML**: `DocHtml.toHtml` — `<meta charset>` + 본문을 escape 후 `<pre style="white-space:pre-wrap;
  word-wrap:break-word">`로 감싸 줄바꿈·탭·자동 줄나눔 보존. 문단 사이는 빈 줄.
- **찾기**: 툴바 메뉴 "찾기" → 입력 → `WebView.findAllAsync(q)` + `setFindListener`로 매치 수 표시,
  찾기바(◀ 현재/전체 ▶ + 닫기)는 기존 `search_bar`(prev/next/close/position) 패턴 미러, `findNext(true/false)`.
- **글자줌**: 핀치 줌(`builtInZoomControls`)만 — v1은 핀치로 충분(사용자 확정). +/− 버튼은 비범위.
- **선택·복사**: WebView 롱프레스 기본 컨텍스트 액션(선택→복사) 사용. 별도 구현 없음.
- **에러**: `Empty`/`Failure`면 WebView 숨기고 error_view 표시(§9). 툴바 제목=파일명, 뒤로가기=종료.

## 9. 에러 처리

| 상황 | 표시 |
|---|---|
| `Empty`(읽었지만 텍스트 0 — 이미지/스캔) | "이 문서에는 읽을 텍스트가 없어요." |
| `Failure`(손상·암호·구버전 .hwp v3·OLE non-HWP·미지원) | "이 파일은 텍스트로 열 수 없어요." + 돌아가기 |
| 라우팅 `UNKNOWN` (MainActivity) | 기존 PDF `error_view` 패턴 재사용("지원하지 않는 형식") |

기존 PDF `error_view`/문자열 스타일과 톤 일치. 신규 문자열은 `strings.xml`에 추가.

## 10. 의존성 & 라이선스

- **추가 의존성은 hwplib 하나뿐**: `kr.dogfoot:hwplib:1.1.10`(Apache-2.0, 전이 의존성 0, Java7,
  java.awt 미사용, jar ~988KB). docx·hwpx는 안드 내장 XmlPullParser로 0 dep.
- **라이선스**: hwplib Apache-2.0 → AGPL v3로 **단방향 호환**(충돌 없음). 출시 OSS 고지 화면에 hwplib 추가.
- 앱 전체 라이선스(AGPL v3, MuPDF 결합)는 불변.

## 11. 테스트 전략

- **JVM 단위**: `DocFormat.detect`(확장자·매직·ZIP/OLE 엿보기 케이스), `DocxXml.parse`/`HwpxXml.parse`
  (샘플 XML 문자열 → 문단·표평탄화·탭·줄바꿈 검증), `DocHtml.toHtml`(escape·`<script>` 주입 차단·줄바꿈
  보존), `RecentFile` format 라운드트립 + 구버전(format 없음) 하위호환.
- **계측 스모크**: 포맷별 픽스처(`sample.docx`/`sample.hwp`/`sample.hwpx`)를 androidTest assets에 두고
  추출→비어있지 않음, `DocTextActivity` 기동+WebView 로드 성공+`findAllAsync` 카운트>0, octet-stream
  `.docx` 인텐트→`DocTextActivity` 라우팅.
- **실기(emulator-5554 + 실폰)**: 실제 카톡 3포맷 열기/공유, 찾기·글자줌·선택복사, 손상/빈 문서 에러.
- 증거 기반 완료: 각 Phase 종료 시 "빌드 exit 0 + 테스트 통과 수 + 실기 동작" 기록.
- ⚠️ `connectedDebugAndroidTest`는 실행 후 앱 APK를 언인스톨 → 실기 수동검증은 그 전에(또는 `installDebug` 후).

## 12. 리스크 & 선검증 포인트

| 리스크 | 대응 |
|---|---|
| **XmlPullParser의 JVM 단위테스트 가능성** | 파서 주입 + kxml2 testImpl(또는 Robolectric). Phase 초기에 1개로 검증 |
| OLE 매직 충돌(.hwp v5 vs 구 .doc) | 확장자 1차 + OLE 내부 서명. 확장자 없으면 보수적 UNKNOWN |
| HWPX `section*.xml` 다중·순서 | 엔트리명 번호 정렬 후 누적 |
| 거대 문서 WebView 성능 | 텍스트라 대체로 무난. 비정상적으로 크면 후속(문단 분할 로드)으로 |
| 최근파일 하위호환 | format 없는 기존 항목=PDF로 간주 |
| octet-stream 라우팅 | 매니페스트는 이미 수용 중 → 게이트만 `DocFormat.detect`로 교체 |

## 13. 비범위 (YAGNI — 이번엔 안 함)

- 이미지·그림·도형·차트, 정확한 레이아웃·폰트·색, 표 셀병합 정밀 재현
- 문서 편집·내보내기·문서→PDF 변환·병합/분할
- 구 `.doc`(OLE Word)·`.xls`·`.ppt`·`.rtf`·`.odt`·**HWP v3** 지원(→ 미지원 에러)
- DOCX 머리말/꼬리말/각주/미주, 변경내용 추적
- 문서 썸네일(최근파일은 일반 아이콘), 교차문단 정밀 선택(WebView 기본 선택 사용)
- "다른 앱으로 열기" 폴백, 글자줌 +/− 버튼(핀치로 충분), 야간 반전(문서 뷰어는 추후 설정과 통합 가능)

## 14. 실행 계획 (Phase 골격)

> 상세 작업분해는 승인 후 writing-plans 로 전개. 수직 슬라이스로 한 포맷을 끝까지 띄운 뒤 포맷을 늘린다.

| Phase | 목표 | 완료 증거 |
|---|---|---|
| **A. 골격 + 라우팅 + DOCX 수직슬라이스** | `DocFormat`/`DocText`/`DocHtml`(순수) + `DocxXml`/`DocxExtractor` + `DocTextActivity`(WebView) + MainActivity 분기. DOCX 한 포맷 end-to-end | `.docx`→텍스트 표시, 단위테스트 통과 |
| **B. HWPX 추출** | `HwpxXml`/`HwpxExtractor`(같은 ZIP+XML 패턴) | `.hwpx`→텍스트 표시 |
| **C. HWP 추출** | hwplib 의존성 + `HwpExtractor` | `.hwp`→한글 텍스트 표시 |
| **D. 뷰어 크롬** | 찾기바(findAllAsync) + 글자줌(textZoom) | 찾기 이동·줌 동작 |
| **E. 마감** | 최근파일 타입 라우팅 + 매니페스트/SAF MIME + 에러 문자열 + OSS 고지 | 카톡 3포맷 인입·재오픈·에러·고지 |

각 Phase는 "작동하는 앱" 상태 유지. 사용자 검증 후 다음 Phase.
