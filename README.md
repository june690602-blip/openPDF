# CleanPDF Viewer

광고 없는 무료 오픈소스 안드로이드 PDF 뷰어. 건설 현장에서 카톡 등으로 받은 계약서·견적서·도면을 빠르고 깔끔하게 보기 위해 만들었습니다.

An ad-free, free, open-source Android PDF viewer.

## 기능

- 연속 세로 스크롤, **부드러운 핀치 줌인/줌아웃·팬·플링**(단일 Matrix 캔버스 렌더)
- 더블탭 줌, 잡을 수 있는 스크롤바, 하단 페이지 표시
- 임의 PDF 열기(SAF) · 카톡 등에서 공유받기(VIEW/SEND) · 암호 PDF · 최근 파일
- 목차/페이지 이동 · 썸네일 · 본문 검색(하이라이트) · 텍스트 선택·복사
- 광고 없음, 추적 없음

## 기술

- Kotlin, Android Views. minSdk 24 / target 36.
- PDF 엔진: **[MuPDF](https://mupdf.com) (Artifex) `fitz` 1.27.1**.
- 렌더: 페이지 비트맵을 document 공간(PDF 포인트)에 세로로 배치하고 단일 `Matrix`(doc→screen)로 그림. 줌/팬/플링은 행렬 연산, 배율이 멈추면 보이는 페이지를 그 배율로 재렌더해 선명하게 유지.

## 빌드

```sh
./gradlew :app:assembleDebug          # 빌드
./gradlew :app:testDebugUnitTest      # JVM 단위 테스트
./gradlew :app:connectedDebugAndroidTest  # 계측 테스트(에뮬/기기 필요)
```

## 라이선스

**GNU AGPL v3** — 전문은 [`LICENSE`](LICENSE) 참고.

MuPDF가 AGPL이라, 이를 링크하는 이 앱 전체도 AGPL v3입니다. 따라서 소스 전체를 영구 공개합니다. 사용 오픈소스: MuPDF (AGPL v3), hwplib (Apache-2.0).
