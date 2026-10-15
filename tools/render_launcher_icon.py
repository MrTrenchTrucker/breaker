# Re-render the 5 foreground PNGs from docs/brand/breaker-icon.svg.
# Run manually (never by a build or a gate).
# The rendered set in the repo is the 0.85-scale fit; the MANIFEST.json written here names the source, its sha256 and every file.
import hashlib, json, os, re, subprocess, sys
from PIL import Image, ImageDraw, ImageChops
W = "/w"
src = open(f"{W}/breaker-icon.svg", "rb").read()
sha = hashlib.sha256(src).hexdigest()
s = src.decode()
bg_re = r'<path d="M0 0 L1024 0 L1024 0 L1024 1024 L1024 1024 L0 1024 L0 1024 L0 0 Z" fill="#0e1113"/>'
assert len(re.findall(re.escape(bg_re), s)) == 1, "background path not found exactly once"
SCALE = 0.85
fg = s.replace(bg_re, "")
i = fg.index("</defs>") + len("</defs>"); j = fg.rindex("</svg>")
fg = fg[:i] + f'<g transform="translate(512 512) scale({SCALE}) translate(-512 -512)">' + fg[i:j] + "</g>" + fg[j:]
open(f"{W}/fg.svg", "w").write(fg)
ver = subprocess.run(["rsvg-convert", "--version"], capture_output=True, text=True).stdout.strip()
out = f"{W}/out"; os.makedirs(out, exist_ok=True)
# control: full SVG (with background) vs the rendered PNG set in the repo
subprocess.run(["rsvg-convert", "-w", "1024", "-h", "1024", "-o", f"{W}/full_rsvg.png", f"{W}/breaker-icon.svg"], check=True)
a = Image.open(f"{W}/full_rsvg.png").convert("RGB"); b = Image.open(f"{W}/breaker-icon.png").convert("RGB")
d = ImageChops.difference(a, b).convert("L"); hist = d.histogram()
mean = sum(i * c for i, c in enumerate(hist)) / sum(hist)
big = sum(hist[30:]) / sum(hist)
print(f"control rsvg-vs-repo: mean diff {mean:.3f}/255, pixels >30 off {big*100:.3f}%")
# glow present? pixels green-ish around the mic outline outside the mic body
dens = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
man = {"source": "docs/brand/breaker-icon.svg", "source_sha256": sha, "renderer": ver,
       "background_removed": "the one full-square path filled #0e1113", "scale": SCALE, "scale_reason": "farthest mic point (head top-right corner, 359.7 px from centre at 1024, measured from the SVG path data) fitted inside the 66/108 safe zone (312.9 px): 0.85 -> 305.8 px; the soft glow tail reaches ~321 px, inside the visible circle (341.3 px)", "files": {}}
for name, px in dens.items():
    p = f"{out}/mipmap-{name}/ic_launcher_foreground.png"; os.makedirs(os.path.dirname(p), exist_ok=True)
    subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), "-o", p, f"{W}/fg.svg"], check=True)
    im = Image.open(p); assert im.size == (px, px) and im.mode == "RGBA", (im.size, im.mode)
    assert im.getpixel((0, 0))[3] == 0, "corner not transparent"
    man["files"][f"mipmap-{name}/ic_launcher_foreground.png"] = {
        "px": px, "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()}
json.dump(man, open(f"{out}/MANIFEST.json", "w"), indent=2)
# masked previews from the xxxhdpi layer on the #0E1113 background
fgi = Image.open(f"{out}/mipmap-xxxhdpi/ic_launcher_foreground.png")
layer = Image.new("RGBA", fgi.size, (14, 17, 19, 255)); layer.alpha_composite(fgi)
n = fgi.size[0]; v0 = round(n * 18 / 108); v1 = n - v0          # visible 72 of 108
vis = layer.crop((v0, v0, v1, v1)).resize((576, 576), Image.LANCZOS)
S = 576; sheet = Image.new("RGBA", (S * 3 + 80, S + 40), (200, 200, 200, 255))
def mask(kind):
    m = Image.new("L", (S, S), 0); dr = ImageDraw.Draw(m)
    if kind == "circle": dr.ellipse((0, 0, S - 1, S - 1), fill=255)
    elif kind == "rounded": dr.rounded_rectangle((0, 0, S - 1, S - 1), radius=S // 6, fill=255)
    else:
        for y in range(S):
            for x in range(S):
                u = abs(2 * x / (S - 1) - 1); w = abs(2 * y / (S - 1) - 1)
                if u ** 5 + w ** 5 <= 1: m.putpixel((x, y), 255)
    return m
for i, k in enumerate(["circle", "squircle", "rounded"]):
    t = Image.new("RGBA", (S, S), (0, 0, 0, 0)); t.paste(vis, (0, 0), mask(k))
    sheet.alpha_composite(t, (20 + i * (S + 20), 20))
    # 66/108 safe-zone circle outline (66 of the visible 72)
    dr = ImageDraw.Draw(sheet); r = S * 66 / 72 / 2; cx = 20 + i * (S + 20) + S / 2; cy = 20 + S / 2
    dr.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(255, 0, 255, 255), width=2)
sheet.save(f"{out}/masked_previews.png")
print(json.dumps(man, indent=1))
