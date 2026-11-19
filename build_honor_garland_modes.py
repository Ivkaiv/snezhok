#!/usr/bin/env python3
"""Build ten verified HONOR frame-animation themes and one shared APK prefix.

Reads only pinned first-party theme archives and fixed trusted sprite members.
The output directory must be new; previous releases are never overwritten.
"""

from __future__ import annotations

from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo
import zlib

from honor_squirrel_scene import animate_squirrels
from honor_branch_rig import articulate_tree
from honor_tree_recoil import add_countdown
from squirrel_motion_assets import CANVAS_H, CANVAS_W, SHEET_SHA256, sprite_assets


ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "exports" / "garland-modes-v4"
SCENE = "unlock/lockscreen/"
MANIFEST = SCENE + "manifest.xml"
DESCRIPTION = "description.xml"
CHANGED = {DESCRIPTION, MANIFEST}
VERSION = "6.0.7"
BASE = ROOT / "exports" / "Snezhok-HONOR-lock-03.hnt"
BASE_SHA = "bba12ebaa4926c4baa4e1d065376edbc3750035edd469c4e1aa578c4b7edb8e8"
SOURCES = {
    "warm": (
        ROOT / "exports/garland-v2/Snezhok-HONOR-garland-warm.hnt",
        "fcf225a5a8045012509ec841a790c9794efb6843ee36ce2316d34684ef3acb00",
        ("gold",),
    ),
    "color": (
        ROOT / "exports/garland-v2/Snezhok-HONOR-garland-color.hnt",
        "a0f7631bb1292a19ea577597394732ff74ae77b10c0ec8968730d00e9f81137a",
        ("red", "amber", "green", "blue", "violet"),
    ),
}
EFFECTS = ("twinkle", "wave", "sparks", "breath", "steady")
TWINKLE = (1, 1, 2, 3, 5, 7, 6, 4, 2, 1, 1, 2, 4, 6, 4, 2,
           1, 1, 1, 2, 3, 5, 3, 2, 1, 1, 2, 4, 3, 2, 1, 1)
BREATH = (1, 1, 2, 2, 3, 4, 5, 6, 7, 7, 6, 5, 4, 3, 2, 2)
SQUIRREL_GROUP_NAMES = {"squirrel_lead", "squirrel_friend"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pinned(path: Path, digest: str) -> bytes:
    require(not path.is_symlink(), f"Unexpected input symlink: {path.name}")
    data = path.read_bytes()
    require(sha(data) == digest, f"Pinned input changed: {path.name}")
    return data


def safe_member(name: str) -> None:
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and ".." not in path.parts
            and "\\" not in name and ":" not in name,
            f"Unexpected archive member: {name}")


def archive_names(archive: ZipFile) -> list[str]:
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate archive members")
    for info in archive.infolist():
        safe_member(info.filename)
        require(not (info.flag_bits & 1), "Encrypted archive entry")
        require(info.file_size < 0xFFFFFFFF, "ZIP64 members are not supported")
    return names


def xml_bytes(node: ET.Element) -> bytes:
    ET.indent(node)
    return ET.tostring(node, encoding="utf-8", xml_declaration=True)


def model(node: ET.Element) -> tuple:
    """Ignore serializer whitespace while preserving all authored XML semantics."""
    return (node.tag, tuple(sorted(node.attrib.items())), (node.text or "").strip(),
            tuple(model(child) for child in node))


def scene_without(scene: ET.Element, skip) -> tuple:
    return (scene.tag, tuple(sorted(scene.attrib.items())), (scene.text or "").strip(),
            tuple(model(child) for child in scene if not skip(child)))


def is_original_squirrel(node: ET.Element) -> bool:
    return node.tag == "Group" and any(
        child.get("src", "").startswith("squirrel-") for child in node.iter())


def update_squirrels(scene: ET.Element) -> dict:
    old = [node for node in scene if is_original_squirrel(node)]
    require(len(old) == 1, "Expected exactly one original squirrel group")
    preserved = scene_without(scene, is_original_squirrel)
    animate_squirrels(scene, detailed=True)
    added = [node for node in scene if node.tag == "Group"
             and node.get("name") in SQUIRREL_GROUP_NAMES]
    require(len(added) == 2 and {node.get("name") for node in added} == SQUIRREL_GROUP_NAMES,
            "Expected exactly the two named first-party squirrel groups")
    require(len([node for node in scene if is_original_squirrel(node)]) == 2,
            "Squirrel replacement left unexpected groups")
    require(scene_without(scene, lambda node: node in added) == preserved,
            "Squirrel helper changed unrelated scene nodes")
    return {"original_groups": 1, "replacement_groups": sorted(SQUIRREL_GROUP_NAMES),
            "all_other_nodes_unchanged": True}


def bulb_nodes(scene: ET.Element) -> list[ET.Element]:
    return [node for node in scene.findall("Image")
            if node.get("src") == "warm-glow.png" or node.get("src", "").startswith("lights/")]


def bulb_height(node: ET.Element) -> float:
    match = re.fullmatch(r"#screen_height\*([0-9.]+)(?:-(\d+)\+(\d+))?", node.get("y", ""))
    require(match is not None, "Unexpected bulb height expression")
    return 2400 * float(match[1]) - int(match[2] or 0) + int(match[3] or 0)


def ordered_bulbs(scene: ET.Element) -> list[ET.Element]:
    bulbs = bulb_nodes(scene)
    require(len(bulbs) == 27, "Expected exactly 27 bulbs")
    return sorted(bulbs, key=lambda node: (bulb_height(node), int(node.get("x", "0"))))


def effect_frames(effect: str, rank: int) -> tuple[tuple[int, ...], int]:
    if effect == "twinkle":
        phase = rank * 7 % len(TWINKLE)
        return tuple(TWINKLE[(tick + phase) % len(TWINKLE)] for tick in range(len(TWINKLE))), 100
    if effect == "wave":
        pulse = (7, 6, 4, 2, 1)
        return tuple(pulse[phase] if phase < len(pulse) else 0
                     for tick in range(54) for phase in [(tick - rank * 2) % 54]), 100
    if effect == "sparks":
        pulse = (7, 2)
        return tuple(pulse[phase] if phase < len(pulse) else 0
                     for tick in range(108) for phase in [(tick - (rank * 37 + 11)) % 108]), 80
    if effect == "breath":
        return BREATH, 400
    require(effect == "steady", "Unknown garland effect")
    return (6,), 0


def apply_effect(scene: ET.Element, palette: str, effect: str) -> None:
    colors = SOURCES[palette][2]
    for rank, node in enumerate(ordered_bulbs(scene)):
        require(len(node.findall("AlphaAnimation")) == 1 and node[0].tag == "AlphaAnimation",
                "Expected the untouched original bulb animation")
        node.remove(node[0])
        diameter = max(52, int(node.get("w", "0")) + 27)
        node.set("w", str(diameter))
        node.set("h", str(diameter))
        color = colors[(rank * 3) % len(colors)]
        levels, frame_ms = effect_frames(effect, rank)
        node.set("src", f"lights/{color}-{levels[0]}.png")
        if effect == "steady":
            continue
        animation = ET.SubElement(node, "SourcesAnimation", repeat="0")
        for tick, level in enumerate((*levels, levels[0])):
            ET.SubElement(animation, "Source", src=f"lights/{color}-{level}.png",
                          time=str(tick * frame_ms))


def write_entry(archive: ZipFile, name: str, data: bytes) -> None:
    safe_member(name)
    entry = ZipInfo(name)
    entry.create_system = 0
    entry.create_version = 31
    entry.extract_version = 20
    entry.external_attr = 0x10 if name.endswith("/") else 0x80
    entry.compress_type = ZIP_STORED if name.endswith("/") else ZIP_DEFLATED
    archive.writestr(entry, data)


def check_png(data: bytes, width: int = 72, height: int = 72) -> None:
    require(data[:8] == b"\x89PNG\r\n\x1a\n", "Trusted sprite is not PNG")
    offset = 8
    seen = []
    while offset < len(data):
        require(offset + 12 <= len(data), "Truncated PNG chunk")
        length = struct.unpack_from(">I", data, offset)[0]
        end = offset + 12 + length
        require(end <= len(data), "PNG chunk exceeds sprite")
        kind = data[offset + 4:offset + 8]
        content = data[offset + 8:offset + 8 + length]
        crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        require(zlib.crc32(kind + content) & 0xFFFFFFFF == crc, "PNG CRC mismatch")
        if kind == b"IHDR":
            require(len(content) == 13
                    and struct.unpack(">IIBB", content[:10]) == (width, height, 8, 6),
                    "Trusted sprite dimensions or RGBA format changed")
        seen.append(kind)
        offset = end
        if kind == b"IEND":
            break
    require(offset == len(data) and seen[0] == b"IHDR" and seen[-1] == b"IEND",
            "Incomplete PNG structure")


def central_offset(payload: bytes) -> int:
    """Reject ZIP64/multipart/trailing data and cross-check the stdlib offset."""
    signature = b"PK\x05\x06"
    offset = payload.rfind(signature, max(0, len(payload) - 65557))
    require(offset >= 0 and offset + 22 <= len(payload), "Missing classic EOCD")
    _, disk, cd_disk, disk_entries, total, cd_size, cd_offset, comment_size = struct.unpack_from(
        "<4s4H2IH", payload, offset)
    require(disk == cd_disk == 0 and disk_entries == total and total < 65535,
            "Multipart/ZIP64 archive is not supported")
    require(cd_offset < 0xFFFFFFFF and cd_size < 0xFFFFFFFF
            and offset + 22 + comment_size == len(payload)
            and cd_offset + cd_size == offset, "Invalid central-directory bounds")
    require(payload[cd_offset:cd_offset + 4] == b"PK\x01\x02", "Missing central-directory signature")
    with ZipFile(io.BytesIO(payload)) as archive:
        require(archive.start_dir == cd_offset and len(archive.infolist()) == total,
                "EOCD and zipfile directory disagree")
    return cd_offset


def verify_references(scene: ET.Element, names: set[str]) -> int:
    count = 0
    for node in scene.iter():
        value = node.get("src")
        if value is None:
            continue
        safe_member(value)
        if node.tag == "Time" and value == "time.png":
            require(all(SCENE + f"time_{glyph}.png" in names for glyph in (*range(10), "dot")),
                    "Missing trusted time-glyph family")
        else:
            require(SCENE + value in names, f"Missing referenced scene asset: {value}")
        count += 1
    return count


def verify_behavior(scene: ET.Element, palette: str, effect: str) -> dict:
    sequences = []
    durations = []
    first_peaks = []
    for rank, node in enumerate(ordered_bulbs(scene)):
        expected_levels, frame_ms = effect_frames(effect, rank)
        color = SOURCES[palette][2][(rank * 3) % len(SOURCES[palette][2])]
        animation = node.find("SourcesAnimation")
        other_animations = [child for child in node if child.tag != "PositionAnimation"]
        if effect == "steady":
            require(not other_animations and node.get("src") == f"lights/{color}-6.png",
                    "Steady bulb must be static with no animation")
            continue
        require(animation is not None and animation.get("repeat") == "0" and len(other_animations) == 1,
                "Unexpected bulb animation shape")
        frames = list(animation)
        times = [int(frame.get("time", "-1")) for frame in frames]
        expected = [f"lights/{color}-{level}.png" for level in (*expected_levels, expected_levels[0])]
        require(all(frame.tag == "Source" for frame in frames)
                and [frame.get("src") for frame in frames] == expected
                and times == [tick * frame_ms for tick in range(len(expected))]
                and node.get("src") == expected[0], "SourcesAnimation frames do not match the authored mode")
        sequences.append(expected_levels)
        durations.append(times[-1])
        first_peaks.append(expected_levels.index(7) * frame_ms)
    result = {"bulbs": 27, "documented_sources_frames_only": True,
              "cycle_ms": durations[0] if durations else None,
              "frames_per_bulb": len(sequences[0]) + 1 if sequences else 0}
    if effect == "twinkle":
        require(len(set(sequences)) == 27, "Twinkle phases are not individually staggered")
        result["distinct_phases"] = 27
    elif effect == "wave":
        require(first_peaks == [rank * 200 for rank in range(27)], "Wave does not travel down the garland")
        result["peak_times_ascending_down_scene"] = True
    elif effect == "sparks":
        require(all(sequence.count(7) == 1 and sum(level > 0 for level in sequence) == 2
                    for sequence in sequences), "Sparks are not short and sparse")
        concurrent = max(sum(sequence[tick] > 0 for sequence in sequences) for tick in range(108))
        require(concurrent <= 2, "Too many simultaneous sparks")
        result.update({"peak_ms": 80, "pulse_including_afterglow_ms": 160,
                       "max_simultaneously_active_bulbs": concurrent})
    elif effect == "breath":
        require(len(set(sequences)) == 1 and durations[0] >= 6000, "Breathing is not synchronized and slow")
        result["synchronized"] = True
    else:
        result["bulb_sources_animation_count"] = 0
    return result


def new_bytes(path: Path, data: bytes) -> None:
    with path.open("xb") as target:
        target.write(data)


def main() -> None:
    require(not OUTPUT.exists(), "Output directory already exists; refusing to overwrite")
    original = pinned(BASE, BASE_SHA)
    source_payloads = {palette: pinned(path, digest)
                       for palette, (path, digest, _) in SOURCES.items()}
    with ZipFile(io.BytesIO(original)) as source:
        names = archive_names(source)
        require(source.testzip() is None, "Base ZIP CRC validation failed")
        common_entries = {name: source.read(name) for name in names if name not in CHANGED}
        baseline = ET.fromstring(source.read(MANIFEST))
        descriptor = ET.fromstring(source.read(DESCRIPTION))
    sprites = {}
    for palette, payload in source_payloads.items():
        with ZipFile(io.BytesIO(payload)) as trusted:
            archive_names(trusted)
            for color in SOURCES[palette][2]:
                for level in range(8):
                    name = SCENE + f"lights/{color}-{level}.png"
                    data = trusted.read(name)  # Fixed exact allowlist; no wildcard extraction.
                    check_png(data)
                    require(name not in common_entries and name not in sprites, "Duplicate trusted sprite")
                    sprites[name] = data
    require(len(sprites) == 48, "Expected exactly 48 trusted sprite files")
    squirrel_frames = sprite_assets()
    require(len(squirrel_frames) == 56, "Expected the complete squirrel motion set")
    for name, data in squirrel_frames.items():
        check_png(data, CANVAS_W, CANVAS_H)
        require(SCENE + name not in common_entries, "Squirrel frame name already exists")
        common_entries[SCENE + name] = data
    squirrel_proof = update_squirrels(baseline)
    branch_assets, branch_contacts = articulate_tree(
        baseline, common_entries[SCENE + "tree.png"], bulb_nodes(baseline))
    for name, payload in branch_assets.items():
        require(SCENE + name not in common_entries, "Branch PNG already exists")
        common_entries[SCENE + name] = payload
    add_countdown(baseline)
    preserved_scene = scene_without(baseline, lambda node: node in bulb_nodes(baseline))
    preserved_descriptor = scene_without(descriptor, lambda node: node.tag in {"title", "version"})
    common_entries[SCENE + "lights/"] = b""
    common_entries.update(sprites)
    buffer = io.BytesIO()
    with ZipFile(buffer, "w", allowZip64=False) as common:
        for name, data in common_entries.items():
            write_entry(common, name, data)
    common_zip = buffer.getvalue()
    split = central_offset(common_zip)
    prefix = common_zip[:split]
    common_hashes = {name: sha(data) for name, data in common_entries.items()}
    with ZipFile(io.BytesIO(common_zip)) as common:
        require(common.testzip() is None and not (set(common.namelist()) & CHANGED),
                "Common archive CRC or excluded-XML check failed")
    OUTPUT.mkdir(mode=0o700)
    (OUTPUT / "assets").mkdir(mode=0o700)
    (OUTPUT / "themes").mkdir(mode=0o700)
    new_bytes(OUTPUT / "assets/common.zip-part", prefix)
    index = {"assets": {"common.zip-part": sha(prefix)}, "themes": {}}
    proof = {}
    for palette in SOURCES:
        for effect in EFFECTS:
            slug = f"{palette}-{effect}"
            scene = deepcopy(baseline)
            apply_effect(scene, palette, effect)
            require(scene_without(scene, lambda node: node in bulb_nodes(scene)) == preserved_scene,
                    "Garland mode changed a non-bulb scene element")
            desc = deepcopy(descriptor)
            require(desc.find("title") is not None and desc.find("version") is not None,
                    "Base descriptor lacks title/version")
            desc.find("title").text = f"Snowflake {palette} {effect} 4"
            desc.find("version").text = VERSION
            require(scene_without(desc, lambda node: node.tag in {"title", "version"}) == preserved_descriptor,
                    "Garland mode changed another descriptor field")
            path = OUTPUT / "themes" / f"Snezhok-HONOR-{slug}-4.hnt"
            new_bytes(path, common_zip)
            with ZipFile(path, "a", allowZip64=False) as completed:
                write_entry(completed, DESCRIPTION, xml_bytes(desc))
                write_entry(completed, MANIFEST, xml_bytes(scene))
            payload = path.read_bytes()
            central_offset(payload)
            require(payload[:split] == prefix, "Appending XML changed the shared ZIP prefix")
            tail_name = slug + ".zip-tail"
            tail = payload[split:]
            new_bytes(OUTPUT / "assets" / tail_name, tail)
            recombined = (OUTPUT / "assets/common.zip-part").read_bytes() + (OUTPUT / "assets" / tail_name).read_bytes()
            require(recombined == payload, "Shared prefix + tail differs from completed HNT")
            with ZipFile(io.BytesIO(recombined)) as verified:
                completed_names = archive_names(verified)
                require(set(completed_names) == set(common_entries) | CHANGED,
                        "Unexpected completed ZIP contents")
                require(verified.testzip() is None, "Completed theme ZIP CRC mismatch")
                for name, digest in common_hashes.items():
                    require(sha(verified.read(name)) == digest, "A common asset was changed")
                xml_count = 0
                for name in completed_names:
                    if name.endswith(".xml"):
                        ET.fromstring(verified.read(name))
                        xml_count += 1
                manifest = ET.fromstring(verified.read(MANIFEST))
                references = verify_references(manifest, set(completed_names))
                actual_desc = ET.fromstring(verified.read(DESCRIPTION))
                require(actual_desc.findtext("title") == f"Snowflake {palette} {effect} 4"
                        and actual_desc.findtext("version") == VERSION, "Wrong HNT title/version")
                behavior = verify_behavior(manifest, palette, effect)
            index["assets"][tail_name] = sha(tail)
            index["themes"][slug] = sha(payload)
            proof[slug] = {"bytes": len(payload), "tail_bytes": len(tail),
                           "crc_pass": True, "xml_documents": xml_count,
                           "scene_references": references, "all_common_assets_unchanged": True,
                           "non_bulb_scene_unchanged": True, "exact_prefix_recombination": True,
                           "behavior": behavior}
    # Check pins again so even a concurrent source edit cannot silently mix a release.
    pinned(BASE, BASE_SHA)
    for path, digest, _ in SOURCES.values():
        pinned(path, digest)
    index_bytes = (json.dumps(index, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
    new_bytes(OUTPUT / "index.json", index_bytes)
    report = {
        "base_sha256": BASE_SHA,
        "sprite_archives_sha256": {palette: source[1] for palette, source in SOURCES.items()},
        "trusted_sprite_sha256": {name: sha(data) for name, data in sprites.items()},
        "squirrel_helper_sha256": sha((ROOT / "honor_squirrel_scene.py").read_bytes()),
        "squirrel_sprite_sheet_sha256": SHEET_SHA256,
        "squirrel_frames": len(squirrel_frames),
        "squirrels": squirrel_proof,
        "branch_contacts": branch_contacts,
        "branch_pngs": len(branch_assets),
        "common_zip_bytes": len(common_zip), "shared_prefix_bytes": split,
        "common_entries": len(common_entries), "trusted_sprites": len(sprites),
        "theme_count": len(proof), "honor_resource_version": VERSION,
        "apk_parts_bytes": len(prefix) + sum(entry["tail_bytes"] for entry in proof.values()),
        "index_sha256": sha(index_bytes), "themes": proof,
        "device_installation_checked": False,
    }
    new_bytes(OUTPUT / "verification.json", (json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode())
    print(json.dumps({"output": str(OUTPUT), "index_sha256": sha(index_bytes),
                      "theme_count": len(proof), "shared_prefix_bytes": split,
                      "total_tail_bytes": sum(entry["tail_bytes"] for entry in proof.values()),
                      "crc_xml_references_behavior_and_recombination": "PASS",
                      "honor_device_checked": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
