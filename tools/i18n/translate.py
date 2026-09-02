#!/usr/bin/env python3
"""Translate Monolith's UI strings with Claude, against a style brief and a real glossary.

values/strings.xml is the source of truth. Every values-<locale>/strings.xml is generated
here. Three things separate this from running the copy through machine translation:

  * every string is sent with its context (section, screen, composable, call site, length
    budget) and a written brief covering the app's voice, so the model translates a button
    as a button and keeps the tone blunt;
  * phrases naming Android Settings toggles are pinned to the wording the device actually
    shows, fetched from AOSP by aosp_glossary.py;
  * a second pass re-reads each translation against the brief and revises what drifted,
    and a deterministic validator checks placeholders, length and do-not-translate terms.

Work is incremental: a string is re-translated only when its English source changes, so
adding one string later costs one small request rather than a full rerun.

    python3 tools/i18n/translate.py --locales de,fr
    python3 tools/i18n/translate.py --dry-run
    python3 tools/i18n/translate.py --force --locales de

There is also an offline mode, for translating in a Claude Code session instead of over the
API. `--emit` writes the same brief, context and glossary out as a file to translate against;
`--apply` reads back a flat {name: text} JSON and runs the identical validator, writer and
state bookkeeping. No API key, no SDK, same checks.

    python3 tools/i18n/translate.py --emit de --out /tmp/de.request.md
    python3 tools/i18n/translate.py --apply de --from /tmp/de.answer.json
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import re
import sys
from pathlib import Path

import strings_res
from usage_context import collect

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
APP_MAIN = ROOT / "app/src/main"
RES = APP_MAIN / "res"
SOURCE_XML = RES / "values/strings.xml"
STATE_PATH = HERE / "state.json"

MODEL = "claude-opus-5"
CHUNK_SIZE = 40
LENGTH_SLACK = 1.35

_PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

TRANSLATION_SCHEMA = {
    "type": "object",
    "properties": {
        "translations": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "text": {"type": "string"},
                    "note": {
                        "type": "string",
                        "description": "Empty unless a choice needs explaining to a reviewer.",
                    },
                },
                "required": ["name", "text", "note"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["translations"],
    "additionalProperties": False,
}

REVIEW_SCHEMA = {
    "type": "object",
    "properties": {
        "reviews": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "verdict": {"type": "string", "enum": ["ok", "revise"]},
                    "text": {
                        "type": "string",
                        "description": "The final string: unchanged when the verdict is ok.",
                    },
                    "reason": {"type": "string"},
                },
                "required": ["name", "verdict", "text", "reason"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["reviews"],
    "additionalProperties": False,
}


# --------------------------------------------------------------------------- helpers


def digest(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]


def placeholders(text: str) -> "list[str]":
    return sorted(_PLACEHOLDER_RE.findall(text))


def budget(name: str, text: str) -> int:
    """Character budget for a translation.

    Visible copy sits in buttons, rows and a widget, so it gets little room. Content
    descriptions and channel descriptions are read aloud or shown in Settings, where length
    costs nothing, so they get enough room not to force awkward phrasing.
    """
    slack = 2.0 if ("a11y" in name or name.endswith("_description")) else LENGTH_SLACK
    return max(len(text) + 12, round(len(text) * slack))


def load_json(path: Path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- prompts


def system_prompt(locale: dict, glossary: dict, aosp: dict) -> list:
    brief = (HERE / "brief.md").read_text(encoding="utf-8")

    lines = [
        f"# Target locale\n\n{locale['name']} (`{locale['tag']}`)",
        "\n# Do not translate\n",
        "Leave these exactly as written, in every string:\n",
    ]
    lines += [f"- {term}" for term in glossary["do_not_translate"]]

    labels = aosp.get(locale["tag"], {})
    if labels:
        lines.append("\n# Android Settings labels\n")
        lines.append(
            "These are the labels this locale's Android build actually shows, taken from "
            "AOSP. When a string points the user at one of them, use this wording verbatim. "
            "Where two variants are listed, Android versions differ; use the first.\n"
        )
        for english, variants in labels.items():
            lines.append(f"- {english} -> {' | '.join(variants)}")

    overrides = glossary.get("manual_overrides", {}).get(locale["tag"], {})
    if overrides:
        lines.append("\n# Required renderings\n")
        lines += [f"- {source} -> {target}" for source, target in overrides.items()]

    return [
        {"type": "text", "text": brief},
        {"type": "text", "text": "\n".join(lines), "cache_control": {"type": "ephemeral"}},
    ]


def build_items(entries: "list[strings_res.StringRes]", context: dict) -> list:
    items = []
    for entry in entries:
        usage = context.get(entry.name, {})
        item = {
            "name": entry.name,
            "source": entry.text,
            "section": entry.section,
            "max_chars": budget(entry.name, entry.text),
        }
        if usage.get("screens"):
            item["screens"] = usage["screens"]
        if usage.get("components"):
            item["used_in"] = usage["components"]
        if usage.get("snippets"):
            item["call_sites"] = usage["snippets"]
        if placeholders(entry.text):
            item["placeholders"] = placeholders(entry.text)
        items.append(item)
    return items


def translation_prompt(items: list, reference: "dict[str, str]", sources: "dict[str, str]") -> str:
    parts = []
    if reference:
        sample = list(reference.items())[-60:]
        parts.append(
            "Wording already settled for this locale. Reuse these choices so the app reads "
            "as one voice:\n"
            + json.dumps(
                [{"source": sources[name], "translation": text} for name, text in sample if name in sources],
                ensure_ascii=False,
                indent=1,
            )
        )
    parts.append(
        "Translate every item below. Return one entry per item, same `name`, in the same "
        "order. Write plain text only.\n\n"
        + json.dumps(items, ensure_ascii=False, indent=1)
    )
    return "\n\n".join(parts)


def review_prompt(items: list, translations: "dict[str, str]") -> str:
    payload = []
    for item in items:
        row = {
            "name": item["name"],
            "source": item["source"],
            "translation": translations.get(item["name"], ""),
            "section": item["section"],
            "max_chars": item["max_chars"],
        }
        if "call_sites" in item:
            row["call_sites"] = item["call_sites"]
        payload.append(row)
    return (
        "You are reviewing a first-pass translation against the brief, not re-translating "
        "it from scratch. For each item, judge whether the translation carries the source's "
        "intent and register, respects the placeholders, obeys the Android Settings labels "
        "and do-not-translate list, and fits `max_chars`. Look hardest for the failure this "
        "app is prone to: copy that is grammatically fine but has been softened into polite, "
        "generic product English, losing the blunt tone.\n\n"
        "Return `ok` with the translation unchanged when it is right. Return `revise` with a "
        "fixed string and a one-line reason when it is not. Do not rewrite for taste alone.\n\n"
        + json.dumps(payload, ensure_ascii=False, indent=1)
    )


def pinned_labels(locale: dict, aosp: dict) -> "frozenset[str]":
    """Every Settings label this locale is allowed to render verbatim."""
    return frozenset(
        variant for variants in aosp.get(locale["tag"], {}).values() for variant in variants
    )


# --------------------------------------------------------------------------- offline mode


def emit(locale: dict, entries, context, glossary, aosp, state, args, out: Path) -> int:
    """Write everything a translator needs for one locale to a single file.

    Same brief, same per-string context, same glossary as the API path builds. Used when the
    translating is done in a session rather than by `call()`.
    """
    translatable = [entry for entry in entries if entry.translatable]
    existing = {item.name: item.text for item in strings_res.read(RES / locale["res"] / "strings.xml")}
    known = state.get(locale["tag"], {})
    stale = [
        entry
        for entry in translatable
        if args.force or entry.name not in existing or known.get(entry.name) != digest(entry.text)
    ]
    if args.limit:
        stale = stale[: args.limit]

    system = "\n\n".join(block["text"] for block in system_prompt(locale, glossary, aosp))
    items = build_items(stale, context)
    body = [
        system,
        "\n# Already settled for this locale\n",
        json.dumps(
            [{"source": sources, "translation": existing[name]}
             for name, sources in ((e.name, e.text) for e in translatable)
             if name in existing and name not in {s.name for s in stale}][-60:],
            ensure_ascii=False,
            indent=1,
        )
        if existing
        else "(nothing yet)",
        "\n# Translate these\n",
        "Answer with a flat JSON object mapping each `name` to its translated text, plain "
        "text only, then feed it back through `--apply`.\n",
        json.dumps(items, ensure_ascii=False, indent=1),
    ]
    out.write_text("\n".join(body), encoding="utf-8")
    print(f"{locale['tag']}: {len(stale)} strings -> {out}")
    return len(stale)


def apply_answers(locale: dict, entries, glossary, aosp, state, path: Path) -> "list[str]":
    """Validate a {name: text} answer file and write the locale's strings.xml."""
    answers = json.loads(path.read_text(encoding="utf-8"))
    by_name = {entry.name: entry for entry in entries if entry.translatable}
    target = RES / locale["res"] / "strings.xml"

    warnings = []
    unknown = sorted(set(answers) - set(by_name))
    if unknown:
        warnings.append(f"[{locale['tag']}] not translatable strings in answer: {', '.join(unknown)}")

    merged = {item.name: item.text for item in strings_res.read(target)}
    for name, text in answers.items():
        if name not in by_name:
            continue
        for problem in validate(by_name[name], text, glossary, pinned_labels(locale, aosp)):
            warnings.append(f"[{locale['tag']}] {name}: {problem}")
        merged[name] = text

    missing = [name for name in by_name if name not in merged]
    if missing:
        warnings.append(f"[{locale['tag']}] {len(missing)} strings still untranslated")

    strings_res.write(target, entries, merged, locale["tag"])
    state[locale["tag"]] = {
        name: digest(entry.text) for name, entry in by_name.items() if name in merged
    }
    print(f"[{locale['tag']}] wrote {target.relative_to(ROOT)} ({len(merged)} strings)")
    return warnings


# --------------------------------------------------------------------------- api


def call(client, system, prompt: str, schema: dict) -> dict:
    with client.messages.stream(
        model=MODEL,
        max_tokens=32000,
        system=system,
        thinking={"type": "adaptive"},
        output_config={
            "effort": "high",
            "format": {"type": "json_schema", "schema": schema},
        },
        messages=[{"role": "user", "content": prompt}],
    ) as stream:
        message = stream.get_final_message()

    if message.stop_reason == "refusal":
        raise RuntimeError(f"request refused: {message.stop_details}")
    text = next(block.text for block in message.content if block.type == "text")
    return json.loads(text)


# --------------------------------------------------------------------------- validation


def validate(
    entry: strings_res.StringRes,
    text: str,
    glossary: dict,
    pinned: "frozenset[str]" = frozenset(),
) -> "list[str]":
    problems = []
    if not text.strip():
        problems.append("empty")
        return problems
    if placeholders(entry.text) != placeholders(text):
        problems.append(
            f"placeholders {placeholders(entry.text)} became {placeholders(text)}"
        )
    limit = budget(entry.name, entry.text)
    if len(text) > limit and text not in pinned:
        # A string that is exactly an Android Settings label is as short as it is allowed to
        # be: matching the device wins over the length heuristic, so it is not a defect.
        problems.append(f"{len(text)} chars over the {limit} budget")
    for term in glossary["do_not_translate"]:
        if term in entry.text and term not in text:
            problems.append(f"dropped {term!r}")
    if "\\" in text or "&amp;" in text or "&lt;" in text:
        problems.append("contains escaping the writer should not have added")
    if text.count("<") != entry.text.count("<"):
        problems.append("stray angle bracket")
    return problems


# --------------------------------------------------------------------------- per locale


def run_locale(client, locale, entries, context, glossary, aosp, state, args) -> dict:
    tag = locale["tag"]
    target_path = RES / locale["res"] / "strings.xml"

    translatable = [entry for entry in entries if entry.translatable]
    existing = {item.name: item.text for item in strings_res.read(target_path)}
    known = state.get(tag, {})

    stale = [
        entry
        for entry in translatable
        if args.force or entry.name not in existing or known.get(entry.name) != digest(entry.text)
    ]
    if args.limit:
        stale = stale[: args.limit]

    fresh = {name: text for name, text in existing.items() if name not in {e.name for e in stale}}
    sources = {entry.name: entry.text for entry in translatable}

    if not stale:
        print(f"[{tag}] up to date ({len(fresh)} strings)")
        return {"locale": tag, "translated": 0, "warnings": []}
    if args.dry_run:
        print(f"[{tag}] would translate {len(stale)} of {len(translatable)} strings")
        for entry in stale[:10]:
            print(f"        {entry.name}: {entry.text[:60]}")
        return {"locale": tag, "translated": 0, "warnings": []}

    system = system_prompt(locale, glossary, aosp)
    warnings: "list[str]" = []
    produced: "dict[str, str]" = {}

    for start in range(0, len(stale), args.chunk_size):
        chunk = stale[start : start + args.chunk_size]
        items = build_items(chunk, context)
        reference = {**fresh, **produced}

        result = call(client, system, translation_prompt(items, reference, sources), TRANSLATION_SCHEMA)
        chunk_out = {row["name"]: row["text"] for row in result["translations"]}

        if not args.no_critic:
            review = call(client, system, review_prompt(items, chunk_out), REVIEW_SCHEMA)
            for row in review["reviews"]:
                if row["verdict"] == "revise" and row["text"].strip():
                    warnings.append(f"[{tag}] {row['name']}: revised, {row['reason']}")
                    chunk_out[row["name"]] = row["text"]

        pinned = pinned_labels(locale, aosp)
        for entry in chunk:
            text = chunk_out.get(entry.name)
            if text is None:
                warnings.append(f"[{tag}] {entry.name}: no translation returned")
                continue
            for problem in validate(entry, text, glossary, pinned):
                warnings.append(f"[{tag}] {entry.name}: {problem}")
            produced[entry.name] = text

        print(f"[{tag}] {min(start + args.chunk_size, len(stale))}/{len(stale)}")

    merged = {**fresh, **produced}
    strings_res.write(target_path, entries, merged, tag)
    state[tag] = {
        entry.name: digest(entry.text) for entry in translatable if entry.name in merged
    }
    print(f"[{tag}] wrote {target_path.relative_to(ROOT)} ({len(merged)} strings)")
    return {"locale": tag, "translated": len(produced), "warnings": warnings}


# --------------------------------------------------------------------------- main


def main() -> int:
    config = load_json(HERE / "locales.json")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--locales", help="comma-separated tags; default is all of locales.json")
    parser.add_argument("--force", action="store_true", help="re-translate everything")
    parser.add_argument("--dry-run", action="store_true", help="report what is stale, call nothing")
    parser.add_argument("--no-critic", action="store_true", help="skip the review pass")
    parser.add_argument("--strict", action="store_true", help="exit non-zero on any warning")
    parser.add_argument("--limit", type=int, default=0, help="translate at most N strings per locale")
    parser.add_argument("--chunk-size", type=int, default=CHUNK_SIZE)
    parser.add_argument("--workers", type=int, default=4, help="locales translated in parallel")
    parser.add_argument("--emit", metavar="LOCALE", help="offline: write one locale's brief and strings to --out")
    parser.add_argument("--out", type=Path, help="destination for --emit")
    parser.add_argument("--apply", metavar="LOCALE", help="offline: read a {name: text} answer file")
    parser.add_argument("--from", dest="answers", type=Path, help="answer file for --apply")
    args = parser.parse_args()

    if args.emit and not args.out:
        parser.error("--emit needs --out")
    if args.apply and not args.answers:
        parser.error("--apply needs --from")
    if args.emit or args.apply:
        args.locales = args.emit or args.apply

    wanted = set(args.locales.split(",")) if args.locales else None
    locales = [item for item in config["locales"] if wanted is None or item["tag"] in wanted]
    if wanted and len(locales) != len(wanted):
        missing = wanted - {item["tag"] for item in locales}
        parser.error(f"unknown locale(s): {', '.join(sorted(missing))}")

    entries = strings_res.read(SOURCE_XML)
    context = collect(APP_MAIN)
    glossary = load_json(HERE / "glossary.json")
    aosp = load_json(HERE / "glossary.generated.json", {})
    if not aosp and not args.dry_run:
        print("warning: no glossary.generated.json, run aosp_glossary.py first", file=sys.stderr)
    state = load_json(STATE_PATH, {})

    if args.emit:
        emit(locales[0], entries, context, glossary, aosp, state, args, args.out)
        return 0

    if args.apply:
        warnings = apply_answers(locales[0], entries, glossary, aosp, state, args.answers)
        STATE_PATH.write_text(
            json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        for line in warnings:
            print(f"  {line}")
        print(f"\n{len(warnings)} to review")
        return 1 if warnings and args.strict else 0

    client = None
    if not args.dry_run:
        import anthropic

        client = anthropic.Anthropic(timeout=900.0)

    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {
            pool.submit(run_locale, client, locale, entries, context, glossary, aosp, state, args): locale
            for locale in locales
        }
        for future in concurrent.futures.as_completed(futures):
            tag = futures[future]["tag"]
            try:
                results.append(future.result())
            except Exception as error:  # one bad locale should not lose the others
                print(f"[{tag}] failed: {error}", file=sys.stderr)
                results.append({"locale": tag, "translated": 0, "warnings": [f"[{tag}] {error}"]})

    if not args.dry_run:
        STATE_PATH.write_text(json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    warnings = [line for result in sorted(results, key=lambda r: r["locale"]) for line in result["warnings"]]
    print()
    for result in sorted(results, key=lambda r: r["locale"]):
        print(f"{result['locale']:>6}: {result['translated']} translated, {len(result['warnings'])} to review")
    if warnings:
        print("\nReview:")
        for line in warnings:
            print(f"  {line}")
    return 1 if warnings and args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
