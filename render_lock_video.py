#!/usr/bin/env python3
"""Render an offline MP4 alternative for Honor's Gallery wallpaper picker.

Only the project's generated assets are used. This is a recorded loop, not an
Android WallpaperService, and it intentionally contains no clock or private data.
"""
from pathlib import Path
import json
import random
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parent
    output = root / "exports" / "Snezhok-lock-video-01.mp4"
    output.parent.mkdir(mode=0o700, exist_ok=True)
    if output.exists():
        raise SystemExit("Output exists; refusing to overwrite")
    font = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    if not font.is_file():
        raise SystemExit("Required local font unavailable")
    width, height, duration, fps = 1080, 2400, 10, 24
    filters = [
        f"[0:v]scale={width}:{height}:force_original_aspect_ratio=increase,"
        f"crop={width}:{height},setsar=1[background]",
        "[1:v]scale=1188:1782,format=rgba[tree]",
        "[background][tree]overlay=x=-54:y=546:shortest=1[scene]",
    ]
    particles = []
    rng = random.Random(171126)
    for index in range(105):
        x = rng.randrange(width)
        y = rng.randrange(height)
        size = rng.randint(4, 12)
        opacity = rng.uniform(.22, .72)
        phase = rng.uniform(0, 6.283185)
        wind = rng.uniform(7, 23)
        speed = height / duration * (2 if index % 12 == 0 else 1)
        particles.append(
            f"drawtext=fontfile={font}:text='•':fontsize={size}:"
            f"fontcolor=white@{opacity:.3f}:"
            f"x='mod({x}+{wind:.3f}*sin(2*PI*t/{duration}+{phase:.4f})+{width},{width})':"
            f"y='mod({y}+{speed:.2f}*t,{height})-16'"
        )
    # Small periodic highlights on the actual warm lights in the tree asset.
    lights = [(0.489, 0.068), (0.478, 0.184), (0.561, 0.242), (0.425, 0.288),
              (0.447, 0.370), (0.596, 0.381), (0.382, 0.531), (0.486, 0.481),
              (0.699, 0.524), (0.225, 0.652), (0.484, 0.634), (0.805, 0.682)]
    for index, (x, y) in enumerate(lights):
        particles.append(
            f"drawtext=fontfile={font}:text='•':fontsize=8:fontcolor=0xFFE6AE:"
            f"x={-54 + x * 1188 - 2:.2f}:y={546 + y * 1782 - 4:.2f}:"
            f"alpha='0.08+0.22*(0.5+0.5*sin(2*PI*t/{duration}+{index:.2f}))'"
        )
    filters.append("[scene]" + ",".join(particles) + ",format=yuv420p[video]")
    graph = output.with_suffix(".ffmpeg-filter.txt")
    if graph.exists():
        raise SystemExit("Filter file exists; refusing to overwrite")
    graph.write_text(";\n".join(filters), encoding="utf-8")
    command = [
        "ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "warning", "-n",
        "-loop", "1", "-framerate", str(fps), "-i", str(root / "art/winter-backdrop-premium-v1.png"),
        "-loop", "1", "-framerate", str(fps), "-i", str(root / "art/christmas-tree-premium-v1.png"),
        "-filter_complex_threads", "1", "-filter_complex_script", str(graph),
        "-map", "[video]", "-t", str(duration), "-an", "-c:v", "libx264",
        "-threads", "2", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(output),
    ]
    result = subprocess.run(command, capture_output=True, text=True, timeout=240)
    print(json.dumps({"exit": result.returncode, "output": str(output),
                      "stderr": result.stderr[-1800:]}, ensure_ascii=False))
    if result.returncode != 0:
        return result.returncode
    output.chmod(0o600)
    print(json.dumps({"bytes": output.stat().st_size, "duration": duration,
                      "fps": fps, "width": width, "height": height,
                      "embedded_clock": False, "recorded_loop": True}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
