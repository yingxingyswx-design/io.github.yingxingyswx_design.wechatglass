# WeChat-NewUI v1.3.1.2

- versionCode：`8`
- versionName：`1.3.1.2.release`（开发版为 `1.3.2.dev`）
- Release tag：`8-1.3.1.2`
- APK：`wechat-glass-1.3.1.2.apk`
- 源码：`wechat-glass-1.3.1.2-source.zip`
- 作用域：仅 `com.tencent.mm`（微信）

## 1.3.1.2 内容

- **余额卡片进入正式功能**：恢复在 release 包中可用，由 GUI 开关「开启余额卡片显示」
  控制（默认关闭，开启前有红色二次确认）。采样仍是纯被动：只在用户自己打开
  「钱包」页时读取页面上已渲染的金额，模块不发请求、不写微信数据库。
- **GUI 顶部显示版本号**：`WeChat-NewUI  1.3.1.2.release  (8)`。
- **版本号熔断**：`versionName` 含 `release` 时用于识别发布构建
  （`BuildFlags.isReleaseBuild()`），后续新增的探索型 hook 必须先过这个闸门。
- 余额卡片每 2 秒跟随本地缓存刷新；金额未变时只更新“更新于”时间（20 秒节流）。

## 1.3.1 内容

- 移除探测期遗留的 `hookFreGriest`（`hookAllMethods` + `setResult`，从未生效）。
- 发现页条目随微信「发现页管理」设置变化重建，不再只显示 4 项。

## 1.3.0 内容

- M3E 化的「通讯录 / 发现 / 我」三页；首页扫一扫按钮；首页长按转发微信原生会话菜单；
  首页状态栏真正透明；首页系统账号去重；四个 tab 统一 `surface` 背景与 32sp 标题。
- Material Design 组默认全部关闭；GUI 顶部红色免责声明。
- 修复：会话点不开、我页资料卡打不开、余额卡片不显示/不刷新。

## 已知问题（下一版修复）

- 从「服务」等原生页面返回后，首页可能掉回微信原生列表。
