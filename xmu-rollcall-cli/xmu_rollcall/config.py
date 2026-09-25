"""Local account and cookie persistence for the desktop dashboard."""

from __future__ import annotations

import copy
import json
import os
import threading
from pathlib import Path

from . import secrets

# 进程级配置读写锁：GUI 线程（保存通知设置）与后台线程（登录完成写回）
# 可能并发 load→改→save，加锁序列化写入，避免 interleaved 写损坏文件
_CONFIG_LOCK = threading.RLock()
# 供调用方做「读-改-写」复合操作时整体持锁（load_config/save_config 内部已分别持锁，
# RLock 可重入，复合操作外层再持一次即可消除 lost-update 窗口）
CONFIG_LOCK = _CONFIG_LOCK


def get_config_dir() -> Path:
    """Return the writable config directory used by the desktop app."""
    if env_path := os.environ.get("XMU_ROLLCALL_CONFIG_DIR"):
        return Path(env_path)

    candidates = []
    try:
        candidates.append(Path.home() / ".xmu_rollcall")
    except (OSError, RuntimeError):
        pass
    # 计划任务的 cwd 可能是 System32；使用用户配置目录作为稳定兜底。
    for variable in ("LOCALAPPDATA", "APPDATA"):
        if base := os.environ.get(variable):
            candidates.append(Path(base) / "xmu_rollcall")
    for directory in candidates:
        try:
            directory.mkdir(parents=True, exist_ok=True)
            test_file = directory / ".test_write"
            test_file.touch()
            test_file.unlink()
            return directory
        except (OSError, RuntimeError):
            continue
    raise OSError("无法找到可写的用户配置目录，请设置 XMU_ROLLCALL_CONFIG_DIR")


CONFIG_DIR = get_config_dir()
CONFIG_FILE = CONFIG_DIR / "config.json"

MIN_POLL_INTERVAL_SECONDS = 1
DEFAULT_POLL_INTERVAL_SECONDS = 30
MAX_POLL_INTERVAL_SECONDS = 300
# 应答延迟（数字/雷达签到拟人化等待）的绝对上限（秒）
MAX_ANSWER_DELAY_SECONDS = 600

DEFAULT_ROLLCALL_SETTINGS = {
    "poll_interval_seconds": DEFAULT_POLL_INTERVAL_SECONDS,
    "number_delay_min": 10,
    "number_delay_max": 30,
    "radar_delay_min": 0,
    "radar_delay_max": 0,
    "manual_confirm": False,
    "wait_before_answer_mode": "none",
    "wait_before_answer_count": 5,
    "wait_before_answer_percent": 15,
    # 早期未接入界面的占位字段保留，避免破坏已有配置文件；它们不参与新门槛判定。
    "wait_before_answer_count_min": 0,
    "wait_before_answer_count_max": 0,
}

DEFAULT_NOTIFICATION_SETTINGS = {
    "system": {
        "enabled": True,
    },
    "pushplus": {
        "enabled": False,
        "token": "",
    },
    "qq_mail": {
        "enabled": False,
        "sender": "",
        "password": "",
        "recipient": "",
        "smtp_host": "smtp.qq.com",
        "smtp_port": "465,587",
    },
}

DEFAULT_APP_SETTINGS = {
    "launch_on_startup": False,
    "theme_mode": "system",
}

DEFAULT_CONFIG = {
    "accounts": [],
    "current_account_id": None,
    "notification_settings": DEFAULT_NOTIFICATION_SETTINGS.copy(),
    "app_settings": DEFAULT_APP_SETTINGS.copy(),
}


_FALSEY_BOOL_TEXT = ("", "false", "0", "no", "off")


def _coerce_bool(value) -> bool:
    """把手编配置里常见的布尔写法收敛为 bool。

    bool("false") is True：字符串 "false"/"0"/"no"/"off" 会被原生 bool() 判成真，
    静默打开通知、手动确认或开机自启等开关（与用户写入的意图相反）。这里只对
    明确的否定文本返回 False，其余沿用真值语义（非空其它字符串/非零数字为真）。
    """
    if isinstance(value, str):
        return value.strip().lower() not in _FALSEY_BOOL_TEXT
    return bool(value)


def normalize_rollcall_settings(settings: dict | None) -> dict:
    if not isinstance(settings, dict):
        settings = None
    merged = DEFAULT_ROLLCALL_SETTINGS.copy()
    merged.update(settings or {})

    for key in (
        "poll_interval_seconds",
        "number_delay_min",
        "number_delay_max",
        "radar_delay_min",
        "radar_delay_max",
        "wait_before_answer_count",
        "wait_before_answer_percent",
        "wait_before_answer_count_min",
        "wait_before_answer_count_max",
    ):
        try:
            merged[key] = max(0, int(merged.get(key, DEFAULT_ROLLCALL_SETTINGS[key])))
        except (TypeError, ValueError, OverflowError):
            # OverflowError：json.load 会把 1e999 解析成 inf，int(inf) 抛它——不捕就
            # 会让 load_config 永久失败（登录/新增账号全废），自愈路径反被绕过。
            merged[key] = DEFAULT_ROLLCALL_SETTINGS[key]

    merged["poll_interval_seconds"] = min(
        MAX_POLL_INTERVAL_SECONDS,
        max(MIN_POLL_INTERVAL_SECONDS, merged["poll_interval_seconds"]),
    )
    # 应答延迟绝对上限：手编 config 的巨大值 + deadline 缺失事件会让延迟
    # 应答等价于永不提交（虽可取消但功能失效）
    for _key in ("number_delay_max", "number_delay_min", "radar_delay_max", "radar_delay_min"):
        merged[_key] = min(MAX_ANSWER_DELAY_SECONDS, max(0, merged[_key]))

    if merged["number_delay_max"] < merged["number_delay_min"]:
        merged["number_delay_max"] = merged["number_delay_min"]
    if merged["radar_delay_max"] < merged["radar_delay_min"]:
        merged["radar_delay_max"] = merged["radar_delay_min"]
    if merged["wait_before_answer_count_max"] < merged["wait_before_answer_count_min"]:
        merged["wait_before_answer_count_max"] = merged["wait_before_answer_count_min"]

    # fixed/random 是从未接入真实自动链路的旧占位值，迁移时必须回到 none，
    # 不能因为磁盘里曾出现占位字段就意外开启自动签到门槛。
    if merged.get("wait_before_answer_mode") not in ("none", "count", "percent"):
        merged["wait_before_answer_mode"] = "none"
    merged["wait_before_answer_count"] = max(1, merged["wait_before_answer_count"])
    merged["wait_before_answer_percent"] = min(
        100, max(1, merged["wait_before_answer_percent"])
    )
    merged["manual_confirm"] = _coerce_bool(merged.get("manual_confirm", False))
    return merged


def normalize_notification_settings(settings: dict | None) -> dict:
    # 手编/外部写入可能给出合法 JSON 但错误类型（数组/字符串）：非 dict 直接
    # 回退默认（与 load 的 accounts 非 dict 滤除同族防御），不再穿透 .items() 崩
    if not isinstance(settings, dict):
        settings = None
    merged = {
        section: values.copy()
        for section, values in DEFAULT_NOTIFICATION_SETTINGS.items()
    }
    for section, values in (settings or {}).items():
        if section in merged and isinstance(values, dict):
            merged[section].update(values)

    for section in ("system", "pushplus", "qq_mail"):
        merged[section]["enabled"] = _coerce_bool(merged[section].get("enabled", False))

    for key in ("token",):
        merged["pushplus"][key] = str(merged["pushplus"].get(key, "")).strip()
    for key in ("sender", "password", "recipient", "smtp_host"):
        merged["qq_mail"][key] = str(merged["qq_mail"].get(key, "")).strip()
    smtp_port = merged["qq_mail"].get("smtp_port", "465,587")
    if isinstance(smtp_port, (list, tuple)):
        smtp_port = ",".join(str(port).strip() for port in smtp_port)
    merged["qq_mail"]["smtp_port"] = str(smtp_port or "465,587").strip() or "465,587"
    return merged


def normalize_app_settings(settings: dict | None) -> dict:
    if not isinstance(settings, dict):
        settings = None
    merged = DEFAULT_APP_SETTINGS.copy()
    merged.update(settings or {})
    merged["launch_on_startup"] = _coerce_bool(merged.get("launch_on_startup", False))
    # 主题模式归一化：只接受 system / light / dark，非法值回退 system
    theme = str(merged.get("theme_mode", "system")).lower()
    merged["theme_mode"] = theme if theme in ("system", "light", "dark") else "system"
    return merged


def ensure_config_dir() -> None:
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    except (OSError, PermissionError) as exc:
        raise RuntimeError(f"无法创建配置目录 {CONFIG_DIR}: {exc}") from exc


def _default_config() -> dict:
    """深拷贝默认配置：浅 copy 会与模块级 DEFAULT_CONFIG 共享嵌套 dict
    （notification_settings/app_settings），调用方原地修改会跨调用污染
    进程级默认值。"""
    return copy.deepcopy(DEFAULT_CONFIG)


def load_config() -> dict:
    ensure_config_dir()
    with _CONFIG_LOCK:
        return _load_config_locked()


def _load_config_locked() -> dict:
    if not CONFIG_FILE.exists():
        return _default_config()

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as file:
            config = json.load(file)
    except Exception as exc:
        raise RuntimeError(f"读取配置失败：{CONFIG_FILE}: {exc}") from exc

    if not isinstance(config, dict):
        return _default_config()

    if "accounts" not in config and config.get("username"):
        config = {
            "accounts": [
                {
                    "id": 1,
                    "name": "",
                    "username": config.get("username", ""),
                    "password": config.get("password", ""),
                    "rollcall_settings": DEFAULT_ROLLCALL_SETTINGS.copy(),
                }
            ],
            "current_account_id": 1,
            # 旧单账号格式的通知/应用设置随迁移保留（此前重建 dict 时静默丢弃）
            "notification_settings": config.get("notification_settings"),
            "app_settings": config.get("app_settings"),
        }

    # 容器形态防御：accounts 可能被手工/外部工具写成 null 或标量。setdefault 不会
    # 替换既有键，若不先判类型，下游的 for / 下标访问会抛 TypeError；而 load_config
    # 每次调用都失败意味着登录（add_account 需先 load）永久不可用，连自愈路径也堵死。
    if not isinstance(config.get("accounts"), list):
        config["accounts"] = []
    # 形态防御：列表内混入非 dict 条目，穿透 load 后 get_account_by_id /
    # get_next_account_id 对其调 .get 抛 AttributeError——在入口直接滤除
    if not all(isinstance(entry, dict) for entry in config["accounts"]):
        config["accounts"] = [entry for entry in config["accounts"] if isinstance(entry, dict)]
    config.setdefault("current_account_id", None)
    config["notification_settings"] = normalize_notification_settings(config.get("notification_settings"))
    config["app_settings"] = normalize_app_settings(config.get("app_settings"))
    for account in config["accounts"]:
        if isinstance(account, dict):
            account["rollcall_settings"] = normalize_rollcall_settings(account.get("rollcall_settings"))
            # 解密敏感字段：旧明文（无 dpapi: 前缀）原样返回，向后兼容
            account["password"] = secrets.unprotect(str(account.get("password", "")))
    notif = config.get("notification_settings")
    if isinstance(notif, dict):
        if isinstance(notif.get("pushplus"), dict):
            notif["pushplus"]["token"] = secrets.unprotect(str(notif["pushplus"].get("token", "")))
        if isinstance(notif.get("qq_mail"), dict):
            notif["qq_mail"]["password"] = secrets.unprotect(str(notif["qq_mail"].get("password", "")))
    return config


def save_config(config: dict) -> None:
    ensure_config_dir()
    with _CONFIG_LOCK:
        # 与持 CONFIG_LOCK 的读-改-写调用方互斥，避免复制期间配置被改动。
        # 写一份加密副本到磁盘；内存中的 config 仍保留明文。
        snapshot = copy.deepcopy(config)
        for account in snapshot.get("accounts", []):
            if isinstance(account, dict):
                account["password"] = secrets.protect(str(account.get("password", "")))
        notif = snapshot.get("notification_settings")
        if isinstance(notif, dict):
            if isinstance(notif.get("pushplus"), dict):
                notif["pushplus"]["token"] = secrets.protect(str(notif["pushplus"].get("token", "")))
            if isinstance(notif.get("qq_mail"), dict):
                notif["qq_mail"]["password"] = secrets.protect(str(notif["qq_mail"].get("password", "")))
        tmp_file = CONFIG_FILE.with_name(CONFIG_FILE.name + ".tmp")
        try:
            with open(tmp_file, "w", encoding="utf-8") as file:
                json.dump(snapshot, file, indent=2, ensure_ascii=False)
            os.replace(tmp_file, CONFIG_FILE)
        except Exception:
            # 失败清理残留 tmp（与 utils.save_session 失败路径对称），异常继续上抛：
            # 6 个调用点均有日志/用户可见反馈兜底，tmp 残留只会让下次写入混淆
            try:
                os.remove(tmp_file)
            except OSError:
                pass
            raise


def get_next_account_id(config: dict) -> int:
    accounts = config.get("accounts", [])
    if not accounts:
        return 1

    def account_id_of(account: dict) -> int:
        # 手工/外部编辑的 id 可能是非数字字符串：逐项容错（非法按 0，
        # 不参与最大值竞争），此前裸 int() 会让「新增账号」直接 ValueError
        try:
            return int(account.get("id", 0))
        except (TypeError, ValueError, OverflowError):
            # id 被写成 1e999（json → inf）时 int() 抛 OverflowError，同样按非法处理
            return 0

    return max(
        (account_id_of(account) for account in accounts if isinstance(account, dict)),
        default=0,
    ) + 1


def add_account(config: dict, username: str, password: str, name: str) -> int:
    account_id = get_next_account_id(config)
    account = {
        "id": account_id,
        "name": name,
        "username": username,
        "password": password,
        "rollcall_settings": DEFAULT_ROLLCALL_SETTINGS.copy(),
    }
    config.setdefault("accounts", []).append(account)
    if config.get("current_account_id") is None:
        config["current_account_id"] = account_id
    return account_id


def get_account_by_id(config: dict, account_id: int | str | None) -> dict | None:
    for account in config.get("accounts", []):
        if str(account.get("id")) == str(account_id):
            return account
    return None


def get_current_account(config: dict) -> dict | None:
    return get_account_by_id(config, config.get("current_account_id"))


def set_current_account(config: dict, account_id: int | str) -> None:
    config["current_account_id"] = account_id


def get_all_accounts(config: dict) -> list[dict]:
    return config.get("accounts", [])


def get_rollcall_settings(account: dict) -> dict:
    return normalize_rollcall_settings(account.get("rollcall_settings") or {})


def set_rollcall_settings(account: dict, settings: dict | None) -> None:
    account["rollcall_settings"] = normalize_rollcall_settings(settings)


def get_notification_settings(config: dict) -> dict:
    return normalize_notification_settings(config.get("notification_settings"))


def set_notification_settings(config: dict, settings: dict | None) -> None:
    config["notification_settings"] = normalize_notification_settings(settings)


def get_app_settings(config: dict) -> dict:
    return normalize_app_settings(config.get("app_settings"))


def set_app_settings(config: dict, settings: dict | None) -> None:
    config["app_settings"] = normalize_app_settings(settings)


def get_cookies_path(account_id: int | str | None = None) -> str:
    ensure_config_dir()
    if account_id is None:
        account_id = load_config().get("current_account_id", 1)
    return str(CONFIG_DIR / f"{account_id}.json")
