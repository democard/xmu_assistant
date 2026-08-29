# -*- mode: python ; coding: utf-8 -*-
"""xmu-assistant onefile spec with PySide6 unused-binary filtering.

--exclude-module 对 PyInstaller 的 PySide6 hook 主动收集的 Qt6 DLL 无效，
必须在 Analysis 之后手动从 a.binaries 过滤掉未使用的 Qt6 二进制。
依据 2026-08-20 onedir 实测：可删未用模块合计约 42 MB（onedir）。
"""
import re

block_cipher = None

# 实测确认项目只用 QtCore/QtGui/QtWidgets；以下 Qt6 二进制与对应 .pyd 均未使用，可删。
# （opengl32sw.dll 是 Qt 软件 OpenGL 兜底，现代 Windows 有显卡驱动，可删）
_EXCLUDE_BIN_PATTERNS = [
    r'opengl32sw\.dll$',
    # 未用 Qt6 模块 DLL
    r'Qt6Quick\.dll$', r'Qt6Qml.*\.dll$', r'Qt6Pdf.*\.dll$',
    r'Qt6OpenGL.*\.dll$', r'Qt6Network.*\.dll$', r'Qt6Svg.*\.dll$',
    r'Qt6VirtualKeyboard.*\.dll$', r'Qt63D.*\.dll$',
    r'Qt6Charts.*\.dll$', r'Qt6DataVisualization.*\.dll$',
    r'Qt6Graphs.*\.dll$', r'Qt6Bluetooth.*\.dll$',
    r'Qt6WebEngine.*\.dll$', r'Qt6WebChannel.*\.dll$',
    r'Qt6WebSockets.*\.dll$', r'Qt6HttpServer.*\.dll$',
    r'Qt6WebView.*\.dll$', r'Qt6Positioning.*\.dll$',
    r'Qt6Location.*\.dll$', r'Qt6Sensors.*\.dll$',
    r'Qt6SerialPort.*\.dll$', r'Qt6SerialBus.*\.dll$',
    r'Qt6TextToSpeech.*\.dll$', r'Qt6SpatialAudio.*\.dll$',
    r'Qt6RemoteObjects.*\.dll$', r'Qt6Scxml.*\.dll$',
    r'Qt6StateMachine.*\.dll$', r'Qt6Help.*\.dll$',
    r'Qt6Designer.*\.dll$', r'Qt6PrintSupport.*\.dll$',
    r'Qt6Sql.*\.dll$', r'Qt6Test.*\.dll$', r'Qt6Concurrent.*\.dll$',
    r'Qt6DBus.*\.dll$', r'Qt6Xml.*\.dll$', r'Qt6Nfc.*\.dll$',
    # Qt 插件剔除（可删插件）：
    # - imageformats/*：PNG 解码内置 Qt6Gui；ICO 必须保留 qico.dll（非内置！），
    #   误删会导致打包后窗口/任务栏/托盘图标全部退化为默认图标；
    #   其余 jpeg/gif/webp/tiff/tga/icns/wbmp/bmp/svg 等额外格式插件删除省 ~5MB。
    # - iconengines/qsvgicon.dll：依赖 Qt6Svg（已删 Svg pyd），桌面无图标引擎需求。
    # - generic/qtuiotouchplugin.dll：触屏输入插件，桌面端不需要。
    # - platforms 仅保留 qwindows.dll（必需）；qoffscreen/qminimal/qdirect2d 供无头/测试/直显，删。
    # - styles/qmodernwindowsstyle.dll 保留（现代样式）。
    r'PySide6[\\/]plugins[\\/]imageformats[\\/](?!qico\.dll$).*\.dll$',
    r'PySide6[\\/]iconengines[\\/].*\.dll$',
    r'PySide6[\\/]plugins[\\/]generic[\\/].*\.dll$',
    r'PySide6[\\/]plugins[\\/]platforms[\\/]q(offscreen|minimal|direct2d)\.dll$',
    # Qt 翻译资源：应用仅中文，96 个 .qm 中非 zh_CN 的 ~1.84MB 是死重；
    # 保留 zh_CN 供 Qt 标准对话框中文显示（2026-08-27 实测归档内 .qm 全集 1.94MB）
    r'PySide6[\\/]translations[\\/](?!.*zh_CN\.qm$).*\.qm$',
    # 未用 PySide6 .pyd 绑定（QtCore/QtGui/QtWidgets 保留）
    r'PySide6[\\/]Qt(Network|Quick|Qml|Pdf|OpenGL|OpenGLWidgets|Svg|SvgWidgets|Multimedia|MultimediaWidgets|WebEngineCore|WebEngineWidgets|WebEngineQuick|WebChannel|WebSockets|HttpServer|WebView|Charts|DataVisualization|Graphs|GraphsWidgets|Bluetooth|UiTools|AxContainer|3DCore|3DRender|3DInput|3DLogic|3DAnimation|3DExtras|CanvasPainter|QuickTest|Quick3D|QuickControls2|QuickWidgets|Positioning|Location|NetworkAuth|Nfc|RemoteObjects|Scxml|Sensors|SerialPort|SerialBus|StateMachine|TextToSpeech|SpatialAudio|Concurrent|DBus|Designer|Xml|Help|PrintSupport|Sql|Test)\.pyd$',
]

# PySide6 Python 模块排除（双保险，对 import 分析有效）
_EXCLUDE_MODULES = [
    'PySide6.QtPrintSupport', 'PySide6.QtSql', 'PySide6.QtNetwork',
    'PySide6.QtTest', 'PySide6.QtConcurrent', 'PySide6.QtDBus',
    'PySide6.QtDesigner', 'PySide6.QtXml', 'PySide6.QtHelp',
    'PySide6.QtMultimedia', 'PySide6.QtMultimediaWidgets',
    'PySide6.QtOpenGL', 'PySide6.QtOpenGLWidgets',
    'PySide6.QtPdf', 'PySide6.QtPdfWidgets',
    'PySide6.QtPositioning', 'PySide6.QtLocation', 'PySide6.QtNetworkAuth',
    'PySide6.QtNfc', 'PySide6.QtQml', 'PySide6.QtQuick', 'PySide6.QtQuick3D',
    'PySide6.QtQuickControls2', 'PySide6.QtQuickWidgets',
    'PySide6.QtRemoteObjects', 'PySide6.QtScxml', 'PySide6.QtSensors',
    'PySide6.QtSerialPort', 'PySide6.QtSerialBus', 'PySide6.QtStateMachine',
    'PySide6.QtTextToSpeech', 'PySide6.QtCharts', 'PySide6.QtSpatialAudio',
    'PySide6.QtSvg', 'PySide6.QtSvgWidgets', 'PySide6.QtDataVisualization',
    'PySide6.QtGraphs', 'PySide6.QtGraphsWidgets', 'PySide6.QtBluetooth',
    'PySide6.QtUiTools', 'PySide6.QtAxContainer', 'PySide6.QtWebChannel',
    'PySide6.QtWebEngineCore', 'PySide6.QtWebEngineWidgets',
    'PySide6.QtWebEngineQuick', 'PySide6.QtWebSockets',
    'PySide6.QtHttpServer', 'PySide6.QtWebView',
    'PySide6.Qt3DCore', 'PySide6.Qt3DRender', 'PySide6.Qt3DInput',
    'PySide6.Qt3DLogic', 'PySide6.Qt3DAnimation', 'PySide6.Qt3DExtras',
    'PySide6.QtCanvasPainter', 'PySide6.QtQuickTest',
    'pytest', 'unittest', 'tkinter',
]

from PyInstaller.utils.hooks import collect_submodules

a = Analysis(
    ['scripts/xmu_dashboard_launcher.py'],
    pathex=['xmu-rollcall-cli'],
    binaries=[],
    datas=[
        ('assets/xmu-assistant.ico', 'assets'),
        # 运行时只加载 mark.png（品牌位图）；app_icon() 首选 ICO（qico.dll 已保留，
        # 必能加载），mark.png 同时兼任 PNG 兜底。icon/logo 两张纯兜底图不再打包
        # （实测省 ~0.73MB，app_icon 的回退链 mark 仍在，行为不变）。
        ('assets/xmu-assistant-mark.png', 'assets'),
    ],
    hiddenimports=['win32crypt']
               + collect_submodules('xmu_rollcall')
               + collect_submodules('xmulogin'),
    hookspath=[],
    runtime_hooks=[],
    excludes=_EXCLUDE_MODULES,
    noarchive=False,
)

# 关键：手动过滤 hook 主动收集的未用 Qt6 二进制（--exclude-module 对此无效）
before = len(a.binaries)
a.binaries = [
    b for b in a.binaries
    if not any(re.search(p, b[0], re.I) for p in _EXCLUDE_BIN_PATTERNS)
]
after = len(a.binaries)
print(f"[spec] filtered binaries: {before} -> {after} (removed {before - after})")

# .qm 翻译资源由 hook 收进 a.datas（不在 binaries），同样需要过滤：
# 非 zh_CN 的翻译对中文应用是死重（96 个 .qm 共 1.94MB，仅留 zh_CN）
before_d = len(a.datas)
a.datas = [
    d for d in a.datas
    if not any(re.search(p, d[0], re.I) for p in _EXCLUDE_BIN_PATTERNS)
]
after_d = len(a.datas)
print(f"[spec] filtered datas: {before_d} -> {after_d} (removed {before_d - after_d})")

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='xmu-assistant',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,  # 先不启用 UPX，验证过滤效果；后续装 UPX 后改 True
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,  # --windowed
    icon='assets/xmu-assistant.ico',
)
