"""The backend's side of `HazardClassifier.classify(image, hint)` (contract call 3).

The schemas match `ClassifyHint` and `ClassificationResult` in shared/openapi.yaml.
The real VLM adapter is #49; until then `VLM_CLASSIFIER=stub` picks StubClassifier.
"""

from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ClassifyHint:
    label: str
    confidence: float


@dataclass(frozen=True)
class ClassificationResult:
    is_hazard: bool
    hazard_type: str
    severity: str  # minor | moderate | severe
    confidence: float
    model_id: str


class ClassifierUnavailable(Exception):
    """The classifier failed or took too long; the API turns this into 503 vlm_unavailable."""


class HazardClassifier(Protocol):
    def classify(self, image: bytes, hint: ClassifyHint) -> ClassificationResult: ...


class StubClassifier:
    """Fixed answer, so the pipeline works before the real VLM exists."""

    def classify(self, image: bytes, hint: ClassifyHint) -> ClassificationResult:
        return ClassificationResult(
            is_hazard=True,
            hazard_type=hint.label or "pothole",
            severity="severe",
            confidence=0.92,
            model_id="stub-0.1",
        )


def build_classifier(name: str) -> HazardClassifier:
    if name == "stub":
        return StubClassifier()
    raise ValueError(f"Unknown VLM_CLASSIFIER {name!r}")


# A small shared pool: a call that times out keeps its worker until it returns,
# but the request handling it has already answered 503.
_pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="classify")


def classify_with_timeout(
    classifier: HazardClassifier, image: bytes, hint: ClassifyHint, timeout_s: float
) -> ClassificationResult:
    future = _pool.submit(classifier.classify, image, hint)
    try:
        return future.result(timeout=timeout_s)
    except FutureTimeout as exc:
        future.cancel()
        raise ClassifierUnavailable("Hazard classifier did not respond in time.") from exc
    except ClassifierUnavailable:
        raise
    except Exception as exc:
        raise ClassifierUnavailable("Hazard classifier failed.") from exc
