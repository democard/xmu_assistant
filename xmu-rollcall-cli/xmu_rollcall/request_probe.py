"""HTTP-JSON 探测共享判定层：401/403 分级与会话过期页面判定单一来源。

core.fetch_first_json（多端点探测）与 courseware._get_json（单端点直读）
原本各自手写同一套分级判定，历史上曾发生两侧漂移需人工对齐（A2-lite /
d518e20 双端对齐批次）。本模块把「判定条件 + 异常类型 + 文案」收敛为一份；
控制流差异属各自调用方语义（多端点探测对 403 记账续探、单端点直读直接
上抛），保留在原位不并入——判定只允许一个来源，处置因场景而异。
"""

from __future__ import annotations

from .utils import SessionExpiredError, response_session_expired


def session_expired_error(label: str) -> SessionExpiredError:
    """会话过期类型化异常（文案与 Android 端重新登录引导口径一致）。"""
    return SessionExpiredError(f"{label}失败：登录已过期，请重新登录")


def forbidden_error(label: str) -> RuntimeError:
    return RuntimeError(f"{label}失败：登录态已失效或当前账号无权访问")


def classify_status(status_code: int, label: str):
    """状态码分级判定单一来源。命中返回已构造异常，未命中返回 None。

    - 401 = 会话确定失效：类型化上抛走重新登录引导（retry_request 对其
      终态短路，不再对已失效会话重试/继续探测其余端点）；
    - 403 保持"资源级无权限"语义的普通 RuntimeError，避免单端点权限问题
      误触发续登。
    调用方拿到异常自行决定 raise 或记账续探。
    """
    if status_code == 401:
        return session_expired_error(label)
    if status_code == 403:
        return forbidden_error(label)
    return None


def detect_expired_page(response) -> bool:
    """302 身份域跳转判定：会话过期时平台把 API GET 302 到统一身份域登录页，
    requests 自动跟随成 200 HTML，仅凭状态码无法识别。

    判定条件本体在 utils.response_session_expired（双端对齐清单），此处只是
    探测语境的单一入口转发。
    """
    return response_session_expired(response)
