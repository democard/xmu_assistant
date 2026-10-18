"""雷达签到坐标页混入：按课程维护坐标，自动/手动雷达签到只提交首选地点。"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QComboBox,
    QDoubleSpinBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..courseware import CourseSummary
from ..radar_coordinates import load_presets


class RadarCoordinatesPageMixin:
    """坐标页构建与增删改回调；课程列表复用课件页已加载的 self.courseware_courses。"""

    REQUIRED_HOST_ATTRS = ("_panel", "radar_coordinates", "refresh_courseware_courses")

    def _build_radar_coordinates_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)

        panel = self._panel("雷达签到坐标")
        grid = QGridLayout(panel)
        grid.setContentsMargins(16, 40, 16, 16)
        grid.setColumnStretch(1, 1)

        self.radar_course_combo = QComboBox()
        self.radar_course_combo.setMinimumWidth(320)
        self.radar_course_combo.currentIndexChanged.connect(
            lambda _index: self.refresh_radar_place_list()
        )
        refresh_button = QPushButton("刷新课程")
        refresh_button.setToolTip("复用课件页的课程接口，读取本学年课程列表。")
        refresh_button.clicked.connect(self._refresh_radar_courses)
        grid.addWidget(QLabel("课程"), 0, 0)
        grid.addWidget(self.radar_course_combo, 0, 1)
        grid.addWidget(refresh_button, 0, 2)

        self.radar_place_list = QListWidget()
        self.radar_place_list.setMinimumHeight(150)
        grid.addWidget(self.radar_place_list, 1, 0, 1, 3)

        buttons = QHBoxLayout()
        self.radar_preferred_button = QPushButton("设为首选")
        self.radar_preferred_button.clicked.connect(self._radar_set_preferred)
        self.radar_delete_button = QPushButton("删除地点")
        self.radar_delete_button.clicked.connect(self._radar_delete)
        buttons.addWidget(self.radar_preferred_button)
        buttons.addWidget(self.radar_delete_button)
        buttons.addStretch(1)
        grid.addLayout(buttons, 2, 0, 1, 3)

        grid.addWidget(QLabel("预置地点"), 3, 0)
        self.radar_zone_combo = QComboBox()
        self.radar_zone_combo.setMinimumWidth(200)
        self.radar_zone_combo.currentIndexChanged.connect(self._refresh_radar_preset_combo)
        self.radar_preset_combo = QComboBox()
        self.radar_preset_combo.setMinimumWidth(260)
        self.radar_add_preset_button = QPushButton("添加并设为首选")
        self.radar_add_preset_button.clicked.connect(self._radar_add_preset)
        grid.addWidget(self.radar_zone_combo, 3, 1)
        grid.addWidget(self.radar_preset_combo, 4, 1)
        grid.addWidget(self.radar_add_preset_button, 4, 2)

        grid.addWidget(QLabel("自定义坐标"), 5, 0)
        self.radar_custom_name = QLineEdit()
        self.radar_custom_name.setPlaceholderText("地点名称，例如 坤銮楼 B207")
        self.radar_custom_lat = QDoubleSpinBox()
        self.radar_custom_lat.setDecimals(6)
        self.radar_custom_lat.setRange(-90.0, 90.0)
        self.radar_custom_lat.setSingleStep(0.0001)
        self.radar_custom_lon = QDoubleSpinBox()
        self.radar_custom_lon.setDecimals(6)
        self.radar_custom_lon.setRange(-180.0, 180.0)
        self.radar_custom_lon.setSingleStep(0.0001)
        self.radar_add_custom_button = QPushButton("添加并设为首选")
        self.radar_add_custom_button.clicked.connect(self._radar_add_custom)
        grid.addWidget(self.radar_custom_name, 5, 1)
        grid.addWidget(QLabel("纬度"))
        grid.addWidget(self.radar_custom_lat, 6, 1)
        grid.addWidget(QLabel("经度"), 7, 0)
        grid.addWidget(self.radar_custom_lon, 7, 1)
        grid.addWidget(self.radar_add_custom_button, 7, 2)

        tip = QLabel(
            "自动雷达签到只提交该课程的首选地点；未配置时不会提交，避免先写入记录。"
            "现场采点在手机端完成，桌面可选用预置楼宇或手动填写经纬度。"
        )
        tip.setObjectName("Subtle")
        tip.setWordWrap(True)
        grid.addWidget(tip, 8, 0, 1, 3)

        self.radar_status_label = QLabel("")
        self.radar_status_label.setObjectName("StatusGood")
        grid.addWidget(self.radar_status_label, 9, 0, 1, 3)

        layout.addWidget(panel)
        layout.addStretch(1)

        self._radar_presets = load_presets()
        zones = sorted({preset.zone for preset in self._radar_presets})
        self.radar_zone_combo.addItem("全部片区", "")
        for zone in zones:
            self.radar_zone_combo.addItem(zone, zone)
        self._refresh_radar_preset_combo()
        return page

    # ---- 数据刷新 ----
    def refresh_radar_course_options(self) -> None:
        if not hasattr(self, "radar_course_combo"):
            return
        previous = self.radar_course_combo.currentData()
        previous_id = getattr(previous, "course_id", "")
        self.radar_course_combo.blockSignals(True)
        self.radar_course_combo.clear()
        for course in getattr(self, "courseware_courses", []):
            term = f"  [{course.term}]" if course.term else ""
            self.radar_course_combo.addItem(f"{course.title}{term}", course)
        self.radar_course_combo.blockSignals(False)
        if previous_id:
            for index in range(self.radar_course_combo.count()):
                item = self.radar_course_combo.itemData(index)
                if item is not None and item.course_id == previous_id:
                    self.radar_course_combo.setCurrentIndex(index)
                    break
        self.refresh_radar_place_list()

    def refresh_radar_place_list(self) -> None:
        if not hasattr(self, "radar_place_list"):
            return
        self.radar_place_list.clear()
        course = self._selected_radar_course()
        if course is None:
            self.radar_place_list.addItem(QListWidgetItem("先刷新课程，再选择课程"))
            return
        places = self.radar_coordinates.places_for(course.course_id, course.title)
        if not places:
            self.radar_place_list.addItem(QListWidgetItem("这门课还没有坐标"))
            return
        for place in places:
            marker = "★ " if place.preferred else "   "
            text = f"{marker}{place.name}   {place.latitude:.6f}, {place.longitude:.6f}"
            item = QListWidgetItem(text)
            item.setData(Qt.ItemDataRole.UserRole, place.id)
            self.radar_place_list.addItem(item)

    def _selected_radar_course(self) -> CourseSummary | None:
        data = self.radar_course_combo.currentData() if hasattr(self, "radar_course_combo") else None
        return data if isinstance(data, CourseSummary) else None

    def _selected_radar_place_id(self) -> str:
        item = self.radar_place_list.currentItem() if hasattr(self, "radar_place_list") else None
        return str(item.data(Qt.ItemDataRole.UserRole) or "") if item is not None else ""

    def _refresh_radar_preset_combo(self, *_args) -> None:
        if not hasattr(self, "radar_preset_combo"):
            return
        zone = self.radar_zone_combo.currentData() if hasattr(self, "radar_zone_combo") else ""
        self.radar_preset_combo.clear()
        for preset in self._radar_presets:
            if zone and preset.zone != zone:
                continue
            self.radar_preset_combo.addItem(f"{preset.name}  ({preset.zone})", preset.id)

    def _refresh_radar_courses(self) -> None:
        if not self._require_login(silent=False):
            return
        self.refresh_courseware_courses(silent=True)

    # ---- 写入 ----
    def _radar_add_preset(self) -> None:
        course = self._selected_radar_course()
        if course is None:
            self._radar_warn("还没有课程列表", "先点“刷新课程”，再选择要配置的课程。")
            return
        preset_id = self.radar_preset_combo.currentData()
        preset = next((item for item in self._radar_presets if item.id == preset_id), None)
        if preset is None:
            self._radar_warn("未选择地点", "先在上方选择一个预置地点。")
            return
        self.radar_coordinates.add(
            course.course_id, course.title, preset.name, preset.latitude, preset.longitude,
            source=f"preset:{preset.id}",
        )
        self._radar_saved(f"已把《{preset.name}》设为《{course.title}》的首选地点")

    def _radar_add_custom(self) -> None:
        course = self._selected_radar_course()
        if course is None:
            self._radar_warn("还没有课程列表", "先点“刷新课程”，再选择要配置的课程。")
            return
        name = self.radar_custom_name.text().strip()
        if not name:
            self._radar_warn("缺少地点名称", "请先填写地点名称，例如“坤銮楼 B207”。")
            return
        try:
            self.radar_coordinates.add(
                course.course_id, course.title, name,
                self.radar_custom_lat.value(), self.radar_custom_lon.value(),
                source="custom",
            )
        except ValueError as exc:
            self._radar_warn("坐标无效", str(exc))
            return
        self.radar_custom_name.clear()
        self._radar_saved(f"已添加《{name}》并设为《{course.title}》的首选地点")

    def _radar_set_preferred(self) -> None:
        place_id = self._selected_radar_place_id()
        if not place_id:
            self._radar_warn("未选择地点", "先在列表中选中一个已保存地点。")
            return
        if self.radar_coordinates.set_preferred(place_id):
            self._radar_saved("已设为首选地点")
        else:
            self._radar_warn("设置失败", "这个地点可能已被删除，请刷新。")

    def _radar_delete(self) -> None:
        place_id = self._selected_radar_place_id()
        if not place_id:
            self._radar_warn("未选择地点", "先在列表中选中要删除的地点。")
            return
        if self.radar_coordinates.delete(place_id):
            self._radar_saved("地点已删除")
        else:
            self._radar_warn("删除失败", "这个地点可能已被删除，请刷新。")

    def _radar_saved(self, message: str) -> None:
        self.refresh_radar_place_list()
        if hasattr(self, "radar_status_label"):
            self.radar_status_label.setText(message)
        self.log(message)

    def _radar_warn(self, title: str, message: str) -> None:
        from PySide6.QtWidgets import QMessageBox

        QMessageBox.information(self, title, message)
