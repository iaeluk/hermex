#!/usr/bin/env python3
"""Wrap direct Compose Text literals that exist in the iOS localization catalog."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "iaeluk" / "hermex" / "ui"
CATALOG = ROOT / "HermesMobile" / "Resources" / "Localizable.xcstrings"
ALIASES = ROOT / "ci" / "android_localization_aliases.json"
SUPPLEMENTAL = ROOT / "ci" / "android_supplemental_localizations.json"
IMPORT = "import com.iaeluk.hermex.ui.localization.localizedString"
LOCALIZABLE_CALLS = (
    "Text",
    "HermexPillButton",
    "HermexSelectorPill",
    "HermexIconButton",
    "SelectorRow",
    "OnboardingActionButton",
    "OnboardingTextField",
    "OnboardingStatusBanner",
    "AttachmentPreviewUnavailable",
    "ComposerInlineIconButton",
    "TranscriptStatusPill",
    "PanelHeaderIconAction",
    "SettingsAccessoryRow",
    "SettingsPickerRow",
    "SettingsPickerSummaryRow",
    "SettingsToggleRow",
    "SettingsInfoRow",
    "SettingsActionRow",
    "DetailLine",
    "SwipeAction",
    "SessionStateBadge",
    "PanelEmptyCard",
    "PanelSectionLabel",
    "PanelSubsection",
    "AnalyticsMetricRow",
    "ActivityPanelRow",
    "ToolDetailSection",
    "MessageActionSheetRow",
)
NAMED_TEXT_ARGUMENTS = ("text", "label", "title", "message", "description", "contentDescription")
ALLOWED_UNLOCALIZED_LITERALS = {
    "!", "#FFD700", "+", "-", "100.64.0.1:8787", ">", "CF-Access-Client-Id: ...",
    "IMG", "REMOTE\nHTTP blocked", "REMOTE\nTap to load", "X", "doc", "●", "✎", "✓", "✦",
}


def decode_kotlin_string(raw: str) -> str | None:
    if "$" in raw:
        return None
    result: list[str] = []
    index = 0
    escapes = {"n": "\n", "r": "\r", "t": "\t", '"': '"', "'": "'", "\\": "\\"}
    while index < len(raw):
        character = raw[index]
        if character != "\\":
            result.append(character)
            index += 1
            continue
        index += 1
        if index >= len(raw):
            return None
        escaped = raw[index]
        if escaped == "u" and index + 4 < len(raw):
            try:
                result.append(chr(int(raw[index + 1 : index + 5], 16)))
            except ValueError:
                return None
            index += 5
            continue
        replacement = escapes.get(escaped)
        if replacement is None:
            return None
        result.append(replacement)
        index += 1
    return "".join(result)


def string_end(source: str, start: int) -> int | None:
    if source.startswith('"""', start):
        return None
    index = start + 1
    while index < len(source):
        if source[index] == "\\":
            index += 2
            continue
        if source[index] == '"':
            return index + 1
        index += 1
    return None


def skip_space(source: str, index: int) -> int:
    while index < len(source) and source[index].isspace():
        index += 1
    return index


def literal_start_after_text_call(source: str, open_paren: int) -> int | None:
    index = skip_space(source, open_paren + 1)
    for argument_name in NAMED_TEXT_ARGUMENTS:
        if not source.startswith(argument_name, index):
            continue
        end = index + len(argument_name)
        if end == len(source) or not (source[end].isalnum() or source[end] == "_"):
            end = skip_space(source, end)
            if end < len(source) and source[end] == "=":
                index = skip_space(source, end + 1)
        break
    return index if index < len(source) and source[index] == '"' else None


def text_literal_edits(source: str, catalog_keys: set[str]) -> list[tuple[int, int, str]]:
    edits: list[tuple[int, int, str]] = []
    index = 0
    state = "code"
    while index < len(source):
        if state == "line_comment":
            if source[index] == "\n":
                state = "code"
            index += 1
            continue
        if state == "block_comment":
            if source.startswith("*/", index):
                state = "code"
                index += 2
            else:
                index += 1
            continue
        if state == "string":
            if source[index] == "\\":
                index += 2
            elif source[index] == '"':
                state = "code"
                index += 1
            else:
                index += 1
            continue
        if state == "triple_string":
            if source.startswith('"""', index):
                state = "code"
                index += 3
            else:
                index += 1
            continue
        if source.startswith("//", index):
            state = "line_comment"
            index += 2
            continue
        if source.startswith("/*", index):
            state = "block_comment"
            index += 2
            continue
        if source.startswith('"""', index):
            state = "triple_string"
            index += 3
            continue
        if source[index] == '"':
            state = "string"
            index += 1
            continue
        call_name = next((name for name in LOCALIZABLE_CALLS if source.startswith(name, index)), None)
        if call_name is not None:
            before_ok = index == 0 or not (source[index - 1].isalnum() or source[index - 1] == "_")
            after = index + len(call_name)
            after_ok = after == len(source) or not (source[after].isalnum() or source[after] == "_")
            open_paren = skip_space(source, after)
            if before_ok and after_ok and open_paren < len(source) and source[open_paren] == "(":
                literal_start = literal_start_after_text_call(source, open_paren)
                if literal_start is not None:
                    literal_end = string_end(source, literal_start)
                    if literal_end is not None:
                        english = decode_kotlin_string(source[literal_start + 1 : literal_end - 1])
                        if english in catalog_keys:
                            literal = source[literal_start:literal_end]
                            edits.append((literal_start, literal_end, f"localizedString({literal})"))
                            index = literal_end
                            continue
        index += 1
    return edits


def add_import(source: str) -> str:
    if IMPORT in source:
        return source
    lines = source.splitlines(keepends=True)
    import_indices = [index for index, line in enumerate(lines) if line.startswith("import ")]
    insert_at = import_indices[-1] + 1 if import_indices else 1
    lines.insert(insert_at, IMPORT + "\n")
    return "".join(lines)


def unknown_ui_copy_literals(source: str, catalog_keys: set[str]) -> set[str]:
    argument_pattern = "(?:" + "|".join(NAMED_TEXT_ARGUMENTS) + ")"
    missing: set[str] = set()
    for call_name in LOCALIZABLE_CALLS:
        pattern = re.compile(
            rf"\b{re.escape(call_name)}\s*\(\s*(?:{argument_pattern}\s*=\s*)?\"((?:\\.|[^\"\\])*)\"",
            re.DOTALL,
        )
        for match in pattern.finditer(source):
            value = decode_kotlin_string(match.group(1))
            if value is not None and value not in catalog_keys and value not in ALLOWED_UNLOCALIZED_LITERALS:
                missing.add(value)
    return missing


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Fail if localizable Compose literals remain.")
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    catalog_keys = set(catalog.get("strings", {}))
    alias_catalog = json.loads(ALIASES.read_text(encoding="utf-8"))
    catalog_keys.update(alias_catalog.get("aliases", {}))
    catalog_keys.update(alias_catalog.get("platform_aliases", {}))
    catalog_keys.update(json.loads(SUPPLEMENTAL.read_text(encoding="utf-8")))
    changed_files = 0
    changed_literals = 0
    stale_files: list[Path] = []
    unknown_literals: dict[Path, set[str]] = {}
    for path in sorted(UI_ROOT.rglob("*.kt")):
        if "localization" in path.parts:
            continue
        source = path.read_text(encoding="utf-8")
        if args.check:
            missing = unknown_ui_copy_literals(source, catalog_keys)
            if missing:
                unknown_literals[path] = missing
        edits = text_literal_edits(source, catalog_keys)
        if not edits:
            continue
        if args.check:
            stale_files.append(path)
            changed_literals += len(edits)
            continue
        for start, end, replacement in reversed(edits):
            source = source[:start] + replacement + source[end:]
        source = add_import(source)
        path.write_text(source, encoding="utf-8")
        changed_files += 1
        changed_literals += len(edits)
    if args.check and stale_files:
        relative = ", ".join(str(path.relative_to(ROOT)) for path in stale_files)
        raise SystemExit(f"Found {changed_literals} unmigrated localizable Compose literals in: {relative}")
    if args.check and unknown_literals:
        details = "; ".join(
            f"{path.relative_to(ROOT)}: {sorted(values)}" for path, values in unknown_literals.items()
        )
        raise SystemExit(f"Found unregistered Android UI copy: {details}")
    if args.check:
        print("Verified that all catalog-backed Compose literals are localized.")
    else:
        print(f"Localized {changed_literals} direct Text literals across {changed_files} Kotlin files.")


if __name__ == "__main__":
    main()
