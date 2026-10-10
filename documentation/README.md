# 开发文档导航

[项目首页](../README.md)面向安装和日常使用；此目录面向源码维护。

| 需要做什么 | 入口 |
| --- | --- |
| 运行、测试和打包 Windows 客户端 | [Windows 开发说明](../xmu-rollcall-cli/README.md) |
| 构建、测试和安装 Android 客户端 | [Android 开发说明](../android/README.md) |
| 找到功能对应的源码和测试 | [模块定位](architecture.md) |
| 排查记录查询的兼容问题 | [接口兼容说明](number-code-compatibility.md) |
| 查看当前源码的验证范围 | [1.7.4 验证记录](verification-1.7.4.md) |
| 查看当前安装包的更新与下载 | [1.7.4 发布说明](releases/v1.7.4.md) |
| 了解提交范围与目录约定 | [贡献指南](../CONTRIBUTING.md) |
| 查阅发布时的验证记录 | [测试报告](../TEST_REPORT.md) |
| 查阅签到统计的历史决策 | [开发记录索引](history/README.md) |

## 文档和产物放在哪里

- `documentation/`：经过筛选、可随源码公开的维护说明。
- `documentation/history/`：已有的历史研究与交接记录，保留原始日期和验证边界。
- `scripts/`：可执行的构建、模拟和启动脚本。
- `tests/` 与 `android/app/src/test/`：各端测试源码及必要样例。
- `release/SHA256SUMS.txt`：发布校验值；EXE / APK 通过 [Releases](https://github.com/democard/xmu_assistant/releases) 分发。

已有 `.gitignore` 将 `docs/` 和 `android/docs/` 留作不提交的内部资料目录；本次继续沿用这一约定。公开文档使用 `documentation/`，避免把旧的本地内部资料一起加入版本管理。

根目录的 `TEST_REPORT.md` 继续作为历史发布链接的稳定入口。版本与安装说明以根目录和各端 README 为准，历史交接记录不是当前功能清单。
