"""Account and cookie transactions, independent of the desktop event loop."""
from __future__ import annotations

import copy
import os
from dataclasses import dataclass
from pathlib import Path

from .config import (
    CONFIG_LOCK, add_account, get_all_accounts, get_cookies_path,
    get_rollcall_settings, load_config, save_config, set_current_account,
    set_rollcall_settings,
)
from .utils import save_session


@dataclass(frozen=True)
class LoginSaveResult:
    account: dict | None
    undo: dict | None
    error: str = ""


def persist_login(session, username: str, password: str, name: str) -> LoginSaveResult:
    """Commit both files or compensate before releasing the settings lock.

    Keep the undo record even on failure: logout may have happened while this
    worker was writing, in which case its event handler must still remove the
    saved cookie instead of restoring the previous one.
    """
    account = None
    undo = None
    config_saved = False
    with CONFIG_LOCK:
        try:
            config = load_config()
            account = next(
                (item for item in get_all_accounts(config) if item.get("username") == username), None,
            )
            undo = {"current_account_id": config.get("current_account_id"), "account": copy.deepcopy(account)}
            if account is None:
                account_id = add_account(config, username, password, name)
                account = next(item for item in get_all_accounts(config) if item.get("id") == account_id)
            else:
                account["password"] = password
                account["name"] = name
            set_current_account(config, account["id"])
            set_rollcall_settings(account, get_rollcall_settings(account))
            cookie_path = Path(get_cookies_path(account["id"]))
            undo.update({
                "cookie": cookie_path.read_bytes() if cookie_path.exists() else None,
                "account_id": account["id"],
                "written_account": copy.deepcopy(account),
            })
            save_config(config)
            config_saved = True
            save_session(session, str(cookie_path), strict=True)
            return LoginSaveResult(account, undo)
        except Exception as exc:
            error = str(exc)
            if config_saved:
                rollback_error = rollback_login(undo, remove_cookie=False)
                if rollback_error:
                    error = f"{error}；{rollback_error}"
            return LoginSaveResult(account, undo, error)


def rollback_login(undo: dict | None, *, remove_cookie: bool) -> str:
    """Undo only this login's fields, preserving unrelated settings updates."""
    if not undo or "account_id" not in undo:
        return ""
    errors = []
    with CONFIG_LOCK:
        try:
            config = load_config()
            account_id = undo["account_id"]
            account = next(
                (item for item in get_all_accounts(config) if str(item.get("id")) == str(account_id)), None,
            )
            prior = undo["account"]
            written = undo["written_account"]
            if prior is None:
                if account == written:
                    config["accounts"] = [
                        item for item in config["accounts"] if str(item.get("id")) != str(account_id)
                    ]
            elif account:
                for key in ("password", "name", "rollcall_settings"):
                    if account.get(key) == written.get(key):
                        if key in prior:
                            account[key] = prior[key]
                        else:
                            account.pop(key, None)
            if str(config.get("current_account_id")) == str(account_id):
                config["current_account_id"] = undo["current_account_id"]
            save_config(config)
        except Exception as exc:
            errors.append(f"配置回滚失败：{exc}")
        try:
            path = Path(get_cookies_path(undo["account_id"]))
            prior_cookie = None if remove_cookie else undo.get("cookie")
            if prior_cookie is None:
                path.unlink(missing_ok=True)
            else:
                tmp_path = path.with_name(path.name + ".tmp")
                try:
                    tmp_path.write_bytes(prior_cookie)
                    os.replace(tmp_path, path)
                finally:
                    tmp_path.unlink(missing_ok=True)
        except Exception as exc:
            errors.append(f"Cookie 回滚失败：{exc}")
    return "；".join(errors)
