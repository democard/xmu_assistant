"""Shared durable file-writing helpers for desktop exports."""
from __future__ import annotations

import os
import tempfile
from pathlib import Path


def write_text_atomically(
    path: str | os.PathLike[str],
    text: str,
    *,
    encoding: str = "utf-8-sig",
    newline: str = "",
) -> None:
    """Write beside the destination, then replace it only after a full write."""
    target = Path(path)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=".xmu-export-", suffix=".tmp", dir=target.parent
    )
    temporary_path = Path(temporary_name)
    try:
        output = os.fdopen(file_descriptor, "w", encoding=encoding, newline=newline)
        file_descriptor = -1
        with output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_path, target)
    finally:
        if file_descriptor >= 0:
            try:
                os.close(file_descriptor)
            except OSError:
                pass
        try:
            temporary_path.unlink()
        except OSError:
            pass
