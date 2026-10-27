#!/usr/bin/env python3
"""Merge rumble-related data sources for all supported systems into one inventory."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUMBLE_ROOT = ROOT / "rumble"
OUTPUT_ROOT = DEFAULT_RUMBLE_ROOT / "merged_inventory"

SYSTEM_CHT_DIRS = {
    "snes":    "Nintendo - Super Nintendo Entertainment System",
    "genesis": "Sega - Mega Drive - Genesis",
    "nes":     "Nintendo - Nintendo Entertainment System",
    "gba":     "Nintendo - Game Boy Advance",
    "gb":      "Nintendo - Game Boy",
    "gbc":     "Nintendo - Game Boy Color",
    "sms":     "Sega - Master System - Mark III",
    "gg":      "Sega - Game Gear",
    "pce":     "NEC - PC Engine - TurboGrafx 16",
}

# Stable-retro game folder suffixes (longest-first matching prevents -Gb matching -GbAdvance)
STABLE_SUFFIXES: dict[str, str] = {
    "-GbAdvance-v0": "gba",
    "-GameGear-v0":  "gg",
    "-Genesis-v0":   "genesis",
    "-Snes-v0":      "snes",
    "-Nes-v0":       "nes",
    "-Gbc-v0":       "gbc",
    "-Sms-v0":       "sms",
    "-Gb-v0":        "gb",
}

SNES_RAW_RE    = re.compile(r"^(7[EF])([0-9A-F]{4})([0-9A-F]{2})$", re.IGNORECASE)
GENESIS_RAW_RE = re.compile(r"^(FF[0-9A-F]{4}):([0-9A-FX]{2,4})$", re.IGNORECASE)
NES_RAW_RE     = re.compile(r"^([0-9A-F]{4}):([0-9A-F]{2})$", re.IGNORECASE)
GBA_RAW_RE     = re.compile(r"^([0-9A-F]{8})[+:]([0-9A-F]{2,8})$", re.IGNORECASE)
SMS_RAW_RE     = re.compile(r"^([0-9A-F]{6}):([0-9A-FX]{2,4})$", re.IGNORECASE)
PCE_RAW_RE     = re.compile(r"^([0-9A-F]{6}):([0-9A-F]{2})$", re.IGNORECASE)
KV_RE          = re.compile(r'^\s*cheat(?P<idx>\d+)_(?P<key>[A-Za-z0-9_]+)\s*=\s*"(?P<value>.*)"\s*$')

# ── Cheat-code format decoders ─────────────────────────────────────────────

# NES Game Genie: 6- or 8-letter codes using alphabet APZLGITYEOXUKSVN.
# Each letter encodes a nibble 0-15. Address bits are scattered across
# nibbles n[1]..n[5] per the scramble table below.
# Verified bit layout (15 non-overlapping bits) matches FCEUX genie.c decode6().
# Test vector: code → expected CPU address (< 0x2000 = WRAM).
_GG_NES_ALPHA = "APZLGITYEOXUKSVN"


def decode_nes_game_genie(code: str) -> int | None:
    """Decode NES Game Genie 6-letter code to CPU address, or None if invalid."""
    c = code.upper().replace("-", "")
    if len(c) not in (6, 8):
        return None
    if not all(ch in _GG_NES_ALPHA for ch in c):
        return None
    n = [_GG_NES_ALPHA.index(ch) for ch in c]
    # 15-bit CPU address, bit assignments verified against FCEUX genie.c decode6():
    #   bits 14:12 = n[3]&7, bit 11 = n[4]&8, bits 10:8 = n[5]&7,
    #   bit 7 = n[3]&8, bits 6:4 = n[1]&7, bit 3 = n[2]&8, bits 2:0 = n[4]&7
    address = (
        ((n[3] & 0x07) << 12) |
        ((n[4] & 0x08) << 8)  |
        ((n[5] & 0x07) << 8)  |
        ((n[3] & 0x08) << 4)  |
        ((n[1] & 0x07) << 4)  |
        (n[2] & 0x08)         |
        (n[4] & 0x07)
    )
    return address


# GBA Code Breaker: codes split on '+' by scan_libretro_database(), so
# the address half arrives as a bare 8-hex-digit string like "8201A454".
# Type byte (first 2 hex digits) 0x00 = master/skip code, others = write.
# Remaining 3 bytes ORed with EWRAM base 0x02000000 give the address.
_GBA_CB_BARE_RE = re.compile(r"^[0-9A-F]{8}$", re.IGNORECASE)


def decode_gba_code_breaker(part: str) -> int | None:
    """Decode bare 8-hex-digit Code Breaker address part → EWRAM address, or None."""
    if not _GBA_CB_BARE_RE.match(part):
        return None
    type_byte = int(part[:2], 16)
    if type_byte == 0x00:
        return None  # master/enable code
    address = 0x02000000 | int(part[2:], 16)
    if 0x02000000 <= address <= 0x0203FFFF:
        return address
    return None


# GB/GBC GameShark: format 01XXYYYY where XX = value byte, YYYY = 16-bit address.
# WRAM lives at 0xC000–0xDFFF.
_GB_GS_RE = re.compile(r"^01[0-9A-F]{2}([0-9A-F]{4})$", re.IGNORECASE)


def decode_gb_gameshark(code: str) -> int | None:
    """Decode GB/GBC GameShark 01XXYYYY → WRAM address, or None."""
    m = _GB_GS_RE.match(code.upper())
    if not m:
        return None
    address = int(m.group(1), 16)
    if 0xC000 <= address <= 0xDFFF:
        return address
    return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge openai_retro, stable_retro, libretro_database, and action_replay_mk_iii."
    )
    parser.add_argument("--rumble-root", default=str(DEFAULT_RUMBLE_ROOT), help="Path to rumble root.")
    parser.add_argument("--output", default=str(OUTPUT_ROOT), help="Output directory.")
    parser.add_argument("--limit", type=int, default=0, help="Limit games per source for quick tests.")
    parser.add_argument("--verbose", action="store_true", help="Print progress.")
    return parser.parse_args()


def normalize_title(name: str) -> str:
    name = re.sub(r"\([^)]*\)", " ", name)
    name = name.replace("&", " and ")
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    raw_tokens = re.findall(r"[A-Za-z0-9]+", name.lower())
    tokens = [token for token in raw_tokens if token not in {"the", "a", "an"}]
    return "".join(tokens)


def slugify(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", value.lower()).strip("_")
    return slug or "game"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def format_address(system: str, address: int) -> str:
    if system == "gba":
        return f"0x{address:08X}"
    if system == "pce":
        return f"0x{address:06X}"
    if system in ("nes", "gb", "gbc", "sms", "gg"):
        return f"0x{address:04X}"
    return f"0x{address:06X}"  # snes, genesis


def read_text_any(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(errors="replace")


def ensure_game(games: dict[tuple[str, str], dict[str, Any]], system: str, display_name: str) -> dict[str, Any]:
    key = (system, normalize_title(display_name))
    game = games.get(key)
    if game is None:
        game = {
            "system": system,
            "normalized_title": key[1],
            "display_name": display_name,
            "aliases": [],
            "sources": {},
            "recommended_primary_source": None,
            "notes": [],
        }
        games[key] = game
    if display_name and display_name not in game["aliases"] and display_name != game["display_name"]:
        game["aliases"].append(display_name)
    if len(display_name) > len(game["display_name"]):
        game["display_name"] = display_name
    return game


def scan_openai_retro(root: Path, games: dict[tuple[str, str], dict[str, Any]], limit: int, verbose: bool) -> None:
    count = 0
    openai_root = root / "openai_retro"
    if not openai_root.exists():
        return
    system_dirs = sorted(d for d in openai_root.iterdir() if d.is_dir())
    for system_dir in system_dirs:
        system = system_dir.name
        base = system_dir / "stable"
        if not base.exists():
            continue
        for path in sorted(base.glob("*.json")):
            payload = load_json(path)
            game_name = payload.get("game", {}).get("name") or path.stem
            game = ensure_game(games, system, game_name)
            triggers = payload.get("triggers", [])
            fields: set[str] = set()
            addresses: set[str] = set()
            for trigger in triggers:
                for field in trigger.get("source_fields", []):
                    name = field.get("name")
                    if name:
                        fields.add(str(name))
                for watch in trigger.get("watch", []):
                    address = watch.get("absolute_address") or watch.get("address")
                    if address:
                        addresses.add(str(address))
            game["sources"]["openai_retro"] = {
                "path": str(path.relative_to(root)),
                "trigger_count": len(triggers),
                "field_names": sorted(fields),
                "field_count": len(fields),
                "distinct_addresses": sorted(addresses),
                "distinct_address_count": len(addresses),
            }
            count += 1
            if verbose and count % 100 == 0:
                print(f"openai_retro: {count}")
            if limit and count >= limit:
                return


def split_stable_name(dirname: str) -> tuple[str, str] | None:
    for suffix, system in sorted(STABLE_SUFFIXES.items(), key=lambda x: -len(x[0])):
        if dirname.endswith(suffix):
            return dirname[: -len(suffix)], system
    return None


def scan_stable_retro(root: Path, games: dict[tuple[str, str], dict[str, Any]], limit: int, verbose: bool) -> None:
    base = root / "stable_retro" / "stable_retro" / "data" / "stable"
    if not base.exists():
        return
    count = 0
    for path in sorted(base.glob("*/data.json")):
        split = split_stable_name(path.parent.name)
        if not split:
            continue
        game_name, system = split
        payload = load_json(path)
        info = payload.get("info", {})
        fields = []
        addresses: set[str] = set()
        for field_name, meta in sorted(info.items()):
            address = meta.get("address")
            if isinstance(address, int):
                absolute_address = format_address(system, address)
                addresses.add(absolute_address)
            else:
                absolute_address = None
            fields.append(
                {
                    "name": field_name,
                    "address": absolute_address,
                    "type": meta.get("type"),
                }
            )
        game = ensure_game(games, system, game_name)
        game["sources"]["stable_retro"] = {
            "path": str(path.relative_to(root)),
            "field_count": len(fields),
            "fields": fields,
            "distinct_address_count": len(addresses),
        }
        count += 1
        if verbose and count % 100 == 0:
            print(f"stable_retro: {count}")
        if limit and count >= limit:
            return


def parse_cht_rows(text: str) -> list[tuple[str, str]]:
    grouped: dict[int, dict[str, str]] = defaultdict(dict)
    for line in text.splitlines():
        match = KV_RE.match(line)
        if not match:
            continue
        idx = int(match.group("idx"))
        grouped[idx][match.group("key")] = match.group("value")
    rows = []
    for idx in sorted(grouped):
        code = grouped[idx].get("code", "").strip()
        if code:
            rows.append((code, grouped[idx].get("desc", "").strip()))
    return rows


def parse_raw_code(system: str, part: str) -> tuple[str, str | None]:
    normalized = re.sub(r"\s+", "", part).upper()
    if system == "snes":
        match = SNES_RAW_RE.match(normalized)
        if not match:
            return "unsupported", None
        bank, addr_hex, _value = match.groups()
        return "raw", f"0x{bank}{addr_hex}"
    if system == "genesis":
        match = GENESIS_RAW_RE.match(normalized)
        if not match:
            return "unsupported", None
        addr_hex, _value = match.groups()
        return "raw", f"0x{addr_hex}"
    if system == "nes":
        match = NES_RAW_RE.match(normalized)
        if match:
            addr_hex, _value = match.groups()
            return "raw", f"0x{addr_hex}"
        # Fallback: NES Game Genie 6/8-letter code → CPU address
        addr = decode_nes_game_genie(normalized)
        if addr is not None and addr < 0x2000:
            return "raw", f"0x{addr & 0x07FF:04X}"
        return "unsupported", None
    if system == "gba":
        match = GBA_RAW_RE.match(normalized)
        if match:
            addr_hex = match.group(1)
            return "raw", f"0x{addr_hex}"
        # Fallback: bare 8-hex-digit Code Breaker address half → EWRAM address
        addr = decode_gba_code_breaker(normalized)
        if addr is not None:
            return "raw", f"0x{addr:08X}"
        return "unsupported", None
    if system in ("sms", "gg"):
        match = SMS_RAW_RE.match(normalized)
        if not match:
            return "unsupported", None
        addr_hex, _value = match.groups()
        return "raw", f"0x{addr_hex}"
    if system == "pce":
        match = PCE_RAW_RE.match(normalized)
        if not match:
            return "unsupported", None
        addr_hex, _value = match.groups()
        addr = int(addr_hex, 16)
        if not (0x1F0000 <= addr <= 0x1F1FFF):
            return "unsupported", None
        return "raw", f"0x{addr:06X}"
    if system in ("gb", "gbc"):
        # Try raw XXXX:YY format first
        match = NES_RAW_RE.match(normalized)
        if match:
            addr_hex, _value = match.groups()
            addr = int(addr_hex, 16)
            if 0xC000 <= addr <= 0xDFFF:
                return "raw", f"0x{addr:04X}"
            return "unsupported", None
        # Fallback: GameShark 01XXYYYY → WRAM address
        addr = decode_gb_gameshark(normalized)
        if addr is not None:
            return "raw", f"0x{addr:04X}"
        return "unsupported", None
    return "unsupported", None


def scan_libretro_database(root: Path, games: dict[tuple[str, str], dict[str, Any]], limit: int, verbose: bool) -> None:
    base = root / "libretro_database" / "cht"
    count = 0
    for system, dirname in SYSTEM_CHT_DIRS.items():
        system_dir = base / dirname
        if not system_dir.exists():
            continue
        for path in sorted(system_dir.glob("*.cht")):
            rows = parse_cht_rows(read_text_any(path))
            raw_entries = []
            distinct_addresses: set[str] = set()
            for code, desc in rows:
                for part in code.split("+"):
                    part = part.strip()
                    if not part:
                        continue
                    kind, address = parse_raw_code(system, part)
                    if kind != "raw" or address is None:
                        continue
                    distinct_addresses.add(address)
                    raw_entries.append({"code": part, "desc": desc, "address": address})
            game_name = path.stem
            game = ensure_game(games, system, game_name)
            game["sources"]["libretro_database"] = {
                "path": str(path.relative_to(root)),
                "total_cheat_rows": len(rows),
                "raw_entry_count": len(raw_entries),
                "distinct_address_count": len(distinct_addresses),
                "sample_raw_entries": raw_entries[:25],
            }
            count += 1
            if verbose and count % 500 == 0:
                print(f"libretro_database: {count}")
            if limit and count >= limit:
                return


def scan_action_replay(root: Path, games: dict[tuple[str, str], dict[str, Any]], limit: int, verbose: bool) -> None:
    path = root / "action_replay_mk_iii" / "preservaction-database.md"
    if not path.exists():
        return
    count = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("|"):
            continue
        cols = [part.strip() for part in line.strip().strip("|").split("|")]
        if len(cols) != 4 or cols[0] in {"ID", "-----:"}:
            continue
        try:
            cheat_count = int(cols[3])
        except ValueError:
            continue
        game_name = cols[2]
        game = ensure_game(games, "snes", game_name)
        game["sources"]["action_replay_mk_iii"] = {
            "path": str(path.relative_to(root)),
            "built_in_cheat_count": cheat_count,
            "mk3_label": cols[1],
        }
        count += 1
        if verbose and count % 50 == 0:
            print(f"action_replay_mk_iii: {count}")
        if limit and count >= limit:
            return


BOBWIN_SYSTEM_DIRS: dict[str, str] = {
    "nes": "nes",
    "gba": "gba",
    "pce": "pce",
}


def scan_bobwin(root: Path, games: dict[tuple[str, str], dict[str, Any]], limit: int, verbose: bool) -> None:
    count = 0
    for system, dirname in BOBWIN_SYSTEM_DIRS.items():
        dir_path = root / "bobwin" / dirname
        if not dir_path.exists():
            continue
        for path in sorted(dir_path.glob("*.cht")):
            rows = parse_cht_rows(read_text_any(path))
            raw_entries = []
            distinct_addresses: set[str] = set()
            for code, desc in rows:
                for part in code.split("+"):
                    part = part.strip()
                    if not part:
                        continue
                    kind, address = parse_raw_code(system, part)
                    if kind != "raw" or address is None:
                        continue
                    distinct_addresses.add(address)
                    raw_entries.append({"code": part, "desc": desc, "address": address})
            game_name = path.stem
            game = ensure_game(games, system, game_name)
            game["sources"]["bobwin"] = {
                "path": str(path.relative_to(root)),
                "total_cheat_rows": len(rows),
                "raw_entry_count": len(raw_entries),
                "distinct_address_count": len(distinct_addresses),
                "sample_raw_entries": raw_entries[:25],
            }
            count += 1
            if verbose and count % 200 == 0:
                print(f"bobwin: {count}")
            if limit and count >= limit:
                return


def choose_primary_source(game: dict[str, Any]) -> None:
    sources = game["sources"]
    if "stable_retro" in sources and sources["stable_retro"]["field_count"] > 0:
        game["recommended_primary_source"] = "stable_retro"
        game["notes"].append("Semantic labeled RAM fields available.")
        return
    if "openai_retro" in sources and sources["openai_retro"]["field_count"] > 0:
        game["recommended_primary_source"] = "openai_retro"
        game["notes"].append("Derived rumble semantics available from prior export.")
        return
    if "libretro_database" in sources and sources["libretro_database"]["raw_entry_count"] > 0:
        game["recommended_primary_source"] = "libretro_database"
        game["notes"].append("Only cheat-address style raw entries available.")
        return
    if "bobwin" in sources and sources["bobwin"]["raw_entry_count"] > 0:
        game["recommended_primary_source"] = "bobwin"
        game["notes"].append("BOBwin cheat addresses available as fallback source.")
        return
    if "action_replay_mk_iii" in sources:
        game["recommended_primary_source"] = "action_replay_mk_iii"
        game["notes"].append("SNES built-in cheat count present, but raw codes are not machine-readable here.")


def write_outputs(output_root: Path, games: dict[tuple[str, str], dict[str, Any]]) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    game_dir = output_root / "games"
    game_dir.mkdir(parents=True, exist_ok=True)

    ordered_games = sorted(games.values(), key=lambda item: (item["system"], item["display_name"].lower()))
    for game in ordered_games:
        choose_primary_source(game)
        system_dir = game_dir / game["system"]
        system_dir.mkdir(parents=True, exist_ok=True)
        out_path = system_dir / f"{slugify(game['display_name'])}.json"
        out_path.write_text(json.dumps(game, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    (output_root / "index.json").write_text(
        json.dumps(ordered_games, indent=2, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )

    with (output_root / "summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "system",
                "display_name",
                "recommended_primary_source",
                "stable_field_count",
                "openai_field_count",
                "openai_trigger_count",
                "libretro_raw_entry_count",
                "libretro_distinct_address_count",
                "action_replay_cheat_count",
            ]
        )
        for game in ordered_games:
            writer.writerow(
                [
                    game["system"],
                    game["display_name"],
                    game["recommended_primary_source"] or "",
                    game["sources"].get("stable_retro", {}).get("field_count", 0),
                    game["sources"].get("openai_retro", {}).get("field_count", 0),
                    game["sources"].get("openai_retro", {}).get("trigger_count", 0),
                    game["sources"].get("libretro_database", {}).get("raw_entry_count", 0),
                    game["sources"].get("libretro_database", {}).get("distinct_address_count", 0),
                    game["sources"].get("action_replay_mk_iii", {}).get("built_in_cheat_count", 0),
                ]
            )


def main() -> None:
    args = parse_args()
    rumble_root = Path(args.rumble_root).resolve()
    output_root = Path(args.output).resolve()
    games: dict[tuple[str, str], dict[str, Any]] = {}

    scan_openai_retro(rumble_root, games, args.limit, args.verbose)
    scan_stable_retro(rumble_root, games, args.limit, args.verbose)
    scan_libretro_database(rumble_root, games, args.limit, args.verbose)
    scan_action_replay(rumble_root, games, args.limit, args.verbose)
    scan_bobwin(rumble_root, games, args.limit, args.verbose)
    write_outputs(output_root, games)

    print(f"Wrote merged inventory for {len(games)} games to {output_root}")


if __name__ == "__main__":
    main()
