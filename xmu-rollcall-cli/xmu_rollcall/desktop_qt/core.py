"""Shared dashboard data models and TronClass query helpers."""

from __future__ import annotations

import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from ..config import (
    DEFAULT_POLL_INTERVAL_SECONDS,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
)
from ..engine import RollcallEngine
from ..rollcall_models import (
    ROLLCALLS_URL,
    RollcallEvent,
    build_rollcall_event,
    first_value,
    format_duration,
    remaining_seconds_from_deadline,
)
from ..utils import (
    API_TIMEOUT,
    RetryCancelled,
    SessionExpiredError,
    base_url,
    clone_session,
    headers,
    remember_good_endpoint,
    ordered_endpoints,
    response_session_expired,
    retry_request,
)
from ..verify import find_number_code

COURSE_ENDPOINTS = (
    "/api/my-courses?per_page=1000",
    "/api/courses?role=student&per_page=1000",
    "/api/courses?course_role=student&per_page=1000",
    "/api/my/courses?per_page=1000",
    "/api/courses?per_page=1000",
    "/api/courses",
)

ROLLCALL_ENDPOINT_TEMPLATES = (
    "/api/course/{course_id}/student/{student_id}/rollcalls?page=1&page_size=100",
    "/api/course/{course_id}/student/{student_id}/rollcalls",
)

# 签到情况页并发抓取各课程签到列表的工作线程数上限：4 路足以显著缩短 N 课程串行
# 等待，又不过度放大对平台的瞬时请求压力。
COURSE_ROLLCALL_WORKERS = 4

# 自适应轮询：存在进行中的签到时（remaining_seconds > 0）的密集轮询间隔上限。
# 只在课堂签到窗口内生效，空闲时段仍按用户设定间隔轮询。
ACTIVE_POLL_INTERVAL_SECONDS = 5


@dataclass
class CourseRollcallRecord:
    course_id: str
    course_title: str
    rollcall_id: str
    rollcall_time: str
    rollcall_type: str
    signed_status: str
    platform_status: str
    detail: str = ""


class MonitorWorker(threading.Thread):
    """Poll rollcall events in a background thread and emit tuple events."""

    # 去重集合上限：托盘常驻监控可能连续运行数天，seen_rollcall_ids 若无界增长
    # 会持续吃内存。用 OrderedDict 做"插入有序集合"，超过上限淘汰最早插入的 ID，
    # 既能正确去重（新轮次的老事件不会重复 emit），又把内存占用钉在常量级。
    MAX_SEEN_ROLLCALL_IDS = 512

    def __init__(self, session, emit, stop_event: threading.Event, interval: int):
        super().__init__(daemon=True)
        # 独立 cookiejar：避免与课件池/应答 worker 并发请求时竞争写入主 session 的
        # cookiejar（requests 不保证 Session 线程安全）。轮询是只读 GET，无 cookie 回写需求。
        self.session = clone_session(session)
        # C2：轮询逻辑统一走 RollcallEngine（拉取→会话判定→解析唯一实现），
        # 去重集合仍保留在本 worker（状态所有者），杜绝 engine/core 双份逻辑漂移。
        self.engine = RollcallEngine(self.session)
        self.emit = emit
        self.stop_event = stop_event
        self.interval = min(
            MAX_POLL_INTERVAL_SECONDS,
            max(MIN_POLL_INTERVAL_SECONDS, int(interval or DEFAULT_POLL_INTERVAL_SECONDS)),
        )
        self.query_count = 0
        # OrderedDict 作为有界去重集合：键即 rollcall_id，值为占位 True。
        # 超过 MAX_SEEN_ROLLCALL_IDS 时 popitem(last=False) 淘汰最早插入者。
        self.seen_rollcall_ids: "OrderedDict[str, bool]" = OrderedDict()
        self.last_payload = {"rollcalls": []}

    def run(self):
        self.emit(("monitor_status", "运行中"))
        while not self.stop_event.is_set():
            active_rollcall = False
            try:
                payload = retry_request(
                    lambda: self.engine.poll_payload(),
                    max_attempts=3,
                    delay=2,
                    label="dashboard_poll",
                    # 可中断重试：停止信号立即生效，旧线程不会赖在重试 sleep 里
                    # （否则暂停后立刻启动会被 is_alive() 挡下，监控静默停摆）
                    stop_event=self.stop_event,
                )
                self.query_count += 1
                self.emit(("poll", self.query_count, time.time(), len(payload.get("rollcalls", []))))

                events = self.engine.build_events(payload)
                # 自适应轮询节奏：存在进行中的签到（remaining_seconds > 0）时切密集
                # 轮询，空闲时段维持用户设定间隔。平均发现延迟从 interval/2 降到
                # ACTIVE_POLL_INTERVAL_SECONDS/2；密集期只覆盖课堂签到窗口（短时），
                # 日均请求量几乎不变。
                active_rollcall = any(
                    event.remaining_seconds is not None and event.remaining_seconds > 0
                    for event in events
                )
                if payload != self.last_payload:
                    self.last_payload = payload
                    for event in events:
                        if not event.rollcall_id or event.rollcall_id in self.seen_rollcall_ids:
                            continue
                        # 有界去重：插入新 ID 后若超上限，淘汰最早插入者，保证长期
                        # 常驻运行内存占用恒定（托盘监控可能连续运行数天）。
                        self.seen_rollcall_ids[event.rollcall_id] = True
                        if len(self.seen_rollcall_ids) > self.MAX_SEEN_ROLLCALL_IDS:
                            self.seen_rollcall_ids.popitem(last=False)
                        self.emit(("rollcall", event))
            except RetryCancelled:
                break
            except SessionExpiredError as exc:
                # 会话过期是终态：立即停止轮询，避免每隔 interval 继续 302 跳身份域登录页
                # （对登录敏感主机的无谓接触）。GUI 收到 error 事件后引导用户重新登录。
                self.emit(("error", f"轮询失败：{exc}"))
                break
            except Exception as exc:
                if self.stop_event.is_set():
                    break
                self.emit(("error", f"轮询失败：{exc}"))

            wait_seconds = (
                min(self.interval, ACTIVE_POLL_INTERVAL_SECONDS)
                if active_rollcall
                else self.interval
            )
            self.stop_event.wait(wait_seconds)
        self.emit(("monitor_status", "已停止"))


def current_academic_year_label() -> str:
    now = time.localtime()
    start_year = now.tm_year if now.tm_mon >= 9 else now.tm_year - 1
    return f"{start_year}-{start_year + 1}"


def unwrap_list(payload, keys: tuple[str, ...]):
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    for key in keys:
        value = payload.get(key)
        if isinstance(value, list):
            return value
        if isinstance(value, dict):
            nested = unwrap_list(value, keys)
            if nested:
                return nested
    for value in payload.values():
        if isinstance(value, dict):
            nested = unwrap_list(value, keys)
            if nested:
                return nested
    return []


def fetch_first_json(session, endpoints: tuple[str, ...], label: str, purpose: str | None = None):
    """依次探测候选端点取第一个 200 JSON。

    purpose：端点记忆键。传入后（如 "courses"）上次成功的端点会被记住并在下次
    首选，省掉 1~5 个必败探测请求；不传则不做记忆（如按课程格式化的签到列表
    URL 各不相同，记忆无意义）。
    """
    errors = []
    candidates = ordered_endpoints(purpose, endpoints) if purpose else list(endpoints)
    for endpoint in candidates:
        url = endpoint if endpoint.startswith("http") else f"{base_url}{endpoint}"
        try:
            response = session.get(url, headers=headers, timeout=API_TIMEOUT)
            # 401 = 会话确定失效：类型化上抛走重新登录引导（与 courseware._get_json、
            # Android 端对齐）；403 保持"资源级无权限"语义按普通探测失败继续。
            if response.status_code == 401:
                raise SessionExpiredError(f"{label}失败：登录已过期，请重新登录")
            if response.status_code == 403:
                raise RuntimeError(f"{label}失败：登录态已失效或当前账号无权访问")
            if response.status_code != 200:
                errors.append(f"{endpoint} -> HTTP {response.status_code}")
                continue
            # 会话过期时平台 302 跳统一身份域（requests 自动跟随成 200 登录页）：
            # 仅凭状态码无法识别，下一行 response.json() 会抛 ValueError 掩盖真实原因为
            # "接口探测失败"。与 courseware._get_json 对齐，显式判定为会话过期上抛，
            # 由上层引导重新登录（P1/P2 过期分流），不按普通探测失败重试其它端点。
            if response_session_expired(response):
                raise SessionExpiredError(f"{label}失败：登录已过期，请重新登录")
            if purpose:
                remember_good_endpoint(purpose, endpoint)
            return response.json(), endpoint
        except SessionExpiredError:
            raise
        except Exception as exc:
            errors.append(f"{endpoint} -> {exc}")
    raise RuntimeError(f"{label} 接口探测失败：" + "；".join(errors[:6]))


def normalize_course(course: dict) -> dict | None:
    if not isinstance(course, dict):
        return None
    course_id = first_value(course, ("id", "course_id", "courseId", "cid", "uuid"))
    title = first_value(course, ("name", "title", "course_title", "course_name", "display_name"))
    if not course_id or not title:
        return None
    academic_year = course.get("academic_year")
    term = ""
    if isinstance(academic_year, dict):
        term = first_value(academic_year, ("name", "code", "id"), "")
    if not term:
        term = first_value(course, ("term_name", "term", "semester", "semester_name", "academic_year", "year"), "")
    return {
        "id": str(course_id),
        "title": str(title),
        "term": str(term),
        "raw": course,
    }


def course_matches_semester(course: dict, semester: str) -> bool:
    if not semester or semester == "全部":
        return True

    raw_semester = course.get("raw", {}).get("semester")
    if isinstance(raw_semester, dict):
        semester_code = str(raw_semester.get("code") or "")
    else:
        semester_code = str(raw_semester or course.get("term") or "")

    if semester == "第一学期":
        return semester_code.endswith("-1")
    if semester == "第二学期":
        return semester_code.endswith("-2")
    if semester == "第三学期":
        return semester_code.endswith("-3")
    return True


def normalize_rollcall_type(rollcall: dict) -> str:
    if rollcall.get("is_radar"):
        return "雷达签到"
    if rollcall.get("is_number"):
        return "数字签到"
    if rollcall.get("is_qrcode") or rollcall.get("is_qr"):
        return "二维码签到"
    value = str(first_value(rollcall, ("rollcall_type", "type", "kind"), "")).lower()
    if "radar" in value:
        return "雷达签到"
    if "number" in value:
        return "数字签到"
    if "qr" in value:
        return "二维码签到"
    return value or "签到"


def normalize_time(value) -> str:
    if not value:
        return "-"
    text = str(value)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return parsed.strftime("%Y-%m-%d %H:%M:%S")
        return parsed.astimezone(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        # 兜底剥离时区后缀：split("+") 只对正偏移有效，负偏移（-05:00）会残留
        text = text.replace("T", " ").replace("Z", "")
        return re.sub(r"\s*([+-]\d{2}:?\d{2})?$", "", text).strip()


# 签到状态词表（A5 双端对齐，与 Android normalizedRollcallStatus 同语义）：
# 必须先判「未签」再判「已签」——unsigned/not_signed 都含 signed 子串，顺序颠倒会把
# 未签误判为已签（用户看着"已签"不去处理，错过签到）。分词用非字母数字边界精确比对，
# 避免 dismiss 命中 miss、define/refine 命中 fine 的子串误判。
ROLLCALL_SIGNED_STATUS_TOKENS = frozenset({"signed", "present", "attended", "on_call_fine", "fine", "done"})
ROLLCALL_ABSENT_STATUS_TOKENS = frozenset({"absent", "missed", "miss", "unanswered"})


def classify_rollcall_status(value) -> str | None:
    """把平台状态原文归类为 '已签到' / '未签到' / None(未知)。纯函数便于单测。

    分词后整词比对；中文状态按包含判定（平台偶有 "已签到"/"未签到" 中文原值）。
    """
    lowered = str(value or "").strip().lower()
    if not lowered:
        return None
    if "未签" in lowered:
        return "未签到"
    if "已签" in lowered:
        return "已签到"
    tokens = {token for token in re.split(r"[^a-z0-9]+", lowered) if token}
    if (
        "unsigned" in tokens
        or tokens & ROLLCALL_ABSENT_STATUS_TOKENS
        or ("not" in tokens and "signed" in tokens)
    ):
        return "未签到"
    if tokens & ROLLCALL_SIGNED_STATUS_TOKENS:
        return "已签到"
    return None


def infer_signed_status(rollcall: dict, student_detail: dict | None, username: str) -> tuple[str, str]:
    platform_status = str(first_value(rollcall, ("status", "rollcall_status", "state"), "unknown"))
    status_text = " ".join(
        str(first_value(rollcall, keys, "")).lower()
        for keys in (
            ("status",),
            ("rollcall_status",),
            ("student_status",),
            ("state",),
        )
    )

    if student_detail:
        own_record = find_student_rollcall(student_detail, username)
        if own_record:
            if own_record.get("updated_at") or own_record.get("answered_at") or own_record.get("submitted_at"):
                return "已签到", platform_status
            own_status = str(first_value(own_record, ("status", "rollcall_status", "state"), "")).lower()
            classified = classify_rollcall_status(own_status)
            if classified is not None:
                return classified, platform_status

    classified = classify_rollcall_status(status_text)
    if classified is not None:
        return classified, platform_status
    return "未知", platform_status


def get_profile_user_id(session) -> str:
    response = session.get(f"{base_url}/api/profile", headers=headers, timeout=(6, 15))
    response.raise_for_status()
    profile = response.json()
    user_id = first_value(profile, ("id", "user_id", "userId", "student_id", "studentId"), "")
    if not user_id:
        raise RuntimeError("无法从 /api/profile 获取平台内部用户 ID")
    return str(user_id)


def find_student_rollcall(payload, username: str) -> dict | None:
    students = unwrap_list(payload, ("student_rollcalls", "students", "data", "items", "list"))
    for student in students:
        if not isinstance(student, dict):
            continue
        user_no = str(first_value(student, ("user_no", "username", "student_no", "number", "account"), ""))
        if username and user_no == username:
            return student
        if student.get("is_current_user") or student.get("is_self"):
            return student
    return None


def fetch_student_rollcall_detail(session, rollcall_id: str):
    if not rollcall_id:
        return None
    try:
        response = session.get(f"{base_url}/api/rollcall/{rollcall_id}/student_rollcalls", headers=headers, timeout=12)
        if response.status_code == 200:
            return response.json()
    except Exception:
        pass
    return None


def fetch_number_code(session, rollcall_id: str) -> str:
    detail = fetch_student_rollcall_detail(session, rollcall_id)
    if not detail:
        return ""
    return find_number_code(detail) or ""


def fetch_course_rollcall_records(
    session,
    username: str,
    academic_year: str,
    semester: str = "全部",
) -> tuple[list[CourseRollcallRecord], str]:
    student_id = get_profile_user_id(session)
    courses_payload, course_endpoint = fetch_first_json(
        session, COURSE_ENDPOINTS, "课程列表", purpose="courses",
    )
    raw_courses = unwrap_list(courses_payload, ("courses", "data", "items", "list", "results"))
    courses = [course for course in (normalize_course(item) for item in raw_courses) if course]

    if academic_year:
        filtered = [
            course for course in courses
            if academic_year in course["term"] or academic_year in str(course["raw"])
        ]
        if filtered:
            courses = filtered

    courses = [course for course in courses if course_matches_semester(course, semester)]

    source = f"课程接口：{course_endpoint}；筛选：{academic_year or '全部学年'} / {semester or '全部'}"
    if not courses:
        return [], source

    # 并发抓取各课程签到列表：原串行 N 课程 × M 端点是「签到情况」页的主要等待来源。
    # 与 courseware.fetch_courseware 同款做法：每线程持有独立克隆 Session（threadlocal），
    # 避免 N 线程并发共用主 session 的 cookiejar 竞争写（requests 不保证 Session 线程安全）。
    # 签到列表是只读 GET，无 Set-Cookie 回写需求，clone 用完即弃。
    thread_local = threading.local()

    def fetch_one_course(course: dict) -> tuple[list[CourseRollcallRecord], str | None]:
        # SessionExpiredError 不在此捕获：会话过期是终态，应整体上抛由 app.py 引导重新登录，
        # 而不是为每门课生成一条"未知"错误记录后继续打身份域（与 retry_request 语义一致）。
        thread_session = getattr(thread_local, "session", None)
        if thread_session is None:
            thread_session = clone_session(session)
            thread_local.session = thread_session

        endpoints = tuple(
            template.format(course_id=course["id"], student_id=student_id)
            for template in ROLLCALL_ENDPOINT_TEMPLATES
        )
        try:
            rollcalls_payload, rollcall_endpoint = fetch_first_json(
                thread_session, endpoints, f"{course['title']} 签到列表",
            )
        except SessionExpiredError:
            raise
        except Exception as exc:
            return [CourseRollcallRecord(
                course_id=course["id"],
                course_title=course["title"],
                rollcall_id="-",
                rollcall_time="-",
                rollcall_type="-",
                signed_status="未知",
                platform_status="-",
                detail=str(exc),
            )], None

        rollcalls = unwrap_list(rollcalls_payload, ("rollcalls", "activities", "data", "items", "list", "results"))
        if not rollcalls:
            return [CourseRollcallRecord(
                course_id=course["id"],
                course_title=course["title"],
                rollcall_id="-",
                rollcall_time="-",
                rollcall_type="-",
                signed_status="无签到记录",
                platform_status="-",
                detail="该课程接口未返回签到记录",
            )], rollcall_endpoint

        course_records: list[CourseRollcallRecord] = []
        for rollcall in rollcalls:
            if not isinstance(rollcall, dict):
                continue
            rollcall_id = str(first_value(rollcall, ("rollcall_id", "id", "activity_id"), ""))
            signed_status, platform_status = infer_signed_status(rollcall, None, username)
            course_records.append(CourseRollcallRecord(
                course_id=course["id"],
                course_title=course["title"],
                rollcall_id=rollcall_id or "-",
                rollcall_time=normalize_time(first_value(rollcall, ("rollcall_time", "created_at", "start_time", "time"), "")),
                rollcall_type=normalize_rollcall_type(rollcall),
                signed_status=signed_status,
                platform_status=platform_status,
                detail="",
            ))
        return course_records, rollcall_endpoint

    # max_workers 上限：4 路并发足以显著缩短 N 课程的串行等待，又不过度放大对
    # 平台的瞬时请求压力（与 courseware 的 8 线程相比更保守，签到列表更轻量）。
    max_workers = min(COURSE_ROLLCALL_WORKERS, len(courses))
    records: list[CourseRollcallRecord] = []
    endpoint_hits: list[str] = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        # executor.map 按提交顺序产出结果，保证 records 的课程顺序与原串行实现一致。
        for course_records, rollcall_endpoint in executor.map(fetch_one_course, courses):
            records.extend(course_records)
            if rollcall_endpoint and rollcall_endpoint not in endpoint_hits:
                endpoint_hits.append(rollcall_endpoint)

    if endpoint_hits:
        source += f"；签到接口示例：{endpoint_hits[0]}"
    return records, source
