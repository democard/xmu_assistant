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

# 所有权标记文件名：写在本应用自己的解包目录里。%TEMP% 的 _MEI* 命名是 PyInstaller
# 全局约定，同机其他 PyInstaller 应用的残留也以 _MEI 开头——无标记的一律不碰
_OWNER_MARKER_NAME = "xmu_assistant_owner.marker"


def mark_own_extraction_dir() -> None:
    """在自己的解包目录写所有权标记（后台清理只删带标记的超龄残留）。失败静默。"""
    mei = getattr(sys, "_MEIPASS", "") or ""
    if not mei:  # 源码态无解包目录：no-op（normpath("") 会变成 "."，必须先判空）
        return
    mei = os.path.normpath(mei)
    try:
        with open(os.path.join(mei, _OWNER_MARKER_NAME), "w", encoding="utf-8") as file:
            file.write("xmu-assistant")
    except OSError:
        pass


def cleanup_orphaned_pyinstaller_temp() -> None:
    """清理 %TEMP% 下残留的 _MEI* 解包目录。

    onefile exe 每次启动在 %TEMP%\\_MEIxxxxxx 解包；崩溃/被杀时该目录不自清，常驻托盘
    长期运行会成片堆积（单份约 80MB）。本函数扫描 %TEMP%，删除非当前进程、且超过
    _MEI_CLEANUP_MIN_AGE_SECONDS 年龄的 _MEI 目录（年龄保护：避免删到正在被并发启动
    实例使用的解包目录）。

    仅删带本应用所有权标记的目录：_MEI* 是 PyInstaller 全局约定，同机其他 PyInstaller
    应用正在使用/遗留的解包目录同名同形，rmtree 会让对方懒加载全失效——无标记的一律
    跳过（宁可少清也不误删）。标记由 mark_own_extraction_dir 在启动期写入，随单实例
    互斥保证只属于存活实例。
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
        if not os.path.isfile(os.path.join(path, _OWNER_MARKER_NAME)):
            # 无本应用标记：外来应用或极旧版本残留。宁可少清也不误删
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
