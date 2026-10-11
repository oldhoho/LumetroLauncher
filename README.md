# LumetroLauncher

基于无障碍服务的 Android 第三方桌面（Launcher），采用偏 Windows 11 / 锤子风格的交互：底部悬浮工作台 + 九键 T9 搜索面板。

由 [queuejw](https://github.com/queuejw) 移植而来，[oldhoho](https://github.com/oldhoho) 维护增强。

## ✨ 主要功能

### 🚀 底部工作台 (Workbench)
- 贴屏幕底部的悬浮条：1 个「最近任务」键 + 6 个应用槽，左右滑动翻页
- 点击图标启动/切换应用，当前前台应用高亮
- 长按图标 + 上滑 → 冻结/解冻应用，冻结应用带 ❄ 角标

### 🔍 全部应用列表（九键面板）
- 4 行九键键盘，T9 模糊搜索：拼音首字母 / 全拼 / 英文 / 中文
- 拼音首字母分组（A-Z）+ 字母索引跳转
- 点击启动应用；长按弹出菜单（隐藏应用、应用信息、冻结等）
- 数字键绑定：短按走 T9 搜索，长按触发绑定（应用 / 快捷方式 / 自定义 Intent）
- 冻结 / 未冻结列表、隐藏与黑名单管理
- 「控制」键长按触发省电暗幕

### ❄️ 应用冻结
- 依赖 Shizuku（需单独安装）
- 冻结 / 解冻、冻结角标、列表批量管理

### 🌑 省电暗幕（OLED）
- 全屏半透明黑遮罩 + 挖洞
- 框选矩形（四角四边可拖拽），暗度可调

### 👆 手势
- 主页上滑 → 打开全部应用列表
- 主页下滑 → 展开通知面板
- 左侧手势条右滑 → 返回
- 音量键连按 3 次 → 打开面板

### 🎨 其他
- 使用系统壁纸作为桌面背景
- 图标包适配（ADW / GO Launcher / Nova）
- 面板背景自定义
- 崩溃日志 / 内存日志（便于排查）

## 📋 权限说明

| 权限 | 用途 | 是否必须 |
|------|------|----------|
| 无障碍服务 | 工作台、手势控制 | ✅ 核心依赖 |
| 悬浮窗 (SYSTEM_ALERT_WINDOW) | 工作台 / 浮层显示 | ✅ 必须 |
| 使用情况访问 (PACKAGE_USAGE_STATS) | 读取应用使用情况 | ✅ 必须 |
| 应用列表 (QUERY_ALL_PACKAGES) | 枚举已安装应用 | ✅ 必须 |
| 管理所有文件 (MANAGE_EXTERNAL_STORAGE) | 壁纸、图标包、日志 | ⚠️ Android 11+ 需要 |
| Shizuku | 应用冻结 | ⚠️ 冻结功能需要 |

## ⚠️ 注意事项
1. 首次启动需授予上述权限，否则部分功能不可用
2. 无障碍服务是核心依赖，必须开启
3. Shizuku 需单独安装，用于冻结功能
4. 部分 ROM（如 vivo OriginOS）会频繁查杀无障碍服务，需把本应用加入白名单 / 开启后台保活

## 🔧 开发环境
- Kotlin + Gradle（AGP 8.5 / Gradle 8.7 / JDK 17）
- minSdk 24（Android 7.0），targetSdk / compileSdk 34
- 仓库已带 GitHub Actions 工作流（`.github/workflows/build.yml`）：push 到 `main` 或手动触发即构建 APK

## 📦 安装
1. 从 [Releases](https://github.com/oldhoho/LumetroLauncher/releases) 下载 APK，或从 Actions 运行的 Artifacts 下载最新构建
2. 安装后授予所有权限，开启无障碍服务
3. 如需冻结功能，安装 Shizuku

## 📄 许可证
Apache License 2.0

---
**⚠️ 风险提示**：本应用需要较高权限（无障碍、悬浮窗、Shizuku），仅建议信任的用户使用。
