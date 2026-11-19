#!/usr/bin/env python3
"""Build two frame-animated garlands for the confirmed HONOR lock screen."""

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from build_honor_lock import ROOT, xml
from repack_honor_lock import write_entry


BASE_THEME = ROOT / "exports" / "Snezhok-HONOR-lock-03.hnt"
BASE_SHA256 = "bba12ebaa4926c4baa4e1d065376edbc3750035edd469c4e1aa578c4b7edb8e8"
SCENE = "unlock/lockscreen/"
HONOR_VERSION = "6.0.4"
TITLES = {"warm": "Snowflake winter warm", "color": "Snowflake winter multicolor"}
WARM = {"gold": ("ffdd8c", "e49335")}
COLOR = {
    "red": ("ff5351", "ff202b"),
    "amber": ("ffbc44", "ff820c"),
    "green": ("62f687", "1cce6d"),
    "blue": ("60ccff", "2785ff"),
    "violet": ("d591ff", "a340ed"),
}
FADE = (0, 0, 1, 2, 3, 4, 5, 6, 7, 7, 6, 5, 4, 3, 2, 1)
CHASE = (0, 0, 1, 1, 2, 3, 5, 7, 6, 4, 2, 1)


def apply_garland(scene: ET.Element, mode: str) -> None:
    colors = tuple(WARM if mode == "warm" else COLOR)
    lights = [node for node in scene.findall("Image") if node.get("src") == "warm-glow.png"]
    if len(lights) != 27:
        raise ValueError(f"Unexpected number of lights: {len(lights)}")
    steps = FADE if mode == "warm" else CHASE
    # For the color chase, sort the bulbs from the upper garland down the tree.
    order = sorted(lights, key=light_height) if mode == "color" else lights
    for index, node in enumerate(order):
        color = colors[(index * 3) % len(colors)]
        old = node.find("AlphaAnimation")
        if old is None:
            raise ValueError("Expected the old brightness control")
        node.remove(old)
        diameter = max(52, int(node.attrib["w"]) + 27)
        node.set("w", str(diameter))
        node.set("h", str(diameter))
        delay = (index * 5 + index // 4) % len(steps) if mode == "warm" else -index
        cycle = ET.SubElement(node, "SourcesAnimation", repeat="0")
        for tick in range(len(steps) + 1):
            level = steps[(tick + delay) % len(steps)]
            name = f"lights/{color}-{level}.png"
            if tick == 0:
                node.set("src", name)
            frame_ms = 115 + 13 * (index % 3) if mode == "warm" else 145
            ET.SubElement(cycle, "Source", src=name, time=str(tick * frame_ms))


def light_height(image: ET.Element) -> float:
    """Compare the in-scene height of the authored positions at reference size."""
    factors = image.attrib["y"].split("*", 1)
    if len(factors) != 2 or factors[0] != "#screen_height":
        raise ValueError("Unexpected light position")
    height = factors[1]
    if "-" not in height:
        return 2400 * float(height)
    percent, offsets = height.split("-", 1)
    bottom, offset = offsets.split("+", 1)
    return 2400 * float(percent) - int(bottom) + int(offset)


def build(mode: str, output: Path) -> None:
    """Create a new file with the original package structure and scene."""
    if output.exists():
        raise FileExistsError(output)
    payload = BASE_THEME.read_bytes()
    if hashlib.sha256(payload).hexdigest() != BASE_SHA256:
        raise ValueError("Device-accepted source theme has changed")
    with ZipFile(io.BytesIO(payload)) as source, tempfile.TemporaryDirectory(
        prefix="honor-garland-", dir=output.parent
    ) as staging:
        directory = Path(staging)
        for color, (glass, halo) in (WARM if mode == "warm" else COLOR).items():
            subprocess.run(
                ["java", "-Djava.awt.headless=true", ROOT / "HonorGarlandSprites.java",
                 directory, color, glass, halo], check=True, timeout=20,
            )
        manifest = SCENE + "manifest.xml"
        scene = ET.fromstring(source.read(manifest))
        apply_garland(scene, mode)
        descriptor = ET.fromstring(source.read("description.xml"))
        descriptor.find("title").text = TITLES[mode]
        descriptor.find("version").text = HONOR_VERSION
        updated = {manifest: xml(scene), "description.xml": xml(descriptor)}
        staged = directory / output.name
        with ZipFile(staged, "x") as package:
            for name in source.namelist():
                if name != SCENE + "warm-glow.png":
                    write_entry(package, name, updated[name] if name in updated else source.read(name))
            write_entry(package, SCENE + "lights/", b"")
            for sprite in sorted(directory.glob("*.png")):
                write_entry(package, SCENE + "lights/" + sprite.name, sprite.read_bytes())
        output.hardlink_to(staged)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=TITLES)
    parser.add_argument("output", type=Path)
    options = parser.parse_args()
    build(options.mode, options.output)
