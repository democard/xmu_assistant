"""磁盘冗余清理：_MEI 解包残留与孤儿 cookie 文件。

自 app.py 机械搬出（tray.py / notifications_page.py 同款手法），函数体逐字保留；
app.py 经 import 引用 _background_disk_cleanup 装配后台线程，行为不变。
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import time

from ..config import CONFIG_DIR, CONFIG_LOCK, load_config
from ..diag_log import log as _diag_log

# _MEI 解包残留的安全清理年龄：仅删 1 小时前的目录，避免误删正在被并发启动实例使用的解包目录
_MEI_CLEANUP_MIN_AGE_SECONDS = 3600


def cleanup_orphaned_pyinstaller_temp() -> None:
    """清理 %TEMP% 下残留的 _MEI* 解包目录。

    onefile exe 每次启动在 %TEMP%\\_MEIxxxxxx 解包；崩溃/被杀时该目录不自清，常驻托盘
    长期运行会成片堆积（单份约 80MB）。本函数扫描 %TEMP%，删除非当前进程、且超过
    _MEI_CLEANUP_MIN_AGE_SECONDS 年龄的 _MEI 目录（年龄保护：避免删到正在被并发启动
    实例使用的解包目录）。
    """
    current_mei = os.path.normcase(getattr(sys, "_MEIPASS", "") or "")
    temp_dir = os.environ.get("TEMP") or os.environ.get("TMP") or tempfile.gettempdir()
    try:
        entries = os.listdir(temp_dir)
    except OSError:
        return
    now = time.time()
    for name in entries:
        if not name.startswith("_MEI"):  # 仅处理 PyInstaller onefile 解包目录
            continue
        path = os.path.join(temp_dir, name)
        if not os.path.isdir(path):
            continue
        if current_mei and os.path.normcase(path) == current_mei:  # 跳过当前进程的解包目录
            continue
        try:
            # 年龄保护：跳过近期创建/修改的目录（可能正被并发启动实例使用）
            if now - os.path.getmtime(path) < _MEI_CLEANUP_MIN_AGE_SECONDS:
                continue
        except OSError:
            continue
        shutil.rmtree(path, ignore_errors=True)


def cleanup_orphaned_cookie_files(config: dict) -> None:
    """删除 CONFIG_DIR 中不属于任何账号的 {id}.json cookie 文件。

    账号被删除后其 cookie 缓存会遗留。保守地只删纯数字命名的 {id}.json（排除 config.json 等）。
    """
    try:
        valid_ids = {
            str(a.get("id")) for a in config.get("accounts", []) if a.get("id") is not None
        }
    except Exception:
        return
    # 全程持 CONFIG_LOCK：快照(listdir)→remove 与登录路径 add_account→save_session
    # 并发时，晚于快照落盘的新账号 cookie 会被误判孤儿删除（TOCTOU，下次恢复必败）。
    # save_session 侧持同一把锁互斥；RLock 允许 load_config 重入。
    with CONFIG_LOCK:
        try:
            entries = os.listdir(CONFIG_DIR)
        except OSError:
            return
        for name in entries:
            if not name.endswith(".json"):
                continue
            stem = name[:-len(".json")]
            if not stem.isdigit() or stem in valid_ids:  # 仅清纯数字账号 cookie，绝不动 config.json
                continue
            try:
                os.remove(os.path.join(CONFIG_DIR, name))
            except OSError:
                pass  # 单文件失败（占用/权限）跳过，继续清其余孤儿文件，不中断循环


def _background_disk_cleanup() -> None:
    """后台执行磁盘冗余清理：_MEI 残留（可能慢，堆积多目录）放后台不阻塞窗口显示。"""
    cleanup_orphaned_pyinstaller_temp()
    try:
        # 锁必须先于 load_config 快照：否则快照与持锁之间登录落盘的新账号
        # cookie 不在快照内，仍会被下方误判孤儿（窗口闭合见 cleanup 内注释）
        with CONFIG_LOCK:
            cleanup_orphaned_cookie_files(load_config())
    except Exception as exc:
        # daemon 线程内不可见（无 stdout）：留 diag 线索供排查孤儿 cookie 未清问题
        _diag_log(f"后台 cookie 清理失败：{exc}")
