"""Read-only digital rollcall code compatibility (never submit or infer attendance)."""

from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import requests

from .utils import API_TIMEOUT, SessionExpiredError, base_url, response_session_expired


def find_number_code(data, depth=0, max_depth=10):
    """Legacy detail responses contain one activity; preserve string/leading zeroes."""
    if depth > max_depth:
        return None
    if isinstance(data, dict):
        for key in ("number_code", "numberCode", "rollcall_number_code", "rollcallNumberCode"):
            code = data.get(key)
            if code is not None and str(code).strip() not in ("", "null"):
                return str(code).strip()
        children = data.values()
    elif isinstance(data, list):
        children = data
    else:
        return None
    for child in children:
        code = find_number_code(child, depth + 1, max_depth)
        if code:
            return code
    return None


def _get_json(session, path):
    try:
        response = session.get(
            base_url + path, headers=session.headers, timeout=API_TIMEOUT,
        )
    except (requests.RequestException, OSError):
        return None
    if response.status_code == 401 or response_session_expired(response):
        raise SessionExpiredError("签到码获取失败：登录已过期，请重新登录")
    if response.status_code != 200:
        return None
    try:
        return response.json()
    except ValueError:
        return None


def rollcall_code_dates(rollcall_time="", end_time="", now=None):
    """Timetable uses the UTC start date; unzoned school display times are +08."""
    for text, is_start in ((rollcall_time, True), (end_time, False)):
        try:
            parsed = datetime.fromisoformat(str(text).strip().replace("Z", "+00:00"))
        except ValueError:
            continue
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone(timedelta(hours=8)))
        day = parsed.astimezone(timezone.utc).date()
        # Only a deadline is known: the activity may have begun before UTC midnight.
        return [day.isoformat()] if is_start else [day.isoformat(), (day - timedelta(days=1)).isoformat()]
    day = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).date()
    return [day.isoformat(), (day - timedelta(days=1)).isoformat()]


def matching_timetable_code(payload, rollcall_id, course_id=""):
    """Never recursively take a code from a different activity/student in the list."""
    rows = payload.get("rollcalls", []) if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        return ""
    for row in rows:
        if not isinstance(row, dict):
            continue
        if str(row.get("rollcall_id") or row.get("id") or "") != str(rollcall_id):
            continue
        course = row.get("course") if isinstance(row.get("course"), dict) else {}
        returned_course = str(row.get("course_id") or course.get("id") or "")
        if course_id and returned_course and returned_course != str(course_id):
            continue
        if row.get("is_radar") or row.get("is_number") is False:
            continue
        for key in ("number_code", "numberCode", "rollcall_number_code", "rollcallNumberCode"):
            code = row.get(key)
            if code is not None and str(code).strip() not in ("", "null"):
                return str(code).strip()
    return ""


def complete_number_code(session, rollcall_id, detail=None, *, course_id="",
                         rollcall_time="", course_title="", course_cache=None, cancelled=None):
    code = find_number_code(detail)
    if code or not rollcall_id:
        return code or ""
    if cancelled and cancelled():
        return ""
    metadata = detail if isinstance(detail, dict) else {}
    course = metadata.get("course") if isinstance(metadata.get("course"), dict) else {}
    course_id = str(course_id or metadata.get("course_id") or course.get("id") or "")
    course_title = course_title or metadata.get("course_title") or course.get("name") or ""
    # Some monitor payloads expose only a course title. Use enrolled courses and
    # require a unique match; do not guess IDs or scan unrelated courses.
    if not course_id and course_title and course_title != "未知课程":
        cache = course_cache if course_cache is not None else {}
        if "courses" not in cache:
            payload = _get_json(session, "/api/my-courses?per_page=1000")
            rows = payload.get("courses", []) if isinstance(payload, dict) else []
            cache["courses"] = rows if isinstance(rows, list) else []
        matches = {
            str(row.get("id") or row.get("course_id") or "")
            for row in cache["courses"] if isinstance(row, dict)
            and str(row.get("name") or row.get("title") or row.get("course_title") or "") == str(course_title)
        } - {""}
        if len(matches) == 1:
            course_id = matches.pop()
    if not course_id:
        return ""
    start = rollcall_time or metadata.get("rollcall_time") or metadata.get("start_time") or metadata.get("created_at") or ""
    end = metadata.get("end_time") or metadata.get("deadline") or ""
    for day in rollcall_code_dates(start, end):
        if cancelled and cancelled():
            return ""
        query = urlencode({"course_ids": course_id, "rollcall_date": day})
        payload = _get_json(session, "/api/timetable_rollcalls?" + query)
        code = matching_timetable_code(payload, rollcall_id, course_id)
        if code:
            return code
    return ""
