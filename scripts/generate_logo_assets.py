from __future__ import annotations

import struct
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"

LOGO_SVG = ASSETS / "xmu-assistant-logo.svg"
ICON_SVG = ASSETS / "xmu-assistant-icon.svg"
FOREGROUND_SVG = ASSETS / "xmu-assistant-icon-foreground.svg"
MARK_SVG = ASSETS / "xmu-assistant-mark.svg"
MARK_FOREGROUND_SVG = ASSETS / "xmu-assistant-mark-foreground.svg"


def render_svg(svg_path: Path, size: int, transparent: bool = True) -> QImage:
    scale = 4 if size <= 256 else 2 if size <= 512 else 1
    canvas = size * scale
    image = QImage(canvas, canvas, QImage.Format.Format_ARGB32_Premultiplied)
    image.fill(Qt.GlobalColor.transparent if transparent else QColor("#083B6F"))
    painter = QPainter(image)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, True)
    painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
    renderer = QSvgRenderer(str(svg_path))
    renderer.render(painter)
    painter.end()
    if scale == 1:
        return image
    return image.scaled(
        size,
        size,
        Qt.AspectRatioMode.KeepAspectRatio,
        Qt.TransformationMode.SmoothTransformation,
    )


def write_png(svg_path: Path, target: Path, size: int, transparent: bool = True) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    image = render_svg(svg_path, size, transparent=transparent)
    if not image.save(str(target), "PNG"):
        raise RuntimeError(f"failed to write {target}")


def png_bytes(svg_path: Path, size: int) -> bytes:
    from PySide6.QtCore import QByteArray, QBuffer, QIODevice

    data = QByteArray()
    buffer = QBuffer(data)
    buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    image = render_svg(svg_path, size, transparent=True)
    image.save(buffer, "PNG")
    return bytes(data)


def write_ico(target: Path, sizes: list[int]) -> None:
    frames = [(size, png_bytes(MARK_SVG if size <= 64 else ICON_SVG, size)) for size in sizes]
    header = struct.pack("<HHH", 0, 1, len(frames))
    directory = bytearray()
    payload = bytearray()
    offset = 6 + len(frames) * 16
    for size, data in frames:
        width = 0 if size >= 256 else size
        height = 0 if size >= 256 else size
        directory.extend(
            struct.pack(
                "<BBBBHHII",
                width,
                height,
                0,
                0,
                1,
                32,
                len(data),
                offset,
            )
        )
        payload.extend(data)
        offset += len(data)
    target.write_bytes(header + directory + payload)


def main() -> None:
    # Source assets for Windows/PyInstaller and visual preview.
    write_png(LOGO_SVG, ASSETS / "xmu-assistant-logo.png", 2048)
    write_png(ICON_SVG, ASSETS / "xmu-assistant-icon.png", 2048)
    write_png(MARK_SVG, ASSETS / "xmu-assistant-mark.png", 1024)
    write_png(MARK_FOREGROUND_SVG, ASSETS / "xmu-assistant-mark-foreground.png", 1024)
    for size in (16, 24, 32, 48, 64, 128, 256, 512, 1024):
        write_png(LOGO_SVG, ASSETS / f"xmu-assistant-logo-{size}.png", size)
        write_png(ICON_SVG, ASSETS / f"xmu-assistant-icon-{size}.png", size)
        write_png(MARK_SVG, ASSETS / f"xmu-assistant-mark-{size}.png", size)
        write_png(MARK_FOREGROUND_SVG, ASSETS / f"xmu-assistant-mark-foreground-{size}.png", size)
    write_ico(ASSETS / "xmu-assistant.ico", [16, 20, 24, 32, 40, 48, 64, 128, 256])

    # Android in-app display assets.
    drawable = ANDROID_RES / "drawable-nodpi"
    write_png(LOGO_SVG, drawable / "xmu_assistant_logo_large.png", 2048)
    write_png(LOGO_SVG, drawable / "xmu_assistant_logo.png", 2048)
    write_png(ICON_SVG, drawable / "xmu_assistant_icon_small.png", 1024)
    write_png(FOREGROUND_SVG, drawable / "xmu_assistant_icon_foreground.png", 1024)
    write_png(MARK_SVG, drawable / "xmu_assistant_mark.png", 1024)
    write_png(MARK_FOREGROUND_SVG, drawable / "xmu_assistant_mark_foreground.png", 1024)

    legacy_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    foreground_sizes = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }
    for folder, size in legacy_sizes.items():
        target_dir = ANDROID_RES / folder
        write_png(MARK_SVG, target_dir / "ic_launcher.png", size)
        write_png(MARK_SVG, target_dir / "ic_launcher_round.png", size)
    for folder, size in foreground_sizes.items():
        write_png(MARK_FOREGROUND_SVG, ANDROID_RES / folder / "ic_launcher_foreground.png", size)


if __name__ == "__main__":
    app = QGuiApplication([])
    main()
