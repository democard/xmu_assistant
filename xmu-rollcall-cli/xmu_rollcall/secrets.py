"""DPAPI-backed secret protection for the desktop dashboard config.

凭据敏感字段（统一身份认证密码 / SMTP 授权码 / PushPlus token / 会话 cookie）的
Windows 用户级加密存储。设计目标：

- 加密仅 Windows（pywin32 win32crypt.CryptProtectData / CryptUnprotectData），
  非 Windows 平台原样透传并打印一次警告（桌面端仅 Windows 部署，CI 可降级）。
- 存储/读取向后兼容旧明文：读取时无 ``dpapi:`` 前缀视为旧明文原样返回；
  保存时一律写密文（首次保存即自动迁移）。
- 幂等：protect 对已加密串（带前缀）不重复加密，避免双重加密。
- win32crypt 在**模块导入期**一次性加载并缓存，保存路径绝不懒加载：
  打包 onefile 的解包目录在运行中途可能失效（被并发清理/杀软拦截），
  那时才 import 会把该次保存写成裸凭据且难以归因（2026-09-19 实证：
  同一份 exe 时而写 dpapi 密文、时而报 No module named 'win32crypt'）。
  应用能启动本身即证明解包目录早期的导入全部成功，故启动期加载是可靠锚点。
"""
from __future__ import annotations

import base64
import os

DPAPI_PREFIX = "dpapi:"
_warned_unsupported = False
_warned_unavailable = False

_win32crypt = None
_win32crypt_error: str | None = None
if os.name == "nt":
    # 启动期导入并把失败原因记下来。此刻不直接 _warn：secrets 被 config 顶层引用，
    # config 尚在初始化、diag 通道未就绪（见 _warn 的延迟导入注释），
    # 告警延到首次使用时补发（_require_win32crypt）。
    try:
        import win32crypt as _win32crypt
    except ImportError as exc:
        _win32crypt = None
        _win32crypt_error = repr(exc)


def _warn(message: str) -> None:
    """安全降级告警双通道：print（源码运行可见）+ diag.log 落盘（exe 无 stdout 可见）。

    diag_log 经函数内延迟导入：secrets 被 config 顶层引用，而 diag_log 模块级
    `from . import config`，模块期直连会成环；调用期导入两模块均已就绪。
    """
    print(message)
    try:
        from .diag_log import log

        log(message)
    except Exception:
        pass


def _require_win32crypt():
    """返回启动期缓存的 win32crypt 模块；不可用时补发一次带根因的告警并返回 None。"""
    global _warned_unavailable
    if _win32crypt is None and os.name == "nt" and not _warned_unavailable:
        _warned_unavailable = True
        reason = _win32crypt_error or "未知原因"
        _warn(
            f"警告：win32crypt 不可用（{reason}），凭据将以明文存储。"
            "请检查 pywin32 安装或打包配置。"
        )
    return _win32crypt


def is_supported() -> bool:
    return os.name == "nt"


def protect(plaintext: str) -> str:
    """加密明文为 ``dpapi:<base64>`` 形式；不支持或失败时原样返回（不阻塞保存）。"""
    if not plaintext:
        return plaintext
    if plaintext.startswith(DPAPI_PREFIX):
        # 已加密，幂等返回（避免对内存中已解密的 config 重复加密造成二次写入损坏）
        return plaintext
    if not is_supported():
        return plaintext
    module = _require_win32crypt()
    if module is None:
        return plaintext
    try:
        blob = module.CryptProtectData(plaintext.encode("utf-8"), None, None, None, None, 0)
        return DPAPI_PREFIX + base64.b64encode(blob).decode("ascii")
    except Exception as exc:  # 加密失败不得丢失用户数据：回退明文（与旧行为一致）+ 提示
        _warn(f"DPAPI 加密失败，按明文存储：{exc}")
        return plaintext


def unprotect(stored: str) -> str:
    """解密 ``dpapi:<base64>``；无前缀视为旧明文原样返回；解密失败返回空（凭据失效需重登）。"""
    if not stored:
        return stored
    if not stored.startswith(DPAPI_PREFIX):
        return stored
    payload = stored[len(DPAPI_PREFIX):]
    # 前缀命中但载荷非合法 base64：视为「真实密码恰以 dpapi: 开头」的明文原样返回，
    # 避免把普通凭据误判为密文去解码失败后清空（凭据丢失需重登）。
    try:
        blob = base64.b64decode(payload, validate=True)
    except Exception:
        return stored
    if not is_supported():
        global _warned_unsupported
        if not _warned_unsupported:
            _warn("警告：检测到加密凭据但当前平台非 Windows，无法解密。")
            _warned_unsupported = True
        return ""
    module = _require_win32crypt()
    if module is None:
        return ""
    try:
        # CryptUnprotectData 返回 (description, plaintext_bytes)
        return module.CryptUnprotectData(blob, None, None, None, 0)[1].decode("utf-8")
    except Exception:
        # 跨用户/损坏：返回空，让上层走"凭据失效需重新登录"路径，而不是报崩溃
        return ""
