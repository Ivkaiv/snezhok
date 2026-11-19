#!/usr/bin/env python3
"""Wrap the original Snowfall lock screen in HONOR's small-theme layout."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

from build_honor_lock import ART, ROOT, run_ffmpeg, xml


SOURCE = ROOT / "exports" / "Snezhok-HONOR-lock-01.hnt"
SOURCE_SHA256 = "e0ae689d2e844e6958e1b55b993a389dd4313ce6b220ddb1200167494c15b03c"
PREVIEWS = {
    "preview/cover.jpg": (1080, 1920, True),
    "preview/preview_unlock_0.jpg": (1080, 2160, True),
    "preview/preview_icons_0.jpg": (1080, 2160, False),
    "preview/preview_widget_0.jpg": (1080, 2160, False),
    "preview/icon_small.jpg": (510, 345, False),
    "preview/icon_small_1.jpg": (640, 640, False),
    "wallpaper/unlock_wallpaper_0.jpg": (1080, 2160, True),
    "wallpaper/home_wallpaper_0.jpg": (2160, 2160, False),
}


def write_entry(archive: ZipFile, name: str, data: bytes) -> None:
    """Use ordinary DOS-style ZIP entries as in HONOR's official sample."""
    entry = ZipInfo(name)
    entry.create_system = 0
    entry.create_version = 31
    entry.extract_version = 20
    entry.external_attr = 0x10 if name.endswith("/") else 0x80
    entry.compress_type = ZIP_STORED if name.endswith("/") else ZIP_DEFLATED
    archive.writestr(entry, data)


def build_descriptor() -> bytes:
    descriptor = ET.Element("HnTheme")
    fields = {
        "title": "Snowflake winter",
        "designer": "Snezhok",
        "screen": "FHD",
        "version": "6.0.2",
        "briefInfo": "A winter lock screen with falling snow, twinkling lights and a lively squirrel.",
        "chargeFlag": "0",
        "priceLevel": "-1",
        "expiredDate": "-1",
        "tag": "个性;扁平风",
        "areaFlag": "1",
        "extra": json.dumps({"extraContentFlag": "0", "category": "插画"}, ensure_ascii=False),
    }
    for name, value in fields.items():
        ET.SubElement(descriptor, name).text = value
    return xml(descriptor)


def neutral_module() -> bytes:
    """Provide a valid resource module with no icon or launcher overrides."""
    data = io.BytesIO()
    with ZipFile(data, "w") as archive:
        write_entry(archive, "theme.xml", xml(ET.Element("resources")))
    return data.getvalue()


def prepare_previews(directory: Path, source: ZipFile) -> dict[str, Path]:
    original = directory / "original-preview.jpg"
    original.write_bytes(source.read("preview/preview_unlock_0.jpg"))
    backdrop = ART / "winter-backdrop-premium-v1.png"
    previews = {}
    for name, (width, height, show_tree) in PREVIEWS.items():
        path = directory / name.replace("/", "-")
        filtergraph = (f"scale={width}:{height}:force_original_aspect_ratio=increase,"
                       f"crop={width}:{height},setsar=1")
        run_ffmpeg("-loglevel", "error", "-i", original if show_tree else backdrop,
                   "-vf", filtergraph, "-frames:v", 1, "-q:v", 3, "-threads", 1, path)
        previews[name] = path
    return previews


def build(output: Path) -> None:
    """Create a fresh package without altering the original theme."""
    if output.exists():
        raise FileExistsError(output)
    source_data = SOURCE.read_bytes()
    if hashlib.sha256(source_data).hexdigest() != SOURCE_SHA256:
        raise ValueError("Original lock-screen package does not match the expected artifact")
    with ZipFile(io.BytesIO(source_data)) as source, tempfile.TemporaryDirectory(
        prefix="honor-small-theme-", dir=output.parent
    ) as staging:
        directory = Path(staging)
        previews = prepare_previews(directory, source)
        staged = directory / output.name
        with ZipFile(staged, "x") as archive:
            for folder in ("preview/", "unlock/", "unlock/lockscreen/", "wallpaper/"):
                write_entry(archive, folder, b"")
            for name, path in previews.items():
                write_entry(archive, name, path.read_bytes())
            for entry in source.infolist():
                if entry.filename.startswith("unlock/") and not entry.is_dir():
                    write_entry(archive, entry.filename, source.read(entry))
            module = neutral_module()
            write_entry(archive, "com.hihonor.android.launcher", module)
            write_entry(archive, "description.xml", build_descriptor())
            write_entry(archive, "icons", module)
        output.hardlink_to(staged)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    build(parser.parse_args().output)
