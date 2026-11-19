#!/usr/bin/env python3
"""Refresh particles and time glyphs inside the device-accepted small theme."""

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from build_honor_lock import ART, ROOT, xml
from repack_honor_lock import write_entry


BASE_THEME = ROOT / "exports" / "Snezhok-HONOR-lock-02.hnt"
BASE_SHA256 = "fe68e6272ad66d9de85c988f2908f78392fe8ed4e0cf8e9faf7fd974cf6215d6"
FONT = ART / "NotoSerif-Regular.ttf"
FONT_SHA256 = "c8f669ceb2c9c60ccf55198b305e08a997ffca79a38cc7eeb551e643cbe66505"
SCENE = "unlock/lockscreen/"
TITLE = "Snowflake winter gold"


def verified_bytes(path: Path, digest: str) -> bytes:
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Unexpected contents: {path.name}")
    return data


def turn_circles_into_images(scene: ET.Element) -> None:
    flake_index = 0
    for index, child in enumerate(list(scene)):
        if child.tag != "Circle":
            continue
        radius = int(child.attrib["r"])
        is_glow = child.find("AlphaAnimation") is not None
        image = ET.Element("Image", {
            "x": child.attrib["x"], "y": child.attrib["y"],
            "align": "center", "alignV": "center",
        })
        if is_glow:
            image.set("src", "warm-glow.png")
            diameter = 2 * radius + 6
            image.append(child.find("AlphaAnimation"))
        else:
            image.set("src", "snow-crystal.png" if flake_index % 6 == 0 else "snow-disk.png")
            diameter = 3 * radius + 8
            image.set("alpha", str(max(185, int(child.attrib["fillColor"][1:3], 16))))
            # Keep the image base in the viewport, then offset it along the existing path.
            image.set("y", "0")
            motion = child.find("PositionAnimation")
            for keyframe in motion:
                keyframe.set("y", str(int(keyframe.attrib["y"]) + int(child.attrib["y"])))
            image.append(motion)
            flake_index += 1
        image.set("w", str(diameter))
        image.set("h", str(diameter))
        scene.remove(child)
        scene.insert(index, image)


def replace_clock(scene: ET.Element) -> None:
    old_clock, date = scene.findall("DateTime")
    index = list(scene).index(old_clock)
    scene.remove(old_clock)
    scene.insert(index, ET.Element("Time", {
        "x": "#screen_width/2", "y": "#screen_height*0.145",
        "align": "center", "alignV": "center", "src": "time.png", "space": "0",
    }))
    y = "#screen_height*0.209"
    date.attrib.update({"y": y, "size": "43", "format": "d MMMM yyyy", "color": "#FFEED4"})
    for x in ("143", "937"):
        line = ET.Element("Image", {
            "src": "date-rule.png", "x": x, "y": y, "w": "115", "h": "3",
            "align": "center", "alignV": "center",
        })
        scene.insert(list(scene).index(date), line)


def updated_description(old: bytes) -> bytes:
    descriptor = ET.fromstring(old)
    descriptor.find("title").text = TITLE
    descriptor.find("version").text = "6.0.3"
    return xml(descriptor)


def build(output: Path) -> None:
    """Produce a new archive while retaining the accepted packaging layout."""
    if output.exists():
        raise FileExistsError(output)
    original = verified_bytes(BASE_THEME, BASE_SHA256)
    verified_bytes(FONT, FONT_SHA256)
    with ZipFile(io.BytesIO(original)) as source, tempfile.TemporaryDirectory(
        prefix="honor-gold-", dir=output.parent
    ) as staging:
        directory = Path(staging)
        subprocess.run(
            ["java", "-Djava.awt.headless=true", ROOT / "HonorLockSprites.java", directory, FONT],
            check=True, timeout=30,
        )
        manifest_path = SCENE + "manifest.xml"
        scene = ET.fromstring(source.read(manifest_path))
        turn_circles_into_images(scene)
        replace_clock(scene)
        staged = directory / output.name
        replacements = {
            manifest_path: xml(scene),
            "description.xml": updated_description(source.read("description.xml")),
        }
        with ZipFile(staged, "x") as target:
            for name in source.namelist():
                data = replacements[name] if name in replacements else source.read(name)
                write_entry(target, name, data)
            for sprite in sorted(directory.glob("*.png")):
                write_entry(target, SCENE + sprite.name, sprite.read_bytes())
        output.hardlink_to(staged)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("output", type=Path)
    build(cli.parse_args().output)
