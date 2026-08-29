"""theme.py PrimaryButton 关键 QSS 规则与 single_instance 常量锚定（C2 补锚）。

PrimaryButton 的 font-weight:700 是第十五/十六轮确认的样式事实：粗体度量
偏紧曾致 8 字主按钮（"刷新签到情况数据"）文字两端裁边，已用短文案「刷新」
规避——未来任何 ≥7 字主按钮都会复现。去粗体或补 padding 属视觉变更需用户
过目，此处锚定现状防静默漂移。

single_instance 的互斥量名/唤起事件名是跨进程协议：新旧 exe 并存时靠同名
互斥量让位（第十六轮实测源码实例对用户 exe 双进程静默 exit 0 即此机制），
改名即双实例并存；窗口标题同时被 FindWindow 唤起路径使用，一并锚定。
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ROOT = ROOT / "xmu-rollcall-cli"

if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from xmu_rollcall.desktop_qt.single_instance import (  # noqa: E402
    MAIN_WINDOW_TITLE,
    SHOW_EVENT_NAME,
    SINGLE_INSTANCE_MUTEX_NAME,
)
from xmu_rollcall.desktop_qt.theme import stylesheet  # noqa: E402

THEME_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "theme.py"


class PrimaryButtonQssContractTests(unittest.TestCase):
    def test_primary_button_rules_anchored_in_both_themes(self):
        source = THEME_PY.read_text(encoding="utf-8")
        blocks = re.findall(r"QPushButton#PrimaryButton \{(.*?)\}", source, re.DOTALL)
        # 浅色/深色两套样式表各一份，规则必须一致
        self.assertGreaterEqual(len(blocks), 2, "PrimaryButton QSS 块应存在于浅/深两套主题")
        for index, block in enumerate(blocks):
            self.assertIn("background: #0d4f8b", block, f"第 {index} 块主按钮底色漂移")
            self.assertIn("color: #ffffff", block, f"第 {index} 块主按钮文字色漂移")
            # 粗体是裁边缺陷的根因（第十五轮 A/B 取证），调整属视觉变更需用户拍板
            self.assertIn("font-weight: 700", block, f"第 {index} 块主按钮粗体被改动（需用户过目）")


class ThemeOutputContractTests(unittest.TestCase):
    """stylesheet() 运行时输出锚（E1）：浅/深两态各自的关键 QSS 规则整体锁定。

    既有测试锚的是 theme.py 源码文本；这里锚组装后的实际输出——未来任何人
    改 _light/_dark 模板拼接（加 EXTRAS、改拼接顺序）导致关键规则丢失，
    源码锚不必然兜住，运行时锚才兜得住。
    """

    def test_light_output_keeps_key_rules(self):
        css = stylesheet("light")
        self.assertIn("QPushButton#PrimaryButton", css, "浅色样式表丢主按钮规则")
        self.assertIn("background: #0d4f8b", css, "浅色主按钮底色漂移")
        self.assertIn("background: #eef3f8", css, "浅色全局背景色漂移")
        self.assertIn("color: #18324f", css, "浅色全局前景色漂移")

    def test_dark_output_keeps_key_rules(self):
        css = stylesheet("dark")
        self.assertIn("QPushButton#PrimaryButton", css, "深色样式表丢主按钮规则")
        self.assertIn("background: #0d4f8b", css, "深色主按钮底色漂移")
        self.assertIn("background: #0d1117", css, "深色全局背景色漂移")
        self.assertIn("color: #e3ebf3", css, "深色全局前景色漂移")

    def test_dark_and_light_outputs_are_distinct(self):
        self.assertNotEqual(stylesheet("light"), stylesheet("dark"), "深浅两态输出相同=深色模式失效")


class QssIdSelectorLivenessTests(unittest.TestCase):
    """QSS 的 #id 选择器必须对应实际 setObjectName 使用点（死选择器防再生）。

    本轮清理 17 组零引用死规则（Hero/Metric/Toast/GuidePanel 等历史 UI 体系
    残留，浅深两态双份）后立的守护：新增 QSS 规则若不带实际 objectName
    使用点，本测试即红。
    """

    def test_every_id_selector_has_a_setobjectname_call(self):
        qss = stylesheet("light") + stylesheet("dark")
        # 只取选择器位置的 #id（后跟 { , :），颜色字面量（后跟空格/;）不误提取
        ids = set(re.findall(r"[A-Za-z]+#([A-Za-z][A-Za-z0-9_]*)\s*[{,:]", qss))
        self.assertTrue(ids, "ID 选择器抽取为空，正则可能失效")
        used = set()
        for path in (ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt").glob("*.py"):
            if path.name == "theme.py":
                continue
            used |= set(re.findall(r'setObjectName\("([A-Za-z]+)"\)', path.read_text(encoding="utf-8")))
        dead = ids - used
        self.assertFalse(dead, f"QSS 死 ID 选择器（无 setObjectName 使用点）: {sorted(dead)}")


class SingleInstanceConstantsContractTests(unittest.TestCase):
    def test_mutex_and_event_names_are_frozen(self):
        self.assertEqual(SINGLE_INSTANCE_MUTEX_NAME, "xmu_assistant_dashboard_single")
        self.assertEqual(SHOW_EVENT_NAME, "xmu_assistant_dashboard_show")
        self.assertEqual(MAIN_WINDOW_TITLE, "xmu助手")


if __name__ == "__main__":
    unittest.main()
