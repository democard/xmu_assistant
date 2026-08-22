import math
import uuid

import requests

from .utils import base_url, retry_request


def find_number_code(data, depth=0, max_depth=10):
    """Extract ``number_code`` from nested TronClass API responses."""
    if depth > max_depth:
        return None
    if isinstance(data, dict):
        number_code = data.get("number_code")
        if number_code is not None:
            return str(number_code)
        for value in data.values():
            nested_code = find_number_code(value, depth + 1, max_depth)
            if nested_code:
                return nested_code
    elif isinstance(data, list):
        for item in data:
            nested_code = find_number_code(item, depth + 1, max_depth)
            if nested_code:
                return nested_code
    return None


def send_code(in_session, rollcall_id):
    """提交数字签到码。返回 True/False；网络层/SessionExpired 异常按契约处理。

    仅用于兼容层：PySide6 桌面端直接组装提交，这里保留统一 bool 契约。
    """
    code_url = f"{base_url}/api/rollcall/{rollcall_id}/student_rollcalls"
    answer_url = f"{base_url}/api/rollcall/{rollcall_id}/answer_number_rollcall"
    request_headers = in_session.headers
    try:
        code_response = retry_request(
            lambda: in_session.get(code_url, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="get_number_code",
        )
        if code_response.status_code != 200:
            return False
        code_data = code_response.json()
    except requests.RequestException:
        return False
    except ValueError:
        return False

    number_code = find_number_code(code_data)
    if not number_code:
        return False

    payload = {"deviceId": str(uuid.uuid4()), "numberCode": number_code}
    try:
        response = retry_request(
            lambda: in_session.put(answer_url, json=payload, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="answer_number",
        )
        return response.status_code == 200
    except requests.RequestException:
        return False


def send_radar(in_session, rollcall_id):
    """雷达签到应答：两次打点求距离 → 圆相交解算候选坐标 → 依次提交候选。
    失败路径统一返回 False（不发异常），由上层区分并记录。
    """
    url = f"{base_url}/api/rollcall/{rollcall_id}/answer"
    # 与 send_code 一致：用会话自身的 headers（含该账号的 token 头），而非模块级 headers
    request_headers = in_session.headers
    lat_1, lat_2 = 24.3, 24.6
    lon_1, lon_2 = 118.0, 118.2

    def payload(lat, lon):
        return {
            "accuracy": 35,
            "altitude": 0,
            "altitudeAccuracy": None,
            "deviceId": str(uuid.uuid4()),
            "heading": None,
            "latitude": lat,
            "longitude": lon,
            "speed": None,
        }

    try:
        # payload 在 lambda 外构造一次：同一签到的多次打点/重试必须保持同一
        # deviceId（设备指纹一致），lambda 内构造会每次重试换 uuid，徒增风控嫌疑。
        body_1 = payload(lat_1, lon_1)
        res_1 = retry_request(
            lambda: in_session.put(url, json=body_1, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="radar_1",
        )
    except requests.RequestException:
        # 与 send_code 契约一致：网络层失败按 False 返回，不向外抛异常打断调用方；
        # SessionExpiredError（RuntimeError 子类）仍透传交由上层分流失效路径。
        return False
    if res_1.status_code == 200:
        return True
    try:
        data_1 = res_1.json()
    except ValueError:
        # 非 200 且返回 HTML 错误页（登录页/网关错误）时 .json() 会抛 ValueError，
        # 无兜底会让整个自动雷达签到流程以未处理异常告终
        return False

    try:
        body_2 = payload(lat_2, lon_2)
        res_2 = retry_request(
            lambda: in_session.put(url, json=body_2, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="radar_2",
        )
    except requests.RequestException:
        return False
    if res_2.status_code == 200:
        return True
    try:
        data_2 = res_2.json()
    except ValueError:
        return False

    if not isinstance(data_1, dict) or not isinstance(data_2, dict):
        return False
    distance_1 = data_1.get("distance")
    distance_2 = data_2.get("distance")
    if not isinstance(distance_1, (int, float)) or not isinstance(distance_2, (int, float)):
        # 缺 distance 字段时 d1 ** 2 会抛 TypeError；此时无法解算，按失败返回
        return False

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
        lat0 = (lat1 + lat2) / 2
        lon0 = (lon1 + lon2) / 2
        x1, y1 = latlon_to_xy(lat1, lon1, lat0, lon0)
        x2, y2 = latlon_to_xy(lat2, lon2, lat0, lon0)
        solutions = circle_intersections(x1, y1, d1, x2, y2, d2)
        if solutions is None:
            return None
        point_1 = xy_to_latlon(solutions[0][0], solutions[0][1], lat0, lon0)
        point_2 = xy_to_latlon(solutions[1][0], solutions[1][1], lat0, lon0)
        return point_1, point_2

    resolutions = solve_two_points(lat_1, lon_1, lat_2, lon_2, distance_1, distance_2)
    if not resolutions:
        return False
    (sol_x_1, sol_y_1), (sol_x_2, sol_y_2) = resolutions
    # 与前两次 PUT 同一重试策略：候选坐标提交也可能遇到抖动，不重试会白白放弃
    try:
        body_3 = payload(sol_x_1, sol_y_1)
        res_3 = retry_request(
            lambda: in_session.put(url, json=body_3, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="radar_3",
        )
    except requests.RequestException:
        return False
    if res_3.status_code == 200:
        return True
    try:
        body_4 = payload(sol_x_2, sol_y_2)
        res_4 = retry_request(
            lambda: in_session.put(url, json=body_4, headers=request_headers, timeout=(6, 15)),
            max_attempts=3,
            delay=2,
            label="radar_4",
        )
    except requests.RequestException:
        return False
    return res_4.status_code == 200
