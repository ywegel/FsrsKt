"""
Generates reference fixtures for the FsrsKt JVM tests using py-fsrs as the oracle.

Setup and usage (from the repository root):
    python3 -m venv tools/fixtures/.venv
    tools/fixtures/.venv/bin/pip install -r tools/fixtures/requirements.txt
    tools/fixtures/.venv/bin/python tools/fixtures/generate_fixtures.py

Output is deterministic (fixed seed, no timestamps), so re-running it must not change any file.
"""

import itertools
import json
import math
import random
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path

from fsrs import Card, Rating, Scheduler
from fsrs.scheduler import (
    DEFAULT_PARAMETERS,
    LOWER_BOUNDS_PARAMETERS,
    MAX_DIFFICULTY,
    MIN_DIFFICULTY,
    STABILITY_MIN,
    UPPER_BOUNDS_PARAMETERS,
)

PY_FSRS_VERSION = "6.3.2"
SEED = 20261029
RANDOM_PARAMETER_SET_COUNT = 10
RANDOM_SAMPLES_PER_PARAMETER_SET = 30

# Fixed input grid used with the default parameters, including the edge values.
GRID_STABILITIES = (
    STABILITY_MIN,
    0.01,
    0.1,
    0.5,
    1.0,
    2.0,
    10.0,
    100.0,
    1000.0,
    36500.0,
)
GRID_ELAPSED_DAYS = (0, 1, 2, 7, 30, 365, 3650)
GRID_DIFFICULTIES = (MIN_DIFFICULTY, 1.5, 5.0, 9.5, MAX_DIFFICULTY)
GRID_RETRIEVABILITIES = (0.0, 0.01, 0.3, 0.9, 0.99, 1.0)

# AGAIN is a lapse, which py-fsrs and FsrsKt both handle with a separate formula.
RECALL_RATINGS = (Rating.Hard, Rating.Good, Rating.Easy)

REVIEW_SEQUENCES_WITH_DEFAULT_PARAMETERS = 50
REVIEW_SEQUENCES_PER_RANDOM_PARAMETER_SET = 10
REVIEW_SEQUENCE_MIN_STEPS = 5
REVIEW_SEQUENCE_MAX_STEPS = 30

# Upper limits of the gap between two reviews: up to an hour, a day, three days or 400 days.
REVIEW_GAP_MAX_SECONDS = (3_600, 86_400, 3 * 86_400, 400 * 86_400)

REFERENCE_DATETIME = datetime(2024, 1, 1, tzinfo=timezone.utc)

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


def sample_log_uniform(rng: random.Random, lower: float, upper: float) -> float:
    # Small and large values are equally likely, unlike with a uniform distribution.
    return math.exp(rng.uniform(math.log(lower), math.log(upper)))


def sample_stability(rng: random.Random) -> float:
    return sample_log_uniform(rng, GRID_STABILITIES[0], GRID_STABILITIES[-1])


def sample_elapsed_days(rng: random.Random) -> int:
    return round(sample_log_uniform(rng, 1, GRID_ELAPSED_DAYS[-1] + 1)) - 1


def sample_difficulty(rng: random.Random) -> float:
    return rng.uniform(MIN_DIFFICULTY, MAX_DIFFICULTY)


def sample_retrievability(rng: random.Random) -> float:
    return rng.uniform(GRID_RETRIEVABILITIES[0], GRID_RETRIEVABILITIES[-1])


def sample_review_gap(rng: random.Random) -> timedelta:
    return timedelta(seconds=rng.randint(60, rng.choice(REVIEW_GAP_MAX_SECONDS)))


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


def retrievability_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    # Own generator per formula, so that adding a formula does not change the existing fixtures.
    rng = random.Random(f"{SEED}-retrievability")
    cases = []
    for name, scheduler in schedulers.items():
        if name == "default":
            inputs = list(itertools.product(GRID_ELAPSED_DAYS, GRID_STABILITIES))
        else:
            inputs = [
                (sample_elapsed_days(rng), sample_stability(rng))
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]

        for elapsed_days, stability in inputs:
            # py-fsrs only computes retrievability from a card and a date, in whole days.
            card = Card(card_id=1, stability=stability, last_review=REFERENCE_DATETIME)
            cases.append(
                {
                    "params": name,
                    "input": {"elapsedDays": elapsed_days, "stability": stability},
                    "expected": scheduler.get_card_retrievability(
                        card,
                        current_datetime=REFERENCE_DATETIME
                        + timedelta(days=elapsed_days),
                    ),
                }
            )
    return cases


def next_difficulty_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    rng = random.Random(f"{SEED}-next-difficulty")
    cases = []
    for name, scheduler in schedulers.items():
        if name == "default":
            inputs = list(itertools.product(GRID_DIFFICULTIES, Rating))
        else:
            inputs = [
                (sample_difficulty(rng), rng.choice(list(Rating)))
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]

        for difficulty, rating in inputs:
            cases.append(
                {
                    "params": name,
                    "input": {"difficulty": difficulty, "rating": rating_name(rating)},
                    "expected": scheduler._next_difficulty(
                        difficulty=difficulty, rating=rating
                    ),
                }
            )
    return cases


def next_stability_short_term_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    rng = random.Random(f"{SEED}-next-stability-short-term")
    cases = []
    for name, scheduler in schedulers.items():
        if name == "default":
            inputs = list(itertools.product(GRID_STABILITIES, Rating))
        else:
            inputs = [
                (sample_stability(rng), rng.choice(list(Rating)))
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]

        for stability, rating in inputs:
            cases.append(
                {
                    "params": name,
                    "input": {"stability": stability, "rating": rating_name(rating)},
                    "expected": scheduler._short_term_stability(
                        stability=stability, rating=rating
                    ),
                }
            )
    return cases


def stability_on_recall_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    rng = random.Random(f"{SEED}-stability-on-recall")
    cases = []
    for name, scheduler in schedulers.items():
        if name == "default":
            inputs = list(
                itertools.product(
                    GRID_DIFFICULTIES,
                    GRID_STABILITIES,
                    GRID_RETRIEVABILITIES,
                    RECALL_RATINGS,
                )
            )
        else:
            inputs = [
                (
                    sample_difficulty(rng),
                    sample_stability(rng),
                    sample_retrievability(rng),
                    rng.choice(RECALL_RATINGS),
                )
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]

        for difficulty, stability, retrievability, rating in inputs:
            cases.append(
                {
                    "params": name,
                    "input": {
                        "difficulty": difficulty,
                        "stability": stability,
                        "retrievability": retrievability,
                        "rating": rating_name(rating),
                    },
                    "expected": scheduler._next_recall_stability(
                        difficulty=difficulty,
                        stability=stability,
                        retrievability=retrievability,
                        rating=rating,
                    ),
                }
            )
    return cases


def stability_on_lapse_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    rng = random.Random(f"{SEED}-stability-on-lapse")
    cases = []
    for name, scheduler in schedulers.items():
        if name == "default":
            inputs = list(
                itertools.product(
                    GRID_DIFFICULTIES, GRID_STABILITIES, GRID_RETRIEVABILITIES
                )
            )
        else:
            inputs = [
                (
                    sample_difficulty(rng),
                    sample_stability(rng),
                    sample_retrievability(rng),
                )
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]

        for difficulty, stability, retrievability in inputs:
            cases.append(
                {
                    "params": name,
                    "input": {
                        "difficulty": difficulty,
                        "stability": stability,
                        "retrievability": retrievability,
                    },
                    "expected": scheduler._next_forget_stability(
                        difficulty=difficulty,
                        stability=stability,
                        retrievability=retrievability,
                    ),
                }
            )
    return cases


def memory_state(card: Card) -> dict:
    return {"difficulty": card.difficulty, "stability": card.stability}


def random_reviews(rng: random.Random) -> list[tuple[timedelta, Rating]]:
    """Returns a random number of (gap, rating) pairs with random ratings. The first gap is zero, as a new card has no previous review."""
    return [
        (timedelta(0) if i == 0 else sample_review_gap(rng), rng.choice(list(Rating)))
        for i in range(
            rng.randint(REVIEW_SEQUENCE_MIN_STEPS, REVIEW_SEQUENCE_MAX_STEPS)
        )
    ]


def review_sequence_steps(
    scheduler: Scheduler, reviews: list[tuple[timedelta, Rating]]
) -> list[dict]:
    """Iterate through all reviews of one review sequence. Returns the inputs and the card's state before and after each step."""
    card = Card(card_id=1)
    review_datetime = REFERENCE_DATETIME
    steps = []
    for gap, rating in reviews:
        is_first_review = card.last_review is None
        review_datetime += gap

        before = None if is_first_review else memory_state(card)
        # Same whole days and same decision between short-term and long-term formula as in Scheduler.review_card.
        elapsed_days = (
            0 if is_first_review else (review_datetime - card.last_review).days
        )

        card, _ = scheduler.review_card(card, rating, review_datetime)

        steps.append(
            {
                "reviewAt": review_datetime.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "rating": rating_name(rating),
                "elapsedDays": elapsed_days,
                "sameDay": not is_first_review and elapsed_days < 1,
                "before": before,
                "after": memory_state(card),
            }
        )
    return steps


def review_sequences(schedulers: dict[str, Scheduler]) -> list[dict]:
    """Build the input + outpu values of all review sequences. Random ones for each parameter set and two that run into STABILITY_MIN on purpose."""
    rng = random.Random(f"{SEED}-review-sequences")
    sequences = []
    for name, scheduler in schedulers.items():
        count = (
            REVIEW_SEQUENCES_WITH_DEFAULT_PARAMETERS
            if name == "default"
            else REVIEW_SEQUENCES_PER_RANDOM_PARAMETER_SET
        )
        for _ in range(count):
            sequences.append(
                {
                    "id": f"seq-{len(sequences) + 1:03d}",
                    "params": name,
                    "steps": review_sequence_steps(scheduler, random_reviews(rng)),
                }
            )

    # Random sequences never get down to STABILITY_MIN, so these two run into it on purpose.
    for sequence_id, gap, count in (
        ("again-streak-short-term", timedelta(minutes=10), 15),
        ("again-streak-long-term", timedelta(days=1), 150),
    ):
        sequences.append(
            {
                "id": sequence_id,
                "params": "default",
                "steps": review_sequence_steps(
                    schedulers["default"], [(gap, Rating.Again)] * count
                ),
            }
        )
    return sequences


def format_json_line(item: dict) -> str:
    return json.dumps(item, allow_nan=False, separators=(", ", ": "))


def format_review_sequence(sequence: dict) -> str:
    """Formats a review sequence with each of its steps on its own line."""
    steps = ",\n".join(f"      {format_json_line(step)}" for step in sequence["steps"])
    return (
        f'{{"id": {json.dumps(sequence["id"])}, "params": {json.dumps(sequence["params"])}, "steps": [\n'
        f"{steps}\n"
        "    ]}"
    )


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

    write_json(
        OUTPUT_DIR / "formulas/retrievability.json",
        header={
            "function": "retrievability",
            "description": "Probability of recalling a card after the given whole days have elapsed, depending on its stability.",
            "pyFsrs": "Scheduler.get_card_retrievability",
            "kotlin": "Fsrs.retrievability",
        },
        list_key="cases",
        items=retrievability_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "formulas/next-difficulty.json",
        header={
            "function": "nextDifficulty",
            "description": "Difficulty of a card after a review, depending on its current difficulty and the rating, clamped to [1, 10].",
            "pyFsrs": "Scheduler._next_difficulty",
            "kotlin": "Fsrs.nextDifficulty",
        },
        list_key="cases",
        items=next_difficulty_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "formulas/next-stability-short-term.json",
        header={
            "function": "nextStabilityShortTerm",
            "description": "Stability of a card after a review within less than a day, depending on its current stability and the rating.",
            "pyFsrs": "Scheduler._short_term_stability",
            "kotlin": "Fsrs.nextStabilityShortTerm",
        },
        list_key="cases",
        items=next_stability_short_term_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "formulas/stability-on-recall.json",
        header={
            "function": "stabilityOnRecall",
            "description": "Stability of a card after a successful long-term review (HARD, GOOD or EASY), depending on its difficulty, stability and retrievability.",
            "pyFsrs": "Scheduler._next_recall_stability",
            "kotlin": "Fsrs.stabilityOnRecall",
        },
        list_key="cases",
        items=stability_on_recall_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "formulas/stability-on-lapse.json",
        header={
            "function": "stabilityOnLapse",
            "description": "Stability of a card after a forgotten long-term review (AGAIN), depending on its difficulty, stability and retrievability.",
            "pyFsrs": "Scheduler._next_forget_stability",
            "kotlin": "Fsrs.stabilityOnLapse",
        },
        list_key="cases",
        items=stability_on_lapse_cases(schedulers),
    )

    write_json(
        OUTPUT_DIR / "review-sequences.json",
        header={
            "description": "Review sequences with random ratings and gaps. Fuzzing and learning steps are disabled, as FsrsKt has neither.",
            "pyFsrs": "Scheduler.review_card",
            "kotlin": "FsrsCalculator.review",
        },
        list_key="sequences",
        items=review_sequences(schedulers),
        format_item=format_review_sequence,
    )


if __name__ == "__main__":
    main()
