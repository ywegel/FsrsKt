"""
Generates reference fixtures for the FsrsKt JVM tests using py-fsrs as the oracle.

Setup and usage (from the repository root):
    python3 -m venv tools/fixtures/.venv
    tools/fixtures/.venv/bin/pip install -r tools/fixtures/requirements.txt
    tools/fixtures/.venv/bin/python tools/fixtures/generate_fixtures.py

Output is deterministic (fixed seed, no timestamps), so re-running it must not change any file.
"""

import json
import math
import random
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path

from fsrs import Card, Rating, Scheduler
from fsrs.scheduler import (
    DEFAULT_PARAMETERS,
    LOWER_BOUNDS_PARAMETERS,
    UPPER_BOUNDS_PARAMETERS,
)

PY_FSRS_VERSION = "6.3.2"
SEED = 20261029
RANDOM_PARAMETER_SET_COUNT = 10

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = REPO_ROOT / "library/src/jvmTest/resources" / f"py-fsrs-{PY_FSRS_VERSION}"


def check_reference() -> None:
    """
    Aborts unless the installed py-fsrs is the expected version and reproduces its own test_memo_state.
    Reference: https://github.com/open-spaced-repetition/py-fsrs/blob/9446cb06605c597a063aeee49f7d188d42e34dc2/tests/test_basic.py#L66
    """
    installed = version("fsrs")
    if installed != PY_FSRS_VERSION:
        raise SystemExit(f"Expected py-fsrs {PY_FSRS_VERSION}, found {installed}")

    scheduler = Scheduler(enable_fuzzing=False)

    ratings = (
        Rating.Again,
        Rating.Good,
        Rating.Good,
        Rating.Good,
        Rating.Good,
        Rating.Good,
    )
    ivl_history = [0, 0, 1, 3, 8, 21]

    card = Card(card_id=1)
    review_datetime = datetime(2022, 11, 29, 12, 30, 0, 0, timezone.utc)

    for rating, ivl in zip(ratings, ivl_history):
        review_datetime += timedelta(days=ivl)
        card, _ = scheduler.review_card(card, rating, review_datetime)

    if not (
        math.isclose(card.stability, 53.62691, abs_tol=1e-4)
        and math.isclose(card.difficulty, 6.3574867, abs_tol=1e-4)
    ):
        raise SystemExit(
            f"py-fsrs self-check failed: S={card.stability}, D={card.difficulty}"
        )


def build_parameter_sets() -> dict[str, tuple[float, ...]]:
    rng = random.Random(SEED)
    parameter_sets = {"default": tuple(DEFAULT_PARAMETERS)}
    for i in range(1, RANDOM_PARAMETER_SET_COUNT + 1):
        parameter_sets[f"random-{i:02d}"] = tuple(
            rng.uniform(lower, upper)
            for lower, upper in zip(LOWER_BOUNDS_PARAMETERS, UPPER_BOUNDS_PARAMETERS)
        )
    return parameter_sets


def build_scheduler(parameters: tuple[float, ...]) -> Scheduler:
    # Scheduler validates the parameter bounds itself.
    return Scheduler(
        parameters=parameters,
        enable_fuzzing=False,
        learning_steps=(),
        relearning_steps=(),
    )


def rating_name(rating: Rating) -> str:
    # Matches the Kotlin enum constant names of FsrsRating.
    return rating.name.upper()


def initial_stability_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    return [
        {
            "params": name,
            "input": {"rating": rating_name(rating)},
            "expected": scheduler._initial_stability(rating=rating),
        }
        for name, scheduler in schedulers.items()
        for rating in Rating
    ]


def initial_difficulty_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    return [
        {
            "params": name,
            "input": {"rating": rating_name(rating)},
            "expected": scheduler._initial_difficulty(rating=rating, clamp=True),
        }
        for name, scheduler in schedulers.items()
        for rating in Rating
    ]


def write_json(path: Path, header: dict, list_key: str, items: list[dict]) -> None:
    """Writes the header fields indented and each list item on its own line."""
    lines = ["{"]
    for key, value in header.items():
        lines.append(f"  {json.dumps(key)}: {json.dumps(value, allow_nan=False)},")
    item_lines = ",\n".join(
        f"    {json.dumps(item, allow_nan=False, separators=(', ', ': '))}"
        for item in items
    )
    lines.append(f"  {json.dumps(list_key)}: [\n{item_lines}\n  ]")
    lines.append("}")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    check_reference()

    parameter_sets = build_parameter_sets()
    schedulers = {name: build_scheduler(w) for name, w in parameter_sets.items()}

    write_json(
        OUTPUT_DIR / "parameter-sets.json",
        header={"pyFsrsVersion": PY_FSRS_VERSION, "seed": SEED},
        list_key="parameterSets",
        items=[{"name": name, "w": list(w)} for name, w in parameter_sets.items()],
    )

    write_json(
        OUTPUT_DIR / "formulas/initial-stability.json",
        header={
            "function": "initialStability",
            "description": "Stability of a new card after its first review, depending on the rating.",
            "pyFsrs": "Scheduler._initial_stability",
            "kotlin": "Fsrs.initialStability",
        },
        list_key="cases",
        items=initial_stability_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "formulas/initial-difficulty.json",
        header={
            "function": "initialDifficulty",
            "description": "Difficulty of a new card after its first review, depending on the rating, clamped to [1, 10].",
            "pyFsrs": "Scheduler._initial_difficulty",
            "kotlin": "Fsrs.initialDifficulty",
        },
        list_key="cases",
        items=initial_difficulty_cases(schedulers),
    )


if __name__ == "__main__":
    main()
