"""
Generates reference fixtures for the FsrsKt JVM tests using py-fsrs as the oracle.

Setup and usage (from the repository root):
    python3 -m venv tools/fixtures/.venv
    tools/fixtures/.venv/bin/pip install -r tools/fixtures/requirements.txt
    tools/fixtures/.venv/bin/python tools/fixtures/generate_fixtures.py

Output is deterministic (fixed seed, no timestamps), so re-running it must not change any file.
"""

import itertools
import math
import random
import types
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path

import fsrs.scheduler as scheduler_module
from fsrs import Card, Rating, ReviewLog, Scheduler, State
from fsrs.scheduler import (
    DEFAULT_PARAMETERS,
    LOWER_BOUNDS_PARAMETERS,
    MAX_DIFFICULTY,
    MIN_DIFFICULTY,
    STABILITY_MIN,
    UPPER_BOUNDS_PARAMETERS,
)
from json_output import format_review_sequence, write_json

PY_FSRS_VERSION = "6.3.2"
SEED = 20261029
RANDOM_PARAMETER_SET_COUNT = 10
RANDOM_SAMPLES_PER_PARAMETER_SET = 30

# Fixed input values used with the default parameters, including the edge values.
FIXED_STABILITIES = (
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
FIXED_ELAPSED_DAYS = (0, 1, 2, 7, 30, 365, 3650)
FIXED_DIFFICULTIES = (MIN_DIFFICULTY, 1.5, 5.0, 9.5, MAX_DIFFICULTY)
FIXED_RETRIEVABILITIES = (0.0, 0.01, 0.3, 0.9, 0.99, 1.0)

# AGAIN is a lapse, which py-fsrs and FsrsKt both handle with a separate formula.
RECALL_RATINGS = (Rating.Hard, Rating.Good, Rating.Easy)

REVIEW_SEQUENCES_WITH_DEFAULT_PARAMETERS = 50
REVIEW_SEQUENCES_PER_RANDOM_PARAMETER_SET = 10
REVIEW_SEQUENCE_MIN_STEPS = 5
REVIEW_SEQUENCE_MAX_STEPS = 30

# Upper limits of the gap between two reviews: up to an hour, a day, three days or 400 days.
REVIEW_GAP_MAX_SECONDS = (3_600, 86_400, 3 * 86_400, 400 * 86_400)

REFERENCE_DATETIME = datetime(2024, 1, 1, tzinfo=timezone.utc)

# Scheduler functions: desired retentions and maximum intervals used with the default parameters.
FIXED_DESIRED_RETENTIONS = (0.7, 0.8, 0.9, 0.95, 0.99)
FIXED_MAXIMUM_INTERVALS = (1, 10, 36500)
# Intervals that are exactly these values plus 0.5 test the half-to-even rounding of Python's round().
HALF_DAY_INTERVAL_FLOORS = (0, 1, 2, 3, 10, 100)

FIXED_FUZZ_INTERVAL_DAYS = (1, 2, 3, 4, 5, 6, 7, 8, 10, 15, 19, 20, 21, 30, 50, 100, 365, 1000, 36500)
FIXED_FUZZ_MAXIMUM_INTERVALS = (1, 2, 3, 5, 7, 20, 21, 100, 36500)
FIXED_FUZZ_U = (0.0, 1e-9, 0.1, 0.25, 0.5, 0.75, 0.9, 0.999999, 1 - 2**-53)

# Gaps between the last review and the time of the retrievability, including negative ones.
FIXED_RETRIEVABILITY_GAPS = (
    timedelta(days=-1),
    timedelta(hours=-1),
    timedelta(0),
    timedelta(minutes=1),
    timedelta(hours=23, minutes=59),
    timedelta(days=1),
    timedelta(days=1, hours=12),
    timedelta(days=7),
    timedelta(days=365),
)

SCHEDULER_SEQUENCES_PER_CONFIG = 20
SCHEDULER_SEQUENCES_PER_RANDOM_PARAMETER_SET = 3
RESCHEDULE_CASES_PER_CONFIG = 10
# Upper limits of a random gap between two reviews, from minutes (learning steps) to 400 days.
SCHEDULER_GAP_MAX_SECONDS = (5 * 60, 3_600, 86_400, 3 * 86_400, 30 * 86_400, 400 * 86_400)

# Scheduler configurations of the scheduler fixtures, pinned in their headers.
MINUTE = timedelta(minutes=1)
SCHEDULER_CONFIGS = {
    "default-steps": {"params": "default", "learning_steps": (MINUTE, 10 * MINUTE), "relearning_steps": (10 * MINUTE,)},
    "no-steps": {"params": "default", "learning_steps": (), "relearning_steps": ()},
    "one-step": {"params": "default", "learning_steps": (10 * MINUTE,), "relearning_steps": (10 * MINUTE,)},
    "three-steps": {
        "params": "default",
        "learning_steps": (MINUTE, 10 * MINUTE, timedelta(days=1)),
        "relearning_steps": (10 * MINUTE, timedelta(hours=1), timedelta(days=2)),
    },
    "retention-0.8": {"params": "default", "desired_retention": 0.8},
    "retention-0.95": {"params": "default", "desired_retention": 0.95},
    "small-maximum-interval": {"params": "default", "maximum_interval": 10},
    "fuzz-default-steps": {"params": "default", "enable_fuzzing": True},
    "fuzz-no-steps": {"params": "default", "learning_steps": (), "relearning_steps": (), "enable_fuzzing": True},
    "fuzz-small-maximum-interval": {"params": "default", "maximum_interval": 10, "enable_fuzzing": True},
}

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

    check_review_card_intervals()
    check_fuzz()


# Same ratings as TEST_RATINGS_1 in the py-fsrs tests.
TEST_RATINGS_1 = (Rating.Good,) * 6 + (Rating.Again,) * 2 + (Rating.Good,) * 5


def check_review_card_intervals() -> None:
    """
    Aborts unless py-fsrs reproduces its own test_review_card, which covers the learning steps and the intervals.
    Reference: https://github.com/open-spaced-repetition/py-fsrs/blob/9446cb06605c597a063aeee49f7d188d42e34dc2/tests/test_basic.py#L32
    """
    scheduler = Scheduler(enable_fuzzing=False)
    card = Card(card_id=1)
    review_datetime = datetime(2022, 11, 29, 12, 30, 0, 0, timezone.utc)

    ivl_history = []
    for rating in TEST_RATINGS_1:
        card, _ = scheduler.review_card(card, rating, review_datetime)
        ivl_history.append((card.due - card.last_review).days)
        review_datetime = card.due

    expected = [0, 2, 11, 46, 163, 498, 0, 0, 2, 4, 7, 12, 21]
    if ivl_history != expected:
        raise SystemExit(f"py-fsrs self-check failed: intervals {ivl_history}, expected {expected}")


def check_fuzz() -> None:
    """
    Aborts unless py-fsrs reproduces its own test_fuzz. Uses a fixed start instead of now, which does not change
    the intervals. Reseeds the global random generator, which the fixtures do not use.
    Reference: https://github.com/open-spaced-repetition/py-fsrs/blob/9446cb06605c597a063aeee49f7d188d42e34dc2/tests/test_basic.py#L576
    """
    scheduler = Scheduler()
    for seed, expected_days in ((42, 12), (12345, 11)):
        random.seed(seed)
        card = Card(card_id=1)
        card, _ = scheduler.review_card(card, Rating.Good, REFERENCE_DATETIME)
        card, _ = scheduler.review_card(card, Rating.Good, card.due)
        previous_due = card.due
        card, _ = scheduler.review_card(card, Rating.Good, card.due)
        if (card.due - previous_due).days != expected_days:
            raise SystemExit(
                f"py-fsrs self-check failed: fuzzed interval {(card.due - previous_due).days} "
                f"with seed {seed}, expected {expected_days}"
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
    return sample_log_uniform(rng, FIXED_STABILITIES[0], FIXED_STABILITIES[-1])


def sample_elapsed_days(rng: random.Random) -> int:
    return round(sample_log_uniform(rng, 1, FIXED_ELAPSED_DAYS[-1] + 1)) - 1


def sample_difficulty(rng: random.Random) -> float:
    return rng.uniform(MIN_DIFFICULTY, MAX_DIFFICULTY)


def sample_retrievability(rng: random.Random) -> float:
    return rng.uniform(FIXED_RETRIEVABILITIES[0], FIXED_RETRIEVABILITIES[-1])


def sample_rating(rng: random.Random) -> Rating:
    return rng.choice(list(Rating))


def sample_review_gap(rng: random.Random) -> timedelta:
    return timedelta(seconds=rng.randint(60, rng.choice(REVIEW_GAP_MAX_SECONDS)))


def formula_inputs(
    parameter_set: str,
    fixed_default_values: tuple[Iterable, ...],
    sample_input: Callable[[], tuple],
) -> list[tuple]:
    """Default parameters use all combinations of the fixed values, random parameter sets use random inputs."""
    if parameter_set == "default":
        return list(itertools.product(*fixed_default_values))
    return [sample_input() for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)]


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
        inputs = formula_inputs(
            name,
            fixed_default_values=(FIXED_ELAPSED_DAYS, FIXED_STABILITIES),
            sample_input=lambda: (sample_elapsed_days(rng), sample_stability(rng)),
        )
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
        inputs = formula_inputs(
            name,
            fixed_default_values=(FIXED_DIFFICULTIES, Rating),
            sample_input=lambda: (sample_difficulty(rng), sample_rating(rng)),
        )
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
        inputs = formula_inputs(
            name,
            fixed_default_values=(FIXED_STABILITIES, Rating),
            sample_input=lambda: (sample_stability(rng), sample_rating(rng)),
        )
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
        inputs = formula_inputs(
            name,
            fixed_default_values=(
                FIXED_DIFFICULTIES,
                FIXED_STABILITIES,
                FIXED_RETRIEVABILITIES,
                RECALL_RATINGS,
            ),
            sample_input=lambda: (
                sample_difficulty(rng),
                sample_stability(rng),
                sample_retrievability(rng),
                rng.choice(RECALL_RATINGS),
            ),
        )
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
        inputs = formula_inputs(
            name,
            fixed_default_values=(
                FIXED_DIFFICULTIES,
                FIXED_STABILITIES,
                FIXED_RETRIEVABILITIES,
            ),
            sample_input=lambda: (
                sample_difficulty(rng),
                sample_stability(rng),
                sample_retrievability(rng),
            ),
        )
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
        (timedelta(0) if i == 0 else sample_review_gap(rng), sample_rating(rng))
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
        # Same whole days as in Scheduler.review_card, None before the first review.
        elapsed_days = (
            None if is_first_review else (review_datetime - card.last_review).days
        )

        card, _ = scheduler.review_card(card, rating, review_datetime)

        steps.append(
            {
                "reviewAt": review_datetime.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "rating": rating_name(rating),
                "elapsedDays": elapsed_days,
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


def iso(value: datetime | None) -> str | None:
    """UTC instant as ISO-8601, with microseconds only if there are any."""
    if value is None:
        return None
    pattern = "%Y-%m-%dT%H:%M:%S.%fZ" if value.microsecond else "%Y-%m-%dT%H:%M:%SZ"
    return value.strftime(pattern)


def card_dict(card: Card) -> dict:
    # State names match the Kotlin enum constant names of FsrsCardState.
    return {
        "state": card.state.name.upper(),
        "step": card.step,
        "stability": card.stability,
        "difficulty": card.difficulty,
        "due": iso(card.due),
        "lastReview": iso(card.last_review),
    }


def config_scheduler(
    parameter_sets: dict[str, tuple[float, ...]], config: dict
) -> Scheduler:
    """Builds the scheduler of a config. Missing keys use the py-fsrs defaults, except for fuzzing, which is off."""
    return Scheduler(
        parameters=parameter_sets[config["params"]],
        desired_retention=config.get("desired_retention", 0.9),
        learning_steps=config.get("learning_steps", (MINUTE, 10 * MINUTE)),
        relearning_steps=config.get("relearning_steps", (10 * MINUTE,)),
        maximum_interval=config.get("maximum_interval", 36500),
        enable_fuzzing=config.get("enable_fuzzing", False),
    )


def config_header(scheduler: Scheduler, params: str) -> dict:
    """The full configuration of a scheduler, steps in seconds, as pinned in the fixture headers."""
    return {
        "params": params,
        "desiredRetention": scheduler.desired_retention,
        "learningStepsSeconds": [step.total_seconds() for step in scheduler.learning_steps],
        "relearningStepsSeconds": [step.total_seconds() for step in scheduler.relearning_steps],
        "maximumInterval": scheduler.maximum_interval,
        "enableFuzzing": scheduler.enable_fuzzing,
    }


def build_scheduler_configs(parameter_sets: dict[str, tuple[float, ...]]) -> dict[str, dict]:
    """The fixed configs plus one with the default steps for every random parameter set."""
    configs = dict(SCHEDULER_CONFIGS)
    for name in parameter_sets:
        if name != "default":
            configs[f"{name}-default-steps"] = {"params": name}
    return configs


@contextmanager
def patched_random(draw: Callable[[], float] | None) -> Iterator[list[float]]:
    """
    Replaces the random() that py-fsrs uses for the fuzz with draw, and records every drawn value.
    Without draw, any use of random() aborts, as the fixture would not be reproducible.
    """

    def fake_random() -> float:
        if draw is None:
            raise SystemExit("py-fsrs drew a random value without a fuzz generator")
        u = draw()
        drawn.append(u)
        return u

    drawn: list[float] = []
    original = scheduler_module.random
    scheduler_module.random = fake_random
    try:
        yield drawn
    finally:
        scheduler_module.random = original


def single_draw(drawn: list[float]) -> float | None:
    if len(drawn) > 1:
        raise SystemExit(f"py-fsrs drew {len(drawn)} random values in one review")
    return drawn[0] if drawn else None


def raw_next_interval(scheduler: Scheduler, stability: float) -> float:
    """
    The interval of Scheduler._next_interval before rounding, in the same operation order.
    Only used to search for inputs that land on .5, never as an expected value.
    """
    return (stability / scheduler._FACTOR) * (
        (scheduler.desired_retention ** (1 / scheduler._DECAY)) - 1
    )


def half_day_stabilities(scheduler: Scheduler) -> list[float]:
    """Stabilities whose unrounded interval is exactly x.5 days, found by searching floats near the exact solution."""
    stabilities = []
    for floor in HALF_DAY_INTERVAL_FLOORS:
        target = floor + 0.5
        guess = target / raw_next_interval(scheduler, 1.0)
        candidates = [guess]
        for _ in range(64):
            candidates.append(math.nextafter(candidates[-1], math.inf))
        lower = guess
        for _ in range(64):
            lower = math.nextafter(lower, -math.inf)
            candidates.append(lower)
        match = next((s for s in candidates if raw_next_interval(scheduler, s) == target), None)
        if match is not None:
            stabilities.append(match)
    return stabilities


def next_interval_cases(parameter_sets: dict[str, tuple[float, ...]]) -> list[dict]:
    rng = random.Random(f"{SEED}-next-interval")
    cases = []
    half_day_case_count = 0
    for name, w in parameter_sets.items():
        if name == "default":
            inputs = list(
                itertools.product(FIXED_STABILITIES, FIXED_DESIRED_RETENTIONS, FIXED_MAXIMUM_INTERVALS)
            )
            half_day_retentions = FIXED_DESIRED_RETENTIONS
        else:
            inputs = [
                (sample_stability(rng), rng.uniform(0.7, 0.99), rng.choice(FIXED_MAXIMUM_INTERVALS))
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]
            half_day_retentions = (0.9, rng.uniform(0.7, 0.99))
        for desired_retention in half_day_retentions:
            stabilities = half_day_stabilities(
                Scheduler(parameters=w, desired_retention=desired_retention, enable_fuzzing=False)
            )
            half_day_case_count += len(stabilities)
            inputs += [(stability, desired_retention, 36500) for stability in stabilities]

        for stability, desired_retention, maximum_interval in inputs:
            scheduler = Scheduler(
                parameters=w,
                desired_retention=desired_retention,
                maximum_interval=maximum_interval,
                enable_fuzzing=False,
            )
            cases.append(
                {
                    "params": name,
                    "input": {
                        "stability": stability,
                        "desiredRetention": desired_retention,
                        "maximumInterval": maximum_interval,
                    },
                    "expected": scheduler._next_interval(stability=stability),
                }
            )
    if half_day_case_count == 0:
        raise SystemExit("Found no stability whose interval lands on .5 days")
    return cases


def fuzz_range_function(scheduler: Scheduler) -> Callable[..., tuple[int, int]]:
    """
    The inner function _get_fuzz_range of Scheduler._get_fuzzed_interval, taken from its code object and bound to
    scheduler, so that the fixtures use the py-fsrs code itself instead of a copy.
    """
    code = next(
        const
        for const in Scheduler._get_fuzzed_interval.__code__.co_consts
        if isinstance(const, types.CodeType) and const.co_name == "_get_fuzz_range"
    )
    if code.co_freevars != ("self",):
        raise SystemExit(f"Unexpected free variables of _get_fuzz_range: {code.co_freevars}")
    return types.FunctionType(code, vars(scheduler_module), closure=(types.CellType(scheduler),))


def fuzz_inputs() -> list[tuple[int, int]]:
    """Fixed interval days per maximum interval, plus the days right at and below each maximum interval."""
    inputs = []
    for maximum_interval in FIXED_FUZZ_MAXIMUM_INTERVALS:
        days = set(d for d in FIXED_FUZZ_INTERVAL_DAYS if d <= maximum_interval)
        days |= {d for d in (maximum_interval - 2, maximum_interval - 1, maximum_interval) if d >= 1}
        inputs += [(d, maximum_interval) for d in sorted(days)]
    return inputs


def fuzz_range_cases() -> list[dict]:
    cases = []
    for interval_days, maximum_interval in fuzz_inputs():
        get_fuzz_range = fuzz_range_function(
            Scheduler(maximum_interval=maximum_interval, enable_fuzzing=True)
        )
        first, last = get_fuzz_range(interval_days=interval_days)
        cases.append(
            {
                "input": {"intervalDays": interval_days, "maximumInterval": maximum_interval},
                "expected": {"first": first, "last": last},
            }
        )
    return cases


def fuzzed_interval_cases() -> list[dict]:
    rng = random.Random(f"{SEED}-fuzzed-interval")
    cases = []
    for interval_days, maximum_interval in fuzz_inputs():
        scheduler = Scheduler(maximum_interval=maximum_interval, enable_fuzzing=True)
        first, last = fuzz_range_function(scheduler)(interval_days=interval_days)
        size = last - first + 1
        # u that land exactly between two days, the edges of [0, 1) and random values.
        values = list(FIXED_FUZZ_U) + [(i + 0.5) / size for i in range(min(size, 4))] + [rng.random() for _ in range(3)]
        for u in values:
            with patched_random(lambda: u) as drawn:
                fuzzed = scheduler._get_fuzzed_interval(interval=timedelta(days=interval_days))
            cases.append(
                {
                    "input": {"intervalDays": interval_days, "maximumInterval": maximum_interval, "u": u},
                    "expected": {"intervalDays": fuzzed.days, "drawsRandom": single_draw(drawn) is not None},
                }
            )
    return cases


def card_retrievability_cases(parameter_sets: dict[str, tuple[float, ...]]) -> list[dict]:
    rng = random.Random(f"{SEED}-card-retrievability")
    cases = []
    for name, w in parameter_sets.items():
        scheduler = Scheduler(parameters=w, enable_fuzzing=False)
        if name == "default":
            inputs = [
                (stability, REFERENCE_DATETIME, REFERENCE_DATETIME + gap)
                for stability, gap in itertools.product(FIXED_STABILITIES, FIXED_RETRIEVABILITY_GAPS)
            ]
            inputs += [
                (None, REFERENCE_DATETIME, REFERENCE_DATETIME + timedelta(days=1)),
                (1.0, None, REFERENCE_DATETIME),
                (None, None, REFERENCE_DATETIME),
            ]
        else:
            inputs = [
                (
                    sample_stability(rng),
                    REFERENCE_DATETIME,
                    REFERENCE_DATETIME + timedelta(seconds=rng.randint(-86_400, 400 * 86_400)),
                )
                for _ in range(RANDOM_SAMPLES_PER_PARAMETER_SET)
            ]
        for stability, last_review, now in inputs:
            card = Card(
                card_id=1,
                state=State.Review,
                stability=stability,
                difficulty=5.0,
                due=REFERENCE_DATETIME,
                last_review=last_review,
            )
            cases.append(
                {
                    "params": name,
                    "input": {"stability": stability, "lastReview": iso(last_review), "now": iso(now)},
                    "expected": scheduler.get_card_retrievability(card, current_datetime=now),
                }
            )
    return cases


def next_review_datetime(rng: random.Random, card: Card, previous: datetime) -> datetime:
    """At the due date, close to it, or after a random gap; never before the previous review."""
    choice = rng.random()
    if choice < 0.35:
        candidate = card.due
    elif choice < 0.5:
        candidate = card.due + timedelta(seconds=rng.randint(-30 * 60, 6 * 3_600))
    else:
        candidate = previous + timedelta(seconds=rng.randint(0, rng.choice(SCHEDULER_GAP_MAX_SECONDS)))
    return max(candidate, previous)


def scheduler_sequence_steps(
    scheduler: Scheduler,
    card: Card,
    first_review: datetime,
    ratings: list[Rating],
    rng: random.Random,
    fuzz_rng: random.Random | None,
) -> list[dict]:
    """Reviews card once per rating and returns the inputs, the drawn fuzz and the card before and after each step."""
    steps = []
    review_datetime = first_review
    for index, rating in enumerate(ratings):
        if index > 0:
            review_datetime = next_review_datetime(rng, card, review_datetime)
        before = card_dict(card)
        with patched_random(fuzz_rng.random if fuzz_rng else None) as drawn:
            card, _ = scheduler.review_card(card, rating, review_datetime)
        steps.append(
            {
                "reviewAt": iso(review_datetime),
                "rating": rating_name(rating),
                "fuzzU": single_draw(drawn),
                "intervalDays": (card.due - review_datetime).days,
                "before": before,
                "after": card_dict(card),
            }
        )
    return steps


def random_ratings(rng: random.Random) -> list[Rating]:
    return [
        sample_rating(rng)
        for _ in range(rng.randint(REVIEW_SEQUENCE_MIN_STEPS, REVIEW_SEQUENCE_MAX_STEPS))
    ]


def scheduler_sequences(schedulers: dict[str, Scheduler]) -> list[dict]:
    """Random sequences from a new card for every config, and cards with more steps than the scheduler."""
    rng = random.Random(f"{SEED}-scheduler-sequences")
    fuzz_rng = random.Random(f"{SEED}-scheduler-sequences-fuzz")
    sequences = []
    for config, scheduler in schedulers.items():
        count = (
            SCHEDULER_SEQUENCES_PER_CONFIG
            if config in SCHEDULER_CONFIGS
            else SCHEDULER_SEQUENCES_PER_RANDOM_PARAMETER_SET
        )
        for _ in range(count):
            sequences.append(
                {
                    "id": f"seq-{len(sequences) + 1:03d}",
                    "config": config,
                    "steps": scheduler_sequence_steps(
                        scheduler,
                        Card(card_id=1, due=REFERENCE_DATETIME),
                        REFERENCE_DATETIME,
                        random_ratings(rng),
                        rng,
                        fuzz_rng if scheduler.enable_fuzzing else None,
                    ),
                }
            )

    # A card scheduled before with more (re)learning steps than the current scheduler has: step >= len(steps).
    for state, step in ((State.Learning, 1), (State.Learning, 2), (State.Relearning, 1), (State.Relearning, 2)):
        for config in ("one-step", "no-steps"):
            for first_rating in Rating:
                card = Card(
                    card_id=1,
                    state=state,
                    step=step,
                    stability=3.0,
                    difficulty=5.0,
                    due=REFERENCE_DATETIME,
                    last_review=REFERENCE_DATETIME - 10 * MINUTE,
                )
                sequences.append(
                    {
                        "id": f"more-steps-{state.name.lower()}-{step}-{config}-{rating_name(first_rating).lower()}",
                        "config": config,
                        "steps": scheduler_sequence_steps(
                            schedulers[config],
                            card,
                            REFERENCE_DATETIME,
                            [first_rating] + [sample_rating(rng) for _ in range(4)],
                            rng,
                            None,
                        ),
                    }
                )
    return sequences


def reschedule_cases(schedulers: dict[str, Scheduler]) -> list[dict]:
    """Random review logs in random order, some at the same instant, rescheduled with every config without fuzz."""
    rng = random.Random(f"{SEED}-reschedule")
    cases = []
    for config, scheduler in schedulers.items():
        if scheduler.enable_fuzzing:
            continue
        for index in range(RESCHEDULE_CASES_PER_CONFIG):
            count = 0 if index == 0 else rng.randint(1, REVIEW_SEQUENCE_MAX_STEPS)
            review_datetime = REFERENCE_DATETIME
            logs = []
            for _ in range(count):
                if logs and rng.random() >= 0.1:  # every tenth log at the same instant as the previous one
                    review_datetime += timedelta(seconds=rng.randint(0, rng.choice(SCHEDULER_GAP_MAX_SECONDS)))
                logs.append(
                    ReviewLog(card_id=1, rating=sample_rating(rng), review_datetime=review_datetime, review_duration=None)
                )
            rng.shuffle(logs)
            card = Card(card_id=1, due=REFERENCE_DATETIME)
            cases.append(
                {
                    "id": f"{config}-{index + 1:02d}",
                    "config": config,
                    "due": iso(card.due),
                    "logs": [
                        {"rating": rating_name(log.rating), "reviewAt": iso(log.review_datetime)} for log in logs
                    ],
                    "expected": card_dict(scheduler.reschedule_card(card, logs)),
                }
            )
    return cases


def write_formula(
    file_name: str,
    function: str,
    description: str,
    py_fsrs: str,
    cases: list[dict],
    kotlin: str | None = None,
) -> None:
    write_json(
        OUTPUT_DIR / f"formulas/{file_name}.json",
        header={
            "function": function,
            "description": description,
            "pyFsrs": py_fsrs,
            "kotlin": kotlin or f"Fsrs.{function}",
        },
        list_key="cases",
        items=cases,
    )


def write_scheduler_file(
    file_name: str,
    function: str,
    description: str,
    py_fsrs: str,
    kotlin: str,
    configs: dict[str, dict],
    list_key: str,
    items: list[dict],
    format_item: Callable[[dict], str] | None = None,
) -> None:
    header = {
        "function": function,
        "description": description,
        "pyFsrs": py_fsrs,
        "kotlin": kotlin,
        "dayCounting": "whole 24-hour periods, rounded down: (now - last_review).days",
        "configs": configs,
    }
    if format_item is None:
        write_json(OUTPUT_DIR / f"scheduler/{file_name}.json", header, list_key, items)
    else:
        write_json(OUTPUT_DIR / f"scheduler/{file_name}.json", header, list_key, items, format_item)


def main() -> None:
    check_reference()

    parameter_sets = build_parameter_sets()
    schedulers = {name: build_scheduler(w) for name, w in parameter_sets.items()}

    write_json(
        OUTPUT_DIR / "parameter-sets.json",
        header={
            "pyFsrsVersion": PY_FSRS_VERSION,
            "seed": SEED,
            "lowerBounds": list(LOWER_BOUNDS_PARAMETERS),
            "upperBounds": list(UPPER_BOUNDS_PARAMETERS),
        },
        list_key="parameterSets",
        items=[{"name": name, "w": list(w)} for name, w in parameter_sets.items()],
    )

    write_formula(
        file_name="initial-stability",
        function="initialStability",
        description="Stability of a new card after its first review, depending on the rating.",
        py_fsrs="Scheduler._initial_stability",
        cases=initial_stability_cases(schedulers),
    )
    write_formula(
        file_name="initial-difficulty",
        function="initialDifficulty",
        description="Difficulty of a new card after its first review, depending on the rating, clamped to [1, 10].",
        py_fsrs="Scheduler._initial_difficulty",
        cases=initial_difficulty_cases(schedulers),
    )
    write_formula(
        file_name="retrievability",
        function="retrievability",
        description="Probability of recalling a card after the given whole days have elapsed, depending on its stability.",
        py_fsrs="Scheduler.get_card_retrievability",
        cases=retrievability_cases(schedulers),
    )
    write_formula(
        file_name="next-difficulty",
        function="nextDifficulty",
        description="Difficulty of a card after a review, depending on its current difficulty and the rating, clamped to [1, 10].",
        py_fsrs="Scheduler._next_difficulty",
        cases=next_difficulty_cases(schedulers),
    )
    write_formula(
        file_name="next-stability-short-term",
        function="nextStabilityShortTerm",
        description="Stability of a card after a review within less than a day, depending on its current stability and the rating.",
        py_fsrs="Scheduler._short_term_stability",
        cases=next_stability_short_term_cases(schedulers),
    )
    write_formula(
        file_name="stability-on-recall",
        function="stabilityOnRecall",
        description="Stability of a card after a successful long-term review (HARD, GOOD or EASY), depending on its difficulty, stability and retrievability.",
        py_fsrs="Scheduler._next_recall_stability",
        cases=stability_on_recall_cases(schedulers),
    )
    write_formula(
        file_name="stability-on-lapse",
        function="stabilityOnLapse",
        description="Stability of a card after a forgotten long-term review (AGAIN), depending on its difficulty, stability and retrievability.",
        py_fsrs="Scheduler._next_forget_stability",
        cases=stability_on_lapse_cases(schedulers),
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

    write_formula(
        file_name="next-interval",
        function="nextInterval",
        description="Interval in whole days after which the retrievability drops to the desired retention. "
        "Rounded half to even like Python's round(), then bounded to [1, maximumInterval]. "
        "Some stabilities were searched so that the unrounded interval is exactly x.5 days.",
        py_fsrs="Scheduler._next_interval",
        cases=next_interval_cases(parameter_sets),
    )
    write_formula(
        file_name="fuzz-range",
        function="fuzzRange",
        description="Range of the fuzzed interval in whole days, depending on the interval and the maximum interval. "
        "Taken from the inner function _get_fuzz_range. Does not depend on the parameters.",
        py_fsrs="Scheduler._get_fuzzed_interval._get_fuzz_range",
        kotlin="FsrsFuzz.fuzzRange",
        cases=fuzz_range_cases(),
    )
    write_formula(
        file_name="fuzzed-interval",
        function="fuzzedInterval",
        description="Fuzzed interval in whole days, with random() of py-fsrs patched to return u. "
        "drawsRandom is false if the interval is too short to be fuzzed and random() is not called.",
        py_fsrs="Scheduler._get_fuzzed_interval",
        kotlin="FsrsFuzz.fuzzedInterval",
        cases=fuzzed_interval_cases(),
    )

    configs = build_scheduler_configs(parameter_sets)
    config_schedulers = {name: config_scheduler(parameter_sets, config) for name, config in configs.items()}
    config_headers = {
        name: config_header(scheduler, configs[name]["params"]) for name, scheduler in config_schedulers.items()
    }

    write_json(
        OUTPUT_DIR / "scheduler/retrievability.json",
        header={
            "function": "retrievability",
            "description": "Retrievability of a card at a given instant. 0 without last review or stability, "
            "otherwise after the whole 24-hour periods since the last review, at least 0.",
            "pyFsrs": "Scheduler.get_card_retrievability",
            "kotlin": "FsrsScheduler.retrievability",
            "dayCounting": "whole 24-hour periods, rounded down: (now - last_review).days",
        },
        list_key="cases",
        items=card_retrievability_cases(parameter_sets),
    )
    write_scheduler_file(
        file_name="review-sequences",
        function="review",
        description="Review sequences with random ratings and gaps from minutes to 400 days, for every config, "
        "plus cards with more (re)learning steps than the scheduler. fuzzU is the value that random() returned "
        "for the fuzz in that step, or null if py-fsrs did not draw one.",
        py_fsrs="Scheduler.review_card",
        kotlin="FsrsScheduler.review",
        configs=config_headers,
        list_key="sequences",
        items=scheduler_sequences(config_schedulers),
        format_item=format_review_sequence,
    )
    write_scheduler_file(
        file_name="reschedule",
        function="reschedule",
        description="Cards recomputed from random review logs in random order, some at the same instant, "
        "for every config without fuzz. The memory state of the expected card is also the result of "
        "FsrsCalculator.replay with the parameters of the config.",
        py_fsrs="Scheduler.reschedule_card",
        kotlin="FsrsScheduler.reschedule, FsrsCalculator.replay",
        configs={name: header for name, header in config_headers.items() if not header["enableFuzzing"]},
        list_key="cases",
        items=reschedule_cases(config_schedulers),
    )


if __name__ == "__main__":
    main()
