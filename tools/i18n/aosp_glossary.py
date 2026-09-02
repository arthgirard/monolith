"""Pull the real localised Android Settings labels out of AOSP.

Several onboarding strings tell the user to go find a toggle: "Display Over Other Apps",
"Allow restricted settings", "App Info". A translator inventing wording for those is worse
than no translation at all, because the user then hunts for a label their phone does not
show. So instead of trusting the model, look the labels up.

The lookup is name-free on purpose. Rather than guessing which AOSP resource holds a phrase,
fetch the English Settings strings, find every resource whose English value matches a phrase
we care about, then read those same resource names out of each locale's file.

Writes glossary.generated.json, which is committed so translate.py runs offline.
"""

from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

SOURCE = (
    "https://android.googlesource.com/platform/packages/apps/Settings/"
    "+/refs/heads/main/res/{qualifier}/strings.xml?format=TEXT"
)

_STRING_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)
_TAG_RE = re.compile(r"<[^>]+>")


def _fetch(qualifier: str) -> "dict[str, str]":
    url = SOURCE.format(qualifier=qualifier)
    with urllib.request.urlopen(url, timeout=60) as response:
        payload = base64.b64decode(response.read()).decode("utf-8")
    out: "dict[str, str]" = {}
    for name, body in _STRING_RE.findall(payload):
        text = _TAG_RE.sub("", body)
        text = text.replace("\\'", "'").replace("&amp;", "&").strip()
        # AOSP wraps many values in literal double quotes to preserve whitespace; aapt strips
        # them, so we do too, otherwise every label comes back quoted.
        if len(text) > 1 and text.startswith('"') and text.endswith('"'):
            text = text[1:-1].strip()
        text = text.replace('\\"', '"')
        if text and "%" not in text:
            out[name] = text
    return out


def _normalise(text: str) -> str:
    return " ".join(text.lower().replace("&", "and").split())


def build() -> "dict[str, dict[str, list[str]]]":
    config = json.loads((HERE / "locales.json").read_text(encoding="utf-8"))
    phrases = json.loads((HERE / "glossary.json").read_text(encoding="utf-8"))["system_label_phrases"]
    wanted = {_normalise(phrase): phrase for phrase in phrases}

    english = _fetch("values")
    # phrase -> the AOSP resource names whose English value is that phrase
    names_for: "dict[str, list[str]]" = {phrase: [] for phrase in phrases}
    for name, text in english.items():
        phrase = wanted.get(_normalise(text))
        if phrase:
            names_for[phrase].append(name)

    missing = [phrase for phrase, names in names_for.items() if not names]
    if missing:
        print(f"  no AOSP resource matches: {', '.join(missing)}")

    generated: "dict[str, dict[str, list[str]]]" = {}
    for locale in config["locales"]:
        try:
            localised = _fetch(locale["aosp"])
        except urllib.error.HTTPError as error:
            print(f"  {locale['tag']}: skipped ({error.code} on {locale['aosp']})")
            continue
        table: "dict[str, list[str]]" = {}
        for phrase, names in names_for.items():
            variants = []
            for name in names:
                value = localised.get(name)
                if value and value not in variants:
                    variants.append(value)
            if variants:
                table[phrase] = variants
        generated[locale["tag"]] = table
        print(f"  {locale['tag']}: {len(table)}/{len(phrases)} labels")
    return generated


def main() -> None:
    print("Fetching AOSP Settings labels...")
    generated = build()
    out = HERE / "glossary.generated.json"
    out.write_text(json.dumps(generated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out.relative_to(HERE.parents[1])}")


if __name__ == "__main__":
    main()
