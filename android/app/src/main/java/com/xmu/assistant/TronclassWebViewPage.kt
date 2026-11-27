package com.xmu.assistant

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val TRONCLASS_WEB_ORIGIN = "https://lnt.xmu.edu.cn"
internal const val TRONCLASS_HOME_URL = "https://lnt.xmu.edu.cn/user/index#/"
internal const val TRONCLASS_MOBILE_URL = "https://c-mobile.xmu.edu.cn/"

/** In-memory SSO cookie cache captured from TronclassLogin so WebView can auto-complete SSO. */
internal object TronclassWebSessionCache {
    private val lock = Any()
    private var owner: String = ""
    private var cookiesByOrigin: Map<String, List<String>> = emptyMap()

    fun update(username: String, cookies: Map<String, List<String>>) = synchronized(lock) {
        if (username.isBlank() || cookies.isEmpty()) return@synchronized
        owner = username
        cookiesByOrigin = cookies
    }

    fun get(username: String): Map<String, List<String>> = synchronized(lock) {
        if (username.isNotBlank() && owner == username) cookiesByOrigin else emptyMap()
    }

    fun clear() = synchronized(lock) {
        owner = ""
        cookiesByOrigin = emptyMap()
    }
}

/** Resolve the best in-app TronClass landing URL for an active rollcall event. */
internal fun tronclassRollcallTargetUrl(courseId: String): String {
    val cleanId = courseId.trim()
    return if (cleanId.isNotBlank()) {
        "$TRONCLASS_WEB_ORIGIN/course/$cleanId/content#/"
    } else {
        TRONCLASS_HOME_URL
    }
}

/** Strip SPA `#...` fragment for non-redirecting HTTP session verification. */
internal fun tronclassHttpProbeUrl(targetUrl: String): String =
    targetUrl.substringBefore('#').ifBlank { "$TRONCLASS_WEB_ORIGIN/user/index" }

/** Parse serialized Cookie header into validated `name=value` pairs for CookieManager injection. */
internal fun extractTronclassCookiePairs(cookieHeader: String): List<String> =
    cookieHeader.split(";")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eqIndex).trim()
            if (name.isBlank()) null else trimmed
        }

/** Clean WebView user agent so campus portals treat it like standard mobile Chrome. */
internal fun sanitizeWebViewUserAgent(defaultUserAgent: String): String =
    defaultUserAgent
        .replace("; wv)", ")")
        .replace("; wv;", ";")

/** Allow only XMU TronClass and identity SSO origins inside the authenticated WebView. */
internal fun isAllowedTronclassHost(host: String?): Boolean {
    val clean = host?.trim()?.lowercase().orEmpty()
    if (clean.isBlank()) return false
    return clean == "lnt.xmu.edu.cn" ||
        clean == "c-mobile.xmu.edu.cn" ||
        clean == "c-identity.xmu.edu.cn" ||
        clean == "ids.xmu.edu.cn" ||
        clean.endsWith(".xmu.edu.cn")
}

private fun probeTronclassHtmlRequiresLogin(
    targetUrl: String,
    cookieHeader: String,
    transport: QueryHttpTransport = OkHttpQueryTransport(),
): Boolean {
    if (cookieHeader.isBlank()) return true
    val probeUrl = tronclassHttpProbeUrl(targetUrl)
    return runCatching {
        val response = transport.execute(
            QueryHttpRequest(
                url = probeUrl,
                method = "GET",
                headers = linkedMapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "Cookie" to cookieHeader,
                ),
                operation = NetworkOperation.ROLLCALL_STATUS,
            ),
        )
        when {
            response.code == 401 || response.code == 403 -> true
            response.code in 300..399 && (
                isIdentityRedirect(probeUrl, response.location) ||
                    response.location.orEmpty().contains("/login", ignoreCase = true)
                ) -> true
            response.code in 200..299 && isKnownLoginForm(response.body) -> true
            else -> false
        }
    }.getOrDefault(false)
}

private suspend fun syncTronclassWebCookiesAsync(
    cookieHeader: String,
    ssoCookiesByOrigin: Map<String, List<String>>,
    webView: WebView,
) = suspendCancellableCoroutine<Unit> { continuation ->
    val result = runCatching {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(webView, true)

        val tasks = mutableListOf<Pair<String, String>>()
        ssoCookiesByOrigin.forEach { (origin, cookieList) ->
            cookieList.forEach { cookieLine -> tasks += origin to cookieLine }
        }
        if (ssoCookiesByOrigin[TRONCLASS_WEB_ORIGIN].isNullOrEmpty()) {
            extractTronclassCookiePairs(cookieHeader).forEach { pair ->
                tasks += TRONCLASS_WEB_ORIGIN to "$pair; Path=/; Secure"
            }
        }

        if (tasks.isEmpty()) {
            manager.removeAllCookies {
                manager.flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
            return@runCatching
        }

        val remaining = AtomicInteger(tasks.size)
        tasks.forEach { (origin, cookieValue) ->
            manager.setCookie(origin, cookieValue) {
                if (remaining.decrementAndGet() == 0) {
                    manager.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }
    if (result.isFailure && continuation.isActive) {
        continuation.resume(Unit)
    }
}

private fun clearTronclassWebCookies() {
    runCatching {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }
}

/**
 * 内置畅课签到 WebView 页：先校验并续新校园会话，再等待 Cookie 异步落盘后直接进入畅课。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TronclassRollcallPage(
    loggedIn: Boolean,
    cookieHeader: String,
    username: String = "",
    password: String = "",
    targetEvent: RollcallEvent?,
    onBack: () -> Unit,
    onOpenStatus: () -> Unit,
    onCookieRefreshed: (String) -> Unit = {},
) {
    if (!loggedIn || cookieHeader.isBlank()) {
        LaunchedEffect(loggedIn, cookieHeader) {
            TronclassWebSessionCache.clear()
            clearTronclassWebCookies()
        }
        EmptyState("请先登录", "登录后即可直接打开畅课签到页面。")
        return
    }

    val targetUrl = remember(targetEvent?.courseId) {
        tronclassRollcallTargetUrl(targetEvent?.courseId.orEmpty())
    }
    var preparing by remember { mutableStateOf(true) }
    var prepError by remember { mutableStateOf("") }
    var pageError by remember { mutableStateOf("") }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val currentWebView = webViewRef
        if (currentWebView != null && currentWebView.canGoBack()) {
            currentWebView.goBack()
        } else {
            onBack()
        }
    }

    LaunchedEffect(loggedIn, targetUrl, webViewRef, reloadNonce) {
        val wv = webViewRef ?: return@LaunchedEffect
        preparing = true
        prepError = ""
        pageError = ""
        var activeCookie = cookieHeader
        var ssoCookies = TronclassWebSessionCache.get(username)

        val refreshResult = withContext(Dispatchers.IO) {
            val needsRefresh = activeCookie.isBlank() ||
                ssoCookies.isEmpty() ||
                SessionHealthProbe().check(activeCookie) != SessionHealth.VALID ||
                probeTronclassHtmlRequiresLogin(targetUrl, activeCookie)
            if (needsRefresh && username.isNotBlank() && password.isNotBlank()) {
                var token = ProcessSessionRecovery.coordinator.tryStartAutoLogin()
                var attempts = 0
                while (token == null && attempts < 10) {
                    Thread.sleep(250L)
                    attempts++
                    token = ProcessSessionRecovery.coordinator.tryStartAutoLogin()
                }
                if (token != null) {
                    try {
                        val loginResult = TronclassLogin().login(username, password)
                        TronclassWebSessionCache.update(username, loginResult.ssoCookiesByOrigin)
                        Result.success(loginResult)
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        ProcessSessionRecovery.coordinator.finishAutoLogin(token)
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }

        if (refreshResult != null) {
            refreshResult.onSuccess { fresh ->
                activeCookie = fresh.cookieHeader
                ssoCookies = fresh.ssoCookiesByOrigin
                onCookieRefreshed(fresh.cookieHeader)
            }.onFailure { error ->
                if (probeTronclassHtmlRequiresLogin(targetUrl, activeCookie)) {
                    prepError = "同步校园登录态失败：${error.message ?: "请检查网络"}"
                }
            }
        }

        syncTronclassWebCookiesAsync(activeCookie, ssoCookies, wv)
        preparing = false
        if (prepError.isBlank()) {
            wv.loadUrl(targetUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    val headerTitle = targetEvent?.courseTitle?.takeIf { it.isNotBlank() } ?: "畅课课程主页"
    val headerSubtitle = when {
        targetEvent != null && targetEvent.teacher.isNotBlank() ->
            "发起人：${targetEvent.teacher} · 已同步校园会话"
        targetEvent != null -> "已同步校园会话 · 请在下方完成签到"
        else -> "已同步校园会话 · 点进课程即可签到"
    }
    val surfaceShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // 顶部精简信息栏：不重复外层"畅课签到"标题，突出当前课程/来源与轻量操作
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = surfaceShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            headerTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        targetEvent?.type?.takeIf { it.isNotBlank() }?.let { type ->
                            PreviewBadge(type, PreviewBadgeTone.BLUE)
                        }
                    }
                    Text(
                        headerSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canGoBack) {
                    OutlinedButton(
                        onClick = { webViewRef?.goBack() },
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("‹ 上页", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = { reloadNonce++ },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("刷新", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onOpenStatus,
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text("详情 ›", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 畅课 WebView 主体容器：最大化展示面积，并对齐圆角裁切与加载/错误兜底
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = surfaceShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(surfaceShape),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (preparing || loadingProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { if (preparing) 0.22f else loadingProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                settings.userAgentString = sanitizeWebViewUserAgent(settings.userAgentString)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val uri = request?.url ?: return true
                                        val scheme = uri.scheme?.lowercase().orEmpty()
                                        if (scheme != "http" && scheme != "https") return true
                                        return !isAllowedTronclassHost(uri.host)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        canGoBack = view?.canGoBack() == true
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            pageError = "页面加载失败（${error?.description ?: "请检查网络"}）"
                                        }
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadingProgress = newProgress
                                    }

                                    override fun onGeolocationPermissionsShowPrompt(
                                        origin: String?,
                                        callback: GeolocationPermissions.Callback?,
                                    ) {
                                        val host = origin?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                                        callback?.invoke(origin, isAllowedTronclassHost(host), false)
                                    }
                                }
                                webViewRef = this
                            }
                        },
                    )
                    val activeError = prepError.ifBlank { pageError }
                    if (preparing) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = themePrimary(),
                                )
                                Text(
                                    "正在同步校园会话并加载畅课…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (activeError.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    activeError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = themeWarning(),
                                    textAlign = TextAlign.Center,
                                )
                                Button(
                                    onClick = { reloadNonce++ },
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text("重新连接畅课")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
