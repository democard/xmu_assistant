"""DPAPI-backed secret protection for the desktop dashboard config.

凭据敏感字段（统一身份认证密码 / SMTP 授权码 / PushPlus token / 会话 cookie）的
Windows 用户级加密存储。设计目标：

- 加密仅 Windows（pywin32 win32crypt.CryptProtectData / CryptUnprotectData），
  非 Windows 平台原样透传并打印一次警告（桌面端仅 Windows 部署，CI 可降级）。
- 存储/读取向后兼容旧明文：读取时无 ``dpapi:`` 前缀视为旧明文原样返回；
  保存时一律写密文（首次保存即自动迁移）。
- 幂等：protect 对已加密串（带前缀）不重复加密，避免双重加密。
"""
from __future__ import annotations

import base64
import os

DPAPI_PREFIX = "dpapi:"
_warned_unsupported = False


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
    try:
        import win32crypt
    except ImportError:
        # pywin32 未安装（依赖漏装/精简/非标准环境）：显式告警，避免"看似加密实则明文"
        print("警告：未安装 pywin32（缺少 win32crypt），凭据将以明文存储。请「pip install pywin32」后重启。")
        return plaintext
    try:
        blob = win32crypt.CryptProtectData(plaintext.encode("utf-8"), None, None, None, None, 0)
        return DPAPI_PREFIX + base64.b64encode(blob).decode("ascii")
    except Exception as exc:  # 加密失败不得丢失用户数据：回退明文（与旧行为一致）+ 提示
        print(f"DPAPI 加密失败，按明文存储：{exc}")
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
            print("警告：检测到加密凭据但当前平台非 Windows，无法解密。")
            _warned_unsupported = True
        return ""
    try:
        import win32crypt
        # CryptUnprotectData 返回 (description, plaintext_bytes)
        return win32crypt.CryptUnprotectData(blob, None, None, None, 0)[1].decode("utf-8")
    except Exception:
        # 跨用户/损坏：返回空，让上层走"凭据失效需重新登录"路径，而不是报崩溃
        return ""
