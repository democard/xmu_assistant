"""单实例互斥与跨进程「双击唤起」事件：常量与模块级函数。

自 app.py 机械搬出（tray.py / notifications_page.py 同款手法），函数体逐字保留；
互斥量/命名事件句柄等模块级全局随迁，app.py 经 import 引用，行为不变。
QTimer 周期监听（install_show_event_watcher/_show_event_tick）一并随迁：
桌面包本就依赖 Qt，整体搬移保持代码块完整。
"""

from __future__ import annotations

from PySide6.QtCore import QTimer

# 单例互斥量名：OS 在进程退出时自动释放。选 Win32 命名互斥量而非 Qt QLocalServer，
# 后者依赖 PySide6.QtNetwork 的命名管道后端，在 PyInstaller onefile 打包后不可靠。
SINGLE_INSTANCE_MUTEX_NAME = "xmu_assistant_dashboard_single"
# 首个实例持有的互斥量句柄（保活到进程结束，避免 GC 提前关闭导致单例失效）
_single_instance_mutex_handle = None
# 跨进程「双击唤起开窗口」命名事件：FindWindow 按标题找窗口的方案在目标实例为
# --startup、主窗口从未创建/不可见（仅托盘）时够不到，改用命名事件置位通知。
SHOW_EVENT_NAME = "xmu_assistant_dashboard_show"
# 独占实例持有的事件句柄（保活到进程退出）；新实例双击时 OpenEvent + SetEvent。
_show_event_handle = None
# 监听 QTimer 的强引用（局部 QTimer 会被 GC 提前销毁导致不再触发）
_active_show_timers: list[QTimer] = []
MAIN_WINDOW_TITLE = "xmu助手"  # 与 setWindowTitle 一致，供 FindWindow 唤起已有实例


def acquire_single_instance() -> bool:
    """创建命名互斥量。True=本实例是首个（持锁）；False=已有实例在跑。

    非 Windows 或无 pywin32 时退化为不限制（不阻塞启动）。
    """
    global _single_instance_mutex_handle
    try:
        import win32event
        import win32api
        import winerror
    except ImportError:
        return True
    try:
        handle = win32event.CreateMutex(None, False, SINGLE_INSTANCE_MUTEX_NAME)
    except Exception:
        return True
    # ERROR_ALREADY_EXISTS(=183) 定义在 winerror（不在 win32event/win32con）：
    # CreateMutex 后立即查 LastError，已存在同名互斥量=首个实例已持有
    if win32api.GetLastError() == winerror.ERROR_ALREADY_EXISTS:
        try:
            win32api.CloseHandle(handle)
        except Exception:
            pass
        return False
    _single_instance_mutex_handle = handle  # 持有到进程退出，OS 自动释放
    # 首个实例额外创建命名「显示主窗口」事件：供后续实例双击唤起时跨进程置位。
    global _show_event_handle
    try:
        _show_event_handle = win32event.CreateEvent(None, True, False, SHOW_EVENT_NAME)
    except Exception:
        _show_event_handle = None
    return True


def raise_existing_instance_window() -> None:
    """找到已有实例的主窗口并唤到前台（用户再次双击 exe 时把托盘里的窗口弹出）。"""
    try:
        import win32gui
        import win32con
    except ImportError:
        return
    try:
        hwnd = win32gui.FindWindow(None, MAIN_WINDOW_TITLE)
        if not hwnd:
            return
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        win32gui.SetForegroundWindow(hwnd)
    except Exception:
        pass


def activate_existing_instance() -> None:
    """非首个实例：通知独占实例把主窗口显示到前台。

    优先用命名事件（SetEvent）——FindWindow 按标题找窗口在目标为 --startup、
    主窗口从未创建/仅托盘时找不到；事件机制与窗口是否显示无关、更可靠。
    目标不支持事件机制（旧版 exe / 无 pywin32）时降级回标题查找。
    """
    try:
        import win32event
        import win32api
    except ImportError:
        raise_existing_instance_window()
        return
    try:
        handle = win32event.OpenEvent(win32event.EVENT_MODIFY_STATE, False, SHOW_EVENT_NAME)
        if handle:
            win32event.SetEvent(handle)
            win32api.CloseHandle(handle)
            return
    except Exception:
        pass
    raise_existing_instance_window()


def _show_event_tick(window: "DashboardWindow") -> None:
    """事件已置位：把主窗口显示到前台并复位事件（QTimer 周期回调）。"""
    try:
        import win32event
    except ImportError:
        return
    try:
        if win32event.WaitForSingleObject(_show_event_handle, 0) != win32event.WAIT_OBJECT_0:
            return
        win32event.ResetEvent(_show_event_handle)
    except Exception:
        return
    # 从未显示（仅托盘/--startup）→ 显示；最小化 → 恢复正常；再置顶激活
    if not window.isVisible():
        window.show()
    elif window.isMinimized():
        window.showNormal()
    if not window.isActiveWindow():
        window.raise_()
        window.activateWindow()


def install_show_event_watcher(window: "DashboardWindow") -> None:
    """独占实例侧：监听「双击唤起」事件并周期检查（覆盖仅托盘常驻场景）。

    目标实例即使主窗口从未创建/不可见，也能在收到事件时把窗口显示出来，
    彻底避免「双击没反应」（旧 FindWindow 方案在窗口未创建时静默失效）。
    """
    try:
        import win32event  # noqa: F401  验证 pywin32 可用
    except ImportError:
        return
    if _show_event_handle is None:
        return
    timer = QTimer()
    timer.setInterval(500)
    timer.timeout.connect(lambda: _show_event_tick(window))
    timer.start()
    _active_show_timers.append(timer)  # 保强引用防 GC
