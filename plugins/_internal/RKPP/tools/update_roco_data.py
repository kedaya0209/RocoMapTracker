#!/usr/bin/env python3
# Copyright (C) 2026 花吹雪又一年
#
# This file is part of Rock Kingdom Protocol Parser (RKPP).
# Licensed under the GNU Affero General Public License v3.0 only (AGPL-3.0-only).

"""Refresh RKPP data files from RocoMITMServer and world-data exports."""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path
from typing import Any


SCALAR_WIRE = {
    "int32": 0,
    "int64": 0,
    "uint32": 0,
    "uint64": 0,
    "sint32": 0,
    "sint64": 0,
    "bool": 0,
    "fixed64": 1,
    "sfixed64": 1,
    "double": 1,
    "string": 2,
    "bytes": 2,
    "fixed32": 5,
    "sfixed32": 5,
    "float": 5,
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_label(path: Path) -> str:
    return path.name or str(path)


def norm_ref(name: str | None) -> str:
    text = str(name or "").strip()
    if text.startswith("."):
        text = text[1:]
    for prefix in ("Next.", "dataconfig."):
        if text.startswith(prefix):
            return text[len(prefix) :]
    return text


def short_name(name: str | None) -> str:
    text = norm_ref(name)
    return text.rsplit(".", 1)[-1] if text else ""


def enum_inner(type_name: str) -> str:
    return type_name.split("<", 1)[1].rsplit(">", 1)[0].strip()


def wire_for(type_name: str, *, is_message: bool, is_enum: bool) -> int:
    if is_message:
        return 2
    if is_enum:
        return 0
    return SCALAR_WIRE.get(type_name, 2)


def convert_field(field: dict[str, Any]) -> dict[str, Any]:
    raw_type = str(field.get("type") or "")
    is_message = raw_type == "message"
    is_enum = raw_type.startswith("enum<") and raw_type.endswith(">")

    if is_message:
        type_name = norm_ref(field.get("ref"))
    elif is_enum:
        type_name = norm_ref(enum_inner(raw_type))
    else:
        type_name = raw_type

    out: dict[str, Any] = {
        "name": field.get("name") or f"field_{field.get('no')}",
        "type": type_name,
        "wire": wire_for(type_name, is_message=is_message, is_enum=is_enum),
    }
    if field.get("desc"):
        out["desc"] = field["desc"]
    if field.get("repeated"):
        out["repeated"] = True
    if field.get("packed"):
        out["packed"] = True
    if is_message:
        out["message"] = True
    if is_enum:
        out["enum"] = True
    return out


def add_aliases(items: dict[str, dict[str, Any]], raw_names: list[str]) -> dict[str, dict[str, Any]]:
    short_counts = Counter(short_name(name) for name in raw_names)
    out = dict(items)
    for raw_name in raw_names:
        key = norm_ref(raw_name)
        alias = short_name(raw_name)
        if alias and alias != key and short_counts[alias] == 1:
            out.setdefault(alias, out[key])
    return out


def convert_messages(data: dict[str, Any]) -> dict[str, Any]:
    raw_messages: dict[str, Any] = data.get("messages") or {}
    raw_enums: dict[str, Any] = data.get("enums") or {}

    message_items: dict[str, dict[str, Any]] = {}
    for full, schema in raw_messages.items():
        key = norm_ref(full)
        fields = {
            str(field.get("no")): convert_field(field)
            for field in (schema.get("fields") or [])
            if field.get("no") is not None
        }
        message_items[key] = {
            "meta": {"parent": "-", "opcode": "-", "source_full_name": full},
            "fields": fields,
        }

    enum_items: dict[str, dict[str, Any]] = {}
    for full, schema in raw_enums.items():
        values = schema.get("values") or {}
        reverse = {str(num): name for name, num in values.items()}
        enum_items[norm_ref(full)] = {"values": reverse}

    return {
        "messages": add_aliases(message_items, list(raw_messages)),
        "enums": add_aliases(enum_items, list(raw_enums)),
    }


def convert_opcodes(data: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    opcodes: dict[str, Any] = data.get("opcodes") or {}
    override_opcodes: dict[str, Any] = overrides.get("opcodes") or {}
    out: dict[str, dict[str, Any]] = {}

    for hex_key, meta in sorted(opcodes.items(), key=lambda kv: int(kv[0], 16)):
        opcode = int(hex_key, 16)
        merged = dict(meta)
        merged.update(override_opcodes.get(hex_key, {}))
        proto_name = merged.get("decode_as") or merged.get("proto_name")
        item = {
            "name": merged.get("name") or short_name(proto_name),
            "full_name": proto_name or "",
            "decode_as": norm_ref(proto_name),
            "desc_cn": merged.get("desc") or merged.get("desc_cn") or "",
            "direction": merged.get("direction") or "unknown",
            "category": merged.get("category") or "unknown",
            "hex": f"0x{opcode:04X}",
            "schema_status": merged.get("schema_status") or "",
        }
        if merged.get("pair"):
            item["pair"] = merged["pair"]
        out[str(opcode)] = {k: v for k, v in item.items() if v not in (None, "")}

    return out


def apply_override_messages(schema: dict[str, Any], overrides: dict[str, Any]) -> None:
    for name, msg_schema in (overrides.get("messages") or {}).items():
        key = norm_ref(name)
        fields = {
            str(field.get("no")): convert_field(field)
            for field in (msg_schema.get("fields") or [])
            if field.get("no") is not None
        }
        schema["messages"][key] = {
            "meta": {"parent": "-", "opcode": "-", "source_full_name": name, "source": "decoder_overrides"},
            "fields": fields,
        }

    for hex_key, override in (overrides.get("opcodes") or {}).items():
        fields = override.get("fields")
        if not fields:
            continue
        name = override.get("decode_as") or override.get("name") or f"Opcode_{hex_key}"
        key = norm_ref(name)
        schema["messages"][key] = {
            "meta": {"parent": "-", "opcode": str(int(hex_key, 16)), "source": "decoder_overrides"},
            "fields": {
                str(field.get("no")): convert_field(field)
                for field in fields
                if field.get("no") is not None
            },
        }


def roco_rows(path: Path) -> dict[str, Any]:
    data = read_json(path)
    rows = data.get("RocoDataRows")
    if not isinstance(rows, dict):
        raise ValueError(f"{path} does not contain RocoDataRows")
    return rows


def write_lookup_csv(path: Path, rows: list[tuple[int, str]], id_field: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=[id_field, "name"])
        writer.writeheader()
        for key, name in sorted(rows):
            writer.writerow({id_field: key, "name": name})


def build_lookup_csvs(world_root: Path, data_dir: Path) -> dict[str, int]:
    bin_dir = world_root / "Bin" / "BinDataCompressed"

    attr_rows = []
    for row in roco_rows(bin_dir / "ATTRIBUTE_CONF.json").values():
        key = row.get("attribute", row.get("id"))
        name = row.get("attribute_name") or row.get("editor_name") or row.get("name")
        if isinstance(key, int) and name:
            attr_rows.append((key, str(name)))

    pet_rows = []
    for row in roco_rows(bin_dir / "PETBASE_CONF.json").values():
        key = row.get("id")
        name = row.get("name")
        if isinstance(key, int) and name:
            pet_rows.append((key, str(name)))

    skill_rows = []
    for row in roco_rows(bin_dir / "SKILL_CONF.json").values():
        key = row.get("id")
        name = row.get("name")
        if isinstance(key, int) and name:
            skill_rows.append((key, str(name)))

    write_lookup_csv(data_dir / "Attr.csv", attr_rows, "attr_id")
    write_lookup_csv(data_dir / "Pet.csv", pet_rows, "pet_id")
    write_lookup_csv(data_dir / "Skill.csv", skill_rows, "skill_id")
    return {"attr": len(attr_rows), "pet": len(pet_rows), "skill": len(skill_rows)}


def refresh(mitm_root: Path, world_root: Path, out_data_dir: Path) -> dict[str, Any]:
    config_dir = mitm_root / "config"
    messages = read_json(config_dir / "messages.json")
    opcodes = read_json(config_dir / "opcodes.json")
    overrides_path = config_dir / "decoder_overrides.json"
    overrides = read_json(overrides_path) if overrides_path.exists() else {}

    schema = convert_messages(messages)
    apply_override_messages(schema, overrides)
    opcode_map = convert_opcodes(opcodes, overrides)

    header = {
        "_generated_by": "tools/update_roco_data.py",
        "_source": {
            "mitm_root": source_label(mitm_root),
            "world_root": source_label(world_root),
            "messages": "config/messages.json",
            "opcodes": "config/opcodes.json",
            "decoder_overrides": "config/decoder_overrides.json",
            "world_data": "Bin/BinDataCompressed",
        },
    }
    schema = {**header, **schema}
    opcode_map = {**header, **opcode_map}

    write_json(out_data_dir / "proto_schema.json", schema)
    write_json(out_data_dir / "opcode.json", opcode_map)
    lookup_counts = build_lookup_csvs(world_root, out_data_dir)

    return {
        "opcodes": len([k for k in opcode_map if k.isdigit()]),
        "messages": len(schema.get("messages") or {}),
        "enums": len(schema.get("enums") or {}),
        **lookup_counts,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Refresh RKPP Data files from Roco data sources.")
    parser.add_argument("--mitm-root", type=Path, required=True)
    parser.add_argument("--world-root", type=Path, required=True)
    parser.add_argument("--out-data-dir", type=Path, default=Path(__file__).resolve().parents[1] / "Data")
    args = parser.parse_args()

    stats = refresh(args.mitm_root.resolve(), args.world_root.resolve(), args.out_data_dir.resolve())
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
