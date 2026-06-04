# CleanPDF Viewer — 설계 문서 (Design Spec)

> 작성일: 2026-06-05 (금) · 상태: 승인 대기 → 승인 시 writing-plans 로 구현계획 전개
> 자매 앱: CleanCAD Viewer (`opendwg`) — 광고 없는 무료 Android DWG 뷰어. 그 성공 패턴을 PDF로 이식.

## 1. 목적 & 동기

**한 줄: 광고 없이 깔끔하게 PDF를 보는 무료 Android 뷰어.**

시중 무료 PDF 뷰어는 광고가 과하고, 정작 검색 같은 기본 기능을 유료로 막는다. 현장(건설 등)
사용자가 카톡 등으로 받은 PDF(계약서·견적서·도면)를 **광고 없이, 기본 기능까지 무료로** 보게 한다.

성공 기준:
- 카톡 등에서 받은 .pdf 를 "열기/공유"로 바로 열람
- 연속 스크롤 + 줌으로 다중페이지 문서·대형 도면 모두 무난히 열람
- 텍스트 **검색 / 선택·복사 / 목차 / 썸네일 점프**가 광고·결제 없이 동작
- 대용량 PDF 에서도 ANR/OOM 없이 부드럽게

## 2. 정체성 & 스택

| 항목 | 값 |
|---|---|
| 표시 이름 | CleanPDF Viewer (가칭) |
| 레포 / 위치 | `openPDF` (`C:\dev\openPDF`) |
| 언어/UI | Kotlin + Android Views(XML 레이아웃) |
| SDK | minSdk 24, compile/targetSdk 36 |
| applicationId | `io.github.june690602_blip.cleanpdf` |
| 렌더 엔진 | **MuPDF (Artifex) `fitz`** — 공식 Android 라이브러리(프리빌트 .so) |
| 라이선스 | **AGPL v3** (MuPDF 결합으로 앱 전체 AGPL) |
| 수익모델 | 무료 · 광고 0 · 데이터 수집 0 |

**CleanCAD 대비 핵심 차이**: LibreDWG 처럼 NDK/JNI 로 엔진을 직접 빌드하지 않는다. MuPDF 가
프리빌트 AAR(`.so` 동봉)과 Java API 를 제공하므로, 우리는 그 위에 Kotlin UI 만 얹는다.

## 3. 아키텍처

```
PDF (content:// 또는 file://)
   │  IntentHandler: VIEW / SEND 인텐트 수신, isLikelyPdf 검사
   ▼
캐시 파일로 복사 (앱 cacheDir)
   │  MuPDF Document.openDocument(path)
   ▼
PdfDocument (래퍼)  ── 페이지수 / 렌더 / 텍스트 / 검색 / outline / 암호
   │
   ▼
PdfReaderView (연속 세로 스크롤 + 핀치 줌)
   │  보이는 페이지만 백그라운드 렌더 → Bitmap LRU 캐시
   │  줌 변경 시 해당 영역 고해상도 재렌더(타일링)
   ▼
오버레이: 검색 하이라이트 / 텍스트 선택 핸들
```

데이터 흐름 원칙:
- **온디맨드 렌더**: 화면에 보이는(±버퍼) 페이지만 렌더. 전체 페이지 선렌더 금지.
- **백그라운드 렌더**: 렌더는 워커 스레드, 결과 Bitmap 만 메인에서 표시. UI 블로킹/ANR 방지.
- **LRU 캐시**: 렌더된 Bitmap 을 메모리 예산 내 LRU 로 보관. 예산 초과 시 가장 오래된 것부터 해제.
- **불변 모델**: 검색결과·목차·최근파일 등 도메인 모델은 불변 데이터 클래스.

## 4. 컴포넌트 설계 (작은 파일 다수, 고응집·저결합)

각 단위는 "무엇을 하나 / 어떻게 쓰나 / 무엇에 의존하나"가 명확해야 한다.

| 컴포넌트 | 책임 | 의존 |
|---|---|---|
| `PdfDocument` | MuPDF `Document`/`Page` 래퍼. 열기·페이지수·페이지 렌더(Bitmap)·텍스트 추출·검색·outline 로드·암호 인증을 도메인 친화 API 로 노출 | MuPDF fitz |
| `PageRenderer` | 페이지+줌배율+영역 → Bitmap. 워커 스레드 실행, 취소(Cookie) 지원 | `PdfDocument` |
| `BitmapCache` | 렌더 결과 LRU 캐시(키=페이지·해상도·타일). 메모리 예산 관리 | — |
| `PdfReaderView` | 연속 세로 스크롤 + 핀치 줌. 가시 페이지 산출, 렌더 요청, 스크롤/플링/줌 제스처 | `PageRenderer`, `BitmapCache` |
| `SearchController` | 질의 → 페이지별 검색(`Page.search`) → 히트 목록(불변) → 하이라이트 rect + 다음/이전 점프 | `PdfDocument`, `PdfReaderView` |
| `TextSelectionController` | 롱프레스 선택 → `StructuredText` 기반 선택영역 → 복사(클립보드) | `PdfDocument`, `PdfReaderView` |
| `OutlinePanel` | `Document.loadOutline()` → 트리 표시 → 페이지 점프 | `PdfDocument` |
| `ThumbnailStrip` | 저해상도 페이지 썸네일 → 그리드/스트립 → 점프 | `PageRenderer` |
| `IntentHandler` | VIEW/SEND 인텐트 → URI 추출(`incomingUri`) → `isLikelyPdf` 게이트 → 캐시 복사 | — |
| `RecentFilesStore` | 최근 연 파일 목록(영속). 불변 리스트 반환, 추가/제거 시 새 객체 | — |
| `SettingsStore` | 다크모드/야간읽기/스크롤모드 등 설정 영속 | — |
| `ViewerActivity` | 화면 조립(툴바·검색바·패널·뷰어), 컴포넌트 배선 | 위 전부 |

> 검증 질문: 각 컨트롤러의 내부 구현을 바꿔도 `ViewerActivity` 가 안 깨지는가? → 인터페이스로
> 분리해 Yes 가 되도록 설계한다.

## 5. 기능 명세 (Tier B)

1. **열람**: 연속 세로 스크롤, 핀치 줌(최대 배율 충분히 크게 — 도면 디테일), 더블탭 줌, 플링.
2. **파일 받기**: 카톡 등 외부앱에서 "열기"(VIEW) + "공유"(SEND/EXTRA_STREAM). content:// 처리.
   `isLikelyPdf`(파일명 .pdf / 매직헤더 `%PDF-`)로 비-PDF 차단.
3. **검색**: 찾기 바 → 전체 페이지 검색, 히트 하이라이트, 다음/이전 이동, 히트 수 표시.
4. **텍스트 선택·복사**: 롱프레스로 선택, 핸들 드래그로 범위 조절, 클립보드 복사.
5. **목차(outline)**: PDF 북마크/목차 → 패널 → 항목 탭 시 해당 페이지로 점프. (목차 없는 PDF 는 비활성)
6. **썸네일 점프**: 페이지 썸네일 → 탭으로 점프. 페이지 번호 입력 점프도 제공.
7. **다크모드 + 야간 읽기**: 앱 크롬 다크(`values-night`) + **페이지 색 반전 토글**(어두운 곳 가독성).
8. **최근 파일**: 최근 연 PDF 리스트 → 탭으로 재열람.

## 6. 에러 처리

- **암호 PDF**: `needsPassword()` 시 비밀번호 입력 다이얼로그 → `authenticatePassword()`. 실패 안내.
- **손상/비-PDF**: `isLikelyPdf` 1차 차단, 그래도 열기 실패 시 친절한 에러 화면(원인+돌아가기).
- **대용량/메모리**: 온디맨드 렌더 + Bitmap LRU 로 OOM 방지. 렌더 취소(`Cookie`)로 스크롤 중 낭비 차단.
- **권한/URI 만료**: content:// 를 즉시 캐시 복사해 핸들 만료 회피(CleanCAD 패턴).

## 7. 라이선스 (AGPL v3) — 준수 의무

MuPDF 링크로 **앱 전체가 AGPL v3**. 로컬 뷰어라 AGPL 네트워크(§13) 조항은 실질 무발동.
- LICENSE = `AGPL-3.0`
- 앱 내 "정보/오픈소스 라이선스" 화면에 **소스 저장소 링크 + AGPL 전문 + MuPDF 고지**
- 스토어 설명에 소스 링크 명시
- 가격(무료/유료)은 자유·가변, **소스 공개만 영속 유지**(클로즈드 전환 불가)

## 8. 테스트 전략

- **단위테스트(JVM)**: `RecentFilesStore`, `isLikelyPdf`, 검색결과/목차 모델 변환, 설정 직렬화.
- **계측테스트(기기/에뮬)**: 인텐트(VIEW/SEND) → 열람 진입, 1페이지 렌더 스모크, 검색 1건 하이라이트.
- **수동 실기 검증**: 실제 카톡 → 열기 경로, 대용량 PDF 스크롤/줌 성능, 야간 반전 가독성.
- 증거 기반 완료: 각 Phase 종료 시 "빌드 exit 0 + 테스트 통과 수 + 실기 동작"을 증거로 기록.

## 9. 리스크 & 선검증 포인트

| 리스크 | 대응 |
|---|---|
| **MuPDF `fitz` AAR 를 Maven 에서 바로 받을 수 있는가** (가장 큰 불확실성) | **Phase 0 에서 먼저 검증.** 안 되면 ① Artifex 배포 Maven 추가 또는 ② MuPDF 소스 NDK 빌드(차선책, LibreDWG 경험 활용) |
| MuPDF Java API 시그니처(버전별 상이) | Phase 0/1 에서 context7 + 공식 문서로 실제 버전 API 확인 후 코딩 |
| 16KB 페이지 정렬(.so) — 최신 Android | CleanCAD 처럼 릴리즈 시 정렬 확인 |
| 연속 스크롤 + 타일 줌 복잡도 | MuPDF 레퍼런스 뷰어(`mupdf-android-viewer`, AGPL) 위젯 재사용/적응 검토 |

## 10. 비범위 (YAGNI — 이번엔 안 함)

- 주석/형광펜/그리기/양식 입력/서명 (Tier C — 추후 별도 스펙)
- 한 장씩 페이징 모드(나중에 설정 토글로 추가 가능)
- 클라우드 동기화/계정/네트워크 기능 일체
- PDF 편집·병합·분할·내보내기

## 11. 세션별 실행 계획 (Phase = 1~몇 세션)

> 상세 작업분해는 승인 후 writing-plans 로 전개. 아래는 큰 골격.

| Phase | 목표 | 완료 증거 |
|---|---|---|
| **0. 골격 + 엔진 연동** | 새 Android 프로젝트, Gradle, **MuPDF AAR 연동 검증**, 샘플 PDF 1페이지 렌더, 실기 `.so` 로드 | 앱 실행 → 1페이지 화면 표시 |
| **1. 연속 스크롤 뷰어** | `PdfReaderView` 연속 스크롤 + 핀치/더블탭 줌 + 온디맨드 렌더 + Bitmap LRU | 다중페이지 PDF 부드럽게 열람 |
| **2. 파일 받기** | VIEW/SEND 인텐트, content URI 캐시복사, `isLikelyPdf`, 최근파일 | 카톡→열기/공유 동작 |
| **3. 탐색** | 목차(outline) 패널 + 페이지 점프 + 썸네일 + 페이지번호 점프 | 큰 문서 빠른 이동 |
| **4. 검색** | 찾기 바, 전체검색, 하이라이트, 다음/이전, 히트수 | 검색→하이라이트 이동 |
| **5. 선택·복사** | 롱프레스 텍스트 선택, 핸들 조절, 클립보드 복사 | 텍스트 복사 동작 |
| **6. 읽기 편의** | 다크모드 + 야간 읽기(페이지 반전) + 설정 화면 | 야간 반전·설정 영속 |
| **7. 출시 준비** | R8 minify, 아이콘, 스토어 listing(KR/EN), 개인정보(수집0), 서명, AGPL 고지 | 릴리즈 AAB 생성 |

각 Phase 는 "작동하는 앱" 상태를 유지(증분 전달). 사용자 검증 후 다음 Phase 로.
