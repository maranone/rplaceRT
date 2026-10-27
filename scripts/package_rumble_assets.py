#!/usr/bin/env python3
"""Package rumble JSON into lightweight app assets."""

from __future__ import annotations

import json
import re
import shutil
import sys
from collections import defaultdict
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_openai_retro_rumble import build_trigger, EVENT_CONDITIONS, EVENT_HAPTICS  # type: ignore

SOURCE_ROOT = Path("rumble/merged_inventory")
MANUAL_SOURCE_ROOT = Path("rumble/manual_profiles")
ASSET_ROOT = Path("app/src/main/assets/rumble")

TIER_ORDER = {"stable": 0, "experimental": 1, "contrib": 2}

# Default numpy-style type for cheat-code entries: 1-byte unsigned, little-endian.
_CHEAT_DEFAULT_TYPE = "<u1"

# Map cheat description keywords → stable_retro-style field names
# that classify_field() in generate_openai_retro_rumble.py recognises.
_SKIP_DESC_RE = re.compile(r'\b(enable|must be on|master|patch|debug|test)\b', re.IGNORECASE)


def _desc_to_field_name(desc: str) -> str | None:
    """Return a classify_field()-compatible field name for a cheat description, or None to skip."""
    if _SKIP_DESC_RE.search(desc):
        return None
    d = desc.lower()
    if re.search(r'\blives?\b', d):
        return "lives"
    if re.search(r'\b(health|hp|life point|hit point|energy|shield|vitality)\b', d):
        return "health"
    if re.search(r'\b(score|point)\b', d):
        return "score"
    if re.search(r'\b(coin|ring|gem|jewel|ammo|bullet|arrow|bomb|key|cash|money|gold|item)\b', d):
        return "coins"
    if re.search(r'\b(kill|enemy|enemies|foe)\b', d):
        return "enemy_health"
    if re.search(r'\b(level|stage|world|zone|act)\b', d):
        return "level"
    return None


def convert_libretro_doc(game: dict) -> dict | None:
    """Build a rumble doc from libretro_database or bobwin cheat addresses."""
    system = game["system"]
    sources = game.get("sources", {})

    # Collect raw entries from all cheat sources, deduplicated by address
    entries_by_addr: dict[str, dict] = {}
    for src_key in ("libretro_database", "bobwin"):
        for entry in sources.get(src_key, {}).get("sample_raw_entries", []):
            addr = entry.get("address")
            if addr and addr not in entries_by_addr:
                entries_by_addr[addr] = entry

    triggers: list[dict] = []
    seen: set[tuple[str, str, str]] = set()
    for entry in entries_by_addr.values():
        desc = entry.get("desc", "")
        field_name = _desc_to_field_name(desc)
        if not field_name:
            continue
        try:
            address_int = int(entry["address"], 16)
            new_triggers = build_trigger(system, field_name, {"address": address_int, "type": _CHEAT_DEFAULT_TYPE})
        except Exception:
            continue
        for t in new_triggers:
            watch = t["watch"][0]
            dedup_key = (t["event"], watch["absolute_address"], t["condition"])
            if dedup_key in seen:
                continue
            seen.add(dedup_key)
            triggers.append(t)

    if not triggers:
        return None

    game_key = slugify(game["display_name"])
    return {
        "schema": f"qrd.rumble.{system}.v1",
        "game": {"name": game["display_name"], "system": system},
        "source": {
            "integration_tier": "contrib",
            "game_key": game_key,
        },
        "triggers": triggers,
    }


def slugify(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", value.lower()).strip("_") or "game"


def normalize_name(value: str) -> str:
    text = value
    text = re.sub(r"\.[A-Za-z0-9]+$", " ", text)
    text = re.sub(r"\([^)]*\)", " ", text)
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"\b(rev(ision)?|version|ver|proto|beta|demo)\s*[A-Za-z0-9._-]*\b", " ", text, flags=re.I)
    text = re.sub(r"[-_]+", " ", text)
    text = re.sub(r"\b(usa|europe|eur|japan|jpn|es|esp|en|fr|de|it|proto|beta|demo|rev|revision|v\d+(?:\.\d+)*)\b", " ", text, flags=re.I)
    text = re.sub(r"[^A-Za-z0-9]+", "", text.lower())
    return text


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def encode_profile_text(doc: dict) -> str:
    lines: list[str] = []
    lines.append(f"game_name={doc['game']['name']}")
    lines.append(f"game_key={doc['source']['game_key']}")
    lines.append(f"system={doc['game']['system']}")
    lines.append(f"integration_tier={doc['source']['integration_tier']}")
    for trigger in doc.get("triggers", []):
        watch = trigger["watch"][0]
        haptic_parts = [
            f"{entry['hand']}:{entry['amplitude']:.3f}:{int(entry['duration_ms'])}"
            for entry in trigger.get("haptic", [])
        ]
        lines.append(
            "\t".join(
                [
                    "trigger",
                    trigger["id"],
                    trigger["event"],
                    watch["absolute_address"],
                    str(int(watch["size"])),
                    watch["value_type"],
                    watch["byte_order"],
                    trigger["condition"],
                    str(float(trigger.get("confidence", 0.0))),
                    "1" if trigger.get("needs_review") else "0",
                    ";".join(haptic_parts),
                ]
            )
        )
    return "\n".join(lines) + "\n"


def merge_docs(docs: list[dict]) -> dict:
    docs = sorted(docs, key=lambda d: TIER_ORDER.get(d["source"]["integration_tier"], 99))
    merged = {
        "game": docs[0]["game"],
        "source": {
            "game_key": docs[0]["source"]["game_key"],
            "integration_tier": docs[0]["source"]["integration_tier"],
        },
        "triggers": [],
    }
    seen: set[tuple[str, str, str]] = set()
    for doc in docs:
        for trigger in doc.get("triggers", []):
            watch = trigger["watch"][0]
            key = (trigger["event"], watch["absolute_address"], trigger["condition"])
            if key in seen:
                continue
            seen.add(key)
            merged["triggers"].append(trigger)
    return merged


def convert_stable_fields_to_doc(game: dict, rumble_root: Path) -> dict | None:
    stable = game["sources"].get("stable_retro")
    if not stable:
        return None
    triggers: list[dict] = []
    unsupported: list[dict] = []
    for field in stable.get("fields", []):
        try:
            field_triggers = build_trigger(
                game["system"],
                field["name"],
                {
                    "address": int(field["address"], 16),
                    "type": field["type"],
                },
            )
        except Exception as exc:
            unsupported.append({"field": field, "reason": str(exc)})
            continue
        if not field_triggers:
            unsupported.append({"field": field, "reason": "No rumble mapping rule."})
            continue
        triggers.extend(field_triggers)
    if not triggers:
        return None
    stable_path = Path(stable["path"])
    game_key = stable_path.parent.name.replace("-v0", "")
    return {
        "schema": f"qrd.rumble.{game['system']}.v1",
        "game": {
            "name": game["display_name"],
            "system": game["system"],
        },
        "source": {
            "data_json": stable["path"],
            "integration_tier": "stable",
            "game_key": game_key,
        },
        "triggers": triggers,
        "unsupported_evidence": unsupported,
    }


def load_openai_doc(game: dict, rumble_root: Path) -> dict | None:
    source = game["sources"].get("openai_retro")
    if not source:
        return None
    path = rumble_root / source["path"]
    if not path.exists():
        return None
    return load_json(path)


def load_docs_from_merged_inventory(source_root: Path) -> list[dict]:
    docs: list[dict] = []
    rumble_root = source_root.parent
    for path in sorted((source_root / "games").glob("*/*.json")):
        game = load_json(path)
        primary = game.get("recommended_primary_source")
        doc = None
        if primary == "stable_retro":
            doc = convert_stable_fields_to_doc(game, rumble_root)
            if doc is None:
                doc = load_openai_doc(game, rumble_root)
        elif primary == "openai_retro":
            doc = load_openai_doc(game, rumble_root)
        elif primary in ("libretro_database", "bobwin"):
            doc = convert_libretro_doc(game)
        else:
            doc = load_openai_doc(game, rumble_root)
            if doc is None and "stable_retro" in game.get("sources", {}):
                doc = convert_stable_fields_to_doc(game, rumble_root)
            if doc is None:
                doc = convert_libretro_doc(game)
        if doc is not None and doc.get("triggers"):
            docs.append(doc)
    return docs


def load_docs_legacy(source_root: Path) -> list[dict]:
    docs: list[dict] = []
    if not source_root.exists():
        return docs
    for path in source_root.rglob("*.json"):
        try:
            docs.append(load_json(path))
        except Exception:
            pass
    return docs


def load_docs(source_root: Path) -> list[dict]:
    docs: list[dict] = []
    if (source_root / "games").exists():
        docs.extend(load_docs_from_merged_inventory(source_root))
    else:
        docs.extend(load_docs_legacy(source_root))
    if MANUAL_SOURCE_ROOT.exists():
        docs.extend(load_docs_legacy(MANUAL_SOURCE_ROOT))
    return docs


def main() -> int:
    if ASSET_ROOT.exists():
        shutil.rmtree(ASSET_ROOT)
    (ASSET_ROOT / "profiles").mkdir(parents=True, exist_ok=True)

    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for doc in load_docs(SOURCE_ROOT):
        grouped[(doc["game"]["system"], doc["source"]["game_key"])].append(doc)

    catalog_lines: list[str] = ["system\tgame_key\tasset_path\taliases\ttrigger_count"]
    for (system, game_key), docs in sorted(grouped.items()):
        merged = merge_docs(docs)
        game_name = merged["game"]["name"]
        aliases = sorted(
            {
                normalize_name(game_name),
                normalize_name(game_key),
                normalize_name(game_key.rsplit("-", 1)[0]),
            }
            - {""}
        )
        asset_rel = Path("profiles") / system / f"{slugify(game_key)}.qrr"
        asset_path = ASSET_ROOT / asset_rel
        asset_path.parent.mkdir(parents=True, exist_ok=True)
        asset_path.write_text(encode_profile_text(merged), encoding="utf-8")
        catalog_lines.append(
            "\t".join(
                [
                    system,
                    game_key,
                    asset_rel.as_posix(),
                    "|".join(aliases),
                    str(len(merged["triggers"])),
                ]
            )
        )

    (ASSET_ROOT / "catalog.tsv").write_text("\n".join(catalog_lines) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
