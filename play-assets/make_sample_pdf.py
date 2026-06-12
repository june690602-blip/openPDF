"""Generate a realistic (fake, no personal data) Korean estimate PDF for store screenshots."""
import os
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample_estimate.pdf")
pdfmetrics.registerFont(TTFont("MG", "C:/Windows/Fonts/malgun.ttf"))
pdfmetrics.registerFont(TTFont("MGB", "C:/Windows/Fonts/malgunbd.ttf"))

W, H = A4
RED = (0.827, 0.184, 0.184)
GRAY = (0.93, 0.93, 0.93)


def won(n):
    return f"{n:,}"


c = canvas.Canvas(OUT, pagesize=A4)

# ---------- Page 1: 견적서 ----------
c.setFillColorRGB(*RED)
c.setFont("MGB", 26)
c.drawCentredString(W / 2, H - 62, "견  적  서")
c.setFillColorRGB(0, 0, 0)

c.setFont("MG", 10)
c.drawString(40, H - 92, "견적일자 : 2026-06-12")
c.drawString(40, H - 107, "견적번호 : Q-2026-0612")

top = H - 135
c.setFont("MGB", 11)
c.drawString(40, top, "공급받는 자")
c.setFont("MG", 10)
c.drawString(40, top - 18, "현장명 : 행복아파트 신축공사 현장")
c.drawString(40, top - 33, "담당   : 현장 자재 담당자")

c.setFont("MGB", 11)
c.drawString(310, top, "공급자")
c.setFont("MG", 10)
c.drawString(310, top - 18, "상호 : (주)튼튼건설자재")
c.drawString(310, top - 33, "사업자번호 : 123-45-67890")
c.drawString(310, top - 48, "주소 : 경기도 ○○시 산업로 123")
c.drawString(310, top - 63, "전화 : 031-123-4567")

items = [
    ("레미콘", "25-24-150", 120, "㎥", 78000),
    ("이형철근", "HD10", 3500, "kg", 1150),
    ("이형철근", "HD13", 2800, "kg", 1150),
    ("시멘트", "보통 40kg", 200, "포", 8500),
    ("세척사", "조립률 2.5", 80, "㎥", 32000),
    ("유로폼", "600×1200", 150, "장", 12000),
    ("압출단열재", "1호 50T", 300, "㎡", 9800),
    ("석고보드", "9.5T", 500, "장", 4200),
    ("내수합판", "12T", 220, "장", 18000),
    ("각재", "30×30", 600, "본", 1800),
]
cols = [("No", 30, "c"), ("품명", 110, "l"), ("규격", 95, "l"),
        ("수량", 55, "r"), ("단위", 40, "c"), ("단가", 80, "r"), ("금액", 105, "r")]
xs = [40]
for _, w, _a in cols:
    xs.append(xs[-1] + w)
right = xs[-1]


def row_cells(y, values, font="MG", size=9):
    c.setFont(font, size)
    for i, (val, (_, w, align)) in enumerate(zip(values, cols)):
        if align == "l":
            c.drawString(xs[i] + 5, y + 6, str(val))
        elif align == "r":
            c.drawRightString(xs[i + 1] - 5, y + 6, str(val))
        else:
            c.drawCentredString((xs[i] + xs[i + 1]) / 2, y + 6, str(val))


yh = top - 92
rh = 21
# header
c.setFillColorRGB(*GRAY)
c.rect(40, yh, right - 40, rh, fill=1, stroke=0)
c.setFillColorRGB(0, 0, 0)
row_cells(yh, [c0 for c0, _, _ in cols], font="MGB", size=9)

total = 0
y = yh
for idx, (name, spec, qty, unit, price) in enumerate(items, 1):
    y -= rh
    amt = qty * price
    total += amt
    row_cells(y, [idx, name, spec, won(qty), unit, won(price), won(amt)])

# grid
c.setLineWidth(0.5)
c.rect(40, y, right - 40, yh - y + rh, fill=0, stroke=1)
for xv in xs[1:-1]:
    c.line(xv, y, xv, yh + rh)
yy = yh + rh
while yy >= y:
    c.line(40, yy, right, yy)
    yy -= rh

# totals
vat = total // 10
grand = total + vat
c.setFont("MGB", 10)
ty = y - 26
for label, val in [("공급가액", total), ("부가세 (10%)", vat), ("합계금액", grand)]:
    c.drawRightString(right - 115, ty, label)
    c.drawRightString(right - 5, ty, won(val) + " 원")
    ty -= 20

c.setFont("MG", 9)
c.drawString(40, ty - 10, "※ 상기와 같이 견적합니다. 견적 유효기간은 발행일로부터 30일입니다.")
c.drawString(40, ty - 26, "※ 결제조건 : 납품 후 익월 말 현금 결제 / 운반비 별도")
c.setFont("MG", 8)
c.setFillColorRGB(0.5, 0.5, 0.5)
c.drawCentredString(W / 2, 40, "본 견적서는 데모용 샘플이며 실제 거래와 무관합니다.  CleanPDF Viewer")
c.showPage()

# ---------- Page 2: 특기사항 ----------
c.setFillColorRGB(*RED)
c.setFont("MGB", 18)
c.drawString(40, H - 70, "특기사항 및 납품 안내")
c.setFillColorRGB(0, 0, 0)
c.setLineWidth(1)
c.line(40, H - 80, W - 40, H - 80)

paras = [
    ("1. 자재 규격", [
        "모든 철근은 KS D 3504 규격품으로 납품하며, 시험성적서를 함께 제출합니다.",
        "레미콘은 25-24-150 규격을 기준으로 하며 현장 타설 일정에 맞춰 배차합니다.",
    ]),
    ("2. 납품 일정", [
        "1차 납품은 계약 후 7일 이내, 2차 납품은 현장 공정에 따라 협의합니다.",
        "우천 시 일정이 조정될 수 있으며, 변경 시 최소 1일 전에 통보합니다.",
    ]),
    ("3. 결제 및 하자", [
        "결제는 납품 확인 후 익월 말 현금 정산을 원칙으로 합니다.",
        "납품 자재의 하자 발생 시 7일 이내 무상 교환 또는 환불 처리합니다.",
    ]),
    ("4. 문의", [
        "자재 변경, 추가 견적, 납품 일정 문의는 공급자 연락처로 연락 바랍니다.",
        "본 문서는 CleanPDF Viewer 데모용 샘플로, 실제 개인정보를 포함하지 않습니다.",
    ]),
]
y = H - 110
for title, lines in paras:
    c.setFont("MGB", 12)
    c.drawString(40, y, title)
    y -= 22
    c.setFont("MG", 10)
    for ln in lines:
        c.drawString(52, y, ln)
        y -= 18
    y -= 12

c.setFont("MG", 8)
c.setFillColorRGB(0.5, 0.5, 0.5)
c.drawCentredString(W / 2, 40, "- 2 -")
c.showPage()
c.save()
print("saved", OUT)
