from dataclasses import dataclass


@dataclass(frozen=True)
class Classification:
    is_hazard: bool
    hazard_type: str | None
    severity: str | None
    confidence: float
    model_id: str


class StubClassifier:
    """Stand-in for the cloud VLM. Honors an explicit hint so tests can drive FR-12."""

    def classify(self, image: bytes | None, hint: dict | None) -> Classification:
        hint = hint or {}
        if hint.get("is_hazard") is False or hint.get("label") == "not_hazard":
            return Classification(False, None, None, 0.0, "stub-0.1")
        hazard_type = hint.get("hazard_type") or hint.get("label") or "pothole"
        severity = hint.get("severity") or "severe"
        confidence = float(hint.get("confidence", 0.92))
        return Classification(True, hazard_type, severity, confidence, "stub-0.1")
