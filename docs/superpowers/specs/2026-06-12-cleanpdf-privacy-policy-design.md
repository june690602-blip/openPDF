# CleanPDF 개인정보처리방침 (Privacy Policy) — Design

작성일: 2026-06-12 · 상태: 구현 완료

## 목적
Google Play 출시에 필요한 개인정보처리방침을 (1) 웹 URL과 (2) 앱 내 화면 두 곳에 제공한다.
이 앱은 **권한 선언 0개**(인터넷 권한조차 없음)이고 최근 파일 목록을 `SharedPreferences`에 로컬로만
저장하므로, 방침의 핵심은 "어떠한 개인정보도 수집하지 않음"이다.

## 결정 사항
| 항목 | 결정 |
|------|------|
| 범위 | 앱 내 표시 + 웹 URL 둘 다 |
| 웹 호스팅 | 레포 루트 `PRIVACY.md` → `github.com/june690602-blip/openPDF/blob/main/PRIVACY.md` (Play Console 등록용) |
| 앱 내 표시 | 홈 화면 ⋮ 메뉴에 "개인정보처리방침" 별도 항목 → 다이얼로그로 한국어 전문 |
| 문의처 | june690602@gmail.com + GitHub 레포 |
| 언어 | PRIVACY.md = 한국어 + 영문 병기 / 앱 내 다이얼로그 = 한국어(영문은 웹 링크) |

## 변경 파일
1. **`PRIVACY.md`** (신규) — 한/영 병기 전문. 8개 절(수집 없음·권한 없음·파일 기기 내 처리·로컬 저장·광고/분석/제3자 없음·아동·변경·문의).
2. **`app/src/main/res/values/strings.xml`** — `privacy_policy`(메뉴/제목), `privacy_text`(다이얼로그 한국어 전문) 추가. `about_text`에 개인정보처리방침 URL 한 줄 추가.
3. **`HomeActivity.kt`** — 메뉴 `MENU_PRIVACY` + `showPrivacy()`. 기존 `showAbout()`을 공용 `showInfoDialog(titleRes, messageRes, linkMask)` 헬퍼로 묶어 중복 제거. 개인정보 다이얼로그는 `WEB_URLS or EMAIL_ADDRESSES` 마스크로 이메일도 탭 가능.

## 비범위 / 의존
- GitHub Pages는 쓰지 않음(레포 파일 URL로 충분). 별도 Pages 활성화 불필요.
- Play Console에 위 URL을 등록하는 작업은 출시 단계에서 수동.

## 검증
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (exit 0).
- 다이얼로그는 검증된 `showAbout()` 패턴 복제. 실기 UI 표시는 출시 검증 시 함께 확인.
