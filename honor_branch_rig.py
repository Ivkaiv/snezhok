"""Articulate three individually traced spruce boughs in the HONOR theme."""

from dataclasses import dataclass
from io import BytesIO
from math import cos, radians, sin
import re
import xml.etree.ElementTree as ET

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from honor_squirrel_scene import LOOP_MS, SquirrelPath, friend_path, lead_path
from honor_tree_recoil import TREE_H, TREE_W, TREE_X, TREE_Y, encode, interpolate, position_frames


Point = tuple[int, int]
TRACK_TICK_MS = 32
TIP_DELAY_MS = 48
SPRING = ((0, 0), (48, .16), (98, .62), (160, 1), (231, .79),
          (320, .25), (411, -.22), (494, -.23), (590, -.05),
          (684, .07), (768, .03), (864, 0))


@dataclass(frozen=True)
class Bough:
    name: str
    root: Point
    joint: Point
    outline: tuple[Point, ...]

    def angle(self, force: float, distal: bool) -> float:
        direction = 1 if self.joint[0] > self.root[0] else -1
        return force * direction * (4.2 if distal else 4.4)

    def tip_fraction(self, x: np.ndarray | float) -> np.ndarray | float:
        direction = 1 if self.joint[0] > self.root[0] else -1
        t = np.clip(.5 + (x - self.joint[0]) * direction / 52, 0, 1)
        return t * t * (3 - 2 * t)


BOUGHS = (
    Bough("left-middle", (345, 1031), (166, 1062), (
        (347, 1001), (308, 1005), (280, 1019), (242, 1027), (210, 1035),
        (155, 1045), (107, 1054), (56, 1041), (12, 1039), (2, 1064),
        (33, 1095), (53, 1113), (92, 1117), (142, 1109), (185, 1101),
        (228, 1091), (272, 1075), (315, 1058), (350, 1051),
    )),
    Bough("left-low", (339, 1293), (188, 1331), (
        (353, 1264), (304, 1271), (264, 1277), (239, 1279), (202, 1282),
        (160, 1291), (124, 1286), (94, 1298), (55, 1304), (12, 1320),
        (0, 1347), (19, 1369), (40, 1371), (42, 1402), (86, 1408),
        (124, 1391), (165, 1373), (223, 1358), (261, 1334), (302, 1316),
        (354, 1320),
    )),
    Bough("right-low", (779, 1295), (910, 1323), (
        (777, 1269), (810, 1275), (855, 1270), (905, 1279), (947, 1288),
        (989, 1295), (1028, 1303), (1049, 1323), (1049, 1354),
        (1019, 1370), (1005, 1363), (1010, 1393), (988, 1410),
        (946, 1388), (903, 1374), (865, 1360), (825, 1340), (788, 1320),
        (769, 1313),
    )),
)


@dataclass
class Motion:
    bough: Bough
    loads: list[tuple[int, float]]
    coverage: np.ndarray

    def angles(self, time: int) -> tuple[float, float]:
        def force(delay: int) -> float:
            return sum(
                strength * interpolate(SPRING, elapsed)
                for landing, strength in self.loads
                if (elapsed := (time - landing - delay) % LOOP_MS) < SPRING[-1][0]
            )

        return self.bough.angle(force(0), False), self.bough.angle(force(TIP_DELAY_MS), True)

    def times(self) -> list[int]:
        times = {0, LOOP_MS}
        for landing, _ in self.loads:
            for delay in (0, TIP_DELAY_MS):
                times.update((landing + delay + age) % LOOP_MS for age, _ in SPRING)
                times.update((landing + delay + age) % LOOP_MS
                             for age in range(0, SPRING[-1][0], TRACK_TICK_MS))
        return sorted(times)

    def offset(self, x: float, y: float, time: int) -> tuple[float, float]:
        root_angle, tip_angle = self.angles(time)
        root_x, root_y = rotation_offset(x, y, self.bough.root, root_angle)
        tip_x, tip_y = rotation_offset(x, y, self.bough.joint, tip_angle)
        weight = float(self.bough.tip_fraction(x))
        correction = rotation_offset(tip_x, tip_y, (0, 0), root_angle)
        return (root_x + weight * (tip_x + correction[0]),
                root_y + weight * (tip_y + correction[1]))

    def contains(self, x: float, y: float) -> bool:
        return (0 <= x < TREE_W and 0 <= y < TREE_H
                and self.coverage[int(y), int(x)] >= .5)


def rotation_offset(x: float, y: float, pivot: Point, angle: float) -> tuple[float, float]:
    dx, dy = x - pivot[0], y - pivot[1]
    rotation = radians(angle)
    c, s = cos(rotation), sin(rotation)
    return ((c - 1) * dx - s * dy, s * dx + (c - 1) * dy)


def outline_mask(bough: Bough) -> np.ndarray:
    matte = Image.new("L", (TREE_W, TREE_H))
    ImageDraw.Draw(matte).polygon(bough.outline, fill=255)
    # Feather the split along the silhouette and the fork inside the crown.
    return np.asarray(matte.filter(ImageFilter.GaussianBlur(5)), dtype=np.float32) / 255


def divide_alpha(portion: np.ndarray, upper: np.ndarray) -> np.ndarray:
    """Recover a lower layer's opacity from its original contribution."""
    return np.divide(portion, 1 - upper, out=np.zeros_like(portion), where=upper < 1)


def cut_bough(tree: np.ndarray, motion: Motion) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    original_alpha = tree[..., 3].astype(np.float32) / 255
    cut_alpha = original_alpha * motion.coverage
    tip_alpha = cut_alpha * motion.bough.tip_fraction(np.arange(TREE_W))
    stem_alpha = divide_alpha(cut_alpha - tip_alpha, tip_alpha)
    background_alpha = divide_alpha(original_alpha - cut_alpha, cut_alpha)
    return stem_alpha, tip_alpha, background_alpha


def png_layer(tree: np.ndarray, alpha: np.ndarray) -> tuple[bytes, tuple[int, int, int, int]]:
    rgba = Image.fromarray(tree.copy())
    rgba.putalpha(Image.fromarray(np.clip(np.rint(alpha * 255), 0, 255).astype(np.uint8)))
    bounds = rgba.getbbox(alpha_only=True)
    if bounds is None:
        raise ValueError("A bough layer is empty")
    x0, y0, x1, y1 = bounds
    # A transparent pixel around the texture prevents hard filtering at the image edge.
    bounds = max(0, x0 - 1), max(0, y0 - 1), min(TREE_W, x1 + 1), min(TREE_H, y1 + 1)
    return encode(rgba.crop(bounds)), bounds


def backfill(tree: np.ndarray, alpha: np.ndarray) -> np.ndarray:
    """Hide the small gaps behind the moving stems with shadowed needles."""
    coverage = tree[..., 3] == 255
    interior = cv2.distanceTransform(coverage.astype(np.uint8), cv2.DIST_L2, 3)
    deep = (interior > 25) & (alpha < .998)
    if not np.any(deep):
        return tree
    fill = cv2.inpaint(np.ascontiguousarray(tree[..., :3]), deep.astype(np.uint8),
                       4, cv2.INPAINT_TELEA)
    back = tree.copy()
    back[deep, :3] = np.rint(fill[deep] * .68).astype(np.uint8)
    alpha[deep] = 1
    return back


def add_bone(scene: ET.Element, index: int, motion: Motion,
             bounds: tuple[int, int, int, int], src: str, tip: bool) -> None:
    left, top, right, bottom = bounds
    px, py = motion.bough.joint if tip else motion.bough.root
    layer = ET.Element("Image", src=src, x=str(TREE_X + left), y=f"{TREE_Y}+{top}",
                       w=str(right - left), h=str(bottom - top),
                       centerX=str(px - left), centerY=str(py - top))
    scene.insert(index, layer)
    rotation = ET.SubElement(layer, "RotationAnimation", repeat="0")
    positions = []
    for time in motion.times():
        base_angle, tip_angle = motion.angles(time)
        angle = base_angle + tip_angle if tip else base_angle
        ET.SubElement(rotation, "Rotation", angle=f"{angle:.3f}", time=str(time))
        if tip:
            dx, dy = rotation_offset(px, py, motion.bough.root, base_angle)
            positions.append((time, round(dx), round(dy)))
    if tip:
        position_frames(layer, positions)


def track_paws(scene: ET.Element, paths: tuple[tuple[str, SquirrelPath, float], ...],
               motions: list[Motion]) -> None:
    for name, path, _ in paths:
        group = scene.find(f"Group[@name='{name}']")
        if group is None or (animation := group.find("PositionAnimation")) is None:
            raise ValueError(f"Missing squirrel path {name}")
        xs = sorted((time, xy[0]) for time, xy in path.positions.items())
        ys = sorted((time, xy[1]) for time, xy in path.positions.items())
        times = set(path.positions).union(*(motion.times() for motion in motions))
        frames = []
        for time in sorted(times):
            x, y = interpolate(xs, time), interpolate(ys, time)
            px, py = x + path.start.x - TREE_X, y + path.start.y + TREE_H
            for motion in motions:
                if motion.contains(px, py):
                    tx, ty = motion.offset(px, py, time)
                    x, y = x + tx, y + ty
                    break
            frames.append((time, round(x), round(y)))
        index = list(group).index(animation)
        group.remove(animation)
        position_frames(group, frames, index)


def track_lamps(bulbs: list[ET.Element], motions: list[Motion]) -> None:
    for lamp in bulbs:
        match = re.fullmatch(r"#screen_height\*0[.]97-1575\+(\d+)", lamp.attrib["y"])
        if match is None:
            continue
        x, y = int(lamp.attrib["x"]) - TREE_X, int(match[1])
        for motion in motions:
            if motion.contains(x, y):
                frames = []
                for time in motion.times():
                    dx, dy = motion.offset(x, y, time)
                    frames.append((time, round(dx), round(dy)))
                position_frames(lamp, frames)
                break


def articulate_tree(scene: ET.Element, tree_bytes: bytes,
                    bulbs: list[ET.Element]) -> tuple[dict[str, bytes], dict[str, int]]:
    base, = scene.findall("Image[@src='tree.png']")
    expected = {"x": str(TREE_X), "y": TREE_Y + "+0", "w": str(TREE_W), "h": str(TREE_H)}
    if any(base.get(key) != value for key, value in expected.items()):
        raise ValueError("Tree layout changed; the boughs must be retraced")
    with Image.open(BytesIO(tree_bytes)) as original:
        tree = np.asarray(original.convert("RGBA").resize((TREE_W, TREE_H), Image.Resampling.LANCZOS))
    paths = (("squirrel_lead", lead_path(detailed=True), 1.00),
             ("squirrel_friend", friend_path(detailed=True), .82))
    assets = {}
    motions = []
    base_alpha = tree[..., 3].astype(np.float32) / 255
    index = list(scene).index(base) + 1
    for bough in BOUGHS:
        coverage = outline_mask(bough)
        motion = Motion(bough, [], coverage)
        for _, path, strength in paths:
            for time, name, foot in path.branch_loads:
                if name == bough.name:
                    x, y = foot.x - TREE_X, foot.y + TREE_H
                    if not motion.contains(x, y) or tree[y, x, 3] < 127:
                        raise ValueError(f"Landing outside {name}: ({x}, {y})")
                    motion.loads.append((time, strength))
        if not motion.loads:
            raise ValueError(f"No landing on {bough.name}")
        if any(np.any((coverage > 0) & (other.coverage > 0)) for other in motions):
            raise ValueError(f"Bough mattes overlap: {bough.name}")
        motions.append(motion)
        stem, tip, below = cut_bough(tree, motion)
        np.copyto(base_alpha, below, where=coverage > 0)
        for part, alpha, distal in (("stem", stem, False), ("tip", tip, True)):
            src = f"boughs/{bough.name}-{part}.png"
            assets[src], bounds = png_layer(tree, alpha)
            add_bone(scene, index, motion, bounds, src, distal)
            index += 1
    back = backfill(tree, base_alpha)
    behind = Image.fromarray(back)
    behind.putalpha(Image.fromarray(np.clip(np.rint(base_alpha * 255), 0, 255).astype(np.uint8)))
    base.set("src", "boughs/tree-base.png")
    assets["boughs/tree-base.png"] = encode(behind)
    track_paws(scene, paths, motions)
    track_lamps(bulbs, motions)
    return assets, {motion.bough.name: len(motion.loads) for motion in motions}
