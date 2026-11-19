"""Author two synchronized, closed squirrel paths using HONOR's native frame tags."""

from dataclasses import dataclass
import math
import xml.etree.ElementTree as ET


LOOP_MS = 24_000


@dataclass(frozen=True)
class Foot:
    x: int
    y: int  # Relative to the bottom of the tree, which sits at 97% of the screen height.


class SquirrelPath:
    def __init__(self, start: Foot, face: str, detailed: bool = False):
        self.start = start
        self.foot = start
        self.face = face
        self.time = 0
        self.detailed = detailed
        self.positions: dict[int, tuple[int, int]] = {0: (0, 0)}
        self.branch_contacts: list[tuple[int, Foot]] = []
        self.branch_loads: list[tuple[int, str, Foot]] = []
        initial = "idle-0" if detailed else "0"
        self.poses: dict[int, str] = {0: f"squirrel-{initial}-{face}.png"}

    def pose(self, time: int, index: int) -> None:
        name = f"idle-{index}" if self.detailed else str(index)
        self.poses[time] = f"squirrel-{name}-{self.face}.png"

    def position(self, time: int, x: float, y: float) -> None:
        self.positions[time] = (round(x - self.start.x), round(y - self.start.y))

    def watch(self, until: int, face: str, curious: bool = False) -> None:
        length = until - self.time
        self.face = face
        self.pose(self.time, 0)
        if self.detailed:
            # The body gently changes silhouette, with the same bottom paw anchor in every frame.
            for frame, offset in enumerate(range(220, length - 90, 220), 1):
                self.pose(self.time + offset, (0, 1, 2, 3, 2, 1)[frame % 6])
            if curious and length >= 700:
                glance = self.time + length // 3
                self.poses[glance] = f"squirrel-run-1-{self.face}.png"
                self.pose(glance + 110, 1)
        else:
            # Keep the old asset schedule available for the earlier release.
            for offset in range(450, length - 130, 650):
                self.position(self.time + offset, self.foot.x, self.foot.y - 2)
                self.position(self.time + offset + 100, self.foot.x, self.foot.y)
        if curious and length >= 700 and not self.detailed:
            self.pose(self.time + length // 3, 1)
            self.pose(self.time + length // 3 + 150, 0)
        self.position(until, self.foot.x, self.foot.y)
        self.time = until

    def move(self, until: int, target: Foot, ground: bool = False,
             branch: str | None = None) -> None:
        duration = until - self.time
        start = self.foot
        dx = target.x - start.x
        dy = target.y - start.y
        self.face = "r" if dx >= 0 else "l"
        stride = 85 if ground else 105
        steps = max(1, math.ceil(math.hypot(dx, dy) / stride))
        if not self.detailed:
            self.pose(self.time, 1)
        for step in range(steps):
            begin = self.time + round(duration * step / steps)
            end = self.time + round(duration * (step + 1) / steps)
            self.bound(begin, end, start, dx, dy, step, steps, ground)
        self.position(until, target.x, target.y)
        if branch is not None:
            self.branch_loads.append((until, branch, target))
        self.pose(until, 0)
        self.foot = target
        self.time = until

    def bound(self, begin: int, end: int, start: Foot, dx: int, dy: int,
              step: int, steps: int, ground: bool) -> None:
        length = end - begin
        if self.detailed:
            gait = "run" if ground else "climb"
            if not ground and dy > 0:
                gait = "down"
            for frame in range(8):
                at = begin + round(length * frame / 8)
                self.poses[at] = f"squirrel-{gait}-{frame}-{self.face}.png"
        else:
            self.pose(begin, 1)
            self.pose(begin + round(length * .23), 2)
            self.pose(begin + round(length * .58), 3)
            self.pose(begin + round(length * .86), 0)
        rise = (10 + step % 4) if ground else (17 + step % 5)
        for sample in range(9):
            fraction = sample / 8
            # Leave time for the paws to settle at each point instead of gliding.
            moving = min(1.0, fraction / .88)
            progress = moving * moving * (3 - 2 * moving)
            travel = (step + progress) / steps
            y = start.y + dy * travel - 4 * rise * moving * (1 - moving)
            self.position(begin + round(length * fraction), start.x + dx * travel, y)
        if not ground:
            travel = (step + 1) / steps
            foot = Foot(round(start.x + dx * travel), round(start.y + dy * travel))
            self.branch_contacts.append((begin + round(length * 7 / 8), foot))

    def xml(self, name: str, width: int, height: int) -> ET.Element:
        if self.time != LOOP_MS or self.foot != self.start:
            raise ValueError("Squirrel must finish at its starting point")
        self.poses[LOOP_MS] = self.poses[0]
        group = ET.Element("Group", name=name, x=str(self.start.x),
                           y=f"#screen_height*0.97{self.start.y:+d}")
        path = ET.SubElement(group, "PositionAnimation", repeat="0")
        for time, (x, y) in sorted(self.positions.items()):
            ET.SubElement(path, "Position", x=str(x), y=str(y), time=str(time))
        sprite = ET.SubElement(group, "Image", src=self.poses[0], x="0", y="0",
                               align="center", alignV="bottom", w=str(width), h=str(height))
        frames = ET.SubElement(sprite, "SourcesAnimation", repeat="0")
        for time, src in sorted(self.poses.items()):
            ET.SubElement(frames, "Source", src=src, time=str(time))
        return group


def lead_path(detailed: bool = False) -> SquirrelPath:
    lead = SquirrelPath(Foot(365, -520), "l", detailed)
    lead.watch(700, "l")
    lead.move(1350, Foot(238, -266), branch="left-low")
    lead.move(2150, Foot(190, -180))
    lead.move(2850, Foot(160, -7))
    lead.move(4080, Foot(388, -8), ground=True)
    lead.watch(6140, "r", curious=True)
    lead.move(8350, Foot(777, -10), ground=True)
    lead.watch(8680, "r")
    lead.move(9670, Foot(873, -185))
    lead.move(10650, Foot(914, -278), branch="right-low")
    lead.move(11950, Foot(688, -520))
    lead.move(13500, Foot(620, -710))
    lead.move(14900, Foot(605, -870))
    lead.watch(17200, "l", curious=True)
    lead.move(18300, Foot(681, -770))
    lead.move(19520, Foot(548, -690))
    lead.move(20500, Foot(445, -659))
    lead.move(21700, lead.start)
    lead.watch(LOOP_MS, "l")
    return lead


def friend_path(detailed: bool = False) -> SquirrelPath:
    friend = SquirrelPath(Foot(865, -14), "l", detailed)
    friend.watch(820, "l")
    friend.move(2230, Foot(644, -8), ground=True)
    friend.move(3600, Foot(489, -8), ground=True)
    friend.watch(5850, "l", curious=True)
    friend.move(8030, Foot(888, -11), ground=True)
    friend.watch(9040, "l")
    friend.move(11250, Foot(218, -10), ground=True)
    friend.move(12250, Foot(178, -165))
    friend.move(13200, Foot(182, -250), branch="left-low")
    friend.move(14350, Foot(225, -516), branch="left-middle")
    friend.move(15600, Foot(470, -864))
    friend.watch(16900, "r", curious=True)
    friend.move(18100, Foot(381, -710))
    friend.move(19400, Foot(198, -510), branch="left-middle")
    friend.move(20500, Foot(215, -225))
    friend.move(21500, Foot(196, -9))
    friend.move(23600, friend.start, ground=True)
    friend.watch(LOOP_MS, "l")
    return friend


def animate_squirrels(scene: ET.Element, detailed: bool = False) -> None:
    old, = [
        group for group in scene.findall("Group")
        if any(node.get("src", "").startswith("squirrel-") for node in group.findall("Image"))
    ]
    sprite = old.find("Image")
    if sprite is None:
        raise ValueError("Missing confirmed squirrel artwork")
    ratio = int(sprite.attrib["h"]) / int(sprite.attrib["w"])
    index = list(scene).index(old)
    scene.remove(old)
    for name, path, width in (("squirrel_lead", lead_path(detailed), 116),
                              ("squirrel_friend", friend_path(detailed), 98)):
        scene.insert(index, path.xml(name, width, round(width * ratio)))
        index += 1
