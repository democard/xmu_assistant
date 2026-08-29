from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_MAIN = ROOT / "android" / "app" / "src" / "main"
ANDROID_SRC = ANDROID_MAIN / "java" / "com" / "xmu" / "assistant"
ANDROID_STRINGS = ANDROID_MAIN / "res" / "values" / "strings.xml"


def _with_strings(text: str) -> str:
    """把 string 资源并进扫描面。

    资源化批次（考试页 B4 / 课件页 B1）把用户可见文案从 kt 源码迁到 strings.xml，
    扫描面必须随之覆盖资源文件，否则守卫会退化成「只认硬编码」——
    那反而会阻碍资源化。语义不变：这些文案必须在发布物里存在。
    """
    return text + "\n" + ANDROID_STRINGS.read_text(encoding="utf-8")


class AndroidSourceTests(unittest.TestCase):
    def test_android_core_sources_exist(self):
        for name in (
            "AssistantSettings.kt",
            "NotificationSenders.kt",
            "RollcallEngine.kt",
            "RollcallModels.kt",
            "TronclassLogin.kt",
            "CoursewareClient.kt",
        ):
            self.assertTrue((ANDROID_SRC / name).exists(), name)

    def test_manifest_uses_f1_icon_and_foreground_service(self):
        manifest = (ANDROID_MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")

        self.assertIn('android:label="@string/app_name"', manifest)
        self.assertIn('android:icon="@mipmap/ic_launcher"', manifest)
        self.assertIn('android:roundIcon="@mipmap/ic_launcher_round"', manifest)
        self.assertIn('android:scheme="xmurollcall"', manifest)
        self.assertIn('android:host="rollcall"', manifest)
        self.assertIn("FOREGROUND_SERVICE_DATA_SYNC", manifest)

    def test_android_ui_is_windows_mobile_port_not_web_cookie_shell(self):
        # MainActivity 拆分后 UI 文案分散在多个文件：合并检查（2026-08-19 守卫测试更新）
        ui = _with_strings(
            "\n".join(
                (ANDROID_SRC / name).read_text(encoding="utf-8")
                for name in ("MainActivity.kt", "MainScreen.kt", "Pages.kt", "UiComponents.kt", "CoursewarePage.kt")
            )
        )

        for title in ("首页", "签到情况", "课程课件", "通知", "教程", "策略"):
            self.assertIn(f'"{title}"', ui)
        self.assertNotIn("打开网页登录并保存 Cookie", ui)
        self.assertNotIn('"签到事件"', ui)
        self.assertNotIn('"设置"', ui)
        self.assertIn("启动监控", ui)
        self.assertIn("开启自动签到", ui)
        self.assertIn("请选择课程", ui)
        self.assertIn("缺少配置", ui)
        self.assertIn("温馨提示", ui)
        self.assertIn("发送测试通知", ui)
        self.assertNotIn("下一步建议", ui)
        # 下载进度表达改为按钮状态文案「下载中」（下载中/下载（N）切换）
        self.assertIn("下载中", ui)
        self.assertIn("本机通知", ui)
        self.assertIn("微信 PushPlus", ui)
        self.assertIn("QQ 邮箱", ui)
        self.assertIn("SMTP 端口", ui)
        self.assertIn("465,587", ui)
        self.assertIn("二维码签到只提醒", ui)
        # 原断言 "Modifier.clickable"→import 文本：UI 拆分后 clickable 用法已移出
        # UiComponents（本轮死导入清理一并移除），改锚本文件真实在用的原生交互
        # API toggleable（语义等价加严：锚定物必须实际存在）
        self.assertIn("androidx.compose.foundation.selection.toggleable", ui)
        self.assertIn("safeDrawingPadding", ui)
        self.assertIn("xmu_assistant_logo", ui)
        self.assertTrue((ROOT / "assets" / "xmu-assistant-icon.svg").exists())
        self.assertTrue((ROOT / "assets" / "xmu-assistant-icon.png").exists())

    def test_android_native_login_ports_windows_account_password_login(self):
        login = (ANDROID_SRC / "TronclassLogin.kt").read_text(encoding="utf-8")
        main = (ANDROID_SRC / "MainActivity.kt").read_text(encoding="utf-8")

        self.assertIn("class TronclassLogin", login)
        self.assertIn("pwdEncryptSalt", login)
        self.assertIn("AES/CBC/PKCS5Padding", login)
        self.assertIn("TronClassH5", login)
        self.assertIn("api/login?login=access_token", login)
        self.assertIn("TronclassLogin().login", main)

    def test_android_rollcall_and_courseware_use_clear_chinese_statuses(self):
        engine = (ANDROID_SRC / "RollcallEngine.kt").read_text(encoding="utf-8")
        models = (ANDROID_SRC / "RollcallModels.kt").read_text(encoding="utf-8")
        courseware = (ANDROID_SRC / "CoursewareClient.kt").read_text(encoding="utf-8")

        self.assertIn("数字签到", engine)
        self.assertIn("雷达签到", engine)
        self.assertIn("二维码签到", engine)
        self.assertIn("未签", models)
        self.assertIn("已签", models)
        self.assertIn("可下载", models)
        self.assertIn("下载成功", models)
        self.assertIn("下载失败", models)
        self.assertIn("已保存入口", models)
        self.assertIn("平台限制", models)
        self.assertIn("平台未提供地址", models)
        self.assertIn("登录过期", models)
        self.assertIn("网络失败", models)
        self.assertIn("fetchCourses", courseware)
        self.assertIn("fetchCourseware", courseware)
        self.assertIn("download", courseware)
        self.assertIn("courseware_activities", courseware)
        self.assertIn("exam", courseware)
        self.assertIn("homework", courseware)

    def test_android_courseware_page_ports_windows_download_flow(self):
        # 课件页拆分为独立 CoursewarePage.kt；条目卡片文案（章节/文件名/下载状态/失败原因）
        # 在 UiComponents.kt 的 CoursewareItemCard 中（2026-08-19 守卫测试更新）
        combined = _with_strings(
            (ANDROID_SRC / "CoursewarePage.kt").read_text(encoding="utf-8")
            + (ANDROID_SRC / "UiComponents.kt").read_text(encoding="utf-8")
        )

        for text in (
            "学年",
            "学期",
            "刷新课程",
            "刷新课件",
            "下载",
            "全选",
            "打开平台页面",
            "章节",
            "课件",
            "文件名",
            "下载状态",
            "失败原因",
        ):
            self.assertIn(text, combined)

    def test_android_tutorial_contains_full_windows_like_sections(self):
        # 教程页（TutorialPage 及其锚点）已移入 Pages.kt（2026-08-19 守卫测试更新）
        pages = (ANDROID_SRC / "Pages.kt").read_text(encoding="utf-8")

        for section in (
            "签到启用教程",
            "开启监控",
            "开启自动签到",
            "设置微信通知",
            "设置 QQ 邮箱通知",
            "下载课件",
            "常见问题",
            "网页版",
            "二维码签到只提醒",
            "模拟成绩",
        ):
            self.assertIn(section, pages)

    def test_android_notifications_use_f1_small_icon_and_bounded_seen_cache(self):
        service = (ANDROID_SRC / "RollcallMonitorService.kt").read_text(encoding="utf-8")
        notify = (ANDROID_SRC / "NotificationSenders.kt").read_text(encoding="utf-8")
        settings = (ANDROID_SRC / "AssistantSettings.kt").read_text(encoding="utf-8")

        self.assertIn("R.drawable.ic_notification", service)
        self.assertIn("MAX_SEEN_ROLLCALLS", service)
        self.assertIn("ArrayDeque", service)
        self.assertIn("notify.qqMailPorts", service)
        self.assertIn("class QQMailSender", notify)
        self.assertIn("parseSmtpPorts", notify)
        self.assertIn("mail.smtp.ssl.enable", notify)
        self.assertIn("EncryptedSharedPreferences", settings)
        self.assertIn("qq_ports", settings)


if __name__ == "__main__":
    unittest.main()
