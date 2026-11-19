#!/usr/bin/env python3
"""Build an original lock-only HNT with HONOR's documented XML scene elements."""

import argparse
import math
from pathlib import Path
import random
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent
ART = ROOT / "art"
TREE_WIDTH = 1050
TREE_HEIGHT = 1575
TREE_LEFT = (1080 - TREE_WIDTH) // 2
BRANCHES = (
    (.27, .78), (.62, .67), (.77, .51), (.55, .39),
    (.63, .27), (.39, .20), (.28, .41), (.42, .58),
)
TREE_LIGHTS = (
    (.489, .068), (.478, .184), (.561, .242), (.425, .288), (.447, .370),
    (.596, .381), (.382, .531), (.486, .481), (.699, .524), (.225, .652),
    (.484, .634), (.805, .682), (.447, .732), (.592, .766), (.654, .758),
    (.610, .823), (.596, .859),
)
GARLAND_LIGHTS = (
    (.05, .037), (.09, .031), (.19, .033), (.26, .007), (.36, .010),
    (.66, .022), (.86, .022), (.93, .045), (.97, .153), (.83, .042),
)


def element(parent, tag, **attributes):
    return ET.SubElement(parent, tag, {key: str(value) for key, value in attributes.items()})


def xml(root):
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def top_of_tree(offset=0):
    return f"#screen_height*0.97-{TREE_HEIGHT}+{round(offset)}"


def bulb(scene, x, y, radius, phase):
    light = element(scene, "Circle", x=x, y=y, r=radius)
    glow = element(light, "AlphaAnimation", repeat=0)
    for t, a in ((0, 35), (720, 150), (1580, 42), (2490, 186), (3440, 35)):
        element(glow, "Alpha", a=a, time=round(t * phase))
    gradient = element(light, "FillGradient")
    radial = element(gradient, "Radial", x=radius, y=radius, r=radius)
    for color, position in (("#FFFFE1", 0), ("#A0FFC56D", .24), ("#00FFAD55", 1)):
        element(radial, "GradientPoint", color=color, position=position)


def add_lights(scene):
    for i, (x, y) in enumerate(GARLAND_LIGHTS):
        bulb(scene, round(x * 1080), f"#screen_height*{y:.3f}", 11 + i % 3, 1 + .066 * i)
    for i, (x, y) in enumerate(TREE_LIGHTS):
        bulb(scene, TREE_LEFT + round(x * TREE_WIDTH), top_of_tree(y * TREE_HEIGHT),
             10 + i % 5, .8 + .037 * i)


def add_snow(scene):
    randomizer = random.Random(171126)
    for _ in range(65):
        x = randomizer.randrange(1080)
        radius = randomizer.choice((3, 3, 4, 4, 5, 6))
        duration = randomizer.randint(15000, 29000)
        phase = randomizer.randint(120, duration - 120)
        sway = randomizer.randint(8, 35)
        alpha = randomizer.randint(105, 220)
        particle = element(scene, "Circle", x=x, y=-28, r=radius,
                           fillColor=f"#{alpha:02X}F2F8FF", strokeColor="#8853728B", weight=1)
        motion = element(particle, "PositionAnimation", repeat=0)
        remaining = duration - phase
        # Put the two wrap frames off screen and start each flake partway in its loop.
        moments = sorted({0, remaining - 1, remaining, duration, *range(600, duration, 600)})
        for t in moments:
            fraction = (phase + t) % duration / duration
            dy = round(fraction * 2840)
            dx = round(sway * math.sin(4 * math.pi * fraction))
            element(motion, "Position", x=dx, y=dy, time=t)


def add_squirrel(scene, poses):
    anchor_x, anchor_y = BRANCHES[0]
    squirrel = element(scene, "Group", x=TREE_LEFT + round(anchor_x * TREE_WIDTH),
                       y=top_of_tree(anchor_y * TREE_HEIGHT))
    motion = element(squirrel, "PositionAnimation", repeat=0)
    scale = 142 / max(rect[0] for rect in poses)
    # The canvas gives all poses the same bottom centre without rescaling their body proportions.
    width = round(max(rect[0] for rect in poses) * scale)
    height = round(max(rect[1] for rect in poses) * scale)
    sprite = element(squirrel, "Image", src="squirrel-0-r.png", x=0, y=0,
                     align="center", alignV="bottom", w=width, h=height)
    frames = element(sprite, "SourcesAnimation", repeat=0)
    elapsed = 0
    element(motion, "Position", x=0, y=0, time=0)
    for index, (x, y) in enumerate(BRANCHES):
        next_x, next_y = BRANCHES[(index + 1) % len(BRANCHES)]
        mirrored = "l" if next_x < x else "r"
        rest = (1900, 2200, 1700, 2350)[index % 4]
        hop = (840, 930, 790, 900)[index % 4]
        for t, pose in ((0, 0), (rest - 150, 1), (rest + hop // 6, 2),
                        (rest + hop * 4 // 5, 3)):
            element(frames, "Source", src=f"squirrel-{pose}-{mirrored}.png", time=elapsed + t)
        element(motion, "Position", x=round((x - anchor_x) * TREE_WIDTH),
                y=round((y - anchor_y) * TREE_HEIGHT), time=elapsed + rest)
        rise = min(TREE_HEIGHT * .10, TREE_HEIGHT * .045 + abs(next_x - x) * TREE_WIDTH * .11)
        for j in range(1, 13):
            fraction = j / 12
            smooth = fraction * fraction * (3 - 2 * fraction)
            dx = (x - anchor_x + (next_x - x) * smooth) * TREE_WIDTH
            dy = (y - anchor_y + (next_y - y) * smooth) * TREE_HEIGHT
            dy -= 4 * rise * fraction * (1 - fraction)
            element(motion, "Position", x=round(dx), y=round(dy),
                    time=elapsed + rest + round(hop * fraction))
        elapsed += rest + hop
    element(frames, "Source", src="squirrel-0-r.png", time=elapsed)


def manifest(poses):
    scene = ET.Element("Lockscreen", version="1", frameRate="60", vibrate="false",
                       screenWidth="1080", displayDesktop="true", showSysWallpaper="false")
    element(scene, "Var", name="notification", expression="1")
    element(scene, "Image", src="backdrop.png", x=540, y="#screen_height/2",
            align="center", alignV="center", w=1080, h="#screen_height", scaleType="center_crop")
    element(scene, "Image", src="tree.png", x=TREE_LEFT, y=top_of_tree(),
            w=TREE_WIDTH, h=TREE_HEIGHT)
    add_lights(scene)
    add_squirrel(scene, poses)
    add_snow(scene)
    element(scene, "DateTime", x=540, y="#screen_height*0.103", color="#FFF0D0",
            align="center", alignV="center", size=154, format="HH:mm",
            shadowColor="#CC192736", shadowDx=2, shadowDy=4)
    element(scene, "DateTime", x=540, y="#screen_height*0.155", color="#F3DFC4",
            align="center", alignV="center", size=42, format="EEEE, d MMMM")
    # HONOR's documented swipe gesture hands off to the system lock screen.
    unlock = element(scene, "Unlocker", name="unlocker", bounceInitSpeed=2000, bounceAcceleration=3000)
    element(unlock, "StartPoint", x=0, y=0, w="#screen_width", h="#screen_height")
    end = element(unlock, "EndPoint", x=0, y=-350, w="#screen_width", h=200)
    path = element(end, "Path", x=0, y=0, w="#screen_width", h="#screen_height")
    element(path, "Position", x=0, y=0)
    element(path, "Position", x=0, y=-180)
    return xml(scene)


def theme_descriptor():
    descriptor = ET.Element("HnTheme")
    for tag, value in (
        ("title", "Снежок · Зимний экран"), ("designer", "Snezhok"),
        ("screen", "FHD"), ("version", "6.0.2"),
        ("briefInfo", "Animated winter lock screen with snow, lights and a squirrel."),
        ("chargeFlag", "0"), ("priceLevel", "-1"), ("expiredDate", "-1"),
        ("areaFlag", "1"), ("extra", '{"extraContentFlag":"1"}'),
    ):
        element(descriptor, tag).text = value
    return xml(descriptor)


def unlock_descriptor():
    descriptor = ET.Element("HnTheme")
    element(descriptor, "item", style="slide")
    element(descriptor, "item", package="com.hihonor.android.thememanager")
    element(descriptor, "item", dynamicPath="lockscreen")
    element(descriptor, "item", minversion="3.0.0")
    element(descriptor, "item", maxHeight=2160)
    return xml(descriptor)


def run_ffmpeg(*arguments):
    return subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostdin", "-n", *map(str, arguments)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=30,
    )


def prepare_poses(directory):
    atlas = ART / "squirrel-jump-sheet-v1.png"
    bounds = []
    for index in range(4):
        cell = f"crop=627:627:{627 * (index % 2)}:{627 * (index // 2)}"
        result = run_ffmpeg("-i", atlas, "-vf", f"format=rgba,{cell},alphaextract,bbox=min_val=16",
                            "-frames:v", 1, "-f", "null", "-")
        hit = re.search(r"x1:(\d+) x2:\d+ y1:(\d+) y2:\d+ w:(\d+) h:(\d+)", result.stderr)
        if hit is None:
            raise ValueError(f"No visible squirrel in atlas cell {index}")
        x, y, width, height = map(int, hit.groups())
        bounds.append((width, height, f"{cell},crop={width}:{height}:{x}:{y}"))
    common_width = max(item[0] for item in bounds)
    common_height = max(item[1] for item in bounds)
    for index, (_, _, crop) in enumerate(bounds):
        for direction, flip in (("r", ""), ("l", ",hflip")):
            filters = f"format=rgba,{crop}{flip},pad={common_width}:{common_height}:(ow-iw)/2:oh-ih:color=black@0"
            run_ffmpeg("-loglevel", "error", "-i", atlas, "-vf", filters, "-frames:v", 1,
                       "-threads", 1, directory / f"squirrel-{index}-{direction}.png")
    return bounds


def build(output):
    if output.exists():
        raise FileExistsError(output)
    with tempfile.TemporaryDirectory(prefix="honor-lock-", dir=output.parent) as staging:
        directory = Path(staging)
        poses = prepare_poses(directory)
        preview = directory / "preview.jpg"
        run_ffmpeg("-loglevel", "error", "-i", ROOT / "exports/video-preview-02s.png",
                   "-frames:v", 1, "-q:v", 3, preview)
        payload = manifest(poses)
        staged = directory / output.name
        with zipfile.ZipFile(staged, "x", compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr("description.xml", theme_descriptor())
            package.writestr("unlock/theme.xml", unlock_descriptor())
            package.writestr("unlock/lockscreen/manifest.xml", payload)
            package.write(ART / "winter-backdrop-premium-v1.png", "unlock/lockscreen/backdrop.png")
            package.write(ART / "christmas-tree-premium-v1.png", "unlock/lockscreen/tree.png")
            for pose in sorted(directory.glob("squirrel-*.png")):
                package.write(pose, f"unlock/lockscreen/{pose.name}")
            for name in ("preview/cover.jpg", "preview/preview_unlock_0.jpg",
                         "wallpaper/unlock_wallpaper_0.jpg"):
                package.write(preview, name)
        output.hardlink_to(staged)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    build(parser.parse_args().output)
