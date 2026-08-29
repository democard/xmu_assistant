"""教程页混入（从 app.py 第六刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得教程页能力；方法体逐字保留搬移前的实现，
依赖的宿主回调（_ui_palette：教程 HTML 的深浅色注入）仍由 DashboardWindow 持有。
"""

from __future__ import annotations

from PySide6.QtWidgets import QTextBrowser, QVBoxLayout, QWidget


class TutorialPageMixin:
    """教程页构建与主题联动（无自有状态，全部经由宿主窗口）。"""

    REQUIRED_HOST_ATTRS = ("_ui_palette",)

    def _build_tutorial_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        guide = QTextBrowser()
        guide.setReadOnly(True)
        guide.setOpenLinks(False)
        guide.anchorClicked.connect(lambda url: guide.scrollToAnchor(url.fragment()))
        self.tutorial_guide = guide
        guide.setHtml(self._tutorial_html())
        layout.addWidget(guide, 1)
        return page

    def _refresh_tutorial_html(self):
        """主题切换后重建教程 HTML（颜色由当前调色板注入，深浅两态一致）。"""
        if getattr(self, "tutorial_guide", None) is not None:
            self.tutorial_guide.setHtml(self._tutorial_html())

    def _tutorial_html(self) -> str:
        pal = self._ui_palette()
        html = """
        <div style="font-family:'Microsoft YaHei','Segoe UI',sans-serif; color:@TUT_TEXT@; line-height:1.72;">
          <h2 style="margin-top:0;">xmu助手教程</h2>
          <p>按下面的清单设置即可。账号、Cookie、通知 Token 和下载目录只保存在本机。</p>
          <table cellspacing="8" cellpadding="10" style="margin:4px 0 16px 0;">
            <tr>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#enable" style="color:@TUT_TEXT@;text-decoration:none;">签到启用教程</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#monitor" style="color:@TUT_TEXT@;text-decoration:none;">开启监控</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#auto" style="color:@TUT_TEXT@;text-decoration:none;">开启自动签到</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#pushplus" style="color:@TUT_TEXT@;text-decoration:none;">设置微信通知</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#qqmail" style="color:@TUT_TEXT@;text-decoration:none;">设置 QQ 邮箱通知</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#export" style="color:@TUT_TEXT@;text-decoration:none;">导出与统计</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#courseware" style="color:@TUT_TEXT@;text-decoration:none;">下载课件</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#faq" style="color:@TUT_TEXT@;text-decoration:none;">常见问题</a></td>
            </tr>
          </table>

          <h3><a name="enable"></a>签到启用教程</h3>
          <ol>
            <li>在“首页”输入学号和密码，点击“登录”。</li>
            <li>看到账号状态变成“已登录”后，再点击“启动监控”。</li>
            <li>关闭窗口不会退出软件；它会留在托盘继续运行。</li>
            <li>要彻底退出，请右键托盘图标，点击“退出”。</li>
          </ol>

          <h3><a name="monitor"></a>开启监控</h3>
          <ol>
            <li>点击“启动监控”后，软件会按策略页的轮询间隔检查签到。</li>
            <li>检测到签到会写入“今日签到事件”，并按通知设置提醒你。</li>
            <li>如果连续网络异常，软件会少量提醒，不会每次轮询都打扰你。</li>
          </ol>

          <h3><a name="auto"></a>开启自动签到</h3>
          <ol>
            <li>勾选“开启自动签到”。</li>
            <li>数字签到和雷达签到会按当前策略自动处理。</li>
            <li>二维码签到只提醒，不会自动提交。</li>
          </ol>

          <h3><a name="pushplus"></a>设置微信通知</h3>
          <ol>
            <li>打开 PushPlus 官网，用微信登录并复制 token。</li>
            <li>在“通知”页勾选“开启微信通知”，把 token 填入 Token。</li>
            <li>点击“保存通知设置”，再点击“发送测试通知”。</li>
            <li>如果失败，通常是 token 没复制完整，重新复制后再试。</li>
          </ol>

          <h3><a name="qqmail"></a>设置 QQ 邮箱通知</h3>
          <ol>
            <li>在 QQ 邮箱<span style="color:@WARN@;font-weight:700;">网页版</span>开启 SMTP 服务，并生成授权码。（打开网页-&gt;点击设置-&gt;点击账号与安全-&gt;安全设置-&gt;下滑找到生成入口）</li>
            <li>“发件 QQ 邮箱”填写你的 QQ 邮箱。</li>
            <li>“SMTP 授权码”填写邮箱生成的授权码，不要填写 QQ 密码。</li>
            <li>“收件邮箱”填写接收提醒的邮箱，可以和发件邮箱相同。</li>
            <li>“端口”可填写多个，例如 465,587；软件会按顺序尝试，465 使用 SSL，587 使用 STARTTLS。</li>
            <li>保存后发送测试通知；能收到邮件才算配置成功。</li>
          </ol>

          <h3><a name="export"></a>导出与统计</h3>
          <ol>
            <li>在“签到情况”页按学年/学期/时间范围筛选后，点击“导出 CSV”保存当前列表。</li>
            <li>CSV 使用 utf-8-sig 编码，Excel 双击即可打开，不会中文乱码。</li>
            <li>勾选“只显示未签”时导出的也是未签记录（导出内容始终与页面所见一致）。</li>
            <li>点击“签到率统计”查看按课程聚合的已签/未签/未知与签到率。</li>
            <li>在“设置”页点击“导出运行日志”，可把最近 300 条日志保存为文本文件反馈问题。</li>
            <li>键盘党：Ctrl+1..6 可直接切换左侧页签；Ctrl+R 刷新当前页（仅签到情况/课件页支持刷新）。</li>
          </ol>

          <h3><a name="courseware"></a>下载课件</h3>
          <ol>
            <li>进入“课程课件”，选择学年、学期和课程，点击“刷新课件”。</li>
            <li>勾选要下载的课件；需要全下时点击“全选”。</li>
            <li>点击“下载”。文件会直接保存，视频/网页/H5 等可能保存为入口文件。</li>
            <li>下载失败时，该课件行会直接显示短原因，弹窗会列出完整原因。</li>
          </ol>

          <h3><a name="faq"></a>常见问题</h3>
          <ol>
            <li>提示登录过期：回到“首页”重新登录。</li>
            <li>通知收不到：先看“通知”页状态是否为“已配置”，再发送测试通知。</li>
            <li>课件读取失败：可能是平台权限、资源失效或登录过期，稍后重试或重新登录。</li>
            <li>不想后台运行：右键托盘图标，点击“退出”。</li>
          </ol>
        </div>
        """
        return (
            html
            .replace("@TUT_TEXT@", pal["tutorial_text"])
            .replace("@NAV_BG@", pal["tutorial_nav_bg"])
            .replace("@NAV_BORDER@", pal["tutorial_nav_border"])
            .replace("@WARN@", pal["warn_accent"])
        )
