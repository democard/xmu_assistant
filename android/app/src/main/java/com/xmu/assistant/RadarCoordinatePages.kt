package com.xmu.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val radarZones = listOf("主楼群", "西部片区", "医学与生命", "东部科研", "公共教学", "北部科研")

private data class CapturedRadarPlace(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

private fun semesterKey(raw: String): String {
    val text = raw.trim()
    val year = Regex("20\\d{2}").find(text)?.value ?: return ""
    val number = Regex("([123])\\D*$").find(text)?.groupValues?.get(1)
    val semester = number ?: when {
        "第一" in text || "fall" in text.lowercase() || "autumn" in text.lowercase() -> "1"
        "第二" in text || "spring" in text.lowercase() -> "2"
        "第三" in text || "summer" in text.lowercase() -> "3"
        else -> return ""
    }
    return year + semester
}

/** Coordinate management is intentionally scoped to the active timetable term. */
internal fun currentTermCoordinateCourses(
    courses: List<CourseSummary>,
    termCode: String,
    scheduleEntries: List<XmuScheduleEntry>,
): List<CourseSummary> {
    val currentKey = semesterKey(termCode)
    if (currentKey.isBlank()) return emptyList()
    val scheduledTitles = scheduleEntries.asSequence()
        .filter { it.termCode.isBlank() || semesterKey(it.termCode) == currentKey }
        .map { normalizeCourseTitle(it.courseName) }
        .filter(String::isNotBlank)
        .toSet()
    return courses.filter { course ->
        val courseKey = semesterKey(course.semesterCode).ifBlank { semesterKey(course.term) }
        if (courseKey.isNotBlank()) courseKey == currentKey
        else normalizeCourseTitle(course.title) in scheduledTitles
    }.distinctBy { it.id.ifBlank { normalizeCourseTitle(it.title) } }
}

private fun sameCourse(left: CourseSummary, right: CourseSummary): Boolean =
    (left.id.isNotBlank() && left.id == right.id) ||
        normalizeCourseTitle(left.title) == normalizeCourseTitle(right.title)

private fun courseChoices(courses: List<CourseSummary>, initial: CourseSummary?): List<CourseSummary> = buildList {
    initial?.let { target -> courses.firstOrNull { sameCourse(it, target) }?.let(::add) }
    addAll(courses)
}.distinctBy { it.id.ifBlank { normalizeCourseTitle(it.title) } }

private fun selectedCourse(courses: List<CourseSummary>, initial: CourseSummary?): CourseSummary? =
    initial?.let { target -> courses.firstOrNull { sameCourse(it, target) } } ?: courses.firstOrNull()

@Composable
internal fun CourseCoordinatePage(
    courses: List<CourseSummary>,
    initialCourse: CourseSummary?,
    store: RadarCoordinateStore,
    show: (String) -> Unit,
    onCourseSelected: (CourseSummary) -> Unit = {},
    onAddPlace: () -> Unit = {},
    onCapture: () -> Unit = {},
) {
    val availableCourses = remember(courses, initialCourse) { courseChoices(courses, initialCourse) }
    var selectedCourseId by rememberSaveable(initialCourse?.id) { mutableStateOf(initialCourse?.id.orEmpty()) }
    var selectedCourseTitle by rememberSaveable(initialCourse?.title) { mutableStateOf(initialCourse?.title.orEmpty()) }
    var revision by remember { mutableIntStateOf(0) }
    val selectedCourse = availableCourses.firstOrNull {
        (selectedCourseId.isNotBlank() && it.id == selectedCourseId) ||
            normalizeCourseTitle(it.title) == normalizeCourseTitle(selectedCourseTitle)
    }
    LaunchedEffect(availableCourses, selectedCourse) {
        if (selectedCourse == null) {
            availableCourses.firstOrNull()?.let {
                selectedCourseId = it.id
                selectedCourseTitle = it.title
                onCourseSelected(it)
            }
        }
    }
    val places = remember(selectedCourseId, selectedCourseTitle, revision) {
        store.placesFor(selectedCourseId, selectedCourseTitle)
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (availableCourses.size > 1) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    availableCourses.forEach { course ->
                        FilterChip(
                            selected = course == selectedCourse,
                            onClick = {
                                selectedCourseId = course.id
                                selectedCourseTitle = course.title
                                onCourseSelected(course)
                            },
                            label = { Text(course.title, maxLines = 1) },
                        )
                    }
                }
            }
        }
        item {
            PreviewCard(soft = true) {
                PreviewListRow(
                    icon = R.drawable.ic_nav_location,
                    title = "自动签到会使用首选地点",
                    subtitle = "这门课可保存任意多个地点，你决定保留多少个。",
                    trailing = {},
                )
            }
        }
        item { PreviewSectionHeader("已保存地点", "${places.size} 个") }
        if (selectedCourse == null) {
            item { EmptyState("本学期暂无课程", "刷新课表和课程后，就可以为本学期课程保存常用地点。") }
        } else if (places.isEmpty()) {
            item { EmptyState("还没有地点", "可从翔安校区地点中选择，也可以在教室现场采点。") }
        } else {
            item {
                PreviewCard {
                    places.forEachIndexed { index, place ->
                        SavedRadarPlaceRow(
                            place = place,
                            showDivider = index < places.lastIndex,
                            onPreferred = {
                                store.setPreferred(place.id)
                                revision++
                                show("已设为首选地点")
                            },
                            onDelete = {
                                store.delete(place.id)
                                revision++
                                show("地点已删除")
                            },
                        )
                    }
                }
            }
        }
        if (selectedCourse != null) {
            item { Button(onClick = onAddPlace, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("添加地点") } }
            item { OutlinedButton(onClick = onCapture, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("现场采点") } }
        }
        item {
            PreviewCard(soft = true) {
                Text("偶尔换教室时", fontWeight = FontWeight.Bold)
                Text(
                    "先在这里切换首选地点；自动签到不会临时弹窗打断你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SavedRadarPlaceRow(place: RadarCoordinate, showDivider: Boolean, onPreferred: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onPreferred).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            RadioButton(selected = place.preferred, onClick = onPreferred)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(place.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    String.format(Locale.US, "%.6f, %.6f · %s", place.latitude, place.longitude, RADAR_COORDINATE_SYSTEM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (place.preferred) PreviewBadge("首选", PreviewBadgeTone.GREEN) else TextButton(onClick = onDelete) { Text("删除") }
        }
        if (showDivider) Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
internal fun AddRadarPlacePage(
    courses: List<CourseSummary>,
    initialCourse: CourseSummary?,
    store: RadarCoordinateStore,
    show: (String) -> Unit,
    onSaved: () -> Unit,
    onCapture: () -> Unit,
) {
    val context = LocalContext.current
    val presets = remember(context) { loadXiangAnRadarPresets(context) }
    val availableCourses = remember(courses, initialCourse) { courseChoices(courses, initialCourse) }
    val selectedCourse = selectedCourse(availableCourses, initialCourse)
    var zone by rememberSaveable { mutableStateOf(radarZones.first()) }
    var selectedPresetId by rememberSaveable { mutableStateOf("") }
    var customMode by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    val preset = presets.firstOrNull { it.id == selectedPresetId }
    val lat = if (customMode) latitude.toDoubleOrNull() else preset?.latitude
    val lon = if (customMode) longitude.toDoubleOrNull() else preset?.longitude
    val placeName = if (customMode) name.trim() else preset?.name.orEmpty()
    val valid = selectedCourse != null && placeName.isNotBlank() && lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PreviewCard {
                PreviewSectionHeader("从预存地点选择", "翔安校区")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    radarZones.forEach { item ->
                        FilterChip(selected = zone == item, onClick = { zone = item }, label = { Text(item) })
                    }
                }
                val zonePresets = presets.filter { it.zone == zone }
                zonePresets.forEachIndexed { index, item ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                customMode = false
                                selectedPresetId = item.id
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Text(
                                    String.format(Locale.US, "%.6f, %.6f · %s", item.latitude, item.longitude, RADAR_COORDINATE_SYSTEM),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RadioButton(selected = !customMode && selectedPresetId == item.id, onClick = {
                                customMode = false
                                selectedPresetId = item.id
                            })
                        }
                        if (index < zonePresets.lastIndex) Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
            }
        }
        item {
            PreviewCard(onClick = { customMode = true; selectedPresetId = "" }) {
                PreviewListRow(
                    icon = R.drawable.ic_nav_settings,
                    title = "自定义坐标",
                    subtitle = "直接填写地点名称、纬度和经度",
                    trailing = { Text("›", style = MaterialTheme.typography.headlineSmall) },
                )
                if (customMode) {
                    OutlinedTextField(name, { name = it }, label = { Text("地点名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            PreviewCard(onClick = onCapture) {
                PreviewListRow(R.drawable.ic_nav_location, "到现场获取坐标", "现在就定位并保存，不需要等到签到时")
            }
        }
        item {
            Button(
                onClick = {
                    if (!valid) return@Button
                    store.add(
                        courseId = selectedCourse!!.id,
                        courseTitle = selectedCourse.title,
                        name = placeName,
                        latitude = lat!!,
                        longitude = lon!!,
                        source = if (customMode) "custom" else "preset:${preset!!.id}",
                        makePreferred = true,
                    )
                    show("地点已保存并设为首选")
                    onSaved()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("保存") }
        }
    }
}

@Composable
internal fun OnsiteRadarCapturePage(
    courses: List<CourseSummary>,
    initialCourse: CourseSummary?,
    store: RadarCoordinateStore,
    show: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val availableCourses = remember(courses, initialCourse) { courseChoices(courses, initialCourse) }
    val selectedCourse = selectedCourse(availableCourses, initialCourse)
    val scope = rememberCoroutineScope()
    var captured by remember { mutableStateOf<CapturedRadarPlace?>(null) }
    var locating by remember { mutableStateOf(false) }
    var placeName by rememberSaveable { mutableStateOf("") }
    var captureStatus by remember { mutableStateOf("等待定位") }
    var captureRequest by remember { mutableIntStateOf(0) }
    var activeSignals by remember { mutableStateOf<List<CancellationSignal>>(emptyList()) }

    fun convertedLocation(location: Location): CapturedRadarPlace {
        val converted = wgs84ToGcj02(location.latitude, location.longitude)
        return CapturedRadarPlace(converted.first, converted.second, location.accuracy)
    }
    fun capture() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            locating = false
            captureStatus = "手机定位未开启"
            show("请先打开手机定位")
            return
        }
        activeSignals.forEach(CancellationSignal::cancel)
        captureRequest += 1
        val request = captureRequest
        locating = true
        captureStatus = "正在同时使用网络与 GPS 定位"
        var pending = providers.size
        var best: CapturedRadarPlace? = null
        val signals = providers.map { CancellationSignal() }
        activeSignals = signals

        fun finish(message: String, notifyFailure: Boolean = false) {
            if (request != captureRequest) return
            activeSignals.forEach(CancellationSignal::cancel)
            activeSignals = emptyList()
            locating = false
            captureStatus = message
            if (notifyFailure) show(message)
        }

        fun locationRequestFailed() {
            pending -= 1
            if (pending == 0 && best == null) {
                finish("读取位置失败，请检查定位权限", notifyFailure = true)
            }
        }

        providers.zip(signals).forEach { (provider, signal) ->
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location: Location? ->
                    if (request != captureRequest) return@getCurrentLocation
                    pending -= 1
                    if (location != null) {
                        val candidate = convertedLocation(location)
                        if (best == null || candidate.accuracyMeters.orDefault() < best!!.accuracyMeters.orDefault()) {
                            best = candidate
                            captured = candidate
                        }
                        if (candidate.accuracyMeters.orDefault() <= 25f) {
                            finish("定位成功，精度良好")
                            return@getCurrentLocation
                        }
                    }
                    if (pending == 0) {
                        if (best != null) finish("定位成功")
                        else finish("暂时无法取得位置，请到室外或窗边重试", notifyFailure = true)
                    }
                }
            } catch (_: SecurityException) {
                locationRequestFailed()
            } catch (_: Throwable) {
                locationRequestFailed()
            }
        }
        scope.launch {
            delay(12_000)
            if (request == captureRequest && locating) {
                if (best != null) finish("定位完成，可保存当前结果")
                else finish("定位超时，请到窗边或室外重新采样", notifyFailure = true)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) capture() else {
            captureStatus = "定位权限未授予"
            show("需要定位权限才能现场采点")
        }
    }
    fun requestCapture() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) capture() else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { requestCapture() }
    DisposableEffect(Unit) {
        onDispose {
            captureRequest += 1
            activeSignals.forEach(CancellationSignal::cancel)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = RoundedCornerShape(14.dp),
                color = themePrimarySoft(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(68.dp).background(themePrimary().copy(alpha = .14f), CircleShape))
                    Box(Modifier.size(18.dp).background(themePrimary(), CircleShape))
                    captured?.accuracyMeters?.let {
                        PreviewBadge("精度 ±${String.format(Locale.US, "%.1f", it)} 米", if (it <= 20f) PreviewBadgeTone.GREEN else PreviewBadgeTone.AMBER)
                    }
                }
            }
        }
        item {
            PreviewCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                captured != null && locating -> "已获取位置，正在提高精度"
                                captured != null -> "当前位置已获取"
                                locating -> "正在获取当前位置"
                                else -> "尚未取得位置"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text("$RADAR_COORDINATE_SYSTEM · $captureStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PreviewBadge(
                        when {
                            captured != null -> "可保存"
                            locating -> "采样中"
                            else -> "待重试"
                        },
                        if (captured != null) PreviewBadgeTone.GREEN else if (locating) PreviewBadgeTone.BLUE else PreviewBadgeTone.AMBER,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PreviewMetric("纬度", captured?.let { String.format(Locale.US, "%.6f", it.latitude) } ?: "—", Modifier.weight(1f))
                    PreviewMetric("经度", captured?.let { String.format(Locale.US, "%.6f", it.longitude) } ?: "—", Modifier.weight(1f))
                }
            }
        }
        item {
            PreviewCard {
                OutlinedTextField(placeName, { placeName = it }, label = { Text("地点名称") }, placeholder = { Text("例如 坤銮楼 B207") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(selectedCourse?.title.orEmpty(), {}, label = { Text("保存到课程") }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::requestCapture, enabled = !locating, modifier = Modifier.weight(1f).height(45.dp)) { Text(if (locating) "采样中" else "重新采样") }
                    Button(
                        onClick = {
                            val point = captured ?: return@Button
                            val course = selectedCourse ?: return@Button
                            store.add(course.id, course.title, placeName.trim(), point.latitude, point.longitude, "onsite", point.accuracyMeters, true)
                            show("现场地点已保存并设为首选")
                            onSaved()
                        },
                        enabled = captured != null && selectedCourse != null && placeName.isNotBlank(),
                        modifier = Modifier.weight(1f).height(45.dp),
                    ) { Text("保存") }
                }
            }
        }
        item {
            PreviewCard(soft = true) {
                PreviewListRow(R.drawable.ic_nav_help, "建议站在常用教室内采点", "等精度稳定后再保存，减少楼间漂移。", trailing = {})
            }
        }
    }
}

private fun Float?.orDefault(): Float = this?.takeIf { it.isFinite() && it >= 0f } ?: Float.MAX_VALUE
