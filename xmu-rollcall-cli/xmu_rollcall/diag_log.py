"""极小的诊断日志辅助：追加写入配置目录下 diag.log。

打包后的 exe（console=False）没有 stdout，print() 输出的诊断线索
（如会话保存/恢复失败原因）完全不可见；这类信息改经本模块落盘。
目录获取复用 config 的现有机制（CONFIG_DIR，兼容
XMU_ROLLCALL_CONFIG_DIR 测试环境变量）；任何写入失败一律静默降级为
no-op——诊断手段本身绝不允许影响主流程。

位于包根（2026-08-28 自 desktop_qt/ 上移）：utils.py 等引擎层模块也要写
诊断日志，若留在 desktop_qt 包内会形成 engine→desktop 的反向依赖
（rollcall_models 当年拆包即为断开此方向的环）。
"""

import datetime
import os
import threading

from . import config

DIAG_LOG_NAME = "diag.log"
DIAG_LOG_MAX_BYTES = 1024 * 1024
DIAG_LOG_BACKUP_NAME = "diag.log.1"

_WRITE_LOCK = threading.Lock()


def log(message: str) -> None:
    """追加一行带时间戳的诊断信息到 CONFIG_DIR/diag.log；失败静默忽略。"""
    try:
        line = "{} {}\n".format(
            datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), message
        )
        path = config.CONFIG_DIR / DIAG_LOG_NAME
        # 同一进程中的线程共用一把锁，覆盖尺寸检查、轮转与追加的整个过程。
        # 文件句柄在轮转前关闭，避免 Windows 拒绝重命名正在打开的日志。
        with _WRITE_LOCK:
            try:
                current_size = path.stat().st_size
            except FileNotFoundError:
                current_size = 0
            incoming_size = len(line.encode("utf-8"))
            if current_size and current_size + incoming_size > DIAG_LOG_MAX_BYTES:
                backup = config.CONFIG_DIR / DIAG_LOG_BACKUP_NAME
                try:
                    os.replace(path, backup)
                except OSError:
                    # 轮转失败时仍尽量保住日志，继续追加主文件。
                    pass
            with open(path, "a", encoding="utf-8") as file:
                file.write(line)
    except Exception:
        pass
