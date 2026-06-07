# DR2 (DocText 구조+이미지) — 새 세션 실행 킥오프

> 이 파일은 새 Claude Code 세션에서 DR2 구현을 subagent-driven으로 시작하기 위한 안내다.
> 계획서: `docs/superpowers/plans/2026-06-08-cleanpdf-doctext-structured.md`
> 스펙: `docs/superpowers/specs/2026-06-08-cleanpdf-doctext-structured-design.md`
> 브랜치: `feat/doctext-structured` (main `a265e94`에서 분기, 스펙+계획 커밋 `fa7d8e0`까지 완료)

---

## 1) 세션 시작 전 셸 준비 (git-bash)

```bash
# 프로젝트 + 브랜치 확인
cd /c/dev/openPDF
git checkout feat/doctext-structured
git status -s            # 깨끗해야 함
git log --oneline -1     # fa7d8e0 (plan 커밋) 확인

# 에뮬레이터 부팅(이미 떠 있으면 생략)
~/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.1 -no-boot-anim &
ADB=~/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB wait-for-device
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done; echo BOOTED
```

## 2) 새 세션에 붙여넣을 프롬프트 (그대로 복사)

```
openPDF(C:\dev\openPDF, ⚠️ Bash cwd가 매번 리셋되니 명령마다 `cd /c/dev/openPDF &&` 선행)에서
DocText 구조+이미지(DR2) 구현계획을 superpowers:subagent-driven-development 스킬로 실행해줘.

- 브랜치: feat/doctext-structured (이미 존재, main a265e94 분기). 먼저 git checkout 으로 확인.
- 계획서: docs/superpowers/plans/2026-06-08-cleanpdf-doctext-structured.md
  (참고 스펙: docs/superpowers/specs/2026-06-08-cleanpdf-doctext-structured-design.md)
- Task마다 새 implementer 서브에이전트(model: sonnet) + 사이에 spec 준수/품질 리뷰. 계획의 코드/커밋을 그대로 따르고,
  빌드는 매 Task green 유지(계획의 toResultStrings 마이그레이션 트릭). 중간에 멈추지 말고 계획대로 계속 진행.
- 순서: S-A(A1~A4 순수 추가) → S-B(B1 모델 cut, B2 DOCX) → S-C(C1 HWPX) → S-D(D1 HWP 객체모델) → S-E(E1 정리, E2 문서, E3 마무리).

환경/주의(계획에도 있음):
- 에뮬 emulator-5554 필요(계측 Task). adb=~/AppData/Local/Android/Sdk/platform-tools/adb.exe, 기기경로 인자엔 MSYS_NO_PATHCONV=1.
- hwplib 객체모델 import는 object가 Kotlin 예약어 → 백틱 필수: kr.dogfoot.hwplib.`object`.…
- connectedDebugAndroidTest는 실행 후 앱을 언인스톨함(수동검증은 그 전에/ installDebug 후).
- main 직접 커밋 금지. 모든 Task 완료 후 superpowers:finishing-a-development-branch 로 마무리(병합 여부는 나한테 물어봐).

지금 Task A1부터 시작해줘.
```

## 3) S-D(HWP) 검증 fixture — 실행자 메모

D1의 HWP 스모크는 **표+이미지가 둘 다 있는 실제 .hwp**가 있어야 SKIP이 아닌 실행이 된다.
PoC에서 확인된 후보(사용자 Downloads):
- `~/Downloads/매장유산 발굴 허가 신청서.hwp` (표 1 + PNG 1)
- `~/Downloads/예비고1 비문학 (2016년).hwp` (표 62 + 이미지 13)

검증 절차(개인정보라 **커밋 금지**):
```bash
cd /c/dev/openPDF
echo "app/src/androidTest/assets/*.hwp" >> .gitignore     # 커밋 방지(한 번만)
mkdir -p app/src/androidTest/assets
cp "/c/Users/bogeun/Downloads/매장유산 발굴 허가 신청서.hwp" app/src/androidTest/assets/sample.hwp
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.june690602_blip.cleanpdf.HwpExtractorSmokeTest
rm -f app/src/androidTest/assets/sample.hwp               # 검증 후 삭제
git status -s                                             # sample.hwp 안 잡히는지 확인(.gitignore)
```
(`.gitignore`의 `*.hwp` 라인은 커밋해도 무방 — 개인 파일이 실수로 올라가는 걸 막아줌.)

## 4) 완료 기준
- 단위/계측 전부 PASS(HWP 스모크는 fixture 없으면 SKIP 허용), PDF 회귀 0.
- 실기: 표+이미지 있는 .docx/.hwpx/.hwp 가 **표=`<table>`, 이미지=인라인**으로 렌더(스크린샷).
- CLAUDE.md 갱신 + 핸드오프 작성 후 브랜치 마무리.
