#!/usr/bin/env python3
"""Export packaged .qrr rumble profiles into community-editable CSV files."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


INPUT_ROOT = Path("app/src/main/assets/rumble")
OUTPUT_ROOT = Path("rumble/community_csv")


def parse_catalog(path: Path) -> list[dict]:
    entries: list[dict] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    for line in lines[1:]:
        if not line.strip():
            continue
        system, game_key, asset_path, aliases, trigger_count = line.split("\t")
        entries.append(
            {
                "system": system,
                "game_key": game_key,
                "asset_path": asset_path,
                "aliases": [alias for alias in aliases.split("|") if alias],
                "trigger_count": int(trigger_count),
            }
        )
    return entries


def qrr_condition_to_csv(value: str) -> str:
    mapping = {
        "prev > curr": "prev_gt_curr",
        "prev < curr": "prev_lt_curr",
        "prev != curr": "changed",
        "prev > 0 && curr == 0": "became_zero",
        "prev == 0 && curr != 0": "became_nonzero",
        "prev == 0 && curr > 0": "became_nonzero",
        "equals": "equals",
        "not_equals": "not_equals",
    }
    return mapping.get(value, value)


def qrr_size_default_cooldown(event_id: str) -> int:
    event = event_id.lower()
    if "pickup" in event:
        return 35
    if "score" in event:
        return 50
    if "damage" in event:
        return 100
    if "death" in event or "game_over" in event:
        return 400
    if "life" in event:
        return 160
    return 75


def qrr_priority(event_id: str) -> int:
    event = event_id.lower()
    if "game_over" in event:
        return 100
    if "death" in event:
        return 90
    if "life_lost" in event:
        return 80
    if "damage" in event:
        return 70
    if "level_complete" in event:
        return 60
    if "life_gained" in event:
        return 50
    if "powerup" in event:
        return 40
    if "enemy" in event:
        return 30
    if "pickup" in event:
        return 20
    if "score" in event:
        return 10
    return 25


def infer_controller(haptic_field: str) -> tuple[str, float, int]:
    hands = []
    amplitudes: list[float] = []
    durations: list[int] = []
    for part in haptic_field.split(";"):
        if not part:
            continue
        hand, amp, dur = part.split(":")
        hands.append(hand)
        amplitudes.append(float(amp))
        durations.append(int(dur))
    if not hands:
        return ("right", 0.5, 50)
    if set(hands) == {"left", "right"}:
        controller = "both"
    elif hands[0] == "left":
        controller = "left"
    else:
        controller = "right"
    return (controller, max(amplitudes), max(durations))


def export_entry(entry: dict, input_root: Path, output_root: Path) -> None:
    qrr_path = input_root / entry["asset_path"]
    if not qrr_path.exists():
        return
    game_name = entry["game_key"]
    rows: list[dict] = []
    for line in qrr_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        if line.startswith("game_name="):
            game_name = line.split("=", 1)[1]
            continue
        if not line.startswith("trigger\t"):
            continue
        cols = line.split("\t")
        if len(cols) < 11:
            continue
        controller, amplitude, duration_ms = infer_controller(cols[10])
        rows.append(
            {
                "event_id": cols[1],
                "address": cols[3],
                "size": cols[4],
                "value_type": cols[5],
                "condition": qrr_condition_to_csv(cols[7]),
                "compare_value": "",
                "controller": controller,
                "amplitude": f"{amplitude:.3f}",
                "effect": "normal",
                "start_ms": "0",
                "duration_ms": str(duration_ms),
                "cooldown_ms": str(qrr_size_default_cooldown(cols[1])),
                "priority": str(qrr_priority(cols[1])),
                "notes": "",
            }
        )

    out_dir = output_root / entry["system"]
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / (Path(entry["asset_path"]).stem + ".csv")
    with out_path.open("w", encoding="utf-8", newline="") as handle:
        handle.write("# schema=qrd_rumble_csv_v1\n")
        handle.write(f"# game_name={game_name}\n")
        handle.write(f"# match_names={'|'.join(entry['aliases'])}\n")
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "event_id",
                "address",
                "size",
                "value_type",
                "condition",
                "compare_value",
                "controller",
                "amplitude",
                "effect",
                "start_ms",
                "duration_ms",
                "cooldown_ms",
                "priority",
                "notes",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default=str(INPUT_ROOT))
    parser.add_argument("--output", default=str(OUTPUT_ROOT))
    args = parser.parse_args()

    input_root = Path(args.input)
    output_root = Path(args.output)
    output_root.mkdir(parents=True, exist_ok=True)

    catalog = parse_catalog(input_root / "catalog.tsv")
    for entry in catalog:
        export_entry(entry, input_root, output_root)

    print(f"exported {len(catalog)} qrr profiles to {output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
