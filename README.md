# 微信NewUI（io.github.yingxingyswx_design.wechatglass）

给微信 8.0.79 换上 **Material 3 Expressive 界面**与**液态玻璃**：
通讯录 / 发现 / 我 三页完全自建，首页与聊天页是 GPU 实时模糊的玻璃。

> 仅用于学习交流。修改微信界面存在**封号风险**，请自行评估、自行承担。
> 模块只做界面：不读写聊天记录、不碰数据库、不发网络请求、不涉及支付。

## 这一版（1.3.4）有什么

- **M3E 三页**：通讯录（单列 + A–Z 索引）、发现（2 列卡片网格）、我（资料卡 + 入口卡片），
  全部按 Material 3 Expressive 的 token 系统绘制，跟随系统深浅色与 Monet 取色。
- **液态玻璃**：首页顶栏、底部导航、聊天页顶栏与输入栏都是 GPU 实时模糊，
  聊天页玻璃**自动跟随微信当前的聊天壁纸**（含纯色背景），不需要任何手动设置。
- **微信余额卡片**（默认关闭，开启前有红色二次确认）：只被动采样你自己打开钱包页时
  已经渲染出来的金额，模块不发请求、不写微信数据库。
- **构建**：Gradle 与 `build.ps1` 两条路径在编译成功后都会自动备份到 git 仓库；
  版本号在 `ModuleVersion.java` 与 `build.gradle.kts` 双处校验，防止版本漂移。

## 美化了什么

- **首页顶栏**：微信原生标题栏换成悬浮液态玻璃胶囊（扫一扫 / 搜索 / 加号），
  暗夜模式自动切换深色玻璃。
- **三段切换器**：顶栏左侧「好友 / 群聊 / 微信」切换，选中态绿色胶囊，带弹簧动画与长按拖拽。
- **首页会话列表**：可选 M3E 自建列表，含「置顶 / 未置顶」分组、头像、时间排序、
  消息类型预览与实时未读角标；长按转发微信原生会话菜单（不写数据库）。
- **通讯录页**：自建单列 + A–Z 分区 + 右侧字母索引，点按进入微信原生资料页。
- **发现页**：2 列卡片网格，条目与图标全部取自微信对象；微信「发现页管理」里
  开启/关闭的入口会实时反映。
- **我页**：资料卡（头像形状跟随「圆形头像」开关）+ 2 列入口卡片 + 余额卡片。
- **聊天页**：顶栏胶囊、表情面板与「+」面板同样玻璃化；消息气泡按 M3 重绘，
  时间标签显示在气泡下方。
- **底部导航**：整合 [WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)（MIT）
  的液态玻璃底栏、水滴选中态与拖拽/弹簧动效；只作用于微信，QQ 不受影响。

## 设计规范

完整的 Material 3 Expressive 规范、颜色/排版/形状/动效 token、四个主页面的布局蓝图与
分轮验收标准见 [docs/M3E设计规范.md](docs/M3E设计规范.md)。

## 环境

- Android 12+（玻璃依赖 `RenderEffect`，更低版本自动退回普通背景）
- LSPosed（Zygisk / Riru 均可）
- 微信 `com.tencent.mm` 8.0.79（模块按该版本混淆名适配，其他版本可能不生效）
- 同时安装本模块 App 用于配置（开关、强制停止/打开微信）

## 使用

1. 安装 APK，在 LSPosed 中启用模块并勾选微信；
2. 强制停止微信后重新打开；
3. 打开模块 App 按需开启「液态玻璃」与「Material Design」；
4. 首页顶栏出现三段切换器、底部出现玻璃导航即生效。

## 配置项

- **运行状态**：App 顶部显示 Root、LSPosed 是否已注入微信、原模块是否仍勾选微信。
- **开启液态玻璃**：总开关。
- **聊天页液态玻璃背景壁纸**：已自动跟随微信聊天背景，手动选择按钮保留但**已停用**。
- **开启 Material Design（实验）**：接管通讯录 / 发现 / 我 三页与首页会话列表；
  关闭则保持微信原生界面（更稳定）。
- **开启圆形头像 / 媒体和表情包使用消息气泡 / 每条消息显示时间**：均依赖 Material Design。
- **开启余额卡片显示**：依赖 Material Design；被动采样，开启前有红色二次确认。
- **强制停止微信 / 打开微信**：改完配置后快速重启微信。
- **冲突检测**：检测已安装的 `WeChat-LiquidGlass`；有 root 时只移除它的微信作用域，
  保留 QQ 等其它作用域；没有 root 时只提示，不全局停用。

## 构建

推荐 Gradle（Android Studio 可直接打开本工程）：

```powershell
.\gradlew.bat :app:assembleRelease
```

- AGP 9.4.1 / Gradle 9.8.2，JDK 17，compileSdk 36 / minSdk 24 / targetSdk 34；
- `local.properties` 指向本机 Android SDK（已在 `.gitignore` 中）；
- 编译成功后自动执行 `publish.ps1`，把源码提交并快照到备份仓库；
- 产物：`out/wechat-glass-<version>.apk` 与 `out/wechat-glass-<version>-source.zip`。

旧的 `build.ps1`（aapt2 + javac + d8 + zipalign + apksigner）保留作离线回退，
同样会在成功后自动备份：

```powershell
.\build.ps1 -DebugSigning
```

## 特别鸣谢

- [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)（MIT）：
  底部导航栏液态玻璃、水滴拖拽与弹簧动效。本模块的 `liquidtab` 包由该项目移植/改编，
  并按 MIT 许可保留版权与许可声明，完整许可见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 已知问题

- 从「服务」等原生页面返回后，首页可能掉回微信原生列表。
- 群聊仍有少量成员昵称被套上消息气泡。
- 底栏的 AGSL 高光在微信 `BlurView` 的软件采样路径下会自动跳过（否则 `RuntimeShader` 会抛
  `Software rendering doesn't support RuntimeShader`），因此部分截图里材质会偏平；这是有意的安全降级。
- 模块按微信 8.0.79 的类名适配，微信升级后可能失效。
