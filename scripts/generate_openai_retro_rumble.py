#!/usr/bin/env python3
"""Generate QuestRetroDepth rumble mapping drafts from openai/retro data.json files."""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from typing import Any


DEFAULT_INPUT = "third_party/openai-retro/retro/data"
DEFAULT_OUTPUT = "rumble/openai_retro"

EVENT_HAPTICS: dict[str, list[dict[str, Any]]] = {
    "damage_taken": [{"hand": "left", "amplitude": 0.85, "duration_ms": 90}],
    "death": [
        {"hand": "left", "amplitude": 1.0, "duration_ms": 600},
        {"hand": "right", "amplitude": 1.0, "duration_ms": 600},
    ],
    "life_lost": [
        {"hand": "left", "amplitude": 0.9, "duration_ms": 300},
        {"hand": "right", "amplitude": 0.9, "duration_ms": 300},
    ],
    "life_gained": [
        {"hand": "left", "amplitude": 0.75, "duration_ms": 180},
        {"hand": "right", "amplitude": 0.75, "duration_ms": 180},
    ],
    "pickup": [{"hand": "right", "amplitude": 0.25, "duration_ms": 18}],
    "score": [{"hand": "right", "amplitude": 0.35, "duration_ms": 60}],
    "powerup_gained": [{"hand": "left", "amplitude": 0.6, "duration_ms": 120}],
    "enemy_defeated": [{"hand": "right", "amplitude": 0.45, "duration_ms": 35}],
    "level_complete": [
        {"hand": "left", "amplitude": 0.8, "duration_ms": 400},
        {"hand": "right", "amplitude": 0.8, "duration_ms": 400},
    ],
    "game_over": [
        {"hand": "left", "amplitude": 1.0, "duration_ms": 1000},
        {"hand": "right", "amplitude": 1.0, "duration_ms": 1000},
    ],
}

EVENT_CONDITIONS: dict[str, str] = {
    "damage_taken": "prev > curr",
    "death": "prev > 0 && curr == 0",
    "life_lost": "prev > curr",
    "life_gained": "prev < curr",
    "pickup": "prev != curr",
    "score": "prev < curr",
    "powerup_gained": "prev == 0 && curr > 0",
    "enemy_defeated": "prev > curr",
    "level_complete": "prev != curr",
    "game_over": "prev == 0 && curr != 0",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate SNES/Genesis rumble JSON drafts from openai/retro data.json files."
    )
    parser.add_argument("--input", default=DEFAULT_INPUT, help="Root folder for retro/data.")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help="Root output folder.")
    parser.add_argument("--limit", type=int, default=0, help="Process at most N games per system.")
    parser.add_argument("--force", action="store_true", help="Overwrite existing outputs.")
    parser.add_argument("--verbose", action="store_true", help="Print per-game progress.")
    parser.add_argument("--self-test", action="store_true", help="Run self-tests and exit.")
    return parser.parse_args()


def slugify(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", value.lower()).strip("_")
    return slug or "game"


SYSTEM_SUFFIXES = {
    "-Snes": "snes",
    "-Genesis": "genesis",
    "-Nes": "nes",
    "-GbAdvance": "gba",
    "-Gb": "gb",
    "-Gbc": "gbc",
    "-GameGear": "gg",
    "-Sms": "sms",
}


def infer_system(game_dir_name: str) -> str | None:
    # Longest suffix wins (e.g. -GbAdvance before -Gb)
    for suffix, system in sorted(SYSTEM_SUFFIXES.items(), key=lambda x: -len(x[0])):
        if game_dir_name.endswith(suffix):
            return system
    return None


def split_game_name(game_dir_name: str) -> tuple[str, str]:
    for suffix, system in sorted(SYSTEM_SUFFIXES.items(), key=lambda x: -len(x[0])):
        if game_dir_name.endswith(suffix):
            return game_dir_name[: -len(suffix)], system
    raise ValueError(f"unsupported game folder: {game_dir_name}")


def parse_type(raw_type: str) -> dict[str, Any]:
    if len(raw_type) < 3:
        raise ValueError(f"unexpected type: {raw_type}")
    endian = raw_type[0]
    kind = raw_type[1]
    size = int(raw_type[2:])
    kind_map = {
        "u": "uint",
        "i": "int",
        "d": "bcd",
        "n": "nibble",
    }
    return {
        "raw_type": raw_type,
        "byte_order": {"<": "little", ">": "big", "|": "native"}[endian],
        "kind": kind_map.get(kind, "unknown"),
        "size": size,
    }


def map_address(system: str, absolute: int) -> dict[str, Any]:
    if system == "snes":
        if not (0x7E0000 <= absolute <= 0x7FFFFF):
            raise ValueError(f"SNES address out of WRAM range: 0x{absolute:06X}")
        bank = (absolute >> 16) & 0xFF
        address = absolute & 0xFFFF
        return {
            "absolute_address": f"0x{absolute:06X}",
            "bank": f"{bank:02X}",
            "address": f"0x{address:04X}",
            "wram_offset": ((bank - 0x7E) << 16) | address,
        }
    if system == "genesis":
        if not (0xFF0000 <= absolute <= 0xFFFFFF):
            raise ValueError(f"Genesis address out of RAM range: 0x{absolute:06X}")
        return {
            "absolute_address": f"0x{absolute:06X}",
            "address": f"0x{absolute:06X}",
            "ram_offset": absolute - 0xFF0000,
        }
    if system == "nes":
        if not (0x0000 <= absolute <= 0x1FFF):
            raise ValueError(f"NES address out of CPU WRAM range: 0x{absolute:04X}")
        return {
            "absolute_address": f"0x{absolute:04X}",
            "address": f"0x{absolute:04X}",
            "ram_offset": absolute & 0x07FF,
        }
    if system == "gba":
        if not (0x02000000 <= absolute <= 0x0203FFFF):
            raise ValueError(f"GBA EWRAM address out of range: 0x{absolute:08X}")
        return {
            "absolute_address": f"0x{absolute:08X}",
            "address": f"0x{absolute:08X}",
            "ram_offset": absolute - 0x02000000,
        }
    if system in ("gb", "gbc"):
        if not (0xC000 <= absolute <= 0xDFFF):
            raise ValueError(f"GB/GBC WRAM address out of range: 0x{absolute:04X}")
        return {
            "absolute_address": f"0x{absolute:04X}",
            "address": f"0x{absolute:04X}",
            "ram_offset": absolute - 0xC000,
        }
    if system in ("sms", "gg"):
        if not (0xC000 <= absolute <= 0xDFFF):
            raise ValueError(f"SMS/GG RAM address out of range: 0x{absolute:04X}")
        return {
            "absolute_address": f"0x{absolute:04X}",
            "address": f"0x{absolute:04X}",
            "ram_offset": absolute - 0xC000,
        }
    if system == "pce":
        if not (0x1F0000 <= absolute <= 0x1F1FFF):
            raise ValueError(f"PCE RAM address out of range: 0x{absolute:06X}")
        return {
            "absolute_address": f"0x{absolute:06X}",
            "address": f"0x{absolute:06X}",
            "ram_offset": absolute - 0x1F0000,
        }
    raise ValueError(f"unsupported system: {system}")


def classify_field(name: str) -> list[tuple[str, str]]:
    key = name.lower()
    if key == "health" or key == "damage":
        return [("damage_taken", "High-confidence gameplay field."), ("death", "Derived from health reaching zero.")]
    if key == "enemy_health":
        return [("enemy_defeated", "Enemy health reaching zero is a strong kill proxy.")]
    if key == "lives":
        return [("life_lost", "Lives decreasing is a stable event."), ("life_gained", "Lives increasing captures extra-life events.")]
    if key.startswith("score"):
        return [("score", "Score increasing is a stable reward/progress event.")]
    if key in {"coins", "rings", "items_collected", "ammo"}:
        return [("pickup", "Pickup-style counter.")]
    if key in {"level", "zone", "act", "level_end_bonus"}:
        return [("level_complete", "Stage/zone progression field; review in gameplay.")]
    if key == "gameover":
        return [("game_over", "Explicit game-over flag.")]
    return []


def build_watch(system: str, address_info: dict[str, Any], type_info: dict[str, Any]) -> dict[str, Any]:
    watch = {
        "absolute_address": address_info["absolute_address"],
        "size": type_info["size"],
        "value_type": type_info["kind"],
        "byte_order": type_info["byte_order"],
        "raw_type": type_info["raw_type"],
    }
    if system == "snes":
        watch["bank"] = address_info["bank"]
        watch["address"] = address_info["address"]
        watch["wram_offset"] = address_info["wram_offset"]
    else:
        watch["address"] = address_info["address"]
        watch["ram_offset"] = address_info["ram_offset"]
    return watch


def build_trigger(system: str, field_name: str, meta: dict[str, Any]) -> list[dict[str, Any]]:
    type_info = parse_type(str(meta["type"]))
    address_info = map_address(system, int(meta["address"]))
    watch = build_watch(system, address_info, type_info)
    triggers: list[dict[str, Any]] = []
    for event, reason in classify_field(field_name):
        addr_suffix = watch["address"].lower().replace("0x", "")
        triggers.append(
            {
                "id": f"{event}_{slugify(field_name)}_{addr_suffix}",
                "event": event,
                "watch": [watch],
                "condition": EVENT_CONDITIONS[event],
                "haptic": EVENT_HAPTICS[event],
                "confidence": 0.8 if event in {"damage_taken", "death", "life_lost", "life_gained", "score", "pickup", "game_over"} else 0.65,
                "needs_review": True,
                "reason": reason,
                "source_fields": [{"name": field_name, "address": watch["absolute_address"], "raw_type": type_info["raw_type"]}],
            }
        )
    return triggers


def dedupe_triggers(triggers: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str]] = set()
    result: list[dict[str, Any]] = []
    for trigger in triggers:
        watch = trigger["watch"][0]
        key = (trigger["event"], watch["absolute_address"], trigger["condition"])
        if key in seen:
            continue
        seen.add(key)
        result.append(trigger)
    return result


def build_game_doc(path: Path) -> tuple[str, dict[str, Any]]:
    game_name, system = split_game_name(path.parent.name)
    payload = json.loads(path.read_text(encoding="utf-8"))
    info = payload.get("info", {})
    triggers: list[dict[str, Any]] = []
    unsupported_fields: list[dict[str, Any]] = []
    for field_name, meta in sorted(info.items()):
        try:
            mapped = build_trigger(system, field_name, meta)
        except (KeyError, TypeError, ValueError) as exc:
            unsupported_fields.append({"name": field_name, "meta": meta, "reason": str(exc)})
            continue
        if mapped:
            triggers.extend(mapped)
        else:
            unsupported_fields.append({"name": field_name, "meta": meta, "reason": "No rumble mapping rule."})

    schema = f"qrd.rumble.{system}.v1"
    tier = path.parent.parent.name
    doc = {
        "schema": schema,
        "game": {"name": game_name, "system": system},
        "source": {"data_json": str(path), "integration_tier": tier, "game_key": path.parent.name},
        "triggers": dedupe_triggers(triggers),
        "unsupported_evidence": unsupported_fields,
    }
    return system, doc


def write_summary(path: Path, rows: list[dict[str, Any]]) -> None:
    fieldnames = [
        "system",
        "game",
        "integration_tier",
        "json_file",
        "source_fields",
        "triggers",
        "unsupported_fields",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def run_self_tests() -> None:
    type_info = parse_type(">u2")
    assert type_info["kind"] == "uint" and type_info["size"] == 2 and type_info["byte_order"] == "big"
    snes_addr = map_address("snes", 0x7E1F2A)
    assert snes_addr["bank"] == "7E" and snes_addr["wram_offset"] == 0x1F2A
    genesis_addr = map_address("genesis", 0xFFF1D2)
    assert genesis_addr["ram_offset"] == 0xF1D2
    assert [event for event, _ in classify_field("lives")] == ["life_lost", "life_gained"]
    assert classify_field("time") == []
    print("self-tests passed")


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_tests()
        return 0

    input_root = Path(args.input)
    output_root = Path(args.output)
    data_files = sorted(input_root.rglob("data.json"))
    per_system_count: dict[str, int] = {}
    summary_rows: list[dict[str, Any]] = []

    for data_file in data_files:
        system = infer_system(data_file.parent.name)
        if system is None:
            continue
        per_system_count.setdefault(system, 0)
        if args.limit > 0 and per_system_count[system] >= args.limit:
            continue

        _, doc = build_game_doc(data_file)
        out_dir = output_root / system / doc["source"]["integration_tier"]
        out_dir.mkdir(parents=True, exist_ok=True)
        out_file = out_dir / f"{slugify(doc['source']['game_key'])}.json"
        if out_file.exists() and not args.force:
            existing = json.loads(out_file.read_text(encoding="utf-8"))
            doc = existing
        else:
            out_file.write_text(json.dumps(doc, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

        per_system_count[system] += 1
        summary_rows.append(
            {
                "system": system,
                "game": doc["game"]["name"],
                "integration_tier": doc["source"]["integration_tier"],
                "json_file": str(out_file),
                "source_fields": len(json.loads(data_file.read_text(encoding='utf-8')).get("info", {})),
                "triggers": len(doc["triggers"]),
                "unsupported_fields": len(doc["unsupported_evidence"]),
            }
        )
        if args.verbose:
            print(f"{doc['source']['game_key']}: {len(doc['triggers'])} triggers")

    output_root.mkdir(parents=True, exist_ok=True)
    summary_name = f"_summary_limit{args.limit}.csv" if args.limit > 0 else "_summary.csv"
    write_summary(output_root / summary_name, summary_rows)
    totals = ", ".join(f"{v} {k}" for k, v in sorted(per_system_count.items()))
    print(f"processed {totals}; wrote {output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
