#!/usr/bin/env python3
"""Generate QuestRetroDepth SNES rumble mapping drafts from RetroArch .cht files."""

from __future__ import annotations

import argparse
import csv
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_INPUT = r"E:\bob\retro\BOBwin_Abril_2024.7z\cheats\retroarch\Snes9x"
DEFAULT_MODEL = "cnshenyang/qwen3-nothink:14b"
SCHEMA = "qrd.rumble.snes.v1"
OLLAMA_URL = "http://127.0.0.1:11434/api/generate"

RAW_CODE_RE = re.compile(r"^(7[EF])([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})$")
GAME_GENIE_RE = re.compile(r"^[0-9A-Fa-f?]{4}-[0-9A-Fa-f?]{4}$")
KV_RE = re.compile(r'^\s*cheat(?P<idx>\d+)_(?P<key>[A-Za-z0-9_]+)\s*=\s*"(?P<value>.*)"\s*$')

EVENT_HAPTICS: dict[str, list[dict[str, Any]]] = {
    "damage_taken": [{"hand": "left", "amplitude": 0.85, "duration_ms": 90}],
    "healed": [{"hand": "left", "amplitude": 0.4, "duration_ms": 80}],
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
    "powerup_lost": [
        {"hand": "left", "amplitude": 0.65, "duration_ms": 160},
        {"hand": "right", "amplitude": 0.65, "duration_ms": 160},
    ],
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
    "healed": "prev < curr",
    "death": "prev > 0 && curr == 0",
    "life_lost": "prev > curr",
    "life_gained": "prev < curr",
    "pickup": "prev != curr",
    "score": "prev < curr",
    "powerup_gained": "prev == 0 && curr > 0",
    "powerup_lost": "prev > 0 && curr == 0",
    "enemy_defeated": "prev > curr",
    "level_complete": "prev != curr",
    "game_over": "prev > 0 && curr == 0",
}


@dataclass(frozen=True)
class ParsedCode:
    code: str
    desc: str
    bank: str | None
    address: int | None
    value: int | None
    wram_offset: int | None
    kind: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate SNES rumble JSON drafts from RetroArch/Snes9x .cht files."
    )
    parser.add_argument("--input", default=DEFAULT_INPUT, help="Folder containing .cht files.")
    parser.add_argument("--output", default="rumble/snes", help="Output folder for per-game JSON.")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Ollama model name.")
    parser.add_argument("--limit", type=int, default=0, help="Process at most N .cht files.")
    parser.add_argument("--no-ollama", action="store_true", help="Use deterministic keyword fallback only.")
    parser.add_argument("--force", action="store_true", help="Overwrite existing JSON files.")
    parser.add_argument("--verbose", action="store_true", help="Print per-file progress.")
    parser.add_argument("--self-test", action="store_true", help="Run parser self-tests and exit.")
    return parser.parse_args()


def load_text(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(errors="replace")


def parse_raw_code(code: str, desc: str) -> ParsedCode:
    normalized = re.sub(r"\s+", "", code).upper()
    raw_match = RAW_CODE_RE.match(normalized)
    if raw_match:
        bank, addr_hex, value_hex = raw_match.groups()
        address = int(addr_hex, 16)
        wram_offset = address if bank == "7E" else 0x10000 + address
        return ParsedCode(
            code=normalized,
            desc=clean_desc(desc),
            bank=bank,
            address=address,
            value=int(value_hex, 16),
            wram_offset=wram_offset,
            kind="wram",
        )

    stripped = code.strip()
    kind = "game_genie" if GAME_GENIE_RE.match(stripped) else "unsupported"
    return ParsedCode(
        code=stripped,
        desc=clean_desc(desc),
        bank=None,
        address=None,
        value=None,
        wram_offset=None,
        kind=kind,
    )


def clean_desc(desc: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(desc or "")).strip()


def parse_csv_style(text: str) -> list[tuple[str, str]]:
    rows: list[tuple[str, str]] = []
    try:
        reader = csv.reader(text.splitlines())
        for row in reader:
            if len(row) >= 2 and row[1].strip():
                rows.append((row[1].strip(), row[2].strip() if len(row) >= 3 else ""))
    except csv.Error:
        return []
    return rows


def parse_kv_style(text: str) -> list[tuple[str, str]]:
    grouped: dict[int, dict[str, str]] = {}
    for line in text.splitlines():
        match = KV_RE.match(line)
        if not match:
            continue
        idx = int(match.group("idx"))
        grouped.setdefault(idx, {})[match.group("key")] = match.group("value")

    rows: list[tuple[str, str]] = []
    for idx in sorted(grouped):
        code = grouped[idx].get("code", "").strip()
        if code:
            rows.append((code, grouped[idx].get("desc", "")))
    return rows


def parse_cht(path: Path) -> tuple[str, list[ParsedCode]]:
    text = load_text(path)
    kv_rows = parse_kv_style(text)
    csv_rows = parse_csv_style(text) if not kv_rows else []
    parser_name = "retroarch_kv" if kv_rows else "csv"
    rows = kv_rows or csv_rows

    parsed: list[ParsedCode] = []
    for code, desc in rows:
        for part in code.split("+"):
            part = part.strip()
            if part:
                parsed.append(parse_raw_code(part, desc))
    return parser_name, parsed


def game_name_from_path(path: Path) -> str:
    return path.stem.strip()


def slugify(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "_", value.lower()).strip("_")
    return slug or "game"


def candidate_dict(code: ParsedCode, idx: int) -> dict[str, Any]:
    assert code.bank and code.address is not None and code.wram_offset is not None
    return {
        "candidate_id": f"c{idx}",
        "code": code.code,
        "desc": code.desc,
        "bank": code.bank,
        "address": f"0x{code.address:04X}",
        "wram_offset": code.wram_offset,
        "value": f"0x{code.value:02X}" if code.value is not None else None,
    }


def keyword_classify(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for cand in candidates:
        desc = cand["desc"].lower()
        event = None
        confidence = 0.45

        if any(word in desc for word in ("game over", "continues if party falls")):
            event = "game_over"
        elif any(word in desc for word in ("no health", "enemy", "boss")):
            event = "enemy_defeated"
        elif any(word in desc for word in ("life", "lives", "1-up", "1up")):
            event = "life_lost"
            if any(word in desc for word in ("extra", "1-up", "1up")):
                event = "life_gained"
        elif any(word in desc for word in ("health", "energy", "hp", "hit points")):
            event = "damage_taken"
        elif any(word in desc for word in ("coin", "ring", "star", "gem", "berry", "item")):
            event = "pickup"
        elif any(word in desc for word in ("score", "points", "bonus")):
            event = "score"
        elif any(word in desc for word in ("power", "cape", "flower", "mushroom", "yoshi", "weapon")):
            event = "powerup_gained"
        elif any(word in desc for word in ("level", "stage", "exit", "complete")):
            event = "level_complete"

        if event:
            output.append(
                {
                    "candidate_id": cand["candidate_id"],
                    "event": event,
                    "condition": EVENT_CONDITIONS[event],
                    "confidence": confidence,
                    "needs_review": True,
                    "reason": "Deterministic keyword fallback.",
                }
            )
    return output


def ollama_classify(model: str, game_name: str, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not candidates:
        return []

    prompt = {
        "task": "Classify SNES cheat RAM candidates into rumble trigger drafts.",
        "rules": [
            "Return only JSON.",
            "Only use candidate_id values from the candidates list.",
            "Do not invent addresses or events.",
            "Use only these events: "
            + ", ".join(sorted(EVENT_HAPTICS.keys())),
            "Classify candidates that clearly describe player health, energy, lives, pickups, score, enemies, powerups, levels, death, or game over.",
            "Skip candidates only when they are cosmetic, menus, debug flags, cheats with no gameplay event, or too ambiguous.",
            "Cheats are static writes; mark needs_review true unless the description is very clear.",
            "Examples: Infinite Lives -> life_lost, Infinite Energy or Health -> damage_taken, Infinite Stars/Coins/Rings/Gems -> pickup, Extra Item/Mushroom/Cape/Yoshi/Weapon -> powerup_gained, Enemy No Health -> enemy_defeated, Level <name> -> level_complete.",
        ],
        "output_schema": {
            "triggers": [
                {
                    "candidate_id": "c0",
                    "event": "damage_taken",
                    "condition": "prev > curr",
                    "confidence": 0.0,
                    "needs_review": True,
                    "reason": "short explanation",
                }
            ]
        },
        "game": game_name,
        "candidates": candidates[:120],
    }

    request_body = {
        "model": model,
        "prompt": json.dumps(prompt, ensure_ascii=True),
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.0},
    }

    for attempt in range(2):
        try:
            body = json.dumps(request_body).encode("utf-8")
            req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=180) as response:
                payload = json.loads(response.read().decode("utf-8"))
            parsed = json.loads(payload.get("response", "{}"))
            return validate_model_triggers(parsed, candidates)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
            if attempt == 1:
                raise RuntimeError(f"Ollama classification failed for {game_name}: {exc}") from exc
            time.sleep(1)
    return []


def validate_model_triggers(parsed: Any, candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not isinstance(parsed, dict) or not isinstance(parsed.get("triggers"), list):
        raise ValueError("response did not contain a triggers list")

    candidate_ids = {candidate["candidate_id"] for candidate in candidates}
    valid: list[dict[str, Any]] = []
    for item in parsed["triggers"]:
        if not isinstance(item, dict):
            continue
        candidate_id = str(item.get("candidate_id", ""))
        event = str(item.get("event", ""))
        if candidate_id not in candidate_ids or event not in EVENT_HAPTICS:
            continue
        condition = str(item.get("condition") or EVENT_CONDITIONS[event])
        try:
            confidence = max(0.0, min(1.0, float(item.get("confidence", 0.5))))
        except (TypeError, ValueError):
            confidence = 0.5
        valid.append(
            {
                "candidate_id": candidate_id,
                "event": event,
                "condition": condition,
                "confidence": confidence,
                "needs_review": bool(item.get("needs_review", True)),
                "reason": clean_desc(str(item.get("reason", ""))),
            }
        )
    return valid


def build_trigger(item: dict[str, Any], candidate_by_id: dict[str, dict[str, Any]]) -> dict[str, Any]:
    cand = candidate_by_id[item["candidate_id"]]
    event = item["event"]
    address_label = str(cand["address"]).lower().replace("0x", "")
    trigger_id = f"{event}_{address_label}"
    return {
        "id": trigger_id,
        "event": event,
        "watch": [
            {
                "bank": cand["bank"],
                "address": cand["address"],
                "wram_offset": cand["wram_offset"],
                "size": 1,
            }
        ],
        "condition": item.get("condition") or EVENT_CONDITIONS[event],
        "value_type": "uint8",
        "haptic": EVENT_HAPTICS[event],
        "confidence": item.get("confidence", 0.5),
        "needs_review": item.get("needs_review", True),
        "reason": item.get("reason", ""),
        "source_cheats": [{"code": cand["code"], "desc": cand["desc"]}],
    }


def build_game_document(
    path: Path,
    parser_name: str,
    parsed_codes: list[ParsedCode],
    classified: list[dict[str, Any]],
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    candidate_by_id = {candidate["candidate_id"]: candidate for candidate in candidates}
    triggers = [build_trigger(item, candidate_by_id) for item in classified if item["candidate_id"] in candidate_by_id]
    unsupported = [
        {"code": code.code, "desc": code.desc, "kind": code.kind}
        for code in parsed_codes
        if code.kind != "wram"
    ]
    return {
        "schema": SCHEMA,
        "game": {"name": game_name_from_path(path), "system": "snes"},
        "source": {"cht_file": str(path), "parser": parser_name},
        "triggers": dedupe_triggers(triggers),
        "unsupported_evidence": unsupported,
    }


def dedupe_triggers(triggers: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, int, str]] = set()
    result: list[dict[str, Any]] = []
    for trigger in triggers:
        watch = trigger["watch"][0]
        key = (trigger["event"], int(watch["wram_offset"]), trigger["condition"])
        if key in seen:
            continue
        seen.add(key)
        result.append(trigger)
    return result


def write_summary(summary_path: Path, rows: list[dict[str, Any]]) -> None:
    fieldnames = [
        "game",
        "json_file",
        "parser",
        "total_codes",
        "wram_codes",
        "unsupported_codes",
        "triggers",
        "needs_review",
    ]
    with summary_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def run_self_tests() -> None:
    csv_text = '"disabled","7E0F4864","Always 100 Bonus Points"\n"disabled","0032-6DAD","Gem Mario"'
    kv_text = 'cheat0_code = "7E0DB405+7E0DBE05"\ncheat0_desc = "Infinite lives"\n'
    csv_path = Path("Super Mario World (USA).cht")
    kv_path = Path("Super Mario World [Esp].cht")

    assert parse_raw_code("7E0F4864", "points").wram_offset == 0x0F48
    assert parse_raw_code("7F000101", "x").wram_offset == 0x10001
    assert parse_raw_code("0032-6DAD", "x").kind == "game_genie"
    assert parse_raw_code("7E0D-C2D7", "hyphenated").kind == "game_genie"
    assert parse_csv_style(csv_text)[0] == ("7E0F4864", "Always 100 Bonus Points")
    assert parse_kv_style(kv_text)[0] == ("7E0DB405+7E0DBE05", "Infinite lives")

    parser_name = "csv"
    csv_codes = [parse_raw_code(code, desc) for code, desc in parse_csv_style(csv_text)]
    assert build_game_document(csv_path, parser_name, csv_codes, keyword_classify([candidate_dict(csv_codes[0], 0)]), [candidate_dict(csv_codes[0], 0)])["triggers"]

    kv_codes: list[ParsedCode] = []
    for code, desc in parse_kv_style(kv_text):
        kv_codes.extend(parse_raw_code(part, desc) for part in code.split("+"))
    assert len([code for code in kv_codes if code.kind == "wram"]) == 2
    assert game_name_from_path(kv_path) == "Super Mario World [Esp]"
    print("self-tests passed")


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_tests()
        return 0

    input_dir = Path(args.input)
    output_dir = Path(args.output)
    if not input_dir.is_dir():
        print(f"input folder not found: {input_dir}", file=sys.stderr)
        return 2

    cht_files = sorted(input_dir.glob("*.cht"))
    if args.limit > 0:
        cht_files = cht_files[: args.limit]

    output_dir.mkdir(parents=True, exist_ok=True)
    summary_rows: list[dict[str, Any]] = []

    for path in cht_files:
        parser_name, parsed_codes = parse_cht(path)
        wram_codes = [code for code in parsed_codes if code.kind == "wram"]
        candidates = [candidate_dict(code, idx) for idx, code in enumerate(wram_codes)]
        output_path = output_dir / f"{slugify(game_name_from_path(path))}.json"

        if output_path.exists() and not args.force:
            if args.verbose:
                print(f"skip existing {output_path}")
            doc = json.loads(output_path.read_text(encoding="utf-8"))
        else:
            classified: list[dict[str, Any]]
            if args.no_ollama or not candidates:
                classified = keyword_classify(candidates)

            else:
                try:
                    classified = ollama_classify(args.model, game_name_from_path(path), candidates)
                except RuntimeError as exc:
                    print(f"warning: {exc}; using keyword fallback", file=sys.stderr)
                    classified = keyword_classify(candidates)

            doc = build_game_document(path, parser_name, parsed_codes, classified, candidates)
            output_path.write_text(json.dumps(doc, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

        needs_review = sum(1 for trigger in doc["triggers"] if trigger.get("needs_review"))
        summary_rows.append(
            {
                "game": game_name_from_path(path),
                "json_file": str(output_path),
                "parser": parser_name,
                "total_codes": len(parsed_codes),
                "wram_codes": len(wram_codes),
                "unsupported_codes": len(parsed_codes) - len(wram_codes),
                "triggers": len(doc["triggers"]),
                "needs_review": needs_review,
            }
        )
        if args.verbose:
            print(f"{path.name}: {len(wram_codes)} WRAM codes, {len(doc['triggers'])} triggers")

    summary_name = f"_summary_limit{args.limit}.csv" if args.limit > 0 else "_summary.csv"
    write_summary(output_dir / summary_name, summary_rows)
    print(f"processed {len(summary_rows)} files; wrote {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
