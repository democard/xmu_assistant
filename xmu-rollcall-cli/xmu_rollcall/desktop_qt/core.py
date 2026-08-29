"""Shared dashboard data models and TronClass query helpers."""

from __future__ import annotations

import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone

from ..config import (
    DEFAULT_POLL_INTERVAL_SECONDS,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
)
from .. import __version__
from .. import request_probe
from ..engine import RollcallEngine
from ..rollcall_models import (
    RollcallEvent,
    build_rollcall_event,
    first_value,
    format_duration,
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
    unwrap_list,
)
from ..verify import find_number_code

# 课程列表端点单一来源（2026-08-28 C3 单点化）：原为与 courseware.py 逐字重复的
# 双份元组，漂移只能靠 B11 守护事后拦截；改导入后结构上不可能漂移。
# core 的 fetch_course_rollcall_records 与 courseware.fetch_courses 共用同一端点序。
from ..courseware import COURSE_ENDPOINTS

ROLLCALL_ENDPOINT_TEMPLATES = (
    "/api/course/{course_id}/student/{student_id}/rollcalls?page=1&page_size=100",
    "/api/course/{course_id}/student/{student_id}/rollcalls",
)

# 签到情况页并发抓取各课程签到列表的工作线程数上限：4 路足以显著缩短 N 课程串行
# 等待，又不过度放大对平台的瞬时请求压力。
COURSE_ROLLCALL_WORKERS = 4

# L1 自动核实的记录数上限：只对时间上最近的 N 条补拉本人签到明细（student_rollcalls），
# 把额外请求量钉在常数级；更早的记录保持聚合状态，用户可选中后手动「核实所选」。
RECENT_VERIFY_LIMIT = 20

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
    # True 表示 signed_status 已按本人签到明细核实（而非聚合状态推断）。
    # 带默认值：旧 UI 快照缺该键仍可反序列化，无需 bump 快照版本。
    verified: bool = False


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
                # 用户主动停止（登出/暂停）触发的过期不再发 error——否则登出后仍会弹
                # 「紧急通知+第三方推送」（_ev_error 的 immediate 分支），与通用异常
                # 分支的 stop_event 检查对齐。
                if not self.stop_event.is_set():
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


def fetch_first_json(session, endpoints: tuple[str, ...], label: str, purpose: str | None = None):
    """依次探测候选端点取第一个 200 JSON。

    判定层（401/403/会话 302 页面）来自 request_probe 单一来源；探测场景的
    处置语义：401 上抛终止，403 与其它失败记账后继续试下一端点。
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
            # 命中 401（SessionExpiredError）在下方 except 里原样重抛；
            # 403 的 RuntimeError 落入 except Exception 记账续探（保留原语义）。
            verdict = request_probe.classify_status(response.status_code, label)
            if verdict is not None:
                raise verdict
            if response.status_code != 200:
                errors.append(f"{endpoint} -> HTTP {response.status_code}")
                continue
            # 会话过期时平台 302 跳统一身份域（requests 自动跟随成 200 登录页）：
            # 仅凭状态码无法识别，显式判定为会话过期上抛，由上层引导重新登录
            # （P1/P2 过期分流），不按普通探测失败重试其它端点。
            if request_probe.detect_expired_page(response):
                raise request_probe.session_expired_error(label)
            payload = response.json()
            if purpose:
                # 解析成功才记好端点：记账先于解析时，解析失败也把该端点置顶为
                # 下次首选、必先再败一次（与 courseware._get_json 记账时机对称）
                remember_good_endpoint(purpose, endpoint)
            return payload, endpoint
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
    if isinstance(term, dict):
        # 平台偶发把 term 字段下发为对象：与 courseware.fetch_courses 的 dict
        # 解包防御对齐，不把 str(dict) 落进展示与学年筛选
        term = first_value(term, ("name", "code", "id"), "")
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


def parse_rollcall_time(text) -> datetime | None:
    """把签到时间文本解析为 naive datetime（排序/选样用）；解析失败返回 None。

    从 app.py 展示层 `_course_record_datetime` 下沉为纯函数（GUI 方法改委托，
    行为不变）：先整串、再截前 19 字符各尝试一次 fromisoformat，统一去掉
    tzinfo——normalize_time 已把带偏移的时间归一为 +08 挂钟文本，这里再剥
    tzinfo 保证跨记录可比。
    """
    value = str(text or "").strip()
    if not value or value == "-":
        return None
    for candidate in (value, value[:19]):
        try:
            return datetime.fromisoformat(candidate.replace("Z", "+00:00")).replace(tzinfo=None)
        except ValueError:
            continue
    return None


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
    response = session.get(f"{base_url}/api/profile", headers=headers, timeout=API_TIMEOUT)
    response.raise_for_status()
    # 与 fetch_first_json/_get_json 同款过期页判定：会话过期时平台把本请求
    # 302 到身份域登录页（自动跟随成 200 HTML），裸 .json() 只会抛
    # JSONDecodeError，消费端无法给出重新登录引导
    if request_probe.detect_expired_page(response):
        raise request_probe.session_expired_error("个人资料")
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
        response = session.get(
            f"{base_url}/api/rollcall/{rollcall_id}/student_rollcalls",
            headers=headers,
            # 统一超时口径（体检 P2 遗留）：原标量 12 会同时作用于连接与读取
            timeout=API_TIMEOUT,
        )
    except Exception:
        return None
    # 会话过期严格分流（风控红线，与 fetch_first_json/courseware 同语义）：
    # 身份域 302/登录页按类型化异常上抛——否则 L1 批量核实会对过期会话连打
    # N 个必败请求空跑登录域。其余非 200 保持"资源级失败"返回 None。
    if response_session_expired(response):
        raise SessionExpiredError("签到明细获取失败：登录已过期，请重新登录")
    if response.status_code == 200:
        try:
            return response.json()
        except ValueError:
            # 200 + 非 JSON（网关错误页直返 200 且无重定向）：与其它非 200 同按
            # 「资源级失败」返回 None，不穿透 L1 核实/数字签到码 worker
            return None
    return None


def verify_own_status(student_detail, username: str, fallback_platform_status: str = "") -> str | None:
    """按本人签到明细判定准确状态；无法判定返回 None。

    返回 None 时调用方保持聚合状态词（fallback_platform_status）不变——即
    "无本人记录 / 明细为空 / 本人状态词无法分类"三种情形都不改写展示值，
    宁可保守也不拿聚合值冒充本人结论。纯函数便于单测。
    """
    own_record = find_student_rollcall(student_detail or {}, username)
    if not own_record:
        return None
    if own_record.get("updated_at") or own_record.get("answered_at") or own_record.get("submitted_at"):
        return "已签到"
    own_status = str(first_value(own_record, ("status", "rollcall_status", "state"), "")).lower()
    return classify_rollcall_status(own_status)


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
    """拉取各课程签到列表，返回聚合状态的记录（L1 阶段一）。

    本人明细核实由 ``verify_recent_rollcall_records`` 单独承担——两者必须
    保持「先渲染聚合状态、再补发核实结果」的顺序，否则核实结果会被阶段一
    的全新记录覆盖（真机验收发现的时序缺陷）。
    """
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
    # 熔断事件：某课程确认会话过期（终态）后，同池排队任务入口检查即跳过，
    # 不再对登录域空跑剩余请求（镜像阶段二 verify_recent_rollcall_records
    # 的 stop 模式；executor.map 抛出后线程池仍会执行已提交任务）。
    stop = threading.Event()

    def fetch_one_course(course: dict) -> tuple[list[CourseRollcallRecord], str | None]:
        # SessionExpiredError 不在此捕获：会话过期是终态，应整体上抛由 app.py 引导重新登录，
        # 而不是为每门课生成一条"未知"错误记录后继续打身份域（与 retry_request 语义一致）。
        if stop.is_set():
            return [], None
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
            stop.set()
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


def verify_recent_rollcall_records(
    session,
    username: str,
    records: list[CourseRollcallRecord],
    limit: int = RECENT_VERIFY_LIMIT,
) -> list[CourseRollcallRecord]:
    """L1 阶段二：按时间降序对最近 limit 条补拉本人明细核实（只读 GET）。

    返回发生变化的更新记录列表（``dataclasses.replace`` 生成，verified=True；
    即使判定结果与聚合值相同也回执——tooltip 需要如实标注「已按本人明细核实」）。
    明细不可得（非 200/网络抖动/无法判定）的记录不出现在回执中，聚合值保持不变。

    会话过期：任务内置位 ``stop`` 熔断事件再上抛 SessionExpiredError——同池
    排队的后续任务入口检查即跳过，不对登录域空跑剩余请求；在途的 ≤ 并发数个
    请求自然收尾。调用方必须在发出 course_rollcalls 首屏事件**之后**再调用
    本函数（事件顺序契约：渲染先行、核实覆盖在后）。
    """
    candidates = sorted(
        (r for r in records if r.rollcall_id and r.rollcall_id != "-"),
        key=lambda record: parse_rollcall_time(record.rollcall_time) or datetime.min,
        reverse=True,
    )[:limit]
    if not candidates:
        return []

    # 每次调用独立的 threadlocal：与 fetch_course_rollcall_records 的克隆会话
    # 策略一致（只读 GET 无 cookie 回写需求，clone 用完即弃）。
    thread_local = threading.local()
    stop = threading.Event()

    def verify_one(record: CourseRollcallRecord):
        if stop.is_set():
            return None
        thread_session = getattr(thread_local, "session", None)
        if thread_session is None:
            thread_session = clone_session(session)
            thread_local.session = thread_session
        try:
            detail = fetch_student_rollcall_detail(thread_session, record.rollcall_id)
        except SessionExpiredError:
            # 任务内置位再上抛：同池排队的后续任务立刻可见，不再空跑登录域
            stop.set()
            raise
        verdict = verify_own_status(detail, username, record.signed_status)
        if verdict is None:
            return None
        return replace(record, signed_status=verdict, verified=True)

    verified_records: list[CourseRollcallRecord] = []
    max_workers = min(COURSE_ROLLCALL_WORKERS, len(candidates))
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        try:
            for updated in executor.map(verify_one, candidates):
                if updated is not None:
                    verified_records.append(updated)
        except SessionExpiredError:
            # 迭代侧兜底置位（多 worker 在途场景）
            stop.set()
            raise
    return verified_records

# ---------------------------------------------------------------------------
# 签到情况导出与统计（纯本地聚合，供 CSV 导出与统计面板复用）
# ---------------------------------------------------------------------------

# 状态显示口径（自 app.py 下沉，单一来源）：原始状态 → 表格/导出统一文案。
COURSE_STATUS_DISPLAY = {
    "未签到": "未签",
    "未签": "未签",
    "未知": "未知",
    "已签到": "已签",
    "已签": "已签",
    "无签到记录": "无记录",
    "无记录": "无记录",
}

COURSE_STATUS_PRIORITY = {
    "未签": 0,
    "未知": 1,
    "已签": 2,
    "无记录": 3,
}


def course_display_status(status: str) -> str:
    """原始 signed_status → 显示口径文案（未知值原样透传，空串归为「未知」）。"""
    return COURSE_STATUS_DISPLAY.get(status, status or "未知")


@dataclass(frozen=True)
class CourseRollcallStat:
    """按课程聚合的签到统计（分母只计真实签到事件，「无记录」占位行不计入）。"""

    course_title: str
    total_rollcalls: int
    signed: int
    unsigned: int
    unknown: int

    @property
    def signed_rate(self) -> float:
        """已签比例 [0,1]；无真实签到事件时 0.0（不除零）。"""
        if self.total_rollcalls <= 0:
            return 0.0
        return self.signed / self.total_rollcalls


def course_rollcall_stats(records: list[CourseRollcallRecord]) -> list[CourseRollcallStat]:
    """按课程聚合签到状态；输出按课程名排序，保证导出/展示/测试三方一致。"""
    buckets: dict[str, dict[str, int]] = {}
    for record in records:
        bucket = buckets.setdefault(
            record.course_title or "（未知课程）",
            {"signed": 0, "unsigned": 0, "unknown": 0, "placeholder": 0},
        )
        display = course_display_status(record.signed_status)
        if display == "已签":
            bucket["signed"] += 1
        elif display == "未签":
            bucket["unsigned"] += 1
        elif display == "无记录":
            bucket["placeholder"] += 1
        else:
            bucket["unknown"] += 1
    return [
        CourseRollcallStat(
            course_title=title,
            total_rollcalls=b["signed"] + b["unsigned"] + b["unknown"],
            signed=b["signed"],
            unsigned=b["unsigned"],
            unknown=b["unknown"],
        )
        for title, b in sorted(buckets.items())
    ]


def course_rollcall_csv(records: list[CourseRollcallRecord]) -> str:
    """签到记录序列化为 CSV 文本（写入层以 utf-8-sig 落盘，Excel 可直接打开）。

    - 列对齐「签到情况」表格并补充平台状态/核实标记/备注；
    - 状态列用显示口径（course_display_status），与页面逐字一致；
    - 日期列取签到时间解析出的 ISO 日期，解析失败留空（不猜）；
    - 字段转义遵循 RFC 4180（csv 模块）；行序与输入一致（导出即所见）。
    """
    import csv
    import io as _io

    buffer = _io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["日期", "课程", "签到时间", "类型", "状态", "平台状态", "已核实", "签到ID", "备注"])
    for record in records:
        record_time = parse_rollcall_time(record.rollcall_time)
        writer.writerow(
            [
                record_time.date().isoformat() if record_time else "",
                record.course_title,
                record.rollcall_time,
                record.rollcall_type,
                course_display_status(record.signed_status),
                record.platform_status,
                "是" if record.verified else "否",
                record.rollcall_id,
                record.detail,
            ]
        )
    return buffer.getvalue()


def format_log_export(lines: list[str]) -> str:
    """把内存日志渲染为可存档文本（写入层以 utf-8-sig 落盘）。

    - 导出头含导出时间 / 应用版本 / 日志条数，便于问题反馈时定位环境；
    - 行内容逐字保留（时间戳已在 self.log() 生成时写入），不改写不排序。
    """
    header = (
        "xmu助手 运行日志导出\n"
        f"导出时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
        f"应用版本：v{__version__}\n"
        f"日志条数：{len(lines)}\n"
    )
    body = "\n".join(lines)
    if not body:
        return header
    return f"{header}{'=' * 32}\n{body}\n"

