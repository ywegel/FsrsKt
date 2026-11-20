"""
Writes the fixtures as readable JSON with small diffs: every list item starts on its own line.
"""

import json
from collections.abc import Callable
from pathlib import Path


def format_json_line(item: dict) -> str:
    return json.dumps(item, allow_nan=False, separators=(", ", ": "))


def format_review_sequence(sequence: dict) -> str:
    """Formats a review sequence with its other fields on the first line and each of its steps on its own line."""
    fields = "".join(
        f"{json.dumps(key)}: {json.dumps(value, allow_nan=False, separators=(', ', ': '))}, "
        for key, value in sequence.items()
        if key != "steps"
    )
    steps = ",\n".join(f"      {format_json_line(step)}" for step in sequence["steps"])
    return f'{{{fields}"steps": [\n{steps}\n    ]}}'


def write_json(
    path: Path,
    header: dict,
    list_key: str,
    items: list[dict],
    format_item: Callable[[dict], str] = format_json_line,
) -> None:
    """Writes the header fields indented and each list item starting on its own line."""
    lines = ["{"]
    for key, value in header.items():
        lines.append(f"  {json.dumps(key)}: {json.dumps(value, allow_nan=False)},")
    item_lines = ",\n".join(f"    {format_item(item)}" for item in items)
    lines.append(f"  {json.dumps(list_key)}: [\n{item_lines}\n  ]")
    lines.append("}")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
