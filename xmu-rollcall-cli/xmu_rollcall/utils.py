import json
import os
import threading
import time as _time

import requests

from . import secrets
from .config import CONFIG_LOCK
from .diag_log import log as _diag_log


# 主会话 cookiejar 的进程级互斥：应答 merge 回写（写 dst.cookies）与各 worker 的
# clone_session 快照读（读 src.cookies）必须串行，RequestsCookieJar 非线程安全，
# 边迭代边 set_cookie 会抛 RuntimeError: dictionary changed size during iteration。
# GUI 线程的 merge 与后台线程的 clone 都可能并发触碰同一个 jar，统一在此持锁。
SESSION_COOKIE_LOCK = threading.RLock()


# 统一 API 超时口径 (connect, read)：原各处 20/30s 标量超时口径混乱（标量会同时
# 作用于连接与读取），服务僵死时一次轮询可挂满 30s。connect 6s 足以完成 TLS 握手，
# read 15s 覆盖慢接口；仅大文件下载保留更长的 DOWNLOAD_TIMEOUT。
# A8 口径声明：Android 端（XmuHttpClients.kt）connect 为 8s——移动网络 RTT 抖动
# 更大，略宽以减少弱网误超时；read 两侧一致 15s。有意差异，不做强行统一
# （统一需真机弱网回归验证后再动，网络口径属风控敏感面）。
API_TIMEOUT = (6, 15)
DOWNLOAD_TIMEOUT = (20, 120)


def tune_session(session, pool_maxsize: int = 16):
    """给 Session 挂载调优过的 HTTPAdapter（连接池扩容 + 传输层瞬时重试）。

    requests 默认 HTTPAdapter(pool_maxsize=10, max_retries=0)：课件 8 线程 +
    签到列表 4 线程同主机并发时池上限正好卡边界；连接被重置等瞬时故障只能靠
    应用层 retry_request 恢复（固定 sleep ≥2s），而 urllib3 层重试通常 <100ms
    且对应用透明。只重试幂等的 GET/HEAD 与 5xx。刻意不含 PUT：签到应答（雷达/
    数字）走 PUT，读超时触发的传输层重试可能在服务端已受理的情况下重复提交
    （风控红线）；应答路径的失败恢复仍由应用层 retry_request + 会话过期终态短路负责。

    非 requests.Session 实例（测试 fake）原样返回。
    """
    if not isinstance(session, requests.Session):
        return session
    from requests.adapters import HTTPAdapter

    try:
        from urllib3.util.retry import Retry
    except ImportError:  # 兜底旧打包环境
        from requests.packages.urllib3.util.retry import Retry  # type: ignore[no-redef]

    retry_kwargs = dict(
        total=2,
        connect=2,
        read=1,
        backoff_factor=0.3,
        status_forcelist=(500, 502, 503, 504),
        raise_on_status=False,
    )
    try:
        retry = Retry(allowed_methods=frozenset({"GET", "HEAD"}), **retry_kwargs)
    except TypeError:
        # urllib3 < 1.26 参数名为 method_whitelist
        retry = Retry(method_whitelist=frozenset({"GET", "HEAD"}), **retry_kwargs)
    adapter = HTTPAdapter(pool_connections=4, pool_maxsize=pool_maxsize, max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


# ---- 课程列表端点记忆 -------------------------------------------------------
# 候选端点探测（COURSE_ENDPOINTS 等 6 个）原每次从头盲试：一旦生效的是第 N 个，
# 之后每次刷新都要先吃 N-1 次失败请求（各最长一个超时）。记住"上次成功的端点"
# 作下次首选，页面首开延迟直接少 1~5 个 RTT。进程级缓存即可（重启后重新探测）。

_ENDPOINT_ORDER_LOCK = threading.Lock()
_LAST_GOOD_ENDPOINTS: dict[str, str] = {}


def remember_good_endpoint(purpose: str, endpoint: str) -> None:
    with _ENDPOINT_ORDER_LOCK:
        _LAST_GOOD_ENDPOINTS[purpose] = endpoint


def ordered_endpoints(purpose: str, endpoints) -> list[str]:
    """返回候选端点顺序：上次成功者优先，其余保持原始顺序。"""
    with _ENDPOINT_ORDER_LOCK:
        last_good = _LAST_GOOD_ENDPOINTS.get(purpose)
    endpoints = list(endpoints)
    if last_good in endpoints:
        endpoints.remove(last_good)
        endpoints.insert(0, last_good)
    return endpoints


def reset_endpoint_memory() -> None:
    """清空端点记忆（测试用，避免用例间进程级状态串扰）。"""
    with _ENDPOINT_ORDER_LOCK:
        _LAST_GOOD_ENDPOINTS.clear()


def retry_request(fn, max_attempts=3, delay=2, backoff=2, label="request", stop_event=None):
    """Run a request callable with exponential-backoff retries.

    stop_event：可选的 threading.Event。传入后重试间隔用 event.wait 实现，
    停止信号可立即打断等待（否则暂停监控后旧线程仍存活在 sleep 中，
    紧接着的重新启动会被 is_alive() 挡下，监控静默停摆）。
    """
    if max_attempts < 1:
        # 防御边角：max_attempts<=1 范围外取值会让循环零次执行后 raise None
        # （TypeError 掩盖真实配置错误）
        raise ValueError(f"{label}: max_attempts 必须 >= 1（当前 {max_attempts}）")
    last_exc = None
    for attempt in range(1, max_attempts + 1):
        if stop_event is not None and stop_event.is_set():
            raise RetryCancelled(f"{label} 已取消")
        try:
            return fn()
        except SessionExpiredError:
            # 会话过期是确定性终态：重试只会重复跟随 302 跳身份域登录页（对登录敏感主机的
            # 无谓接触，风控红线）。直接上抛，由上层走「停止轮询 / 引导重新登录」，绝不按网络抖动重试。
            raise
        except Exception as exc:
            last_exc = exc
            if attempt < max_attempts:
                if stop_event is not None:
                    if stop_event.wait(delay):
                        raise RetryCancelled(f"{label} 已取消") from exc
                else:
                    _time.sleep(delay)
                delay *= backoff
    raise last_exc


class RetryCancelled(Exception):
    """重试被停止信号打断（监控暂停/退出），调用方应静默收尾而非报错。"""


class SessionExpiredError(RuntimeError):
    """会话已过期：平台对 API 请求返回 302 跳统一身份域（或登录页），而非 401。
    与网络故障/服务端错误严格区分，GUI 可据此引导用户重新登录。"""


# 统一身份域（与 Android 端 SessionHealth.kt 的 KNOWN_IDENTITY_HOSTS 对齐）
IDENTITY_HOSTS = ("c-identity.xmu.edu.cn", "ids.xmu.edu.cn")
# 登录页特征：CAS（ids/authserver）+ Keycloak（/auth/realms/xmu/），
# 与 Android SessionHealth.IDENTITY_MARKERS 对齐
LOGIN_PAGE_MARKERS = ("pwdencryptsalt", "authserver/login", "/auth/realms/xmu/")


def clone_session(src: requests.Session) -> requests.Session:
    """克隆一个 Session：复制 cookies/headers，独立 cookiejar。

    用途：后台多线程（如课件 8 线程池）不应与主 Session 共用同一 cookiejar——
    requests 不保证 Session 线程安全，并发 Set-Cookie 会竞争写入 cookiejar。
    clone 在 GUI 线程或 worker 入口生成（读 src.cookies 是快照式 dict 复制），
    worker 内的请求与可能的新 Set-Cookie 都落到独立 cookiejar，互不干扰。

    非 requests.Session 实例（如测试用的 fake session，仅实现 .get 拦截）原样返回，
    保留其请求拦截能力。
    """
    if not isinstance(src, requests.Session):
        return src
    cloned = requests.Session()
    # dict_from_cookiejar → cookiejar_from_dict：快照式复制，不共享底层结构。
    # 持 SESSION_COOKIE_LOCK：读主会话 jar 时与 GUI 线程的 merge 回写互斥。
    with SESSION_COOKIE_LOCK:
        cloned.cookies = requests.utils.cookiejar_from_dict(
            requests.utils.dict_from_cookiejar(src.cookies)
        )
    cloned.headers.update(src.headers)
    # trust_env 随源会话：运行时 proxy_guard 补丁已令新会话 trust_env=False，
    # 克隆复制源值即与全局禁用系统代理口径一致；未打补丁环境（库形态）随源。
    cloned.trust_env = getattr(src, "trust_env", True)
    # 克隆会话同样挂载调优 Adapter（连接池 + 幂等 GET 传输层重试），
    # 让课件线程池/监控/签到列表等 worker 路径都享受传输层提速。
    tune_session(cloned)
    return cloned


def merge_cookies(dst: requests.Session, src: requests.Session) -> None:
    """把 worker Session 内新增/更新的 cookie 合并回主 Session。

    只应在 GUI 线程调用（单点写，无竞争）；worker 结束后由 GUI 线程执行。
    持 SESSION_COOKIE_LOCK：与后台线程对主 jar 的 clone 快照读互斥，防撕裂。
    """
    with SESSION_COOKIE_LOCK:
        for cookie in src.cookies:
            dst.cookies.set_cookie(cookie)


def merge_worker_session_cookies(
    main_session,
    account: dict | None,
    worker_session,
    worker_account_id: str,
) -> bool:
    """把后台 worker 克隆会话内的新 cookie 回写主会话（须 GUI 线程单点调用）。

    守卫（两轮复查 H2）：
    - main_session 为空（已登出）：直接丢弃，避免 None.cookies 抛异常崩 GUI；
    - 账号已切换（account.id != worker_account_id）：丢弃，避免把旧账号续登 cookie
      合进新账号会话（同名会话 token 会被顶号）。
    返回是否实际执行了合并。
    """
    if main_session is None:
        return False
    current_id = str((account or {}).get("id") or "")
    if worker_account_id and worker_account_id != current_id:
        return False
    if worker_session is None or worker_session is main_session:
        return False
    merge_cookies(main_session, worker_session)
    return True


def late_worker_result_accepted(current_account: dict | None, worker_account_id: str | None) -> bool:
    """判定后台刷新 worker 晚到结果是否仍归属当前会话（体检报告 P1-2）。

    - 已登出（current_account 为 None）：一律过期，防止旧账号数据灌回界面；
    - worker 未携带账号快照（旧事件/测试桩）：视为接受，保持向后兼容；
    - 其余按 id 字符串比对，换号后的晚到结果一律丢弃。
    """
    if current_account is None:
        return False
    if not worker_account_id:
        return True
    return str(worker_account_id) == str(current_account.get("id"))


def answer_failure_detail(error: BaseException) -> tuple[str, bool]:
    """把应答异常规整为 (展示文案, 是否会话过期)。

    会话过期（SessionExpiredError）是终态：需引导重新登录（走后台错误通知），而非当
    普通失败处理——与监控过期分支语义一致（P1/P2 过期分流）。纯函数便于单测。
    """
    if isinstance(error, SessionExpiredError):
        return "登录已过期，请重新登录", True
    return str(error), False


# 截止保护余量：剩余时间不足延迟 + 该秒数时跳过延迟立即提交
AUTO_ANSWER_DEADLINE_MARGIN_SECONDS = 10


def compute_auto_answer_delay(rollcall_type: str, settings: dict, remaining_seconds) -> float:
    """自动应答的拟人化延迟（秒）。0 = 立即提交。

    - number_delay_*（数字签到）/ radar_delay_*（雷达签到）随机延迟；
    - 配置 max<=0 视为关闭延迟（立即提交）；
    - 截止保护：剩余时间 ≤ 延迟 + 10s 时跳过延迟立即提交，避免延迟期间签到截止。
    """
    import random as _random
    prefix = "number_delay" if rollcall_type == "数字签到" else "radar_delay"
    try:
        lo = float(settings.get(f"{prefix}_min", 0))
        hi = float(settings.get(f"{prefix}_max", 0))
    except (TypeError, ValueError):
        return 0.0
    if hi <= 0:
        return 0.0
    delay = _random.uniform(max(0.0, lo), max(lo, hi))
    if remaining_seconds is not None:
        try:
            remaining = float(remaining_seconds)
        except (TypeError, ValueError):
            remaining = None
        if remaining is not None and remaining <= delay + AUTO_ANSWER_DEADLINE_MARGIN_SECONDS:
            return 0.0
    return delay


def response_session_expired(response, peek_body: bool = True) -> bool:
    """判定 requests 响应是否为「会话过期」的表现。

    requests 默认自动跟随重定向，会话过期时 API GET 会被 302 带到身份域登录页
    （最终 200 HTML），仅凭状态码无法识别。判定规则（与 Android 端对齐）：
    - 最终 URL host 落在统一身份域；或
    - 发生过跳转（response.history 非空）且最终页面是含登录特征的 HTML。

    peek_body=False 用于流式响应（下载），避免为探测 body 提前消费流。
    """
    final_url = str(getattr(response, "url", "") or "").lower()
    if any(host in final_url for host in IDENTITY_HOSTS):
        return True
    if not getattr(response, "history", None):
        return False
    try:
        content_type = str(response.headers.get("Content-Type", "")).lower()
    except Exception:
        return False
    if "text/html" not in content_type:
        return False
    if not peek_body:
        # 跳转到 HTML 且无法安全读 body：按登录页处理（API 请求的正常响应不会是 HTML）
        return True
    try:
        head = response.text[:4096].lower()
    except Exception:
        return False
    return any(marker in head for marker in LOGIN_PAGE_MARKERS) or any(
        host in head for host in IDENTITY_HOSTS
    )


base_url = "https://lnt.xmu.edu.cn"

headers = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "Referer": "https://ids.xmu.edu.cn/authserver/login",
}


def save_session(sess: requests.Session, path: str):
    tmp_path = f"{path}.tmp"
    # 与 maintenance.cleanup_orphaned_cookie_files 互斥：登录落盘的新账号
    # cookie 不得被并发孤儿清理误删（写路径与清理路径共用 CONFIG_LOCK）
    with CONFIG_LOCK:
        try:
            cookie_dict = requests.utils.dict_from_cookiejar(sess.cookies)
            # cookie 等同于登录态：整个 JSON 内容加密落盘（与凭据字段同安全级别）
            plaintext = json.dumps(cookie_dict)
            protected = secrets.protect(plaintext)
            # 原子写（tmp + os.replace）：直接截断写在进程中途被杀时会留下半个密文，
            # 下次自动恢复必败且无提示；与 config.py 的写盘姿势保持一致。
            with open(tmp_path, "w", encoding="utf-8") as file:
                file.write(protected)
            os.replace(tmp_path, path)
        except Exception as exc:
            # 失败清理残留 tmp，避免下次误判；不能静默吞掉：
            # cookie 写盘失败会导致下次启动自动恢复失败且毫无线索
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except OSError:
                pass
            # console=False 的打包 exe 里 print 不可见，失败线索落 diag.log
            _diag_log(f"保存会话缓存失败（{path}）：{exc}")


def load_session(sess: requests.Session, path: str):
    # 文件不存在是正常路径（首次运行/已登出），不算失败、不打印；
    # 只有文件在却恢复不了（损坏/解密失败）才留下线索。
    if not os.path.exists(path):
        return False
    try:
        with open(path, "r", encoding="utf-8") as file:
            raw = file.read()
        # 兼容旧明文：无 dpapi: 前缀直接按 JSON 解析；有前缀先解密
        plaintext = secrets.unprotect(raw) if raw.startswith(secrets.DPAPI_PREFIX) else raw
        cookie_dict = json.loads(plaintext)
        sess.cookies = requests.utils.cookiejar_from_dict(cookie_dict)
        return True
    except Exception as exc:
        # 与 save_session 的失败日志对称：恢复失败原因不能零日志吞掉，
        # 否则缓存损坏/解密失败时只表现为「静默不恢复」，无任何线索
        _diag_log(f"恢复会话缓存失败（{path}）：{exc}")
        return False


def verify_session(sess: requests.Session) -> dict | None:
    """Verify that a TronClass session is still valid.

    返回约定：
    - dict（含 name）：会话有效；
    - {}：会话确定失效（401/403，或服务端明确拒绝）；
    - None：网络故障无法判定（超时/DNS/连接错误）——调用方不应据此要求重新登录，
      否则开机自启网络尚未就绪时会被误判为「登录态已失效」。
    """
    try:
        response = sess.get(f"{base_url}/api/profile", headers=headers, timeout=API_TIMEOUT)
    except requests.RequestException:
        return None
    except Exception:
        return None
    if response.status_code == 200:
        try:
            data = response.json()
        except ValueError:
            return {}
        if isinstance(data, dict) and "name" in data:
            return data
        return {}
    if response.status_code in (401, 403):
        return {}
    # 其他状态码（5xx/3xx 异常页）：无法判定，按未知处理
    return None


def unwrap_list(payload, keys: tuple[str, ...]):
    """从嵌套 JSON 里按候选键递归找出第一个非空列表（批量端点包裹形态各异）。

    原为 desktop_qt/core 与 courseware 各持一份的同语义实现，收敛到 utils
    单一来源；两处经别名引用，行为不变。
    """
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
