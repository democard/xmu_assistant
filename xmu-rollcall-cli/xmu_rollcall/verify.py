import math
import re
import uuid

import requests

from .number_code import complete_number_code, find_number_code  # re-export legacy helper
from .request_probe import classify_status
from .radar_geometry import solve_spherical_candidates
from .utils import (
    API_TIMEOUT,
    SessionExpiredError,
    base_url,
    response_session_expired,
    retry_request,
)


def _raise_if_session_expired(response):
    """会话过期判定：302 落身份域/登录页（最终 200 HTML）也必须识别为过期。

    requests 对 302 的 PUT 重放会降级为 GET，最终停在 200 登录页——仅凭
    status_code==200 会假成功（界面记「已签到」实际未签成）。send_* 的
    SessionExpiredError 透传契约（见各 docstring 与 send_radar 网络层注释）
    由上游 answer_failure_detail 分流，此处让契约在过期页场景真正生效。
    """
    # 401 是确定失效；403 的资源权限语义仍由调用方按原有 bool 契约处理。
    if response.status_code == 401:
        raise classify_status(response.status_code, "签到应答")
    if response_session_expired(response):
        raise SessionExpiredError("登录已过期，请重新登录")


# 平面辅助函数保留兼容旧调用；生产雷达路径通过 solve_two_points 使用球面交会。
def latlon_to_xy(lat, lon, lat0, lon0):
    radius = 6371000
    x = math.radians(lon - lon0) * radius * math.cos(math.radians(lat0))
    y = math.radians(lat - lat0) * radius
    return x, y

def xy_to_latlon(x, y, lat0, lon0):
    radius = 6371000
    lat = lat0 + math.degrees(y / radius)
    lon = lon0 + math.degrees(x / (radius * math.cos(math.radians(lat0))))
    return lat, lon

def circle_intersections(x1, y1, d1, x2, y2, d2):
    distance = math.hypot(x2 - x1, y2 - y1)
    if distance == 0:
        # 两打点坐标解算后重合（同心）：along 计算 2*distance 除零——
        # Android 同构 solveRadarCandidates 的 d==0.0 早退先例
        return None
    if distance > d1 + d2 or distance < abs(d1 - d2):
        return None
    along = (d1 ** 2 - d2 ** 2 + distance ** 2) / (2 * distance)
    # 两圆相切/浮点舍入时 d1²-along² 可为极小负数，sqrt 会抛 ValueError
    # 被上层当普通失败；钳到 0 退化为单点交，继续提交候选坐标。
    height = math.sqrt(max(0.0, d1 * d1 - along * along))
    midpoint_x = x1 + along * (x2 - x1) / distance
    midpoint_y = y1 + along * (y2 - y1) / distance
    offset_x = -(y2 - y1) * height / distance
    offset_y = (x2 - x1) * height / distance
    return (
        (midpoint_x + offset_x, midpoint_y + offset_y),
        (midpoint_x - offset_x, midpoint_y - offset_y),
    )

def solve_two_points(lat1, lon1, lat2, lon2, d1, d2):
    return solve_spherical_candidates(lat1, lon1, lat2, lon2, d1, d2)


def send_code(in_session, rollcall_id, number_code="", *, course_id="", rollcall_time="", course_title=""):
    """提交数字签到码。返回 True/False；网络层/SessionExpired 异常按契约处理。

    仅用于兼容层：PySide6 桌面端直接组装提交，这里保留统一 bool 契约。
    """
    code_url = f"{base_url}/api/rollcall/{rollcall_id}/student_rollcalls"
    answer_url = f"{base_url}/api/rollcall/{rollcall_id}/answer_number_rollcall"
    request_headers = in_session.headers
    number_code = str(number_code or "").strip()
    if not number_code:
        code_data = None
        try:
            code_response = retry_request(
                lambda: in_session.get(code_url, headers=request_headers, timeout=API_TIMEOUT),
                max_attempts=3,
                delay=2,
                label="get_number_code",
            )
            _raise_if_session_expired(code_response)
            if code_response.status_code == 200:
                code_data = code_response.json()
        except requests.RequestException:
            pass
        except ValueError:
            pass
        number_code = complete_number_code(
            in_session, rollcall_id, code_data, course_id=course_id,
            rollcall_time=rollcall_time, course_title=course_title,
        )
    if not number_code:
        return False

    payload = {"deviceId": str(uuid.uuid4()), "numberCode": number_code}
    try:
        response = retry_request(
            lambda: in_session.put(answer_url, json=payload, headers=request_headers, timeout=API_TIMEOUT),
            # 写请求不盲重试：超时可能只是回执丢失，重复 PUT 会造成重复签到。
            max_attempts=1,
            delay=2,
            label="answer_number",
        )
        _raise_if_session_expired(response)
        return response.status_code == 200
    except requests.RequestException:
        return False


def radar_response_detail(code, data):
    """保留可诊断的拒绝原因，不记录请求凭据、坐标或整个响应体。"""
    reason = ""
    if isinstance(data, dict):
        for key in ("message", "msg", "error", "detail", "code"):
            value = data.get(key)
            if isinstance(value, (str, int)) and not isinstance(value, bool):
                reason = str(value).strip()
                if reason:
                    break
    reason = re.sub(r"(?i)bearer\s+\S+", "Bearer [redacted]", reason)
    reason = re.sub(
        r"(?i)(password|token|cookie|authorization)\s*[:=]\s*[^\s;,]+",
        r"\1=[redacted]", reason,
    )
    reason = " ".join(reason.split())[:200]
    return f"HTTP {code}" + (f"，{reason}" if reason else "")


def send_radar(in_session, rollcall_id, *, on_failure=None):
    """两次距离查询和球面交会；写请求回执不明时停止，不盲重放。"""
    url = f"{base_url}/api/rollcall/{rollcall_id}/answer"
    device_id = str(uuid.uuid4())
    last_detail = ""

    def fail(detail):
        if on_failure is not None:
            on_failure(f"雷达签到失败：{detail}")
        return False

    def submit(lat, lon, stage):
        nonlocal last_detail
        body = {
            "accuracy": 35, "altitude": 0, "altitudeAccuracy": None,
            "deviceId": device_id, "heading": None,
            "latitude": lat, "longitude": lon, "speed": None,
        }
        try:
            response = retry_request(
                lambda: in_session.put(
                    url, json=body, headers=in_session.headers,
                    timeout=API_TIMEOUT, allow_redirects=False,
                ),
                max_attempts=1, delay=2, label="radar",
            )
            _raise_if_session_expired(response)
        except requests.RequestException:
            last_detail = f"{stage}网络回执未收到，提交结果未知；请核实本人记录"
            return None
        try:
            data = response.json()
        except ValueError:
            data = None
        last_detail = f"{stage}：{radar_response_detail(response.status_code, data)}"
        # 登录页已在上面分流；其它 HTML 网关页也不能当作成功。
        text = (getattr(response, "text", "") or "").strip()
        content_type = str(getattr(response, "headers", {}).get("Content-Type", "")).lower()
        html = data is None and (text.startswith("<") or (text and "text/html" in content_type))
        return response.status_code == 200 and not html, data

    distances = []
    for index, (lat, lon) in enumerate(((24.3, 118.0), (24.6, 118.2)), 1):
        result = submit(lat, lon, f"第{index}次距离查询")
        if result is None:
            return fail(last_detail)
        success, data = result
        if success:
            return True
        distance = data.get("distance") if isinstance(data, dict) else None
        if (
            isinstance(distance, bool) or not isinstance(distance, (int, float))
            or not math.isfinite(distance) or distance < 0
        ):
            return fail(f"{last_detail}；缺少有效的数值 distance")
        distances.append(distance)

    candidates = solve_two_points(24.3, 118.0, 24.6, 118.2, *distances)
    if not candidates:
        return fail("两次距离无法形成球面交点，请核对距离单位和返回规则")
    seen = set()
    for index, (lat, lon) in enumerate(candidates, 1):
        if (lat, lon) in seen:
            continue
        seen.add((lat, lon))
        result = submit(lat, lon, f"候选位置{index}提交")
        if result is None:
            return fail(last_detail)
        if result[0]:
            return True
    return fail(last_detail)


def send_radar_at(in_session, rollcall_id, latitude, longitude, *, on_failure=None):
    """按已配置的课程坐标提交一次，不发送任何探测坐标。

    与 Android 端 answerRadarAt 同语义：未配置坐标的课程不会被提交，避免先写入
    记录再回头修正。坐标必须是有限数值，调用方负责取课程首选地点。
    """
    try:
        latitude = float(latitude)
        longitude = float(longitude)
    except (TypeError, ValueError):
        latitude = longitude = float("nan")
    if (
        not (math.isfinite(latitude) and math.isfinite(longitude))
        or not -90.0 <= latitude <= 90.0
        or not -180.0 <= longitude <= 180.0
    ):
        if on_failure is not None:
            on_failure("雷达签到失败：课程坐标无效")
        return False

    url = f"{base_url}/api/rollcall/{rollcall_id}/answer"
    body = {
        "accuracy": 35, "altitude": 0, "altitudeAccuracy": None,
        "deviceId": str(uuid.uuid4()), "heading": None,
        "latitude": latitude, "longitude": longitude, "speed": None,
    }

    def fail(detail):
        if on_failure is not None:
            on_failure(f"雷达签到失败：{detail}")
        return False

    try:
        response = retry_request(
            lambda: in_session.put(
                url, json=body, headers=in_session.headers,
                timeout=API_TIMEOUT, allow_redirects=False,
            ),
            # 写请求不盲重试：超时可能只是回执丢失，重复 PUT 会造成重复签到。
            max_attempts=1, delay=2, label="radar",
        )
        _raise_if_session_expired(response)
    except requests.RequestException:
        return fail("网络回执未收到，提交结果未知；请核实本人记录")

    try:
        data = response.json()
    except ValueError:
        data = None
    detail = radar_response_detail(response.status_code, data)
    text = (getattr(response, "text", "") or "").strip()
    content_type = str(getattr(response, "headers", {}).get("Content-Type", "")).lower()
    html = data is None and (text.startswith("<") or (text and "text/html" in content_type))
    if response.status_code == 200 and not html:
        return True
    return fail(detail)
