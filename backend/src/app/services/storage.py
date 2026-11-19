"""Stores detection frames on local disk under IMAGE_STORAGE_DIR (S3 can replace this later)."""

import uuid
from pathlib import Path

JPEG_MAGIC = b"\xff\xd8\xff"


def is_jpeg(data: bytes) -> bool:
    return data.startswith(JPEG_MAGIC)


class ImageStore:
    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)

    def save(self, data: bytes) -> str:
        """Writes the frame and returns its key (a generated file name, never user input)."""
        self.root.mkdir(parents=True, exist_ok=True)
        key = f"{uuid.uuid4().hex}.jpg"
        (self.root / key).write_bytes(data)
        return key

    def path(self, key: str) -> Path:
        return self.root / key

    def delete(self, key: str) -> None:
        self.path(key).unlink(missing_ok=True)
