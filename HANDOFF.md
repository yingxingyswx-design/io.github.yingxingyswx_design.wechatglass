# 交接文档（HANDOFF）

> 更新于 2026-11-25，**v1.2.1 已发布**。新对话先读这份，再动代码。

## 0. 一句话现状

微信 8.0.79 的 LSPosed 模块，**v1.2.1 已发布**（Store 标准 tag `4-1.2.1`）。已完成：首页液态玻璃顶栏 + 三段切换器、底部液态玻璃导航、聊天页玻璃（面板/输入栏）、媒体/表情消息气泡（面积 +25% / 居中 / 保留尾巴）、每条消息时间胶囊、自建列表未读实时刷新。

**下一步：把「通讯录 / 发现 / 我」三个页面按 M3 Expressive 重做**（首页自建列表已完成，这三页仍是微信原生 UI）。

## 0.1 当前版本与产物

- `versionCode = 4` / `versionName = 1.2.1`；产物 `out/wechat-glass-1.2.1.apk`
- Store release tag：`4-1.2.1`（Xposed-Modules-Repo 的 bot 会把 `v.x.y` 自动规范成 `<versionCode>-x.y`）
- 备份仓 `C:\git\wechat_chat_ui` HEAD `ece57ae`，工作树干净；三个仓库都有 v1.2.1

## 0.2 两条硬规则（用户明确要求）

1. **每次编译成功后必须 `.\publish.ps1 -Message "..."` 提交到 `C:\git\wechat_chat_ui`**（生成快照 + commit）。
2. **任何新增封号风险的 hook 必须先经用户批准**：改方法语义（`setResult`/`invokeOriginalMethod`）、全局/系统类 hook、`hookAllMethods`、触碰消息/账号/DB/网络/支付。只读 UI 属性、本地绘制、在已有 hook 里加分支不需要批准。

## 0.3 环境与设备注意

- 设备 user 0（Henry）/ user 10（壶中界）；测试默认 **user 0**，装包要两个 user 都装。
- adb 会间歇性掉线：**每次 adb 操作前先 `adb devices`，没设备就停下报告**，不要空等/重试。
- user 10 的模块状态偶发显示"等待微信回应"（不稳定，已确认注入正常），优先级低。
- 命令模板：`.gradlew.bat :app:assembleRelease` → `adb push out\wechat-glass-1.2.1.apk /data/local/tmp/wg.apk` → `adb shell pm install -r --user 0 /data/local/tmp/wg.apk`（user 10 同理）。


## 0.4 下一步：通讯录 / 发现 / 我 的 M3 化

- 设计依据：`docs/M3E设计规范.md`（四页布局蓝图、组件、分轮 R3/R4/R5）与 `docs/GoogleChat-UI-Spec.md`（尺寸/颜色/圆角基准）。
- 已完成、可参考的实现：
  - `HomeGlassHook`：decor 级 overlay、原生 ActionBar 隐藏、列表 padding、`ViewPager.getCurrentItem()` 判页；
  - `HomeCategoryPage`：自建 ListView、`applyAvatarShape()` 跟随「圆形头像」开关、未读角标实时刷新。
- 微信侧结构：`LauncherUI` 下 `MainTabUI` / `MoreTabUI`；四个 tab 由 `LauncherUIBottomTabView` 切换，通讯录/发现/我 各占 `WxViewPager` 的一页。
- 建议路线（低风险优先）：
  1. 先做「悬浮玻璃顶栏 + 大标题」：复用 `HomeGlassHook` 的 overlay/chip，按当前 ViewPager 页区分；
  2. 再做列表皮肤：优先给**原生列表**加 padding/圆角/分隔线，而不是自建列表（首页自建列表已被证明成本很高、易抖动）；
  3. 头像/图标统一走 `applyAvatarShape()` 与 m3 token。
- 红线：不碰消息/通讯录数据、不写 DB、不改网络与支付；微信类名一律字符串反射。
- 验收：`docs/M3E设计规范.md` §9 清单（日/夜、Monet 开/关、点击偏移 0、切 tab 10 次无重影）。

## 1. 仓库与产物

- 源码仓库（备份，带 CHANGES/快照）：`C:\codes\模块\wechat_chat_ui` → 远程 `https://github.com/yingxingyswx-design/WeChat-NewUI-paw`（分支 `release-v0.2`）
  - 每轮改动必须 `.\publish.ps1 -Message "..."`（生成备份快照 + commit），重要节点加 `important:` 前缀。
- 公开模块仓库（Store 用）：`https://github.com/Xposed-Modules-Repo/io.github.yingxingyswx_design.wechatglass`
  - 另有一份自建镜像：`https://github.com/yingxingyswx-design/io.github.yingxingyswx_design.wechatglass`
- 包名：`io.github.yingxingyswx_design.wechatglass`（versionCode 1 / versionName 1.0）
  - 旧包 `com.wechatglass` 已废弃并卸载（Store 拒过：com.* 域名式包名无 TXT 验证）。
- 产物：`out/wechat-glass-1.1.apk`（v1.1，约 1.10 MB）

## 2. 构建 / 安装 / 测试

```powershell
cd C:\codes\模块\wechat_chat_ui
.\gradlew.bat :app:assembleRelease
# 构建成功且 out\wechat-glass-1.1.apk 时间戳更新后再安装
adb push out\wechat-glass-1.1.apk /data/local/tmp/wg.apk
adb shell pm install -r --user 0 /data/local/tmp/wg.apk
adb shell pm install -r --user 10 /data/local/tmp/wg.apk
adb shell am force-stop --user 0 com.tencent.mm
adb shell am start --user 0 -n com.tencent.mm/.ui.LauncherUI
```

- 2026-11-23 起构建已迁移到 Gradle：AGP 9.4.1 / Gradle 9.8.2 / JDK 17 / compileSdk 36，`local.properties` 指向本机 SDK；release 用 `debug.keystore` 签名，和历史安装包一致。
- 旧的 `build.ps1`（aapt2 + javac + d8 + zipalign + apksigner）保留作离线回退，见 `README.md`。
- 设备：Xiaomi 2211133C（fuxi），用户 `0:Henry`、`10:壶中界`；adb serial 常为 `c412785e`，偶尔掉线。
- 模块日志 tag：`WeChatGlass`（用 `android.util.Log`，不是 XposedBridge.log）。
- 崩溃/现象排查：`adb logcat -c` → 复现 → `adb logcat -d -v threadtime > debug/full_*.txt`。

## 3. 模块结构（`app/src/main/java/com/wechatglass/`）

| 文件 | 职责 |
|---|---|
| `HookEntry.java` | 总入口：hook 微信 `LauncherUI`/`MainTabUI`/适配器 setter/底部 tab；安装各 Hook |
| `HomeGlassHook.java` | 首页核心：overlay、顶栏、切换器、自建页、原生行扫描、配置应用、tick |
| `HomeCategoryBar.java` | 三段切换器：液态玻璃胶囊、弹簧动画（`Spring`+`Choreographer`）、长按拖拽 |
| `HomeCategoryPage.java` | 自建会话列表（ListView）：置顶/未置顶分组、头像、时间、入口行、顶部空面积 |
| `ConversationIndex.java` | 只读 SQL 索引：好友/群/系统、会话、置顶位、预览格式化 |
| `FilteredConversationAdapter.java` | 包装微信原生 `iq5.a1`，按分类过滤 |
| `HomeConversationFilter.java` | 分类过滤（FRIEND/GROUP/SYSTEM），attach 到原生列表 |
| `ChatGlassHook.java` | 聊天页：玻璃顶栏、表情/+ 面板、壁纸模糊 |
| `HookConfig.java` | 微信进程内的配置：广播收 CONFIG、回 STATE、存 WeChat prefs |
| `SettingsActivity.java` | 模块 GUI：液态玻璃开关、M3 开关、壁纸、强制停止/打开微信 |
| `ConfigProvider.java` | 未使用的 ContentProvider（authority 已改为新包名） |
| `AvatarLoader.java` | 反射 `com.tencent.mm.pluginsdk.ui.u.b(ImageView, username, ...)` |
| `liquid/*` | 玻璃：`Backdrop`、`LiquidGlassPanel`、`PanelGlassDrawable`、`Spring`、`StackBlur`、`ViewGeom`、`LiquidPanelFactory` |

## 4. 微信侧关键混淆名 / 接口（8.0.79）

- 会话列表：`com.tencent.mm.ui.conversation.ConversationListView`（id `mjd`），适配器 `iq5.a1`，列表 header 数量为 1。
- TaskBar（下拉小程序面板）：`com.tencent.mm.plugin.taskbar.ui.TaskBarContainer` / `TaskBarView`；分区 `TaskBarSectionWeAppRecyclerView`（`xq4.n0`）、`TaskBarSectionCommonUsedWeAppRecyclerView`（`xq4.m`）。**`xq4.q` 是“搜索小程序”行，不是设备行**（踩过）。
- 底部 tab：`LauncherUIBottomTabView.setTo(int)`；当前页用 `ViewPager.getCurrentItem()`（WxViewPager）判断更可靠。
- 顶栏：`androidx.appcompat.widget.ActionBarContainer`（隐藏它，放我们的 overlay）。
- 存储：`l51.i9.b()` → `.r()` 联系人存储、`.s()` 会话存储、`.d()` DB；SQL 走 `callMethod(db,"A",sql,null)`。
- 好友 SQL：`type & 1 != 0 and type & 32 = 0 and type & 8 = 0 and verifyFlag & 8 = 0 and deleteFlag = 0`，排除 `%@chatroom`/`%@im.chatroom`。
- 会话表 `rconversation`：`username, digest, content, msgType, flag, conversationTime, unReadCount`。
- **置顶位是 `flag & 0x4000000000000000L`**（16 位十六进制；之前写成 14 位 0x40000000000000 导致永远不置顶）。
- 头像：`com.tencent.mm.pluginsdk.ui.u.b`。
- 已登入设备行：`xq4.q` 实为搜索行；设备行/笔记行仍在原生 header 里，未完全定位。

## 5. 玻璃实现要点

- `LiquidGlassPanel`：`RenderNode` 采样 backdrop → 饱和度 → 模糊 → 面层 + 高光 + 1dp 微信灰描边（`#D6D6D6` 亮 / `#3C3C3C` 暗）。
- **AGSL 透镜（LENS_MIN_SDK）已禁用**（设为 99）：它的采样坐标错误会把屏幕平铺成多列重影。要恢复折射必须先修 shader 的 `size/offset`。
- **backdrop 绝不能用 `decor`**：玻璃在 decor 里，采样 decor 会递归把自己画进去（多列重影）。
- 首页玻璃 backdrop：原生列表时用 `ConversationListView.getParent()`（MainUIView）；自建列表时用 `HomeCategoryPage`。
- `findVisibleList()` **不要要求 `isShown()`**：自建列表显示时原生列表是 INVISIBLE，但仍要能被采样。

## 6. 配置通道（GUI ↔ 微信）

- 微信侧：`HookConfig` 注册 `com.wechatglass.CONFIG`（写）与 `com.wechatglass.QUERY`（读），收到 QUERY 回 `com.wechatglass.STATE` 给模块 App。
- GUI：打开时发 QUERY，收到 STATE 后刷新开关（**不要在 onOpen 时 push**，会拿本地旧值覆盖微信侧）。
- 用户点开关才 push CONFIG。
- 开关语义：**关 = 微信原生列表（默认），开 = 自建列表（实验）**。
- 已知坑：`M3Switch.setChecked()` 里不能调 `performClick()`（会递归爆栈，已修）。

## 7. 当前策略：原生列表主线

- `tryInstall()`：`setupList()` → `resolveHomeList()`（先解析 `homeListView`）→ 非原生模式才 `installCategoryPage()` → `installCategoryBar()`。
- **顺序很重要**：页面需要 `homeListView` 才能挂载；以前先装页面导致 backdrop=null、切换器没玻璃。
- 原生列表**不要设 INVISIBLE**：会触发 ViewPager 页面回收，切 tab 回来出现抽搐/重影；自建页是盖在它上面的不透明视图。
- `attach()` 必须复用已有 hook，不能销毁重建（否则切换器弹簧从 0 重播 = “一直切换动画”）。

## 8. 已知问题 / 雷区

1. AGSL 折射未恢复（透镜坐标 bug）。
2. 自建列表与微信 ViewPager 仍有冲突风险（切 tab 抽搐/重影出现过）；所以默认原生。
3. 自建列表顶部三个入口行文本不准：`xq4.q` 是“搜索小程序”，不是“已登入设备”；置顶笔记行未定位。
4. 预览格式化：贴纸/图片/语音/视频/位置/名片/App 标题已做；`%s` 占位符（群聊 digest）还没替换成发送者名字。
5. 只读 DB，未写 `rconversation`（封号风险考虑）。
6. 无版本门禁：只按 8.0.79 混淆名适配。
7. 壁纸：聊天页玻璃采样的是模块 App 里选的壁纸，必须在 App 内「选择壁纸」。

## 9. 下一步：自建列表用真正的 M3 Expressive

目标：把 `HomeCategoryPage` 从“类似 M3 的自绘列表”升级为**按 M3 Expressive 规范**的界面。

建议方案：
- **设计 token**：M3 颜色角色（surface / surfaceContainer / onSurfaceVariant / primary）、`16dp` 页边距、`56–64dp` 行高、`16–24dp` 圆角、state layer（hover/press 8%/12%）。
- **列表行**：左侧 40dp 圆角方形头像，标题 `bodyLarge`（16sp/500），预览 `bodyMedium`（14sp/onSurfaceVariant），右侧时间 `labelSmall`；不画分隔线，用分组间距 + 小节标题（`labelLarge`，颜色 onSurfaceVariant）。
- **分组**：置顶 / 未置顶，小节标题 `titleSmall` + `8dp` 上间距；不要灰色大色块（用户明确不喜欢“传统 Header”感）。
- **切换器**：M3 分段按钮（segmented button）规格：40dp 高、全圆角、选中 tonal 填充（微信绿）、图标+文字、弹簧 morph。
- **动效**：继续用 `liquid.Spring`；行按压用 state layer + `scale 0.98`；切换用 container transform / fade-through（M3 motion）。
- **玻璃**：玻璃只做“背景层”，内容层必须清晰；不要在文字上叠模糊。
- **验收**：与原生列表对比截图；切 tab 来回 10 次无重影/抽搐；置顶分组正确；点击偏移为 0（注意 ListView header 的 `getHeaderViewsCount()`）。

实现顺序建议：
1. 先做视觉 token 与行布局（不动数据）。
2. 再做分组/空态/入口行（入口行先只放“小程序”，设备/笔记行待定位）。
3. 最后做动效与玻璃背景（避免又陷入 ViewPager 问题）。
4. 每步 `publish.ps1` 提交 + 真机截图。

## 10. 发布流程（Store）

- 官方仓库：`Xposed-Modules-Repo/io.github.yingxingyswx_design.wechatglass`（MAINTAIN 权限，邀请已接受）。
- 要求：仓库描述非空（已设）、至少一个 release、tag = `VersionCode-VersionName`（当前 `1-1.0`），release 带 APK。
- 发版：改 manifest 的 versionCode/versionName → 构建 → 在官方仓库发新 tag（如 `2-1.1`）→ 上传 APK。
- 注意：**只覆盖 release 资产不会触发 bot**；要重新创建 release/tag。
- 提交入口：https://modules.lsposed.org/submission （#2709 已 approved）。

## 11. 调试速查

- LSPosed 配置库（root）：`/data/adb/lspd/config/modules_config.db`（含 `modules` / `modules_state` / `scope`）。
- 抓配置：`adb shell su -c "cp /data/adb/lspd/config/modules_config.db /sdcard/mc.db"` → `adb pull`（用 `adb pull`，别用 PowerShell `>` 重定向往二进制）。
- 屏幕：`adb shell screencap -p /sdcard/x.png` + `adb pull`。
- 点击坐标：底部 tab 约 `(250,2270)=微信`、`(440,2270)=通讯录`、`(620,2270)=发现`、`(800,2270)=我`。
- 切 tab 复现：`input tap 440 2270` → `input tap 250 2270`。

## 12. 2026-11-23：M3E 全量方向

用户已确认：**全量使用 Material 3 Expressive**（聊天、通讯录、发现、我统一设计语言），
动态色彩基于系统 Monet，默认微信绿。完整规范见 [docs/M3E设计规范.md](docs/M3E设计规范.md)。

已落地设计系统底座（`com.wechatglass.m3`：`M3ColorScheme` / `M3Theme` / `M3Type` /
`M3Motion` / `M3Shape` / `M3Drawable`），下一步按 R2–R6 分轮替换页面：
微信页 → 通讯录 → 发现 → 我 → 聊天悬浮层/底部导航。

边界：聊天消息列表/气泡仍由微信原生 RecyclerView/item 渲染，模块只重绘 item 的
背景/圆角/字体/间距，不替换消息引擎和点击逻辑；每轮 Gradle 构建、真机验证、
`publish.ps1` 提交。

## 13. 2026-11-23：LiquidGlass 底栏移植

`com.wechatglass.liquidtab` 已从 [WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)
（MIT）移植底栏：`LiquidGlassInstaller` / `TabBarBridge` / `LiquidGlassHostLayout` /
`DropletPanel` / `DropletDragController` 等。libxposed API 102 的 hook 桥已换成我们的
legacy Xposed API 82（`LiquidGlassModule` shim），作用域只传 `HostApp.WECHAT`。

踩坑：微信自己的 `BlurView` 会把 DecorView 画进软件 Canvas，`LiquidGlassPanel` 的
AGSL 高光会抛 `Software rendering doesn't support RuntimeShader`；已在
`drawInteractiveHighlight` 加 `canvas.isHardwareAccelerated()` 判断，软件路径跳过高光。

冲突检测只移除原模块的 `com.tencent.mm` scope，保留 QQ。App 顶部运行状态显示
Root / LSPosed 注入 / 原模块冲突。

## 14. 近期关键提交

- `7544fbc` important: 新 App 图标（自适应 + 传统）
- `ffe200b` fix: 切换器玻璃恢复（先解析 home 列表再装页面）
- `d9cd442` 公开仓库同步
- 新包名重命名后：源码/仓库/Store 提交见 `io.github.yingxingyswx_design.wechatglass`
