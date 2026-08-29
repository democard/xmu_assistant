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

    try:
        home_config_dir = Path.home() / ".xmu_rollcall"
        home_config_dir.mkdir(parents=True, exist_ok=True)
        test_file = home_config_dir / ".test_write"
        test_file.touch()
        test_file.unlink()
        return home_config_dir
    except (OSError, PermissionError, RuntimeError):
        return Path.cwd() / ".xmu_rollcall"


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
        "wait_before_answer_count_min",
        "wait_before_answer_count_max",
    ):
        try:
            merged[key] = max(0, int(merged.get(key, DEFAULT_ROLLCALL_SETTINGS[key])))
        except (TypeError, ValueError):
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

    if merged.get("wait_before_answer_mode") not in ("none", "fixed", "random"):
        merged["wait_before_answer_mode"] = "none"
    merged["manual_confirm"] = bool(merged.get("manual_confirm", False))
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
        merged[section]["enabled"] = bool(merged[section].get("enabled", False))

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
    merged["launch_on_startup"] = bool(merged.get("launch_on_startup", False))
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

    config.setdefault("accounts", [])
    # 形态防御：手工/外部工具写入的 accounts 可能混入非 dict 条目，穿透 load
    # 后 get_account_by_id / get_next_account_id 对其调 .get 抛 AttributeError，
    # 连「新增账号」自愈路径也一并堵死——在入口直接滤除
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
    # 写一份加密副本到磁盘：内存中的 config 仍保留明文（调用方继续使用），
    # 落盘一律密文（首次保存即把旧明文配置迁移为密文）
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
    with _CONFIG_LOCK:
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
        except (TypeError, ValueError):
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
