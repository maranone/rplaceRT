#!/usr/bin/env python3
"""Import libretro_database '(Rumbles)' cheat files as manual profiles for missing games only."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
LIBRETRO_ROOT = ROOT / "rumble" / "libretro_database" / "cht"
MANUAL_ROOT = ROOT / "rumble" / "manual_profiles"
CATALOG_PATH = ROOT / "app" / "src" / "main" / "assets" / "rumble" / "catalog.tsv"
MERGED_SUMMARY_PATH = ROOT / "rumble" / "merged_inventory" / "summary.csv"

SYSTEM_DIRS = {
    "snes": "Nintendo - Super Nintendo Entertainment System",
    "genesis": "Sega - Mega Drive - Genesis",
}

EVENT_MAP = [
    ("life loss", "life_lost", "prev > curr", 0.9),
    ("lose life", "life_lost", "prev > curr", 0.9),
    ("megabomb use", "powerup_lost", "prev > curr", 0.9),
    ("megabombs use", "powerup_lost", "prev > curr", 0.9),
    ("bomb use", "powerup_lost", "prev > curr", 0.9),
    ("energy loss", "damage_taken", "prev > curr", 0.85),
    ("health loss", "damage_taken", "prev > curr", 0.85),
    ("damage", "damage_taken", "prev > curr", 0.75),
    ("ring pickup", "pickup", "prev != curr", 0.75),
    ("pickup", "pickup", "prev != curr", 0.7),
    ("ring loss", "pickup", "prev != curr", 0.6),
    ("enemy hit", "enemy_defeated", "prev != curr", 0.7),
    ("attack", "enemy_defeated", "prev != curr", 0.55),
    ("score", "score", "prev < curr", 0.7),
    ("game over", "game_over", "prev == 0 && curr != 0", 0.85),
    ("death", "death", "prev > 0 && curr == 0", 0.85),
]

EVENT_HAPTICS: dict[str, list[dict[str, Any]]] = {
    "damage_taken": [{"hand": "left", "amplitude": 0.75, "duration_ms": 180}],
    "death": [{"hand": "left", "amplitude": 1.0, "duration_ms": 500}, {"hand": "right", "amplitude": 1.0, "duration_ms": 500}],
    "life_lost": [{"hand": "left", "amplitude": 0.63, "duration_ms": 250}],
    "pickup": [{"hand": "right", "amplitude": 0.22, "duration_ms": 80}],
    "score": [{"hand": "right", "amplitude": 0.3, "duration_ms": 80}],
    "enemy_defeated": [{"hand": "left", "amplitude": 0.51, "duration_ms": 100}],
    "game_over": [{"hand": "left", "amplitude": 1.0, "duration_ms": 700}, {"hand": "right", "amplitude": 1.0, "duration_ms": 700}],
    "powerup_lost": [{"hand": "left", "amplitude": 1.0, "duration_ms": 250}],
}

ALIAS_OVERRIDES = {
    "snes": {
        "contraspirits": {"contraiiithealienwars"},
        "firepower2000": {"superswiv"},
        "superstarwarsempirestrike": {"superstarwarstheempirestrikesback"},
        "superstarwarsreturnofthe": {"superstarwarsreturnofthejedi"},
        "teenagemutantninjaturtlesiv": {"teenagemutantninjaturtlesivturtlesintime"},
        "battletoadsanddoubledrago": {"battletoadsanddoubledragon"},
    },
    "genesis": {
        "battletoadsanddoubledrago": {"battletoadsdoubledragon"},
        "michaeljacksonsmoonw": {"michaeljacksonsmoonwalker"},
        "revengeofshinobithe": {"revengeofshinobi"},
        "storyofthortheasuccessorof": {"beyondoasis", "storyofthor"},
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import missing SNES/Genesis Rumbles profiles.")
    parser.add_argument("--limit", type=int, default=0, help="Limit imported games.")
    parser.add_argument("--force", action="store_true", help="Overwrite existing manual profiles.")
    return parser.parse_args()


def slugify(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", value.lower()).strip("_") or "game"


def normalize_name(value: str) -> str:
    text = value
    text = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    text = re.sub(r"\.[A-Za-z0-9]+$", " ", text)
    text = re.sub(r"\([^)]*\)", " ", text)
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"\b(rev(ision)?|version|ver|proto|beta|demo)\s*[A-Za-z0-9._-]*\b", " ", text, flags=re.I)
    text = re.sub(r"[-_]+", " ", text)
    text = re.sub(r"\b(usa|europe|eur|japan|jpn|es|esp|en|fr|de|it|proto|beta|demo|rev|revision|v\d+(?:\.\d+)*)\b", " ", text, flags=re.I)
    tokens = re.findall(r"[A-Za-z0-9]+", text.lower())
    tokens = [token for token in tokens if token not in {"the", "a", "an", "of", "and"}]
    return "".join(tokens)


def token_signature(value: str) -> tuple[str, ...]:
    text = value
    text = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    text = re.sub(r"\([^)]*\)", " ", text)
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"[-_]+", " ", text)
    tokens = re.findall(r"[A-Za-z0-9]+", text.lower())
    return tuple(token for token in tokens if token not in {"the", "a", "an", "of", "and", "usa", "europe", "japan", "world"})


def title_from_rumbles_name(path: Path) -> str:
    return re.sub(r"\s*\(Rumbles\)\s*$", "", path.stem, flags=re.I).strip()


def game_key_from_title(title: str, system: str) -> str:
    compact = re.sub(r"[^A-Za-z0-9]+", "", title)
    suffix = "Snes" if system == "snes" else "Genesis"
    return f"{compact}-{suffix}"


def load_existing_aliases() -> dict[str, set[str]]:
    aliases: dict[str, set[str]] = defaultdict(set)
    if not CATALOG_PATH.exists():
        pass
    else:
        lines = CATALOG_PATH.read_text(encoding="utf-8").splitlines()
        for line in lines[1:]:
            cols = line.split("\t")
            if len(cols) < 5:
                continue
            system = cols[0]
            aliases[system].add(normalize_name(cols[1]))
            for alias in cols[3].split("|"):
                alias = alias.strip()
                if alias:
                    aliases[system].add(normalize_name(alias))
    if MERGED_SUMMARY_PATH.exists():
        with MERGED_SUMMARY_PATH.open(encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                system = row.get("system")
                display_name = row.get("display_name")
                if system and display_name:
                    aliases[system].add(normalize_name(display_name))
    return aliases


def is_existing_game(system: str, title: str, existing_aliases: dict[str, set[str]]) -> bool:
    normalized = normalize_name(title)
    system_aliases = existing_aliases.get(system, set())
    if normalized in system_aliases:
        return True
    for canonical, variants in ALIAS_OVERRIDES.get(system, {}).items():
        if normalized == canonical or normalized in variants:
            if canonical in system_aliases or any(variant in system_aliases for variant in variants):
                return True
    title_tokens = token_signature(title)
    if not title_tokens:
        return False
    for alias in system_aliases:
        alias_tokens = token_signature(alias)
        if not alias_tokens:
            continue
        overlap = set(title_tokens) & set(alias_tokens)
        if len(overlap) >= max(2, min(len(title_tokens), len(alias_tokens)) - 1):
            return True
        joined_title = "".join(title_tokens)
        joined_alias = "".join(alias_tokens)
        short = min(len(joined_title), len(joined_alias))
        if short >= 12 and (joined_title.startswith(joined_alias[:short]) or joined_alias.startswith(joined_title[:short])):
            return True
    return False


def parse_rumbles_file(path: Path) -> list[dict[str, str]]:
    grouped: dict[int, dict[str, str]] = defaultdict(dict)
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = [part.strip() for part in line.split("=", 1)]
        match = re.match(r"cheat(\d+)_(.+)", key)
        if not match:
            continue
        idx = int(match.group(1))
        grouped[idx][match.group(2)] = value.strip('"')
    return [grouped[idx] for idx in sorted(grouped)]


def absolute_address(system: str, offset: int) -> str:
    if system == "snes":
        return f"0x{0x7E0000 + offset:06X}"
    return f"0x{0xFF0000 + offset:06X}"


def classify(desc: str) -> tuple[str, str, float] | None:
    lowered = desc.lower()
    for needle, event, condition, confidence in EVENT_MAP:
        if needle in lowered:
            return event, condition, confidence
    return None


def hand_for_port(port: str) -> str:
    return "right" if port == "2" else "left"


def haptics_for(event: str, port: str) -> list[dict[str, Any]]:
    hand = hand_for_port(port)
    haptics = []
    for item in EVENT_HAPTICS[event]:
        copied = dict(item)
        copied["hand"] = hand if len(EVENT_HAPTICS[event]) == 1 else copied["hand"]
        haptics.append(copied)
    return haptics


def make_trigger(system: str, idx: int, cheat: dict[str, str]) -> dict[str, Any] | None:
    desc = cheat.get("desc", "").strip()
    address_text = cheat.get("address", "").strip()
    if not desc or not address_text.isdigit():
        return None
    classified = classify(desc)
    if classified is None:
        return None
    event, condition, confidence = classified
    offset = int(address_text)
    absolute = absolute_address(system, offset)
    return {
        "id": f"{event}_{idx}_{offset:x}",
        "event": event,
        "watch": [
            {
                "absolute_address": absolute,
                "size": 1,
                "value_type": "uint",
                "byte_order": "native",
                "raw_type": "|u1",
                "bank": "7E" if system == "snes" else None,
                "address": f"0x{offset:04X}" if system == "snes" else absolute,
                "wram_offset": offset if system == "snes" else None,
                "ram_offset": offset if system == "genesis" else None,
            }
        ],
        "condition": condition,
        "haptic": haptics_for(event, cheat.get("rumble_port", "1")),
        "confidence": confidence,
        "needs_review": True,
        "reason": f"Imported from libretro Rumbles profile: {desc}",
        "source_fields": [
            {
                "name": slugify(desc),
                "address": absolute,
                "raw_type": "|u1",
            }
        ],
    }


def clean_trigger(trigger: dict[str, Any]) -> dict[str, Any]:
    watch = trigger["watch"][0]
    cleaned_watch = {
        "absolute_address": watch["absolute_address"],
        "size": watch["size"],
        "value_type": watch["value_type"],
        "byte_order": watch["byte_order"],
        "raw_type": watch["raw_type"],
        "address": watch["address"],
    }
    if watch.get("bank") is not None:
        cleaned_watch["bank"] = watch["bank"]
    if watch.get("wram_offset") is not None:
        cleaned_watch["wram_offset"] = watch["wram_offset"]
    if watch.get("ram_offset") is not None:
        cleaned_watch["ram_offset"] = watch["ram_offset"]
    trigger = dict(trigger)
    trigger["watch"] = [cleaned_watch]
    return trigger


def main() -> None:
    args = parse_args()
    existing_aliases = load_existing_aliases()
    imported = 0
    skipped_existing = 0
    skipped_unmapped = 0

    for system, dirname in SYSTEM_DIRS.items():
        source_dir = LIBRETRO_ROOT / dirname
        for path in sorted(source_dir.glob("*Rumbles*.cht")):
            title = title_from_rumbles_name(path)
            normalized = normalize_name(title)
            if is_existing_game(system, title, existing_aliases):
                skipped_existing += 1
                continue

            cheats = parse_rumbles_file(path)
            triggers = []
            for idx, cheat in enumerate(cheats):
                trigger = make_trigger(system, idx, cheat)
                if trigger is not None:
                    triggers.append(clean_trigger(trigger))
            if not triggers:
                skipped_unmapped += 1
                continue

            game_key = game_key_from_title(title, system)
            profile = {
                "schema": f"qrd.rumble.{system}.v1",
                "game": {"name": re.sub(r"[^A-Za-z0-9]+", "", title), "system": system},
                "source": {
                    "data_json": f"manual:libretro_database/{dirname}/{path.name}",
                    "integration_tier": "manual",
                    "game_key": game_key,
                },
                "triggers": triggers,
                "unsupported_evidence": [],
            }

            out_dir = MANUAL_ROOT / system
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{slugify(title)}_{system}.json"
            if out_path.exists() and not args.force:
                skipped_existing += 1
                continue
            out_path.write_text(json.dumps(profile, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
            imported += 1
            existing_aliases[system].add(normalized)
            if args.limit and imported >= args.limit:
                print(f"Imported {imported} profiles.")
                print(f"Skipped existing: {skipped_existing}")
                print(f"Skipped unmapped: {skipped_unmapped}")
                return

    print(f"Imported {imported} profiles.")
    print(f"Skipped existing: {skipped_existing}")
    print(f"Skipped unmapped: {skipped_unmapped}")


if __name__ == "__main__":
    main()
