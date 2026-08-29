"""Read and download courseware available to the logged-in LNT account."""

from __future__ import annotations

import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

from . import request_probe
from .rollcall_models import first_value as _first_value
from .utils import (
    API_TIMEOUT,
    DOWNLOAD_TIMEOUT,
    SessionExpiredError,
    base_url,
    clone_session,
    headers,
    remember_good_endpoint,
    ordered_endpoints,
    response_session_expired,
    unwrap_list as _unwrap_list,
)


COURSE_ENDPOINTS = (
    "/api/my-courses?per_page=1000",
    "/api/courses?role=student&per_page=1000",
    "/api/courses?course_role=student&per_page=1000",
    "/api/my/courses?per_page=1000",
    "/api/courses?per_page=1000",
    "/api/courses",
)

API_HEADERS = {**headers, "Accept": "application/json, text/plain, */*"}
COURSEWARE_DETAIL_WORKERS = 8
NON_COURSEWARE_TYPES = {"homework", "exam"}
DIRECT_URL_EXTENSIONS = (
    ".pdf", ".ppt", ".pptx", ".doc", ".docx", ".xls", ".xlsx", ".zip",
    ".rar", ".7z", ".mp4", ".mov", ".m4v", ".mp3", ".m4a", ".m3u8",
)
WINDOWS_RESERVED_NAMES = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{number}" for number in range(1, 10)),
    *(f"LPT{number}" for number in range(1, 10)),
}

# available_path 同名查名的防御性上限，与安卓 CoursewareClient.reserveDownloadFiles 一致（D3）
MAX_AVAILABLE_PATH_ATTEMPTS = 50


@dataclass(frozen=True)
class CourseSummary:
    course_id: str
    title: str
    term: str = ""
    semester_code: str = ""
    search_text: str = ""

    @property
    def display_name(self) -> str:
        return f"{self.title}  [{self.term}]" if self.term else self.title


@dataclass(frozen=True)
class CoursewareItem:
    course_id: str
    activity_id: str
    activity_title: str
    activity_type: str
    module_name: str
    syllabus_name: str
    upload_id: str
    reference_id: str
    filename: str
    size: int
    media_type: str
    published_at: str
    upload_status: str
    allow_download: bool
    source_url: str = ""

    @property
    def download_status(self) -> str:
        if not self.upload_id:
            return "已保存入口" if self.entry_url else "资源缺失"
        if self.upload_status != "ready":
            return "文件处理中"
        if not self.allow_download:
            return "可下载（平台版权保护）"
        return "可下载"

    @property
    def entry_url(self) -> str:
        if self.source_url:
            return self.source_url
        if self.course_id and self.activity_id:
            return f"{base_url}/course/{self.course_id}/learning-activity#/{self.activity_id}"
        return ""


def _find_url(payload) -> str:
    if isinstance(payload, str):
        value = payload.strip()
        return value if value.startswith(("https://", "http://")) else ""
    if isinstance(payload, list):
        for item in payload:
            found = _find_url(item)
            if found:
                return found
    if isinstance(payload, dict):
        for key in (
            "url", "source_url", "link", "href", "video_url", "play_url",
            "resource_url", "download_url", "external_url", "preview_url",
        ):
            found = _find_url(payload.get(key))
            if found:
                return found
        for value in payload.values():
            found = _find_url(value)
            if found:
                return found
    return ""


def _activity_entry_url(course_id: str, activity_id: str) -> str:
    return f"{base_url}/course/{course_id}/learning-activity#/{activity_id}"


def _get_json(session, endpoint: str, label: str):
    """单端点直读 JSON。判定层（401/403/会话 302 页面）来自 request_probe
    单一来源；直读场景的处置语义：401/403 都直接上抛，其它非 200 走
    raise_for_status 交调用方重试链路。
    """
    response = session.get(f"{base_url}{endpoint}", headers=API_HEADERS, timeout=API_TIMEOUT)
    # 命中即原样上抛（异常由 request_probe 统一构造）。
    verdict = request_probe.classify_status(response.status_code, label)
    if verdict is not None:
        raise verdict
    response.raise_for_status()
    # 会话过期时平台返回 302 跳身份域（requests 自动跟随成 200 登录页），
    # 显式判定为会话过期而非「平台未返回 JSON」（与 Android 端对齐）
    if request_probe.detect_expired_page(response):
        raise request_probe.session_expired_error(label)
    try:
        return response.json()
    except ValueError as exc:
        raise RuntimeError(f"{label}失败：平台未返回 JSON 数据") from exc


def fetch_courses(session) -> tuple[list[CourseSummary], str]:
    errors = []
    # 端点记忆：上次成功的端点本次优先尝试，省掉 1~5 个必败探测请求
    for endpoint in ordered_endpoints("courses", COURSE_ENDPOINTS):
        try:
            payload = _get_json(session, endpoint, "课程列表读取")
        except SessionExpiredError:
            # 会话过期是终态：直接上抛走重新登录引导，不再记账续探剩余端点
            # （全部必败且空跑登录域）——与 core.fetch_first_json 的终态短路
            # 同语义（request_probe.classify_status 的 401 处置约定）。
            raise
        except Exception as exc:
            errors.append(f"{endpoint}: {exc}")
            continue

        raw_courses = _unwrap_list(payload, ("courses", "data", "items", "list", "results"))
        courses = []
        for raw in raw_courses:
            if not isinstance(raw, dict):
                continue
            course_id = _first_value(raw, ("id", "course_id", "courseId", "cid", "uuid"))
            title = _first_value(raw, ("name", "title", "course_title", "course_name", "display_name"))
            if not course_id or not title:
                continue
            academic_year = raw.get("academic_year")
            if isinstance(academic_year, dict):
                term = _first_value(academic_year, ("name", "code", "id"), "")
            else:
                term = academic_year or ""
            if not term:
                term = _first_value(raw, ("term_name", "semester_name", "term", "year"), "")
            if isinstance(term, dict):
                term = _first_value(term, ("name", "code", "id"), "")
            raw_semester = raw.get("semester")
            if isinstance(raw_semester, dict):
                semester_code = _first_value(raw_semester, ("code", "id", "name"), "")
            else:
                semester_code = raw_semester or term
            # search_text：学年筛选兜底用（app._courseware_course_matches_filters 在
            # term 不含学年时回退到此）。只保留筛选需要的字段，避免把整段 raw dict
            # （含无关字段/嵌套）转成串存进每个 CourseSummary 浪费内存。
            search_parts = [
                str(academic_year) if not isinstance(academic_year, dict)
                else _first_value(academic_year, ("name", "code", "id"), str(academic_year)),
                str(term),
                str(title),
                str(semester_code or ""),
            ]
            courses.append(CourseSummary(
                str(course_id),
                str(title),
                str(term),
                str(semester_code or ""),
                " ".join(part for part in search_parts if part),
            ))

        if courses:
            remember_good_endpoint("courses", endpoint)
            return courses, endpoint
    raise RuntimeError("课程列表接口读取失败：" + "；".join(errors[:4]))


def _module_lookup(modules: list) -> tuple[dict[str, str], dict[str, str]]:
    module_names: dict[str, str] = {}
    syllabus_names: dict[str, str] = {}
    for module in modules:
        if not isinstance(module, dict):
            continue
        module_id = str(module.get("id") or "")
        module_names[module_id] = str(module.get("name") or "未分组")
        for syllabus in module.get("syllabuses") or []:
            if isinstance(syllabus, dict):
                syllabus_names[str(syllabus.get("id") or syllabus.get("syllabus_id") or "")] = str(
                    syllabus.get("summary") or syllabus.get("name") or ""
                )
    return module_names, syllabus_names


def _courseware_order_lookup(modules: list) -> dict[tuple[str, str], tuple[int, int]]:
    order: dict[tuple[str, str], tuple[int, int]] = {}
    for module_index, module in enumerate(modules):
        if not isinstance(module, dict):
            continue
        module_id = str(module.get("id") or "")
        order[(module_id, "")] = (module_index, -1)
        order[(module_id, "0")] = (module_index, -1)
        for syllabus_index, syllabus in enumerate(module.get("syllabuses") or []):
            if not isinstance(syllabus, dict):
                continue
            syllabus_id = str(syllabus.get("id") or syllabus.get("syllabus_id") or "")
            order[(module_id, syllabus_id)] = (module_index, syllabus_index)
    return order


def _activity_number(activity: dict, fallback: int) -> int:
    try:
        return int(activity.get("id"))
    except (TypeError, ValueError):
        return fallback


def _activity_order_key(
    activity: dict,
    order_lookup: dict[tuple[str, str], tuple[int, int]],
    fallback_index: int,
) -> tuple[int, int, int, int]:
    module_id = str(activity.get("module_id") or "")
    syllabus_id = str(activity.get("syllabus_id") or "")
    module_order, syllabus_order = order_lookup.get(
        (module_id, syllabus_id),
        order_lookup.get((module_id, "0"), order_lookup.get((module_id, ""), (999999, 999999))),
    )
    return module_order, syllabus_order, _activity_number(activity, fallback_index), fallback_index


def _courseware_items_from_detail(
    course_id: str,
    activity: dict,
    detail: dict,
    module_names: dict[str, str],
    syllabus_names: dict[str, str],
) -> list[CoursewareItem]:
    uploads = detail.get("uploads") or []
    common = {
        "course_id": str(course_id),
        "activity_id": str(activity["id"]),
        "activity_title": str(detail.get("title") or activity.get("title") or "未命名课件"),
        "activity_type": str(detail.get("type") or activity.get("type") or "material"),
        "module_name": module_names.get(str(detail.get("module_id") or activity.get("module_id") or ""), "未分组"),
        "syllabus_name": syllabus_names.get(str(detail.get("syllabus_id") or activity.get("syllabus_id") or ""), ""),
        "published_at": str((detail.get("data") or {}).get("publish_time") or detail.get("created_at") or ""),
        "source_url": _find_url(detail) or _activity_entry_url(str(course_id), str(activity["id"])),
    }
    if not uploads:
        return [CoursewareItem(
            **common,
            upload_id="",
            reference_id="",
            filename="",
            size=0,
            media_type="",
            upload_status="",
            allow_download=False,
        )]

    items = []
    for upload in uploads:
        if not isinstance(upload, dict):
            continue
        items.append(CoursewareItem(
            **common,
            upload_id=str(upload.get("id") or ""),
            reference_id=str(upload.get("reference_id") or ""),
            filename=str(upload.get("name") or common["activity_title"]),
            size=int(upload.get("size") or 0),
            media_type=str(upload.get("type") or "file"),
            upload_status=str(upload.get("status") or ""),
            allow_download=bool(upload.get("allow_download")),
        ))
    return items


def _courseware_item_from_activity(
    course_id: str,
    activity: dict,
    module_names: dict[str, str],
    syllabus_names: dict[str, str],
    reason: str = "",
) -> CoursewareItem:
    activity_id = str(activity.get("id") or "")
    return CoursewareItem(
        course_id=str(course_id),
        activity_id=activity_id,
        activity_title=str(activity.get("title") or "未命名课件"),
        activity_type=str(activity.get("type") or "material"),
        module_name=module_names.get(str(activity.get("module_id") or ""), "未分组"),
        syllabus_name=syllabus_names.get(str(activity.get("syllabus_id") or ""), ""),
        upload_id="",
        reference_id="",
        filename="",
        size=0,
        media_type=reason or "entry",
        published_at=str(activity.get("created_at") or ""),
        upload_status="entry",
        allow_download=False,
        source_url=_find_url(activity) or _activity_entry_url(str(course_id), activity_id),
    )


# 章节结构（modules）一次会话内几乎不变，却随每次课件刷新重复拉取。
# 按 course_id 做 TTL 进程级缓存：命中则每门课每次刷新省 1 个 RTT；
# TTL 过期或进程重启后自然重新拉取。会话过期异常不缓存（直接上抛）。
MODULES_CACHE_TTL_SECONDS = 300
_modules_cache: dict[str, tuple[float, dict]] = {}
_modules_cache_lock = threading.Lock()


def _get_modules_cached(session, course_id: str):
    with _modules_cache_lock:
        hit = _modules_cache.get(course_id)
        if hit is not None and time.monotonic() - hit[0] < MODULES_CACHE_TTL_SECONDS:
            return hit[1]
    payload = _get_json(session, f"/api/courses/{course_id}/modules", "课程章节读取")
    with _modules_cache_lock:
        # 写入时清过期项：TTL 只影响命中与否、过期条目永不删除的话，托盘
        # 常驻依次浏览全课程会让 payload 按课程数无界驻留内存
        now = time.monotonic()
        for key in [k for k, (ts, _p) in _modules_cache.items() if now - ts >= MODULES_CACHE_TTL_SECONDS]:
            del _modules_cache[key]
        _modules_cache[course_id] = (now, payload)
    return payload


def reset_modules_cache() -> None:
    """清空 modules 缓存（测试用，避免用例间进程级状态串扰）。"""
    with _modules_cache_lock:
        _modules_cache.clear()


def fetch_courseware(session, course_id: str) -> list[CoursewareItem]:
    activities = _get_json(
        session,
        f"/api/course/{course_id}/courseware-activities",
        "课件活动读取",
    )
    modules_payload = _get_modules_cached(session, course_id)
    modules = _unwrap_list(modules_payload, ("modules", "data", "items", "list"))
    module_names, syllabus_names = _module_lookup(modules)
    order_lookup = _courseware_order_lookup(modules)

    raw_activities = activities if isinstance(activities, list) else _unwrap_list(
        activities, ("activities", "data", "items", "list")
    )

    courseware_activities = [
        (index, activity)
        for index, activity in enumerate(raw_activities)
        if isinstance(activity, dict) and activity.get("id") and activity.get("type") not in NON_COURSEWARE_TYPES
    ]
    courseware_activities.sort(key=lambda indexed: _activity_order_key(indexed[1], order_lookup, indexed[0]))
    ordered_activities = [activity for _index, activity in courseware_activities]

    def load_activity_items(activity: dict) -> list[CoursewareItem]:
        # 每个线程持有独立 Session（threadlocal）：避免 8 线程并发请求共用主
        # session 的 cookiejar 竞争写入（requests 不保证 Session 线程安全）。
        # 课件读取是只读 API，无 Set-Cookie 回写需求，clone 用完即弃。
        thread_session = getattr(thread_local, "session", None)
        if thread_session is None:
            thread_session = clone_session(session)
            thread_local.session = thread_session
        try:
            detail = _get_json(thread_session, f"/api/activities/{activity['id']}", "课件详情读取")
            return _courseware_items_from_detail(course_id, activity, detail, module_names, syllabus_names)
        except SessionExpiredError:
            # 体检报告 P1-3：会话过期必须穿透本线程向上抛（触发 GUI 重登引导）。
            # 若被下方通用 except 吞掉，会把过期伪装成逐条「资源缺失」，
            # 用户看到整屏垃圾条目却永远等不到重新登录提示。
            raise
        except Exception as exc:
            return [_courseware_item_from_activity(course_id, activity, module_names, syllabus_names, str(exc))]

    items: list[CoursewareItem] = []
    if ordered_activities:
        thread_local = threading.local()
        max_workers = min(COURSEWARE_DETAIL_WORKERS, len(ordered_activities))
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            for activity_items in executor.map(load_activity_items, ordered_activities):
                items.extend(activity_items)

    return items


def sanitize_filename(filename: str, fallback: str = "courseware") -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", filename).strip().rstrip(". ")
    if not cleaned:
        cleaned = fallback
    if cleaned.split(".", 1)[0].upper() in WINDOWS_RESERVED_NAMES:
        cleaned = f"_{cleaned}"
    return cleaned[:240]


def available_path(directory: Path, filename: str) -> Path:
    """在目录里挑一个不冲突的落地文件名（同名自动加序号）。

    D3：与安卓端 CoursewareClient.reserveDownloadFiles 的限次语义统一。
    本函数只做「查名」（exists/.part 探测），不碰磁盘写入，理论上不会失败到上限；
    上限是防御性兜底——避免极端目录（同名 >= MAX 个）下无限循环，命中即抛清晰错误。
    """
    target = directory / sanitize_filename(filename)
    # 正式文件不存在即复用原名（即使有同名 .part 残留）：
    # .part 是断点续传的依据——若因它存在而换名，重试永远从零开始，
    # 断点续传形同虚设。并发同名下载由调用方单 worker 串行保证。
    if not target.exists():
        return target
    stem, suffix = target.stem, target.suffix
    number = 2
    while number <= MAX_AVAILABLE_PATH_ATTEMPTS:
        candidate = directory / f"{stem} ({number}){suffix}"
        if not candidate.exists() and not candidate.with_name(candidate.name + ".part").exists():
            return candidate
        number += 1
    raise RuntimeError("目录内同名课件过多，请清理后重试")


def _looks_direct_url(url: str) -> bool:
    return url.split("?", 1)[0].lower().endswith(DIRECT_URL_EXTENSIONS)


def _write_url_shortcut(directory: Path, item: CoursewareItem) -> Path:
    target = available_path(directory, f"{item.filename or item.activity_title or item.activity_id}.url")
    target.write_text(f"[InternetShortcut]\nURL={item.entry_url}\n", encoding="utf-8")
    return target


def download_courseware(session, item: CoursewareItem, destination: str | Path) -> Path:
    directory = Path(destination)
    directory.mkdir(parents=True, exist_ok=True)
    if not item.upload_id:
        if item.source_url and _looks_direct_url(item.source_url):
            return _download_url(session, item.source_url, directory, item.filename or item.activity_title)
        if item.entry_url:
            return _write_url_shortcut(directory, item)
        raise RuntimeError("资源缺失")
    if item.upload_status != "ready":
        if item.entry_url:
            return _write_url_shortcut(directory, item)
        raise PermissionError(item.download_status)
    if not item.reference_id:
        if item.entry_url:
            return _write_url_shortcut(directory, item)
        raise RuntimeError("课件缺少 reference_id，无法获取签名下载地址")

    signed_payload = _get_json(
        session,
        f"/api/uploads/reference/{item.reference_id}/url",
        "签名下载地址读取",
    )
    signed_url = signed_payload.get("url") if isinstance(signed_payload, dict) else ""
    if not isinstance(signed_url, str) or not signed_url.startswith(("https://", "http://")):
        raise RuntimeError("平台未返回有效的签名下载地址")
    return _download_url(session, signed_url, directory, item.filename or item.activity_title)


def _download_url(session, url: str, directory: Path, filename: str) -> Path:
    target = available_path(directory, filename or url.rsplit("/", 1)[-1] or "courseware")
    partial = target.with_name(target.name + ".part")
    # 断点续传：.part 已有字节则带 Range 续传（平台实测返回 206 Partial Content）。
    # 服务端忽略 Range 返回 200 时按全量覆盖处理——文件可能已变化，不可盲目追加。
    resume_from = partial.stat().st_size if partial.exists() else 0
    request_headers = {"User-Agent": headers["User-Agent"]}
    if resume_from > 0:
        request_headers["Range"] = f"bytes={resume_from}-"
    response = session.get(
        url,
        headers=request_headers,
        timeout=DOWNLOAD_TIMEOUT,
        stream=True,
    )
    try:
        if response.status_code == 401:
            # 401 = 会话确定失效：类型化上抛引导重新登录（与 Android CoursewareClient
            # downloadUrl 的 401/403 分流对齐；PermissionError 会被上层当普通失败，
            # 用户得不到"请重新登录"的指引）
            raise SessionExpiredError("登录已过期，请重新登录后再试")
        if response.status_code == 403:
            # 403 = 平台拒绝（版权保护/防盗链/无权限），不是会话过期：
            # 不应触发续登风暴（A11 语义，与 Android 端一致）
            raise PermissionError("平台拒绝下载该课件")
        response.raise_for_status()
        # 会话过期时签名地址会 302 跳身份域/登录页：流式响应不读 body 探测，
        # 仅按最终 URL 与跳转事实判定（与 _get_json 同语义）
        if response_session_expired(response, peek_body=False):
            raise SessionExpiredError("登录已过期，请重新登录后再试")
        content_type = (response.headers.get("Content-Type") or "").lower()
        if "text/html" in content_type:
            raise RuntimeError("下载返回了登录页面，请重新登录后再试")
        # 对齐 Android FileDownloadTransport 非文件载荷判别：JSON（错误响应）/
        # xhtml 同样不是文件载荷——否则错误体写入 .part，下次以污染长度作
        # Range 起点续传出损坏文件
        if "application/json" in content_type or "application/xhtml" in content_type:
            raise RuntimeError("下载返回了非文件内容，请稍后再试")
        # 206 = 服务端确认从断点续传，追加写；200 = 全量响应，覆盖重下
        append_mode = resume_from > 0 and response.status_code == 206
        with partial.open("ab" if append_mode else "wb") as file:
            for chunk in response.iter_content(chunk_size=1024 * 256):
                if chunk:
                    file.write(chunk)
        # 短 body 干净收尾校验：服务端提前断流但连接正常结束时 iter_content
        # 不报错，截断文件会被 os.replace 扶正为成品——比对 Content-Length
        # （206 断点续传时按起始偏移折算），不符即报错并保留 .part 供续传
        expected_total = str(response.headers.get("Content-Length") or "")
        if expected_total.isdigit():
            base = resume_from if append_mode else 0
            received = partial.stat().st_size - base
            if received != int(expected_total):
                raise RuntimeError(
                    f"下载不完整（收到 {received}/{expected_total} 字节），已保留断点续传记录"
                )
        os.replace(partial, target)
        return target
    finally:
        response.close()
        # 失败时刻意保留 .part：已下载的字节是断点续传的依据。
        # 所有失败分支都在写文件之前抛出（流式探测不消费 body），
        # 或写入了合法的前缀字节，保留均安全；成功路径已 os.replace 不存在。
