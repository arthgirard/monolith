"""Work out where each string is used, so the translator sees more than a bare word.

"Clear", "Held", "Day" are unanswerable in isolation. The screen file, the enclosing
composable and the call line together say whether a string is a button, a stat label or a
content description, which is most of what a human translator would ask for.
"""

from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

_KT_REF_RE = re.compile(r"R\.string\.(\w+)")
_XML_REF_RE = re.compile(r"@string/(\w+)")
_FUN_RE = re.compile(r"^\s*(?:@\w+\s+)*(?:private |internal |public |)fun\s+(\w+)")

_SNIPPET_LIMIT = 140
_MAX_PER_FIELD = 3


def _enclosing_fun(lines: "list[str]", index: int) -> str:
    for i in range(index, -1, -1):
        match = _FUN_RE.match(lines[i])
        if match:
            return match.group(1)
    return ""


def collect(app_dir: Path) -> "dict[str, dict]":
    hits: "dict[str, dict[str, list[str]]]" = defaultdict(
        lambda: {"screens": [], "components": [], "snippets": []}
    )

    def note(name: str, screen: str, component: str, snippet: str) -> None:
        entry = hits[name]
        for key, value in (("screens", screen), ("components", component), ("snippets", snippet)):
            if value and value not in entry[key] and len(entry[key]) < _MAX_PER_FIELD:
                entry[key].append(value)

    for path in sorted(app_dir.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            for name in _KT_REF_RE.findall(line):
                note(name, path.stem, _enclosing_fun(lines, index), line.strip()[:_SNIPPET_LIMIT])

    for path in sorted(app_dir.rglob("*.xml")):
        if "/res/values" in path.as_posix():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            for name in _XML_REF_RE.findall(line):
                note(name, path.stem, "", line.strip()[:_SNIPPET_LIMIT])

    return {name: dict(value) for name, value in hits.items()}
