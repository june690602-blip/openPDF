# CleanPDF Viewer — DocText 핸드오프 (한글·워드 텍스트 읽기)

> 작성일: 2026-06-06 · 상태: **완료, 브랜치 `feat/doctext-reader`** (main `6c05712`에서 분기, 미병합)
> 스펙: `specs/2026-06-06-cleanpdf-doctext-design.md` · 계획: `plans/2026-06-06-cleanpdf-doctext.md`
> 구현 방식: subagent-driven TDD (Task당 implementer + 2단계 리뷰), 최종 종합 리뷰(opus) 통과.

PDF 코어를 건드리지 않고 `MainActivity`에 포맷 라우터를 끼워 **DOCX·HWP·HWPX 를 "텍스트로 읽기"**만 추가.

---

## 1. 완료 상태 & 증거
- **세 포맷 모두 텍스트 추출 동작**: DOCX(`word/document.xml`), HWPX(`Contents/section*.xml`), HWP v5(hwplib).
- 인입: 카톡 VIEW/SEND·SAF "파일 열기"(`*/*`)·최근파일에서 포맷 자동 분기.
- 뷰어: 오프라인 WebView — 스크롤·선택·복사·**찾기바(findAllAsync)**·**핀치 글자줌**.
- 표는 셀=`\t`, 행=`\n` 평탄화. 실패 시 친절한 에러(빈문서/못읽음/미지원 구분).
- **테스트: 단위 85, 계측 13(12 pass + HWP smoke 1 skip), 0 실패.** PDF 기존 계측 8개 회귀 없음.
- 빌드: `assembleDebug` BUILD SUCCESSFUL. 최종 종합 리뷰: **READY TO MERGE**(critical/important 0).

## 2. 핵심 결정 (브레인스토밍 확정)
① WebView(오프라인) ② 별도 `DocTextActivity` ③ 친절한 에러 화면만 ④ 찾기+글자줌(핀치만) ⑤ 표 셀\t/행\n.

추가 구현 결정:
- **공용 `XmlFlowText`**: DOCX·HWPX 가 동일 local element name(`p`/`t`/`tbl`/`tr`/`tc`)을 쓰므로 스펙의 `DocxXml`+`HwpxXml` 를 하나로 통합(DRY). prefix(`w:`/`hp:`) 무관하게 `substringAfterLast(':')` 로 매칭. 텍스트는 `<t>` 안에서만 수집(태그 사이 들여쓰기 공백 누출 차단).
- **포맷 감지 2단계**: 순수 `detectFormat(name, head)`(확장자+`%PDF-`) → 확장자 없는 ZIP/OLE 만 IO `DocProbe`로 컨테이너 엿보기. **OLE 매직은 hwp v5 ↔ 구 .doc 동일** → 확장자/서명으로 구분, 아니면 UNKNOWN.
- **재추출, 텍스트 캐시 없음**: 열 때마다 추출(텍스트라 빠름). 최근파일은 `RecentFile.format`만 저장.
- **PDF 8대 불변조건 무접촉**: 문서 경로는 fitz/`PdfDocument`/`PageRenderer`/`BitmapCache` 미사용, 별도 액티비티 + 별도 단일 bg executor. `route(PDF)`는 기존 `openFile` 그대로.

## 3. 파일 (신규/수정)
**순수 (JVM 단위테스트):** `doc/DocFormat.kt`(enum+detectFormat+매직), `doc/DocText.kt`(DocText+ExtractResult), `doc/XmlFlowText.kt`(공용 파서), `doc/DocHtml.kt`(escape+`<pre>`), `doc/DocTextExtractor.kt`(인터페이스+`toResult`+`Extractors` 팩토리), `doc/HwpxExtractor.kt`의 `sectionIndex`.
**IO (계측/JVM):** `doc/DocProbe.kt`(컨테이너 정밀판정, ZIP는 JVM 테스트), `doc/DocxExtractor.kt`, `doc/HwpxExtractor.kt`, `doc/HwpExtractor.kt`(hwplib).
**UI:** `DocTextActivity.kt`, `res/layout/activity_doc_text.xml`, `res/menu/doc_menu.xml`.
**수정:** `MainActivity.kt`(loadFromUri 라우팅 + `route()` + showRecent 라우팅 + SAF `*/*`), `io/PdfSource.kt`(`displayName`/`peekHead` 추가, `looksLikePdf` 제거), `store/RecentFilesStore.kt`(`format` 필드), `AndroidManifest.xml`(VIEW/SEND doc MIME + DocTextActivity 등록), `res/values/strings.xml`(+5), `res/menu/reader_menu.xml`(open_file), `gradle/libs.versions.toml`+`app/build.gradle.kts`(hwplib).

## 4. 빌드 & 테스트
- 빌드: `./gradlew :app:assembleDebug`
- 단위: `./gradlew :app:testDebugUnitTest` (85개, Robolectric 포함)
- 계측: `./gradlew :app:connectedDebugAndroidTest` (에뮬 `emulator-5554` 필요; 실행 후 앱 언인스톨)
- 단일 계측: `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`

## 5. 미검증 / 후속 (우선순위 순)
1. **HWP 런타임 실기 검증** — `HwpExtractorSmokeTest`는 픽스처 없으면 **skip**(개인정보 .hwp 를 레포에 커밋 안 함). hwplib 자체는 PoC 검증됨 + 컴파일로 API 결합 확인. **실기 확인 권장**: 실제 `.hwp`를 카톡/SAF로 열어 한글 텍스트 표시 확인. (자동 스모크를 원하면 비민감 `sample.hwp`를 `app/src/androidTest/assets/`에 두면 됨 — 단 공개 레포라 민감문서 금지.)
2. **on-device 수동 3포맷 검증** — 실제 카톡/SAF로 .docx/.hwp/.hwpx 열어 텍스트·표·찾기·핀치줌·복사·에러(손상/빈/미지원) 확인. (계측 테스트가 DOCX 렌더+찾기, HWPX 추출을 에뮬에서 검증하나, 실제 인텐트 경로는 미캡처.)
3. **출시(Phase 7) OSS 고지** — 앱 내 오픈소스 라이선스 화면이 아직 없음. 출시 시 **hwplib (Apache-2.0)** 고지 추가 필수(+ 기존 MuPDF AGPL). 현재는 의존성만 추가된 상태.
4. **(minor) 중복 SAF 조회** — `MainActivity.loadFromUri`가 `displayName`+`peekHead`+`copyToCache`(내부 `displayName` 재호출)로 content-resolver 왕복 ~4회. `name`을 `copyToCache`에 넘기면 줄일 수 있음. 기능 무해.
5. **(minor) 캐시 파일명 basename** — `copyToCache`가 표시이름을 그대로 `opened_<ts>_<name>`에 사용(앱 샌드박스 내, provider 제공 이름이라 위험 낮음). basename 정제하면 더 깔끔. 기존 동작.

## 6. 함정/메모
- `connectedDebugAndroidTest`는 실행 후 앱 APK 언인스톨 → 수동검증은 그 전에 또는 `installDebug` 후.
- 확장자 없는 octet-stream `.hwp`(OLE)는 `DocProbe`의 64KB 서명 스캔(best-effort) — 거의 모든 실파일은 확장자가 있어 1차 `detectFormat`로 끝남.
- HWPX element 이름은 표준 OWPML(`hp:p`/`hp:t`) 가정 — 합성 픽스처로 단위 통과. 실제 한글 저장 `.hwpx`로 한 번 더 확인하면 안전(후속 #2).
- WebView 보안: JS off + content escape + baseURL null + file/content access off → 주입/유출 벡터 없음.

## 7. 다음
- 사용자 검증 후 `feat/doctext-reader` → main 병합.
- 로드맵 Phase 6(다크모드/야간 읽기)·Phase 7(출시)는 그대로. 출시 시 OSS 고지에 hwplib 포함.
