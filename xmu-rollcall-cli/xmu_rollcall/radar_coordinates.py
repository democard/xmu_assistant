"""Per-course radar signing coordinates for the desktop client.

与 Android 端 RadarCoordinateStore 同语义：一门课可保存任意多个地点，恰好一个
首选地点；匹配优先使用平台课程 id，缺 id 时回落到规范化课程名。数据落在
用户配置目录的 radar_coordinates.json，与账号无关（换号不清空）。
"""

from __future__ import annotations

import json
import math
import threading
import uuid
from dataclasses import asdict, dataclass
from importlib import resources
from pathlib import Path

from .config import get_config_dir

COORDINATE_SYSTEM = "GCJ-02"
COORDINATE_FILE = "radar_coordinates.json"
_PRESET_FILE = "xiang_an_coordinates_gcj02.json"


@dataclass
class RadarCoordinate:
    id: str
    course_id: str
    course_title: str
    name: str
    latitude: float
    longitude: float
    source: str = "custom"
    preferred: bool = False
    accuracy_meters: float | None = None
    created_at_ms: int = 0


@dataclass(frozen=True)
class RadarPreset:
    id: str
    name: str
    zone: str
    latitude: float
    longitude: float
    confidence: str = ""


def normalize_course_title(value: str) -> str:
    """与 Android 端一致：忽略大小写、空格与常见分隔符。"""
    text = (value or "").strip().lower()
    for token in (" ", "\t", "·", "•", "_", "—", "–", "-"):
        text = text.replace(token, "")
    return text


def _preset_candidates() -> list[Path]:
    paths: list[Path] = []
    try:
        paths.append(Path(resources.files("xmu_rollcall").joinpath("data", _PRESET_FILE)))
    except (ImportError, AttributeError, TypeError, OSError):
        pass
    paths.append(Path(__file__).resolve().with_name("data") / _PRESET_FILE)
    return paths


def load_presets() -> list[RadarPreset]:
    """读取随包分发的翔安校区预置地点；文件缺失时返回空列表而不是抛错。"""
    for path in _preset_candidates():
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            continue
        try:
            rows = json.loads(raw).get("locations") or []
        except (ValueError, AttributeError):
            return []
        presets: list[RadarPreset] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            try:
                latitude = float(row["latitude"])
                longitude = float(row["longitude"])
                if not (
                    math.isfinite(latitude)
                    and -90.0 <= latitude <= 90.0
                    and math.isfinite(longitude)
                    and -180.0 <= longitude <= 180.0
                ):
                    continue
                presets.append(
                    RadarPreset(
                        id=str(row["id"]),
                        name=str(row["name"]),
                        zone=str(row.get("zone") or "未分组"),
                        latitude=latitude,
                        longitude=longitude,
                        confidence=str(row.get("confidence") or ""),
                    )
                )
            except (KeyError, TypeError, ValueError):
                continue
        return presets
    return []


class RadarCoordinateStore:
    """JSON 持久化的课程坐标库；方法内部加锁，可被 GUI 与后台线程共用。"""

    def __init__(self, path: Path | None = None):
        self._path = Path(path) if path is not None else get_config_dir() / COORDINATE_FILE
        self._lock = threading.RLock()
        self._items = self._read()

    # ---- 查询 ----
    def all(self) -> list[RadarCoordinate]:
        with self._lock:
            return list(self._items)

    def places_for(self, course_id: str = "", course_title: str = "") -> list[RadarCoordinate]:
        cid = str(course_id or "")
        with self._lock:
            matched = [item for item in self._items if _matches(item, cid, course_title)]
        return sorted(
            matched,
            key=lambda item: (item.course_id != cid, not item.preferred, item.created_at_ms),
        )

    def preferred_for(self, course_id: str = "", course_title: str = "") -> RadarCoordinate | None:
        places = self.places_for(course_id, course_title)
        return places[0] if places else None

    # ---- 写入 ----
    def add(
        self,
        course_id: str,
        course_title: str,
        name: str,
        latitude: float,
        longitude: float,
        source: str = "custom",
        accuracy_meters: float | None = None,
        make_preferred: bool = True,
    ) -> RadarCoordinate:
        name = (name or "").strip()
        if not name:
            raise ValueError("地点名称不能为空")
        latitude = float(latitude)
        longitude = float(longitude)
        if not (math.isfinite(latitude) and -90.0 <= latitude <= 90.0):
            raise ValueError("纬度无效")
        if not (math.isfinite(longitude) and -180.0 <= longitude <= 180.0):
            raise ValueError("经度无效")
        if not (course_id or course_title):
            raise ValueError("请先选择课程")

        with self._lock:
            items = list(self._items)
            matched = [item for item in self._items if _same_course(item, course_id, course_title)]
            preferred = make_preferred or not matched
            if preferred:
                for index, item in enumerate(items):
                    if item.preferred and _same_course(item, course_id, course_title):
                        items[index] = _replace(item, preferred=False)
            item = RadarCoordinate(
                id=str(uuid.uuid4()),
                course_id=str(course_id or ""),
                course_title=(course_title or "").strip(),
                name=name,
                latitude=latitude,
                longitude=longitude,
                source=source or "custom",
                preferred=preferred,
                accuracy_meters=_clean_accuracy(accuracy_meters),
                created_at_ms=_now_ms(),
            )
            items.append(item)
            self._write(items)
            return item

    def set_preferred(self, coordinate_id: str) -> bool:
        with self._lock:
            target = next((item for item in self._items if item.id == coordinate_id), None)
            if target is None:
                return False
            items = list(self._items)
            for index, item in enumerate(items):
                if _same_course(item, target.course_id, target.course_title):
                    items[index] = _replace(item, preferred=item.id == coordinate_id)
            self._write(items)
            return True

    def delete(self, coordinate_id: str) -> bool:
        with self._lock:
            removed = next((item for item in self._items if item.id == coordinate_id), None)
            if removed is None:
                return False
            items = [item for item in self._items if item.id != coordinate_id]
            if removed.preferred:
                for index, item in enumerate(items):
                    if _same_course(item, removed.course_id, removed.course_title):
                        items[index] = _replace(item, preferred=True)
                        break
            self._write(items)
            return True

    def clear(self) -> None:
        with self._lock:
            self._write([])

    # ---- 持久化 ----
    def _read(self) -> list[RadarCoordinate]:
        try:
            raw = self._path.read_text(encoding="utf-8")
        except OSError:
            return []
        try:
            rows = json.loads(raw)
        except ValueError:
            return []
        if not isinstance(rows, list):
            return []
        items: list[RadarCoordinate] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            try:
                latitude = float(row["latitude"])
                longitude = float(row["longitude"])
                if not (
                    math.isfinite(latitude)
                    and -90.0 <= latitude <= 90.0
                    and math.isfinite(longitude)
                    and -180.0 <= longitude <= 180.0
                ):
                    continue
                items.append(
                    RadarCoordinate(
                        id=str(row.get("id") or uuid.uuid4()),
                        course_id=str(row.get("course_id") or ""),
                        course_title=str(row.get("course_title") or ""),
                        name=str(row.get("name") or "未命名地点"),
                        latitude=latitude,
                        longitude=longitude,
                        source=str(row.get("source") or "custom"),
                        preferred=bool(row.get("preferred")),
                        accuracy_meters=_clean_accuracy(row.get("accuracy_meters")),
                        created_at_ms=int(row.get("created_at_ms") or 0),
                    )
                )
            except (KeyError, TypeError, ValueError, OverflowError):
                continue
        return items

    def _write(self, items: list[RadarCoordinate]) -> None:
        payload = json.dumps([asdict(item) for item in items], ensure_ascii=False, indent=2)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(".tmp")
        try:
            tmp.write_text(payload, encoding="utf-8")
            tmp.replace(self._path)
        except OSError:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass  # 保留原写入错误，不能让清理失败掩盖原因。
            raise
        self._items = items


def _matches(item: RadarCoordinate, course_id: str, course_title: str) -> bool:
    if course_id and item.course_id:
        return str(course_id) == item.course_id
    return bool(course_title) and normalize_course_title(course_title) == normalize_course_title(item.course_title)


def _same_course(item: RadarCoordinate, course_id: str, course_title: str) -> bool:
    cid = str(course_id or "")
    if cid or item.course_id:
        return bool(cid) and cid == item.course_id
    return bool(course_title) and normalize_course_title(course_title) == normalize_course_title(item.course_title)


def _clean_accuracy(value: object) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        accuracy = float(value)
    except (TypeError, ValueError):
        return None
    return accuracy if math.isfinite(accuracy) and accuracy >= 0.0 else None


def _replace(item: RadarCoordinate, **changes) -> RadarCoordinate:
    data = asdict(item)
    data.update(changes)
    return RadarCoordinate(**data)


def _now_ms() -> int:
    import time

    return int(time.time() * 1000)
