"""Export animation cells from the pinned ImageGen atlas to transparent HONOR sprites."""

import hashlib
from io import BytesIO
from pathlib import Path
import subprocess

from PIL import Image


SHEET = Path(__file__).resolve().parent / "art" / "squirrel-motion-sheet-v2.png"
SHEET_SHA256 = "5cb4eb943a75112c0a079f2c6e53b0d86ec55f794287d7ea0c8c3c8aa872c47a"
CANVAS_W = 244
CANVAS_H = 202
ACTOR_SCALE = .62
ALPHA_THRESHOLD = tuple(255 if value >= 24 else 0 for value in range(256))


def alpha_bounds(image: Image.Image) -> tuple[int, int, int, int]:
    if image.mode != "RGBA":
        raise ValueError("Squirrel sprites need transparent RGBA")
    bounds = image.getchannel("A").point(ALPHA_THRESHOLD).getbbox()
    if bounds is None:
        raise ValueError("Squirrel cell contains no visible pixels")
    return bounds


def encode(source: list[str], filters: str, data: bytes | None = None) -> bytes:
    process = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error",
         *source, "-vf", filters, "-frames:v", "1", "-threads", "1",
         "-c:v", "png", "-f", "image2pipe", "pipe:1"],
        input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15,
    )
    if process.returncode or not process.stdout:
        raise RuntimeError(process.stderr.decode(errors="replace").strip() or "Cannot encode squirrel")
    return process.stdout


def cells(sheet: Image.Image) -> list[tuple[int, int, int, int]]:
    bounds = []
    for row in range(4):
        for column in range(4):
            x = column * sheet.width // 4
            y = row * sheet.height // 4
            end_x = (column + 1) * sheet.width // 4
            end_y = (row + 1) * sheet.height // 4
            crop = sheet.crop((x, y, end_x, end_y))  # Read-only alpha measurement.
            left, top, right, bottom = alpha_bounds(crop)
            if left == 0 or top == 0 or right == crop.width or bottom == crop.height:
                raise ValueError("A sprite touches a neighbouring cell")
            bounds.append((x + left - 1, y + top - 1, right - left + 2, bottom - top + 2))
    return bounds


def padded(
    input_args: list[str], crop: tuple[int, int, int, int], vertical: float = 1,
    data: bytes | None = None,
) -> bytes:
    x, y, width, height = crop
    drawn_w = round(width * ACTOR_SCALE)
    drawn_h = round(height * ACTOR_SCALE * vertical)
    # Keep the chest and feet aligned when the tail spreads across more of the cell.
    left = round(CANVAS_W * .5 - drawn_w * .59)
    top = CANVAS_H - drawn_h - 2
    if left < 0 or top < 0 or left + drawn_w > CANVAS_W:
        raise ValueError("A normalized squirrel would clip")
    return encode(
        input_args,
        f"format=rgba,crop={width}:{height}:{x}:{y}:exact=1,"
        f"scale={drawn_w}:{drawn_h}:flags=lanczos,"
        f"pad={CANVAS_W}:{CANVAS_H}:{left}:{top}:black@0,format=rgba",
        data,
    )


def descending(source: list[str], crop: tuple[int, int, int, int]) -> bytes:
    x, y, width, height = crop
    pitch = .21
    tilted = encode(source, (
        f"format=rgba,crop={width}:{height}:{x}:{y}:exact=1,"
        f"rotate={pitch}:ow=rotw({pitch}):oh=roth({pitch}):c=black@0,format=rgba"
    ))
    with Image.open(BytesIO(tilted)) as image:
        left, top, right, bottom = alpha_bounds(image)
    return padded(["-f", "image2pipe", "-i", "pipe:0"], (
        left, top, right - left, bottom - top), data=tilted)


def sprite_assets() -> dict[str, bytes]:
    if hashlib.sha256(SHEET.read_bytes()).hexdigest() != SHEET_SHA256:
        raise ValueError("The selected squirrel sheet has changed")
    with Image.open(SHEET) as sheet:
        grid = cells(sheet)
    source = ["-i", str(SHEET)]
    result = {}

    def add(name: str, facing_right: bytes) -> None:
        facing_left = encode(["-f", "image2pipe", "-i", "pipe:0"], "hflip", facing_right)
        for direction, data in (("r", facing_right), ("l", facing_left)):
            with Image.open(BytesIO(data)) as image:
                visible = alpha_bounds(image)
                if image.size != (CANVAS_W, CANVAS_H) or not (
                    0 < visible[0] < visible[2] < CANVAS_W
                    and 0 < visible[1] < visible[3] < CANVAS_H
                ):
                    raise ValueError(f"Invalid sprite geometry: {name}")
            result[f"squirrel-{name}-{direction}.png"] = data

    for frame in range(8):
        add(f"run-{frame}", padded(source, grid[frame]))
        add(f"climb-{frame}", padded(source, grid[frame + 8]))
        add(f"down-{frame}", descending(source, grid[frame]))
    for frame, stretch in enumerate((1.0, 1.012, 1.023, 1.036)):
        add(f"idle-{frame}", padded(source, grid[0], stretch))
    return result
