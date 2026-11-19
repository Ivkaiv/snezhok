"""Render local, paw-synchronized branch bending as HONOR image frames."""

from bisect import bisect_right
from dataclasses import dataclass
from io import BytesIO
from math import pi, sin
import re
import xml.etree.ElementTree as ET

from PIL import Image

from honor_squirrel_scene import LOOP_MS, friend_path, lead_path


TREE_X = 15
TREE_W = 1050
TREE_H = 1575
TREE_Y = "#screen_height*0.97-1575"
AMOUNTS = (-4, -2, 0, 2, 4, 6, 8, 10, 12)
RECOIL = ((0, 0), (60, 1), (150, .28), (214, -.32), (304, .10), (390, 0))


@dataclass(frozen=True)
class Branch:
    name: str
    box: tuple[int, int, int, int]

    def weight(self, x: float, y: float) -> float:
        left, top, right, bottom = self.box
        if left < x < right and top < y < bottom:
            return (sin(pi * (x - left) / (right - left))
                    * sin(pi * (y - top) / (bottom - top))) ** 2
        return 0.0

    def src(self, amount: int) -> str:
        return f"branches/{self.name}-{amount:+d}.png"


# Each rectangle is outside the central trunk, with stationary, feathered edges.
BRANCHES = (
    Branch("left-crown", (275, 630, 508, 945)),
    Branch("right-crown", (533, 630, 782, 945)),
    Branch("left-middle", (184, 948, 467, 1150)),
    Branch("right-middle", (545, 949, 830, 1130)),
    Branch("left-low", (80, 1150, 426, 1480)),
    Branch("right-low", (690, 1130, 992, 1450)),
)


def interpolate(frames, time):
    index = bisect_right(frames, (time, float("inf"))) - 1
    if index >= len(frames) - 1:
        return frames[-1][1]
    start, first = frames[index]
    end, second = frames[index + 1]
    return first + (second - first) * (time - start) / (end - start)


def press_amount(presses, at):
    force = sum(strength * interpolate(RECOIL, delta)
                for time, strength in presses
                if 0 <= (delta := (at - time) % LOOP_MS) < RECOIL[-1][0])
    return min(AMOUNTS, key=lambda amount: abs(amount - force))


def branch_frames(presses):
    samples = {0, LOOP_MS}
    for at, _ in presses:
        samples.update((at + delta) % LOOP_MS for delta in range(0, 391, 15))
        samples.update((at + delta) % LOOP_MS for delta, _ in RECOIL)
    frames = []
    for time in sorted(samples):
        amount = press_amount(presses, time)
        if not frames or amount != frames[-1][1] or time == LOOP_MS:
            frames.append((time, amount))
    return frames


def frame_amount(frames, time):
    return frames[bisect_right(frames, (time, AMOUNTS[-1])) - 1][1]


def encode(image):
    out = BytesIO()
    image.save(out, format="PNG", optimize=True)
    return out.getvalue()


def render_branch(tree, branch, amount):
    # Map the cropped texture back to its undeformed coordinates. Sampling
    # the whole source supplies neighbouring pixels at every cell boundary.
    left, top, right, bottom = branch.box
    mesh = []

    def point(x, y):
        return x, y - amount * branch.weight(x, y)

    for y in range(top, bottom, 12):
        for x in range(left, right, 12):
            x2, y2 = min(x + 12, right), min(y + 12, bottom)
            quad = point(x, y) + point(x, y2) + point(x2, y2) + point(x2, y)
            mesh.append(((x - left, y - top, x2 - left, y2 - top), quad))
    return tree.transform((right - left, bottom - top), Image.Transform.MESH, mesh,
                          resample=Image.Resampling.BICUBIC)


def position_frames(node, frames, index=None):
    animation = ET.Element("PositionAnimation", repeat="0")
    node.insert(len(node) if index is None else index, animation)
    for time, x, y in frames:
        ET.SubElement(animation, "Position", x=str(x), y=str(y), time=str(time))


def shift_paws(scene, paths, timelines):
    for name, path, _ in paths:
        group = scene.find(f"Group[@name='{name}']")
        if group is None or (animation := group.find("PositionAnimation")) is None:
            raise ValueError(f"Missing path for {name}")
        artist_frames = sorted(path.positions.items())
        key_times = set(path.positions)
        for frames in timelines.values():
            for time, _ in frames:
                key_times.update((max(0, time - 12), time))
        xs = [(t, p[0]) for t, p in artist_frames]
        ys = [(t, p[1]) for t, p in artist_frames]
        adjusted = []
        for time in sorted(key_times):
            x, y = interpolate(xs, time), interpolate(ys, time)
            px, py = x + path.start.x - TREE_X, y + path.start.y + TREE_H
            dip = sum(branch.weight(px, py) * frame_amount(frames, time)
                      for branch, frames in timelines.items())
            adjusted.append((time, round(x), round(y + dip)))
        index = list(group).index(animation)
        group.remove(animation)
        position_frames(group, adjusted, index)


def shift_bulbs(bulbs, timelines):
    for bulb in bulbs:
        y = re.fullmatch(r"#screen_height\*0[.]97-1575\+(\d+)", bulb.attrib["y"])
        if y is None:
            continue
        px, py = int(bulb.attrib["x"]) - TREE_X, int(y[1])
        for branch, frames in timelines.items():
            weight = branch.weight(px, py)
            if weight < .10:
                continue
            motion = {}
            for time, amount in frames:
                if time:
                    motion[max(0, time - 12)] = round(weight * frame_amount(frames, time - 1))
                motion[time] = round(weight * amount)
            position_frames(bulb, [(time, 0, y) for time, y in sorted(motion.items())])
            break  # The branch rectangles do not overlap.


def animate_tree(scene, tree_data, bulbs):
    base, = scene.findall("Image[@src='tree.png']")
    expected = {"x": str(TREE_X), "y": TREE_Y + "+0", "w": str(TREE_W), "h": str(TREE_H)}
    if any(base.get(key) != value for key, value in expected.items()):
        raise ValueError("Tree layout changed; branch coordinates need adjustment")
    with Image.open(BytesIO(tree_data)) as original:
        tree = original.convert("RGBA").resize((TREE_W, TREE_H), Image.Resampling.LANCZOS)
    trunk = tree.copy()
    assets = {}
    paths = (("squirrel_lead", lead_path(detailed=True), 12),
             ("squirrel_friend", friend_path(detailed=True), 10))
    timelines = {}
    contacts = {}
    index = list(scene).index(base)
    for branch in BRANCHES:
        presses = []
        for _, path, strength in paths:
            for time, foot in path.branch_contacts:
                x, y = foot.x - TREE_X, foot.y + TREE_H
                if branch.weight(x, y) >= .25 and tree.getpixel((x, y))[3] >= 127:
                    presses.append((time, strength))
        if not presses:
            raise ValueError(f"No paw contact on {branch.name}")
        frames = branch_frames(presses)
        timelines[branch] = frames
        contacts[branch.name] = len(presses)
        left, top, right, bottom = branch.box
        trunk.paste((0, 0, 0, 0), branch.box)
        for amount in sorted({amount for _, amount in frames}):
            image = tree.crop(branch.box) if amount == 0 else render_branch(tree, branch, amount)
            assets[branch.src(amount)] = encode(image)
        index += 1
        layer = ET.Element("Image", src=branch.src(frames[0][1]), x=str(TREE_X + left),
                           y=f"{TREE_Y}+{top}", w=str(right-left), h=str(bottom-top))
        animation = ET.SubElement(layer, "SourcesAnimation", repeat="0")
        for time, amount in frames:
            ET.SubElement(animation, "Source", src=branch.src(amount), time=str(time))
        scene.insert(index, layer)
    base.set("src", "branches/trunk.png")
    assets[base.attrib["src"]] = encode(trunk)
    shift_paws(scene, paths, timelines)
    shift_bulbs(bulbs, timelines)
    return assets, contacts


def add_countdown(scene):
    date, = scene.findall("DateTime")
    index = list(scene).index(date) + 1
    units = ("day", "hour", "minute")
    for offset, unit in enumerate(units):
        scene.insert(index + offset, ET.Element("CountDownTime", name=f"new_year_{unit}",
                                                date="01-01", countDownType="loop", unit=unit))
    scene.insert(index + len(units), ET.Element(
        "Text", x="#screen_width/2", y="#screen_height*0.255", align="center",
        alignV="center", size="31", color="#FFFFE5AB",
        Radius="4", shadowDx="0", shadowDy="2", shadowColor="#B01C1B24",
        format="До Нового года · %d дн. %d ч. %d мин.",
        paras=",".join(f"#new_year_{unit}" for unit in units)))
