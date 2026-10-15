#!/usr/bin/env python3
"""Generate application icon resources from the approved AZ RemoteConnect logo.

The repository stores the user's icon in text-safe base64 form because the
repository content connector accepts UTF-8 text files. Run before either build.
"""
from base64 import b64decode
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
encoded = (ROOT / "branding" / "remote-a-icon.jpg.b64").read_text(encoding="ascii").strip()
source = Image.open(BytesIO(b64decode(encoded, validate=True))).convert("RGB")
assert source.width == source.height and source.width >= 96
image = source.convert("RGBA")

android = ROOT / "android" / "app" / "src" / "main" / "res"
for qualifier, pixels in {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}.items():
    folder = android / ("mipmap-" + qualifier)
    folder.mkdir(parents=True, exist_ok=True)
    image.resize((pixels, pixels), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")

windows = ROOT / "windows" / "azremoteconnect.ico"
image.save(windows, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(f"Generated Android launcher icons and Windows icon from {source.size} branded source.")
