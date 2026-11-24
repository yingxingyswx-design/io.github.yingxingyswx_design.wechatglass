package com.wechatglass;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import android.widget.TextView;

public class SettingsActivity extends Activity {

    private static final String PREFS = "settings";
    private static final String KEY_GLASS = "glass_enabled";
    private static final String KEY_WALLPAPER = "wallpaper_uri";
    private static final String KEY_NATIVE = "native_list";
    private static final String KEY_AUTO_CONFLICT = "auto_disable_original";
    private static final String KEY_MONET = "color_monet";
    private static final String KEY_SEED = "color_seed";
    private static final String KEY_MESSAGE_TIME = "message_time";
    private static final int DEFAULT_SEED = 0xFF07C160;
    private static final int[] SEED_COLORS = {
            0xFF07C160, 0xFF1A73E8, 0xFF6750A4, 0xFFFF7A00,
            0xFFE53935, 0xFF00A6A6, 0xFFE91E63, 0xFFF2B600
    };
    private static final String[] SEED_NAMES = {
            "微信绿", "蓝", "紫", "橙", "红", "青", "粉", "琥珀"
    };
    private static final String ACTION = "com.wechatglass.CONFIG";
    private static final String ACTION_QUERY = "com.wechatglass.QUERY";
    private static final String ACTION_STATE = "com.wechatglass.STATE";
    /** Full display name of the module the conflict guard watches. */
    private static final String NAV_BAR_MODULE =
            "液态玻璃导航栏模块（" + ConflictGuard.ORIGINAL + "）";

    private TextView wallpaperHint;
    private M3Switch glassSwitch;
    private M3Switch nativeSwitchField;
    private TextView conflictStatus;
    private M3Switch autoConflictSwitch;
    private volatile boolean conflictCheckRunning;
    private TextView statusRootValue;
    private TextView statusInjectValue;
    private TextView statusConflictValue;
    private volatile boolean stateReceived;
    private int statusGeneration;
    private boolean hasResumed;
    private M3Switch monetSwitch;
    private M3Switch messageTimeSwitch;
    private M3Switch balanceCardSwitch;
    private M3Switch roundAvatarSwitch;
    private M3Switch mediaBubbleSwitch;
    private LinearLayout swatchRow;
    private int selectedSeed = DEFAULT_SEED;

    // New keys live in the module's own prefs and are mirrored to WeChat's prefs
    // through the config broadcast (same channel as the rest of the settings).
    private static final String KEY_ROUND_AVATAR = "round_avatar";
    private static final String KEY_MEDIA_BUBBLE = "media_bubble";
    private static final String KEY_BALANCE_CARD = "balance_card";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.wechatglass.m3.M3ColorScheme scheme =
                com.wechatglass.m3.M3ColorScheme.forContext(this);
        int bg = scheme.surface;
        int card = scheme.surfaceContainerLow;
        int onBg = scheme.onSurface;
        int onVar = scheme.onSurfaceVariant;
        int barColor = scheme.primary;
        // Transparent status bar: the page is laid out underneath it and the
        // content gets a matching top inset, so the system bar shows the page
        // background instead of an opaque block.
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(barColor);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(barColor);
        }
        int lightFlags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            lightFlags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(scheme.dark ? 0 : lightFlags);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28) + statusBarHeight(), pad, dp(28));
        scroll.addView(root);
        scroll.setClipToPadding(false);
        setContentView(scroll);

        TextView versionLabel = new TextView(this);
        versionLabel.setText("WeChat-NewUI  "
                + io.github.yingxingyswx_design.wechatglass.BuildConfig.VERSION_NAME
                + "  (" + io.github.yingxingyswx_design.wechatglass.BuildConfig.VERSION_CODE
                + ")");
        versionLabel.setTextSize(13f);
        versionLabel.setTypeface(android.graphics.Typeface.create(
                "monospace", android.graphics.Typeface.NORMAL));
        versionLabel.setTextColor(onVar);
        versionLabel.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(versionLabel, lp(-1, -2, 0, 0));

        TextView disclaimer = new TextView(this);
        disclaimer.setText("本模块造成的任何后果，本模块开发者概不负责");
        disclaimer.setTextSize(20f);
        disclaimer.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD));
        disclaimer.setTextColor(scheme.error);
        disclaimer.setPadding(dp(2), dp(2), dp(2), dp(6));
        root.addView(disclaimer, lp(-1, -2, 0, 0));

        TextView banner = new TextView(this);
        banner.setText("非液态玻璃选项需要微信运行时动态注入！");
        banner.setTextSize(17f);
        banner.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        banner.setTextColor(scheme.error);
        banner.setPadding(dp(2), dp(4), dp(2), dp(2));
        root.addView(banner, lp(-1, -2, 0, 0));

        TextView banner2 = new TextView(this);
        banner2.setText("聊天页液态玻璃需要手动选择壁纸！");
        banner2.setTextSize(17f);
        banner2.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        banner2.setTextColor(scheme.error);
        banner2.setPadding(dp(2), dp(2), dp(2), dp(4));
        root.addView(banner2, lp(-1, -2, 0, dp(16)));

        TextView title = new TextView(this);
        title.setText("液态玻璃");
        title.setTextSize(30f);
        title.setTextColor(onBg);
        title.setGravity(Gravity.START);
        root.addView(title, lp(-1, -2, 0, dp(4)));

        TextView sub = new TextView(this);
        sub.setText("外观与效果");
        sub.setTextSize(14f);
        sub.setTextColor(onVar);
        root.addView(sub, lp(-1, -2, 0, dp(24)));

        buildStatusCard(root, card, onBg, onVar);

        // 2. 对微信强制停止 / 打开微信
        root.addView(section("操作", card, onBg, onVar));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button stopButton = new Button(this);
        stopButton.setText("强制停止微信");
        stopButton.setAllCaps(false);
        stopButton.setTextSize(15f);
        stopButton.setOnClickListener(v -> forceStopWeChat());
        styleButton(stopButton, true);
        Button openButton = new Button(this);
        openButton.setText("打开微信");
        openButton.setAllCaps(false);
        openButton.setTextSize(15f);
        openButton.setOnClickListener(v -> openWeChat());
        styleButton(openButton, false);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(72), 1f);
        stopLp.rightMargin = dp(8);
        actions.addView(stopButton, stopLp);
        actions.addView(openButton, new LinearLayout.LayoutParams(0, dp(72), 1f));
        root.addView(actions, lp(-1, -2, 0, dp(8)));

        // 3. 液态玻璃总开关
        root.addView(section("液态玻璃", card, onBg, onVar));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("开启液态玻璃");
        label.setTextSize(16f);
        label.setTextColor(onBg);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, labelLp);
        M3Switch glass = new M3Switch(this);
        glassSwitch = glass;
        glass.setCheckedNoAnim(getSharedPreferences(PREFS, 0).getBoolean(KEY_GLASS, true));
        glass.setOnClickListener(v -> {
            userToggled = true;
            boolean on = !glass.isChecked();
            glass.setChecked(on);
            getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_GLASS, on).apply();
            pushConfig();
        });
        row.addView(glass);
        root.addView(row, lp(-1, -2, 0, dp(8)));

        // 4. 选择聊天壁纸（聊天页玻璃必需）
        root.addView(section("聊天页液态玻璃背景壁纸", card, onBg, onVar));

        wallpaperHint = new TextView(this);
        wallpaperHint.setTextSize(13f);
        wallpaperHint.setTextColor(onVar);
        String saved = getSharedPreferences(PREFS, 0).getString(KEY_WALLPAPER, null);
        wallpaperHint.setText(saved == null ? "未选择（玻璃只显示内容）" : saved);
        root.addView(wallpaperHint, lp(-1, -2, 0, dp(8)));

        Button pick = new Button(this);
        pick.setText("选择壁纸");
        pick.setAllCaps(false);
        pick.setTextSize(16f);
        pick.setOnClickListener(v -> pickWallpaper());
        styleButton(pick, false);
        root.addView(pick, lp(-1, dp(72), 0, dp(8)));

        // 5-8. Material Design 相关
        root.addView(section("Material Design", card, onBg, onVar));

        LinearLayout nativeRow = new LinearLayout(this);
        nativeRow.setOrientation(LinearLayout.HORIZONTAL);
        nativeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView nativeLabel = new TextView(this);
        nativeLabel.setText("开启 Material Design（实验）");
        nativeLabel.setTextSize(16f);
        nativeLabel.setTextColor(onBg);
        nativeRow.addView(nativeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        M3Switch nativeSwitch = new M3Switch(this);
        nativeSwitchField = nativeSwitch;
        // Checked = Material Design (custom list); unchecked = WeChat native.
        nativeSwitch.setCheckedNoAnim(!getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_NATIVE, true));
        nativeSwitch.setOnClickListener(v -> {
            userToggled = true;
            boolean on = !nativeSwitch.isChecked();
            nativeSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit()
                    .putBoolean(KEY_NATIVE, !on).apply();
            updateM3GatedSwitches();
            pushConfig();
        });
        nativeRow.addView(nativeSwitch);
        root.addView(nativeRow, lp(-1, -2, 0, dp(8)));

        // 6. 开启圆形头像
        LinearLayout roundAvatarRow = new LinearLayout(this);
        roundAvatarRow.setOrientation(LinearLayout.HORIZONTAL);
        roundAvatarRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView roundAvatarLabel = new TextView(this);
        roundAvatarLabel.setText("开启圆形头像");
        roundAvatarLabel.setTextSize(16f);
        roundAvatarLabel.setTextColor(onBg);
        roundAvatarRow.addView(roundAvatarLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        roundAvatarSwitch = new M3Switch(this);
        roundAvatarSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_ROUND_AVATAR, false));
        roundAvatarSwitch.setOnClickListener(v -> {
            userToggled = true;
            boolean on = !roundAvatarSwitch.isChecked();
            roundAvatarSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit()
                    .putBoolean(KEY_ROUND_AVATAR, on).commit();
            pushConfig();
        });
        roundAvatarRow.addView(roundAvatarSwitch);
        root.addView(roundAvatarRow, lp(-1, -2, 0, dp(8)));

        // 7. 媒体和表情包使用消息气泡
        LinearLayout mediaBubbleRow = new LinearLayout(this);
        mediaBubbleRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaBubbleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mediaBubbleLabel = new TextView(this);
        mediaBubbleLabel.setText("媒体和表情包使用消息气泡");
        mediaBubbleLabel.setTextSize(16f);
        mediaBubbleLabel.setTextColor(onBg);
        mediaBubbleRow.addView(mediaBubbleLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mediaBubbleSwitch = new M3Switch(this);
        mediaBubbleSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_MEDIA_BUBBLE, false));
        mediaBubbleSwitch.setOnClickListener(v -> {
            userToggled = true;
            boolean on = !mediaBubbleSwitch.isChecked();
            mediaBubbleSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit()
                    .putBoolean(KEY_MEDIA_BUBBLE, on).commit();
            pushConfig();
        });
        mediaBubbleRow.addView(mediaBubbleSwitch);
        root.addView(mediaBubbleRow, lp(-1, -2, 0, dp(8)));

        TextView gatedHint = new TextView(this);
        gatedHint.setText("圆形头像、媒体气泡与每条消息时间需先开启 Material Design，否则不可用。");
        gatedHint.setTextSize(13f);
        gatedHint.setTextColor(onVar);
        root.addView(gatedHint, lp(-1, -2, 0, dp(10)));

        // 8. 每条消息显示发送时间
        LinearLayout messageTimeRow = new LinearLayout(this);
        messageTimeRow.setOrientation(LinearLayout.HORIZONTAL);
        messageTimeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView messageTimeLabel = new TextView(this);
        messageTimeLabel.setText("每条消息显示发送时间（功耗增加）");
        messageTimeLabel.setTextSize(16f);
        messageTimeLabel.setTextColor(onBg);
        messageTimeRow.addView(messageTimeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        messageTimeSwitch = new M3Switch(this);
        messageTimeSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_MESSAGE_TIME, false));
        messageTimeSwitch.setOnClickListener(v -> {
            userToggled = true;
            boolean on = !messageTimeSwitch.isChecked();
            messageTimeSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit()
                    .putBoolean(KEY_MESSAGE_TIME, on).commit();
            pushConfig();
        });
        messageTimeRow.addView(messageTimeSwitch);
        root.addView(messageTimeRow, lp(-1, -2, 0, dp(8)));

        TextView messageTimeHint = new TextView(this);
        messageTimeHint.setText("开启后会在每条消息上方显示时间，滚动时开销更高；默认关闭。");
        messageTimeHint.setTextSize(13f);
        messageTimeHint.setTextColor(onVar);
        root.addView(messageTimeHint, lp(-1, -2, 0, dp(16)));

        // 开启余额卡片显示（被动采样，默认关闭，开启前二次确认）
        LinearLayout balanceRow = new LinearLayout(this);
        balanceRow.setOrientation(LinearLayout.HORIZONTAL);
        balanceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView balanceLabel = new TextView(this);
        balanceLabel.setText("开启余额卡片显示");
        balanceLabel.setTextSize(16f);
        balanceLabel.setTextColor(onBg);
        balanceRow.addView(balanceLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        balanceCardSwitch = new M3Switch(this);
        balanceCardSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_BALANCE_CARD, false));
        balanceCardSwitch.setOnClickListener(v -> {
            if (balanceCardSwitch.isChecked()) {
                balanceCardSwitch.setCheckedNoAnim(false);
                getSharedPreferences(PREFS, 0).edit()
                        .putBoolean(KEY_BALANCE_CARD, false).commit();
                pushConfig();
            } else {
                showBalanceWarning();
            }
        });
        balanceRow.addView(balanceCardSwitch);
        root.addView(balanceRow, lp(-1, -2, 0, dp(8)));

        TextView balanceHint = new TextView(this);
        balanceHint.setText("仅对已获取的数据采样，需要手动刷新");
        balanceHint.setTextSize(13f);
        balanceHint.setTextColor(onVar);
        root.addView(balanceHint, lp(-1, -2, 0, dp(16)));

        // 动态色彩
        root.addView(section("动态色彩", card, onBg, onVar));

        LinearLayout monetRow = new LinearLayout(this);
        monetRow.setOrientation(LinearLayout.HORIZONTAL);
        monetRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView monetLabel = new TextView(this);
        monetLabel.setText("使用系统 Monet 取色");
        monetLabel.setTextSize(16f);
        monetLabel.setTextColor(onBg);
        monetRow.addView(monetLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        monetSwitch = new M3Switch(this);
        monetSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_MONET, true));
        monetSwitch.setOnClickListener(v -> {
            boolean on = !monetSwitch.isChecked();
            monetSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_MONET, on).apply();
            refreshSwatches();
            pushConfig();
            recreate();
        });
        monetRow.addView(monetSwitch);
        root.addView(monetRow, lp(-1, -2, 0, dp(4)));

        TextView monetHint = new TextView(this);
        monetHint.setText("关闭后使用下方色卡；默认微信绿。");
        monetHint.setTextSize(13f);
        monetHint.setTextColor(onVar);
        root.addView(monetHint, lp(-1, -2, 0, dp(10)));

        android.widget.HorizontalScrollView swatchScroll =
                new android.widget.HorizontalScrollView(this);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setPadding(dp(2), dp(2), dp(2), dp(2));
        swatchScroll.addView(swatchRow);
        root.addView(swatchScroll, lp(-1, dp(56), 0, dp(16)));

        selectedSeed = getSharedPreferences(PREFS, 0).getInt(KEY_SEED, DEFAULT_SEED);
        refreshSwatches();

        // 冲突检测
        root.addView(section("冲突检测", card, onBg, onVar));

        conflictStatus = new TextView(this);
        conflictStatus.setText("正在检测 WeChat-LiquidGlass…");
        conflictStatus.setTextSize(14f);
        conflictStatus.setTextColor(onVar);
        root.addView(conflictStatus, lp(-1, -2, 0, dp(8)));

        LinearLayout autoConflictRow = new LinearLayout(this);
        autoConflictRow.setOrientation(LinearLayout.HORIZONTAL);
        autoConflictRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView autoConflictLabel = new TextView(this);
        autoConflictLabel.setText("检测到" + NAV_BAR_MODULE + "时自动停用");
        autoConflictLabel.setTextSize(16f);
        autoConflictLabel.setTextColor(onBg);
        autoConflictRow.addView(autoConflictLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoConflictSwitch = new M3Switch(this);
        autoConflictSwitch.setCheckedNoAnim(getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_AUTO_CONFLICT, true));
        autoConflictSwitch.setOnClickListener(v -> {
            boolean on = !autoConflictSwitch.isChecked();
            autoConflictSwitch.setChecked(on);
            getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO_CONFLICT, on).apply();
            if (on) {
                checkConflictAsync(false);
            }
        });
        autoConflictRow.addView(autoConflictSwitch);
        root.addView(autoConflictRow, lp(-1, -2, 0, dp(8)));

        Button conflictButton = new Button(this);
        conflictButton.setText("检测并停用" + NAV_BAR_MODULE);
        conflictButton.setAllCaps(false);
        conflictButton.setTextSize(15f);
        conflictButton.setOnClickListener(v -> checkConflictAsync(true));
        styleButton(conflictButton, false);
        root.addView(conflictButton, lp(-1, dp(72), 0, dp(8)));

        updateM3GatedSwitches();

        registerStateReceiver();
        refreshStatus();
        checkConflictAsync(false);
    }

    private boolean userToggled;
    private android.content.BroadcastReceiver stateReceiver;

    private void sendQuery() {
        try {
            Intent intent = new Intent(ACTION_QUERY);
            intent.setPackage("com.tencent.mm");
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** WeChat replies with its real state, so the switches never lie. */
    private void registerStateReceiver() {
        stateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                stateReceived = true;
                android.util.Log.i("WeChatGlassGui", "STATE received");
                if (statusInjectValue != null) {
                    setStatusValue(statusInjectValue, "已注入微信", true);
                }
                if (userToggled) {
                    return;
                }
                boolean glass = intent.getBooleanExtra("glass", true);
                boolean nativeList = intent.getBooleanExtra("native_list", true);
                if (glassSwitch != null) {
                    glassSwitch.setCheckedNoAnim(glass);
                }
                if (nativeSwitchField != null) {
                    nativeSwitchField.setCheckedNoAnim(!nativeList);
                }
                if (intent.hasExtra("message_time")) {
                    boolean messageTime = intent.getBooleanExtra("message_time", false);
                    if (messageTimeSwitch != null) {
                        messageTimeSwitch.setCheckedNoAnim(messageTime);
                    }
                    getSharedPreferences(PREFS, 0).edit()
                            .putBoolean(KEY_MESSAGE_TIME, messageTime).apply();
                }
                if (intent.hasExtra("round_avatar")) {
                    boolean roundAvatar = intent.getBooleanExtra("round_avatar", true);
                    if (roundAvatarSwitch != null) {
                        roundAvatarSwitch.setCheckedNoAnim(roundAvatar);
                    }
                    getSharedPreferences(PREFS, 0).edit()
                            .putBoolean(KEY_ROUND_AVATAR, roundAvatar).apply();
                }
                if (intent.hasExtra("media_bubble")) {
                    boolean mediaBubble = intent.getBooleanExtra("media_bubble", true);
                    if (mediaBubbleSwitch != null) {
                        mediaBubbleSwitch.setCheckedNoAnim(mediaBubble);
                    }
                    getSharedPreferences(PREFS, 0).edit()
                            .putBoolean(KEY_MEDIA_BUBBLE, mediaBubble).apply();
                }
                updateM3GatedSwitches();
                if (!intent.hasExtra("color_monet") && !intent.hasExtra("color_seed")) {
                    return;
                }
                boolean monet = intent.getBooleanExtra("color_monet", true);
                int seed = intent.getIntExtra("color_seed", DEFAULT_SEED);
                boolean changed = selectedSeed != seed
                        || (monetSwitch != null && monetSwitch.isChecked() != monet);
                selectedSeed = seed;
                if (monetSwitch != null) {
                    monetSwitch.setCheckedNoAnim(monet);
                }
                getSharedPreferences(PREFS, 0).edit()
                        .putBoolean(KEY_MONET, monet)
                        .putInt(KEY_SEED, seed)
                        .apply();
                refreshSwatches();
                if (changed) {
                    recreate();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter,
                    android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasResumed) {
            refreshStatus();
        } else {
            hasResumed = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            stateReceiver = null;
        }
        super.onDestroy();
    }

    /** Sends both switches to WeChat's process. */
    private void pushConfig() {
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage("com.tencent.mm");
            intent.putExtra("glass", glassSwitch == null || glassSwitch.isChecked());
            intent.putExtra("native_list",
                    !(nativeSwitchField != null && nativeSwitchField.isChecked()));
            intent.putExtra("color_monet",
                    monetSwitch == null || monetSwitch.isChecked());
            intent.putExtra("color_seed", selectedSeed);
            intent.putExtra("message_time",
                    messageTimeSwitch != null && messageTimeSwitch.isChecked());
            intent.putExtra("round_avatar",
                    roundAvatarSwitch != null && roundAvatarSwitch.isChecked());
            intent.putExtra("media_bubble",
                    mediaBubbleSwitch != null && mediaBubbleSwitch.isChecked());
            intent.putExtra("balance_card",
                    balanceCardSwitch != null && balanceCardSwitch.isChecked());
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void styleButton(Button button, boolean filled) {
        com.wechatglass.m3.M3ColorScheme scheme =
                com.wechatglass.m3.M3Theme.colors(this);
        button.setAllCaps(false);
        button.setTextSize(15f);
        button.setTextColor(filled ? scheme.onPrimary : scheme.onPrimaryContainer);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? scheme.primary : scheme.primaryContainer);
        bg.setCornerRadius(dp(20));
        button.setBackground(bg);
        button.setMinHeight(dp(72));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
    }

    private void refreshSwatches() {
        if (swatchRow == null) {
            return;
        }
        com.wechatglass.m3.M3ColorScheme scheme =
                com.wechatglass.m3.M3Theme.colors(this);
        boolean monet = monetSwitch != null && monetSwitch.isChecked();
        swatchRow.removeAllViews();
        for (int i = 0; i < SEED_COLORS.length; i++) {
            final int seed = SEED_COLORS[i];
            View swatch = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(seed);
            boolean selected = !monet && (seed == selectedSeed);
            dot.setStroke(dp(selected ? 3 : 1),
                    selected ? scheme.onSurface : scheme.outlineVariant);
            swatch.setBackground(dot);
            swatch.setAlpha(monet ? 0.45f : 1f);
            swatch.setContentDescription(SEED_NAMES[i]);
            swatch.setOnClickListener(v -> selectSeed(seed));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
            p.setMargins(dp(6), dp(4), dp(6), dp(4));
            swatchRow.addView(swatch, p);
        }
    }

    private void selectSeed(int seed) {
        selectedSeed = seed;
        getSharedPreferences(PREFS, 0).edit().putInt(KEY_SEED, seed).apply();
        if (monetSwitch != null && monetSwitch.isChecked()) {
            monetSwitch.setChecked(false);
            getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_MONET, false).apply();
        }
        refreshSwatches();
        pushConfig();
        recreate();
    }

    private void buildStatusCard(LinearLayout root, int card, int onBg, int onVar) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        bg.setCornerRadius(dp(28));
        box.setBackground(bg);
        box.setPadding(dp(20), dp(16), dp(20), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("运行状态");
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(onBg);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextSize(13f);
        refresh.setTextColor(onVar);
        refresh.setPadding(dp(12), dp(6), dp(4), dp(6));
        refresh.setOnClickListener(v -> refreshStatus());
        header.addView(refresh);
        box.addView(header);

        statusRootValue = statusValueView(onBg);
        statusInjectValue = statusValueView(onBg);
        statusConflictValue = statusValueView(onBg);
        box.addView(statusRow("Root", statusRootValue, onVar));
        box.addView(statusRow("LSPosed 注入", statusInjectValue, onVar));
        box.addView(statusRow("导航栏模块冲突", statusConflictValue, onVar));

        root.addView(box, lp(-1, -2, 0, dp(16)));
    }

    private TextView statusValueView(int onBg) {
        TextView value = new TextView(this);
        value.setTextSize(13f);
        value.setTextColor(onBg);
        value.setGravity(Gravity.END);
        value.setMaxLines(2);
        return value;
    }

    private LinearLayout statusRow(String label, TextView value, int onVar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14f);
        name.setTextColor(onVar);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    private void refreshStatus() {
        if (statusRootValue == null) {
            return;
        }
        final int generation = ++statusGeneration;
        statusRootValue.setText("检测中…");
        statusInjectValue.setText("等待微信回应…");
        statusConflictValue.setText("检测中…");
        stateReceived = false;
        sendQuery();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ConflictGuard.Status status = ConflictGuard.detect(app);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != statusGeneration) {
                            return;
                        }
                        setStatusValue(statusRootValue,
                                status.root ? "已授权" : "未授权", status.root);
                        if (!status.installed) {
                            setStatusValue(statusConflictValue, "未安装导航栏模块", true);
                        } else if (status.conflict()) {
                            setStatusValue(statusConflictValue, "导航栏模块仍勾选微信", false);
                        } else {
                            setStatusValue(statusConflictValue, "无冲突（未勾选微信）", true);
                        }
                    }
                });
            }
        }, "wxglass-status").start();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation != statusGeneration) {
                    return;
                }
                if (!stateReceived) {
                    setStatusValue(statusInjectValue, "未注入 / 微信未运行", false);
                }
            }
        }, 1800);
    }

    private void setStatusValue(TextView value, String text, boolean ok) {
        if (value == null) {
            return;
        }
        value.setText("● " + text);
        value.setTextColor(ok ? com.wechatglass.m3.M3Theme.colors(this).primary
                : com.wechatglass.m3.M3Theme.colors(this).error);
    }

    private void checkConflictAsync(boolean manual) {
        if (conflictCheckRunning) {
            return;
        }
        conflictCheckRunning = true;
        if (conflictStatus != null) {
            conflictStatus.setText(manual ? "正在检测并停用…" : "正在检测 WeChat-LiquidGlass…");
        }
        final boolean allowAuto = autoConflictSwitch == null || autoConflictSwitch.isChecked();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                ConflictGuard.Status status = ConflictGuard.detect(app);
                boolean disabled = false;
                if (status.installed && status.conflict() && (manual || allowAuto)) {
                    disabled = ConflictGuard.disableWeChatScope(app);
                    if (disabled) {
                        status = ConflictGuard.detect(app);
                    }
                }
                final ConflictGuard.Status result = status;
                final boolean didDisable = disabled;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        conflictCheckRunning = false;
                        showConflictStatus(result, didDisable);
                    }
                });
            }
        }, "wxglass-conflict").start();
    }

    private void showConflictStatus(ConflictGuard.Status status, boolean disabled) {
        if (conflictStatus == null) {
            return;
        }
        if (!status.installed) {
            conflictStatus.setText("未检测到 " + NAV_BAR_MODULE + "，无冲突。");
            return;
        }
        if (disabled && !status.conflict()) {
            conflictStatus.setText("已移除" + NAV_BAR_MODULE + "的微信作用域；QQ 作用域保留。\n"
                    + "请点下方「强制停止微信」再重新打开，整合后的底栏才会接管。");
            return;
        }
        if (!status.root) {
            conflictStatus.setText("已安装" + NAV_BAR_MODULE + "；模块 App 没有 root 权限，无法自动检查。\n"
                    + "请在 LSPosed 里确认 " + ConflictGuard.ORIGINAL
                    + " 只勾选 QQ、不勾选微信。");
            return;
        }
        if (status.lspctl == null) {
            conflictStatus.setText("已安装" + NAV_BAR_MODULE + "，但有 root 也没找到 LSPosed 的 lspctl。\n"
                    + "请在 LSPosed 里只取消 " + ConflictGuard.ORIGINAL
                    + " 的微信作用域，保留 QQ。");
            return;
        }
        if (status.conflict()) {
            conflictStatus.setText("检测到" + NAV_BAR_MODULE + "勾选了微信。\n"
                    + "自动移除微信作用域失败（root 未授权？），请在 LSPosed 里只取消微信，保留 QQ。");
            return;
        }
        if (!status.wechatScope()) {
            conflictStatus.setText(NAV_BAR_MODULE + "没有勾选微信，不冲突；QQ 作用域保留。");
            return;
        }
        conflictStatus.setText(NAV_BAR_MODULE + "的微信作用域已移除，当前不会冲突。");
    }

    private void forceStopWeChat() {
        int user = android.os.Process.myUid() / 100000;
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "am force-stop --user " + user + " com.tencent.mm")
                    .redirectErrorStream(true).start();
            // Do not block the UI thread forever if the su prompt is denied.
            process.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroy();
            }
        } catch (Throwable throwable) {
            try {
                android.app.ActivityManager manager = (android.app.ActivityManager)
                        getSystemService(ACTIVITY_SERVICE);
                if (manager != null) {
                    manager.killBackgroundProcesses("com.tencent.mm");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void openWeChat() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void pickWallpaper() {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, 1001);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("image/*");
            startActivityForResult(fallback, 1001);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            getSharedPreferences(PREFS, 0).edit().putString(KEY_WALLPAPER, uri.toString()).apply();
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                if (in != null) in.close();
                if (bmp != null) {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        if (!dir.exists()) dir.mkdirs();
                        java.io.FileOutputStream out =
                                new java.io.FileOutputStream(new java.io.File(dir, "wallpaper.png"));
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                        out.close();
                        wallpaperHint.setText("已保存: " + uri);
                    }
                }
            } catch (Throwable t) {
                wallpaperHint.setText("保存失败: " + t);
            }
            wallpaperHint.setText(uri.toString());
        }
    }

    private View section(String text, int color, int onBg, int onVar) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20f);
        t.setTextColor(onBg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(28));
        t.setBackground(g);
        t.setPadding(dp(20), dp(18), dp(20), dp(18));
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Height of the system status bar, so the page can sit underneath it. */
    private int statusBarHeight() {
        int id = getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    /** Red warning shown before the balance card can be enabled. */
    private void showBalanceWarning() {
        com.wechatglass.m3.M3ColorScheme scheme =
                com.wechatglass.m3.M3Theme.colors(this);
        TextView message = new TextView(this);
        message.setText("开启后会大幅度提高封号风险，如果账号封禁，本模块的开发者概不负责");
        message.setTextSize(16f);
        message.setTextColor(scheme.error);
        int pad = dp(20);
        message.setPadding(pad, pad, pad, pad);
        new android.app.AlertDialog.Builder(this)
                .setTitle("警告")
                .setView(message)
                .setPositiveButton("仍然开启", (dialog, which) -> {
                    balanceCardSwitch.setCheckedNoAnim(true);
                    getSharedPreferences(PREFS, 0).edit()
                            .putBoolean(KEY_BALANCE_CARD, true).commit();
                    pushConfig();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** Keeps the Material Design dependent switches in sync with the mode. */
    private void updateM3GatedSwitches() {
        boolean m3 = nativeSwitchField != null && nativeSwitchField.isChecked();
        applyGated(roundAvatarSwitch, m3);
        applyGated(mediaBubbleSwitch, m3);
        applyGated(messageTimeSwitch, m3);
    }

    private void applyGated(M3Switch sw, boolean enabled) {
        if (sw == null) {
            return;
        }
        sw.setEnabled(enabled);
        sw.setAlpha(enabled ? 1f : 0.35f);
    }
}
