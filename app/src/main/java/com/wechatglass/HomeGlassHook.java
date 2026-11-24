package com.wechatglass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Home (LauncherUI) top bar: same three floating glass islands as the chat top
 * bar, plus transparent status bar. Only the home ActionBar and list padding are
 * touched.
 */
final class HomeGlassHook {

    private static final String TAG = "WeChatGlassHome";
    private static final boolean DEBUG_HIDE_OVERLAY = false;
    private static final Map<Activity, HomeGlassHook> INSTANCES = new WeakHashMap<>();
    private static volatile boolean nativeBarAllowed;
    /** setTo(): 0 = Weixin, 1 = Discover, 2 = Contacts, 3 = Me; -1 until known. */
    private static volatile int bottomTab = -1;

    /**
     * Repaints WeChat's native tab icon/label with the current theme accent.
     * The native layout, ripple, red dots and click handling stay untouched.
     */
    static void applyBottomTabTheme(Object tabView, int index) {
        if (tabView != null) {
            bottomTabView = new java.lang.ref.WeakReference<>(tabView);
        }
        Object target = tabView != null ? tabView
                : (bottomTabView == null ? null : bottomTabView.get());
        if (!(target instanceof View)) {
            return;
        }
        try {
            com.wechatglass.m3.M3ColorScheme scheme =
                    com.wechatglass.m3.M3Theme.colors(((View) target).getContext());
            int selected = scheme.primary;
            int idle = scheme.onSurfaceVariant;
            // MainTabUI.onPageScrolled interpolates between these fields every
            // frame; changing only the TextViews would be overwritten as soon
            // as the user swipes. Replace the source colors + channel deltas.
            int sr = (selected >> 16) & 0xFF;
            int sg = (selected >> 8) & 0xFF;
            int sb = selected & 0xFF;
            int ir = (idle >> 16) & 0xFF;
            int ig = (idle >> 8) & 0xFF;
            int ib = idle & 0xFF;
            de.robv.android.xposed.XposedHelpers.setIntField(target, "m", selected);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "n", sr);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "o", sg);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "p", sb);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "q", idle);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "r", ir);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "s", ig);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "t", ib);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "u", sr - ir);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "v", sg - ig);
            de.robv.android.xposed.XposedHelpers.setIntField(target, "w", sb - ib);

            String[] fields = {"f", "h", "g", "i"};
            for (int i = 0; i < fields.length; i++) {
                Object tab = de.robv.android.xposed.XposedHelpers
                        .getObjectField(target, fields[i]);
                if (tab == null) {
                    continue;
                }
                android.widget.TextView label = (android.widget.TextView)
                        de.robv.android.xposed.XposedHelpers
                                .getObjectField(tab, "f");
                if (label != null) {
                    label.setTextColor(i == index ? selected : idle);
                }
                Object icon = de.robv.android.xposed.XposedHelpers
                        .getObjectField(tab, "e");
                if (icon != null) {
                    Object selectedPaint = de.robv.android.xposed.XposedHelpers
                            .getObjectField(icon, "m");
                    Object idlePaint = de.robv.android.xposed.XposedHelpers
                            .getObjectField(icon, "n");
                    if (selectedPaint instanceof android.graphics.Paint) {
                        ((android.graphics.Paint) selectedPaint).setColorFilter(
                                new android.graphics.PorterDuffColorFilter(
                                        i == index ? scheme.primary : scheme.onSurfaceVariant,
                                        android.graphics.PorterDuff.Mode.SRC_ATOP));
                    }
                    if (idlePaint instanceof android.graphics.Paint) {
                        ((android.graphics.Paint) idlePaint).setColorFilter(
                                new android.graphics.PorterDuffColorFilter(
                                        scheme.onSurfaceVariant,
                                        android.graphics.PorterDuff.Mode.SRC_ATOP));
                    }
                    if (icon instanceof View) {
                        ((View) icon).invalidate();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void onBottomTab(int tab) {
        if (bottomTab == tab) {
            return;
        }
        bottomTab = tab;
        for (HomeGlassHook hook : INSTANCES.values()) {
            try {
                hook.updateTabState();
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isNativeBarAllowed() {
        return nativeBarAllowed;
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int tint;
    private final int border;

    private View decor;
    private View actionBar;
    private View pagerView;
    private View toolbar;
    private View titleView;
    private View leftView;
    private View rightView;
    private View searchView;
    private View plusView;

    private FrameLayout overlay;
    private GlassLayerView glassLayer;
    private boolean homeWallLoop;
    private int homeWallTicks;
    private int scrollLogCount;
    private GlassChipView leftChip;
    private GlassChipView titleChip;
    private GlassChipView searchChip;
    private GlassChipView plusChip;
    private GlassChipView scanChip;
    private HomeCategoryBar categoryBar;
    private int barAttempts;
    private HomeCategoryPage categoryPage;
    private M3PageHost pages;
    private android.widget.ListView homeListView;
    private View taskBarContainer;
    private boolean miniProgramShown;
    private int miniHits;
    private long attachedAt;
    private View topMask;
    private int tabMode; // 0 = full, 1 = title only, 2 = no bar
    private int actionBarHeight;
    private int legacyTopInset;
    private boolean legacyRemoved;
    private View frostedContent;
    private int lastFrostTop = -1;
    private GlassRenderer renderer;
    private View list;
    private int listBaseTop;
    private int listBaseBottom;
    private boolean installed;
    private int attempts;
    private boolean overlaySet;
    private long lastGeometrySignature = Long.MIN_VALUE;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!installed) {
                return;
            }
            // WeChat re-shows its ActionBar on tab switches; keep ours on top
            // and the native one hidden without running the full geometry pass.
            keepNativeBarHidden();
            if (tabMode == 0) {
                // WeChat repaints the status strip on resume/scroll; re-clear it
                // on the existing tick instead of hooking its setter.
                paintStatusStrip();
            }
            if (homeListView != null) {
                // WeChat can replace the adapter again on resume; this is a
                // cheap identity check, not a re-wrap.
                HomeConversationFilter.attach(homeListView);
            }
            if (categoryPage != null && ConversationIndex.loaded()) {
                // The index loads on a background thread; the first show() can
                // happen before it has any conversations. Show once per index
                // version (and after every explicit reload).
                int version = ConversationIndex.version();
                if (!categoryPageFilled || version != lastIndexVersion
                        || !categoryPage.hasRows()) {
                    categoryPageFilled = true;
                    lastIndexVersion = version;
                    categoryPage.show(HomeConversationFilter.mode());
                }
                // Keep the unread/read badges live: re-read rconversation on a
                // background thread every couple of seconds. The version bump on
                // completion makes the next tick re-render the list.
                long nowMs = android.os.SystemClock.uptimeMillis();
                if (nowMs - lastIndexRefresh > 2500L) {
                    lastIndexRefresh = nowMs;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ConversationIndex.refresh();
                        }
                    }, "wxglass-refresh").start();
                }
            }
            scanNativeRows();
            long now = android.os.SystemClock.uptimeMillis();
            long sinceAttach = now - attachedAt;
            if (isMiniProgramSurface()) {
                miniHits++;
            } else {
                miniHits = 0;
            }
            // Two consecutive hits (~1.2s) and never during the launch
            // transition, where the TaskBar container is briefly at the top.
            boolean miniStable = sinceAttach > 2500L && miniHits >= 2;
            if (miniStable != miniProgramShown) {
                miniProgramShown = miniStable;
                android.util.Log.i("WeChatGlass", "HL mini=" + miniStable
                        + " hits=" + miniHits
                        + " taskTop=" + taskBarTop()
                        + " first=" + (homeListView == null ? -1
                        : homeListView.getFirstVisiblePosition()));
                updateTabState();
            }
            if (decor != null && HookConfig.balanceCardEnabled(activity)) {
                long scanNow = android.os.SystemClock.uptimeMillis();
                if (scanNow - lastWalletScan > 2500L) {
                    lastWalletScan = scanNow;
                    // Passive: only reads text WeChat already rendered.
                    WalletBalanceCache.capture(activity, decor);
                }
            }
            // The TaskBar header is the list's first row. The adapter swap
            // pins the list to the first conversation synchronously, so no
            // periodic correction is needed here.
            maybeUpdateGeometry();
            handler.postDelayed(this, 600L);
        }
    };

    /**
     * The home page paints its own status-bar strip: a full-width child of the
     * page root, exactly status-bar height, with WeChat's #EDEDED fill. The
     * window colour is already transparent, so this view is what showed as a
     * grey band. Paint it with our page surface so the bar reads as transparent.
     */
    /**
     * WeChat paints a status-bar-height strip inside the home page in two
     * places: one view under {@code MainUIView} (TaskBar's own strip) and one
     * direct child of the page's {@code FrostedContentView} (MainUI.y0). Both
     * get {@code #EDEDED}. Clearing them lets our page surface show through the
     * status bar; the tick re-runs this because WeChat repaints on resume.
     */
    private void paintStatusStrip() {
        if (decor == null || !(pagerView instanceof ViewGroup)) {
            return;
        }
        try {
            ViewGroup pager = (ViewGroup) pagerView;
            if (pager.getChildCount() == 0) {
                return;
            }
            View page = pager.getChildAt(0);
            int status = Ui.statusBarHeight(decor);
            clearStrips(page, status, page.getWidth(), 0);
        } catch (Throwable ignored) {
        }
    }

    private void clearStrips(View view, int status, int pageWidth, int depth) {
        if (view == null || depth > 9) {
            return;
        }
        if (view.getWidth() > 0 && view.getHeight() > 0
                && Math.abs(view.getHeight() - status) <= 4
                && Math.abs(view.getWidth() - pageWidth) <= 4) {
            android.graphics.drawable.Drawable background = view.getBackground();
            if (background instanceof android.graphics.drawable.ColorDrawable
                    && ((android.graphics.drawable.ColorDrawable) background).getColor()
                    != Color.TRANSPARENT) {
                view.setBackgroundColor(Color.TRANSPARENT);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearStrips(group.getChildAt(i), status, pageWidth, depth + 1);
            }
        }
    }

    /**
     * WeChat replaces the conversation page when returning from a native page
     * (e.g. 服务). The custom list is a child of that page, so it disappears
     * with it; put it back on the new instance instead of staying native.
     */
    private void ensureCategoryPage() {
        if (activity == null || tabMode != 0 || HookConfig.nativeList(activity)) {
            return;
        }
        if (categoryPage != null && categoryPage.getParent() != null) {
            if (categoryPage.getVisibility() != View.VISIBLE) {
                categoryPage.setVisibility(View.VISIBLE);
            }
            return;
        }
        categoryPage = null;
        homeListView = null;
        resolveHomeList();
        if (homeListView != null && homeListView.getParent() instanceof ViewGroup) {
            installCategoryPage();
            android.util.Log.i("WeChatGlass", "HL category page re-attached");
        }
    }
    private void keepNativeBarHidden() {
        if (actionBar != null) {
            if (actionBar.getVisibility() != View.GONE) {
                actionBar.setVisibility(View.GONE);
            }
            if (actionBar.getAlpha() != 0f) {
                actionBar.setAlpha(0f);
            }
        }
        if (toolbar != null) {
            if (toolbar.getVisibility() != View.GONE) {
                toolbar.setVisibility(View.GONE);
            }
            if (toolbar.getAlpha() != 0f) {
                toolbar.setAlpha(0f);
            }
        }
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            overlay.bringToFront();
        }
    }

    private void maybeUpdateGeometry() {
        long signature = geometrySignature();
        if (signature == lastGeometrySignature) {
            return;
        }
        lastGeometrySignature = signature;
        updateGeometry();
    }

    private long geometrySignature() {
        int decorWidth = decor == null ? 0 : decor.getWidth();
        int decorHeight = decor == null ? 0 : decor.getHeight();
        int barWidth = actionBar == null ? 0 : actionBar.getWidth();
        int barHeight = actionBar == null ? 0 : actionBar.getHeight();
        int barTop = actionBar == null ? 0 : actionBar.getTop();
        int listPadding = list == null ? 0 : list.getPaddingTop();
        long signature = decorWidth;
        signature = signature * 31 + decorHeight;
        signature = signature * 31 + barWidth;
        signature = signature * 31 + barHeight;
        signature = signature * 31 + barTop;
        signature = signature * 31 + listPadding;
        signature = signature * 31 + tabMode;
        signature = signature * 31 + (toolbar == null ? 0 : toolbar.getVisibility());
        return signature;
    }

    private HomeGlassHook(Activity activity) {
        this.activity = activity;
        boolean night = Ui.isNight(activity);
        // Solid WeChat white / dark grey; see ChatGlassHook for rationale.
        this.tint = night ? 0xFF2C2C2C : 0xFFFFFFFF;
        this.border = night ? 0x1AFFFFFF : 0x1A000000;
    }

    static void attach(Activity activity) {
        if (activity == null) {
            return;
        }
        HomeGlassHook old = INSTANCES.get(activity);
        if (old != null) {
            // Returning to the home tab must not rebuild the switch: a fresh
            // bar starts its position spring at 0 and replays the selection
            // animation, which looked like an endless tab-switch loop.
            old.updateTabState();
            old.keepNativeBarHidden();
            return;
        }
        HomeGlassHook hook = new HomeGlassHook(activity);
        INSTANCES.put(activity, hook);
        hook.install();
    }

    static void detach(Activity activity) {
        HomeGlassHook hook = INSTANCES.remove(activity);
        if (hook != null) {
            hook.destroy();
        }
    }

    static void reapplyStatusBar(Activity activity) {
        HomeGlassHook hook = INSTANCES.get(activity);
        if (hook != null) {
            // Transparency is a home-tab-only behaviour; leave WeChat's own
            // status bar colour untouched on the other tabs.
            if (bottomTab <= 0) {
                hook.applyStatusBar(false);
            }
        }
    }

    private void install() {
        decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        decor.post(new Runnable() {
            @Override
            public void run() {
                if (!tryInstall()) {
                    attempts++;
                    if (attempts < 20) {
                        decor.postDelayed(this, 150L);
                    }
                }
            }
        });
    }

    private boolean tryInstall() {
        if (installed) {
            return true;
        }
        View ab = Ui.findByClass(decor, "androidx.appcompat.widget.ActionBarContainer");
        if (ab == null) {
            return false;
        }
        actionBar = ab;
        // WeChat re-shows its ActionBar on tab switches and when the TaskBar
        // opens; the 600ms tick left a visible flash. Re-hide it the moment it
        // is laid out again.
        actionBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (actionBar != null && actionBar.getVisibility() != View.GONE) {
                    actionBar.setAlpha(0f);
                    actionBar.setVisibility(View.GONE);
                    if (toolbar != null && toolbar != actionBar) {
                        toolbar.setVisibility(View.GONE);
                    }
                }
            }
        });
        // The layout listener still lets one frame through on a freshly created
        // tab page, which showed up as WeChat's ActionBar flashing on 通讯录 /
        // 发现. Re-hide before the frame is drawn instead.
        final View decorView = decor;
        decorView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (actionBar != null && actionBar.getVisibility() != View.GONE) {
                                actionBar.setAlpha(0f);
                                actionBar.setVisibility(View.GONE);
                                if (toolbar != null && toolbar != actionBar) {
                                    toolbar.setVisibility(View.GONE);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                });
        if (pagerView == null) {
            pagerView = findViewPager(decor);
        }
        toolbar = Ui.findByClass(ab, "androidx.appcompat.widget.Toolbar");
        if (toolbar == null) {
            toolbar = ab;
        }

        applyStatusBar();
        
        Ui.clearActionBarBackground(actionBar);
        Ui.transparent(toolbar);

        titleView = findTitleView();
        leftView = findLeftView();
        rightView = findRightView();
        Ui.transparent(leftView);

        View rightGroup = toolbar.findViewById(0x7f0a183e);
        if (rightGroup instanceof ViewGroup) {
            ViewGroup rg = (ViewGroup) rightGroup;
            if (rg.getChildCount() > 0) {
                searchView = rg.getChildAt(0);
            }
            if (rg.getChildCount() > 1) {
                plusView = rg.getChildAt(1);
            }
        }
        frostedContent = Ui.findByClass(decor, "com.tencent.mm.ui.FrostedContentView");
        Log.i("WeChatGlassFrost", "find frost=" + (frostedContent == null ? "null"
                : frostedContent.getClass().getName()));

        HookConfig.installReceiver(activity);
        HookConfig.setOnChange(new Runnable() {
            @Override
            public void run() {
                for (HomeGlassHook hook : INSTANCES.values()) {
                    try {
                        hook.applyConfig();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        renderer = new GlassRenderer(decor);
        renderer.setGlassEnabled(HookConfig.glassEnabled(activity));

        actionBarHeight = actionBar.getHeight() > 0 ? actionBar.getHeight()
                : Ui.dp(activity, 56f) + Ui.statusBarHeight(decor);
        legacyTopInset = actionBar.getTop() + actionBar.getHeight();
        if (legacyTopInset < actionBarHeight) {
            legacyTopInset = Ui.statusBarHeight(decor) + actionBarHeight;
        }
        if (legacyTopInset <= 0) {
            legacyTopInset = Ui.dp(activity, 80f);
        }
        // Remove the old ActionBar visuals but keep its layout height.
        actionBar.setAlpha(0f);
        disableWindowContentOverlay();

        int chipHeight = Ui.dp(activity, 40f);
        overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, actionBarHeight);
        overlayParams.gravity = Gravity.TOP;
        ((ViewGroup) decor).addView(overlay, overlayParams);

        leftChip = new GlassChipView(activity, renderer, chipHeight / 2f, tint, border);
        searchChip = new GlassChipView(activity, renderer, chipHeight / 2f, tint, border);
        plusChip = new GlassChipView(activity, renderer, chipHeight / 2f, tint, border);
        scanChip = new GlassChipView(activity, renderer, chipHeight / 2f, tint, border);

        leftChip.setIcon(copyIcon(leftView, Ui.isNight(activity)), Ui.dp(activity, 22f));
        searchChip.setIcon(copyIcon(searchView, Ui.isNight(activity)), Ui.dp(activity, 22f));
        plusChip.setIcon(copyIcon(plusView, Ui.isNight(activity)), Ui.dp(activity, 22f));
        scanChip.setIcon(scanIcon(Ui.isNight(activity)), Ui.dp(activity, 22f));
        clickThrough(leftChip, leftView);
        clickThrough(searchChip, searchView);
        clickThrough(plusChip, plusView);
        scanChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScan();
            }
        });
        renderer.setHiddenViews(overlay);


        addChip(overlay, searchChip, chipHeight);
        addChip(overlay, plusChip, chipHeight);
        addChip(overlay, scanChip, chipHeight);
        renderer.addSkipTarget(searchChip);
        renderer.addSkipTarget(plusChip);
        renderer.addSkipTarget(scanChip);
        renderer.addClient(searchChip);
        renderer.addClient(plusChip);
        renderer.addClient(scanChip);

        actionBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                maybeUpdateGeometry();
            }
        });

        if (!homeWallLoop) {
            homeWallLoop = true;
            decor.postDelayed(new Runnable() {
                @Override public void run() {
                    if (activity != null && activity.hasWindowFocus()) {
                        com.wechatglass.liquid.LiquidPanelFactory.enableCaptureWallpaper(false);
                        if (decor instanceof ViewGroup) {
                            ViewGroup g = (ViewGroup) decor;
                            for (int i = 0; i < g.getChildCount(); i++) {
                                com.wechatglass.liquid.LiquidPanelFactory
                                        .forceRecapture(g.getChildAt(i));
                            }
                        }
                        if (homeWallTicks++ % 8 == 0) {
                            
                        }
                    }
                    if (decor != null) decor.postDelayed(this, 120);
                }
            }, 500);
        }
        com.wechatglass.liquid.LiquidPanelFactory.enableCaptureWallpaper(false);
        android.util.Log.i("WeChatGlass", "CB scope home page OFF");
        enableOverlay();
        final android.app.Activity act = activity;
        final ViewGroup ov = overlay;
        if (ov != null) {
            ov.post(new Runnable() {
                @Override public void run() {
                    View[] chips = {titleChip, searchChip, plusChip, scanChip};
                    View bg = categoryPage != null ? categoryPage : findVisibleList(decor);
                    if (bg == null) {
                        return;
                    }
                    for (View ch : chips) {
                        if (ch == null || ch.getWidth() <= 0 || ch.getHeight() <= 0) continue;
                        try {
                            if (bg instanceof ViewGroup) {
                                com.wechatglass.liquid.LiquidPanelFactory
                                        .setWallpaperEnabled((ViewGroup) bg, false);
                                android.util.Log.i("WeChatGlass", "CB scope home OFF "
                                        + bg.getClass().getSimpleName() + "@" + System.identityHashCode(bg));
                            }
                            View lp = com.wechatglass.liquid.LiquidPanelFactory.create(act,
                                    (ViewGroup) bg,
                                    act.getResources().getDisplayMetrics().density,
                                    Ui.isNight(act));
                            if (lp == null) continue;
                            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                                    ch.getWidth(), ch.getHeight());
                            flp.leftMargin = ch.getLeft();
                            flp.topMargin = ch.getTop();
                            ov.addView(lp, 0, flp);
                            android.util.Log.i("WeChatGlass", "HL liquid pill " + ch.getWidth() + "x" + ch.getHeight());
                        } catch (Throwable t) {
                            android.util.Log.e("WeChatGlass", "HL liquid pill fail", t);
                        }
                    }
                }
            });
        }
        forceContentBehindTop();
        setupList();
        // Resolve WeChat's list before anything that needs it: the custom page
        // anchors to that list, and the switch samples the page it sits on.
        resolveHomeList();
        if (!HookConfig.nativeList(activity)) {
            installCategoryPage();
        }
        installCategoryBar();
        // Self-built M3E pages for 通讯录 / 发现 / 我. They mount into each tab's
        // own page object and read the native list as their data source.
        pages = new M3PageHost(activity, pagerView);
        // The storage objects only work after MMApplication's kernel is up, so
        // the read-only category index is built a moment after the page shows.
        final ClassLoader indexLoader = activity.getClassLoader();
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ConversationIndex.load(indexLoader);
                    }
                }, "wxglass-index").start();
            }
        }, 2500L);
        if (list != null && list.getParent() instanceof ViewGroup) {
            renderer.setBackdrop((ViewGroup) list.getParent());
        }
        if (list instanceof ViewGroup && overlay != null
                && HookConfig.glassEnabled(activity)) {
            renderer.setLayerMode(true);
            boolean darkTheme = Ui.isNight(activity);
            if (titleView instanceof android.widget.TextView) {
                int tc = ((android.widget.TextView) titleView).getCurrentTextColor();
                darkTheme = (tc & 0xFFFFFF) > 0x999999;
            }
            final int tintColor = darkTheme ? 0x33000000 : 0x33FFFFFF;
            android.util.Log.i("WeChatGlass", "HL theme dark=" + darkTheme + " tint=" + Integer.toHexString(tintColor));
            final View[] blurChips = {titleChip, searchChip, plusChip, scanChip};
            overlay.post(new Runnable() {
                @Override public void run() {
                    boolean any = false;
                    for (View ch : blurChips) {
                        if (ch == null || ch.getWidth() <= 0 || ch.getHeight() <= 0) {
                            continue;
                        }
                        View bv = WeChatBlurBar.create(activity, (ViewGroup) decor,
                                ch.getWidth(), ch.getHeight(), tintColor);
                        if (bv == null) {
                            continue;
                        }
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                ch.getWidth(), ch.getHeight());
                        lp.leftMargin = ch.getLeft();
                        lp.topMargin = ch.getTop();
                        overlay.addView(bv, 0, lp);
                        any = true;
                    }
                    overlay.bringChildToFront(titleChip);
                    overlay.bringChildToFront(searchChip);
                    overlay.bringChildToFront(plusChip);
                    overlay.bringChildToFront(scanChip);
                    android.util.Log.i("WeChatGlass", "HL blur chips any=" + any);
                }
            });
        }

        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (renderer != null) {
                            renderer.requestUpdate();
                        }
                    }
                });

        installed = true;
        attachedAt = android.os.SystemClock.uptimeMillis();
        updateGeometry();
        lastGeometrySignature = geometrySignature();
        handler.postDelayed(tick, 600L);
        if (renderer != null) {
            renderer.requestUpdate();
        }
        applySystemBars();
        Log.i(TAG, "home glass attached");
        return true;
    }

    private void disableWindowContentOverlay() {
        try {
            View overlayLayout = Ui.findByClass(decor,
                    "androidx.appcompat.widget.ActionBarOverlayLayout");
            if (overlayLayout == null) return;
            java.lang.reflect.Field field = overlayLayout.getClass().getDeclaredField("i");
            field.setAccessible(true);
            field.set(overlayLayout, null);
            overlayLayout.setWillNotDraw(true);
            overlayLayout.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hide the 1px divider instead of detaching it. A GONE child contributes
     * nothing to LinearLayout measurement, so the visual result is identical
     * while WeChat keeps a valid view tree.
     */
    private void removeTaskBarLine() {
        try {
            View taskBar = Ui.findByClass(decor,
                    "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer");
            hideOnePixelLine(taskBar);
        } catch (Throwable ignored) {
        }
    }

    private boolean hideOnePixelLine(View view) {
        if (view == null) return false;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (view.getClass() == View.class && lp != null && lp.height <= 1
                && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
            if (lp.height != 0) {
                lp.height = 0;
                view.setLayoutParams(lp);
            }
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hideOnePixelLine(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Hide TaskBarContainer's fake top bar (cxw.xml) instead of removing it.
     * Its parent is a FrameLayout, which ignores GONE children, so the layout
     * is unchanged while TaskBarContainer.o keeps pointing at a live view.
     */
    private void removeTaskBarHeader() {
        try {
            View content = decor.findViewById(android.R.id.content);
            View taskBar = Ui.findByClass(decor,
                    "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer");
            if (taskBar == null && content != null) {
                View list = Ui.findByClassContains(content, "ConversationListView");
                if (list != null) {
                    Object value = getField(list, "i");
                    if (value instanceof View) {
                        taskBar = (View) value;
                    }
                }
            }
            if (taskBar == null) {
                return;
            }
            Object fake = getField(taskBar, "o");
            if (fake instanceof View) {
                View fakeBar = (View) fake;
                if (fakeBar.getVisibility() != View.GONE) {
                    fakeBar.setVisibility(View.GONE);
                }
                if (fakeBar.getAlpha() != 0f) {
                    fakeBar.setAlpha(0f);
                }
                ViewGroup.LayoutParams lp = fakeBar.getLayoutParams();
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    fakeBar.setLayoutParams(lp);
                }
            }
        } catch (Throwable throwable) {
            Log.w("WeChatGlassTaskBar", "hide header failed", throwable);
        }
    }

    private Object getField(Object target, String name) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void forceWrapperEtE() {
        EdgeForce.apply(activity);
    }

    private void setField(Object target, String name, Object value) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private void applyStatusBar() {
        applyStatusBar(true);
    }

    /**
     * @param resetBackgrounds true on install, where the page has to become
     *     full-bleed. On a later onResume this must stay false: WeChat repaints
     *     its own page background when the app comes back, and clearing it
     *     again exposes the window's dark background — which reads as the
     *     contacts page "switching to dark mode".
     */
    private void applyStatusBar(boolean resetBackgrounds) {
        try {
            Window window = activity.getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            if (resetBackgrounds) {
                decor.setBackgroundColor(Color.TRANSPARENT);
            }
            View overlayLayout = Ui.findByClass(decor,
                    "androidx.appcompat.widget.ActionBarOverlayLayout");
            if (overlayLayout != null) {
                overlayLayout.setBackground(null);
            }
            if (resetBackgrounds) {
                View content = decor.findViewById(android.R.id.content);
                if (content != null) {
                    content.setBackgroundColor(Color.TRANSPARENT);
                }
                if (frostedContent != null) {
                    frostedContent.setBackgroundColor(Color.TRANSPARENT);
                }
                if (list != null) {
                    list.setBackgroundColor(Color.TRANSPARENT);
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    controller.setSystemBarsAppearance(
                            Ui.isNight(activity) ? 0 : mask, mask);
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "status bar failed", throwable);
        }
    }

    private View findTitleView() {
        View text = toolbar.findViewById(android.R.id.title);
        if (text == null) {
            text = toolbar.findViewById(android.R.id.text1);
        }
        if (text != null) {
            return text;
        }
        return findText(toolbar, 0);
    }

    private View findText(View root, int depth) {
        if (root == null || depth > 4) {
            return null;
        }
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.length() > 0 && root.getVisibility() == View.VISIBLE) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private View findLeftView() {
        if (!(toolbar instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) toolbar;
        View best = null;
        int width = toolbar.getWidth();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            if (width <= 0 || child.getLeft() < width / 3) {
                if (best == null || child.getLeft() < best.getLeft()) {
                    best = child;
                }
            }
        }
        return best;
    }

    private View findRightView() {
        View menu = Ui.findByClass(toolbar, "androidx.appcompat.widget.ActionMenuView");
        if (menu != null && menu.getWidth() > 0) {
            return menu;
        }
        if (!(toolbar instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) toolbar;
        View best = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            if (best == null || child.getRight() > best.getRight()) {
                best = child;
            }
        }
        return best;
    }

    /**
     * WeChat's home list is ConversationListView (not a plain RecyclerView) and
     * the page is wrapped in a NestedScrollView. Both carry a 180px top padding
     * even when the window itself is already full-bleed.
     */
    private void clearTopPaddings(View view) {
        if (view == null) {
            return;
        }
        String name = view.getClass().getName();
        if (name.contains("ConversationListView")
                || name.contains("NestedScrollView")
                || name.contains("RecyclerView")
                || name.contains("ListView")) {
            if (view.getPaddingTop() != 0) {
                view.setPadding(view.getPaddingLeft(), 0,
                        view.getPaddingRight(), view.getPaddingBottom());
            }
            if (view instanceof ViewGroup) {
                ((ViewGroup) view).setClipToPadding(false);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearTopPaddings(group.getChildAt(i));
            }
        }
    }

    /**
     * Hide legacy ActionBar/Toolbar/ActionMenuView in place. GONE contributes
     * zero height to both FrameLayout and ActionBarOverlayLayout, so the layout
     * is identical to detaching them, but WeChat keeps a valid view tree.
     */
    private void hideLegacyBars(View view) {
        if (view == null) {
            return;
        }
        String name = view.getClass().getName();
        if (name.contains("ActionBarContainer")
                || name.endsWith(".Toolbar")
                || name.contains("ActionMenuView")) {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
            view.setAlpha(0f);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideLegacyBars(group.getChildAt(i));
            }
        }
    }

    private void applyListTopInset(View contentRoot) {
        int inset = listTopInset();
        // Locale-independent: the discover page owns a PullDownListView. The
        // old code keyed this off the Chinese title "发现", which never matched
        // on Japanese/English builds.
        View discover = Ui.findByClassContains(contentRoot, "PullDownListView");
        if (discover != null && discover.isShown()) {
            int top = Ui.statusBarHeight(decor) + Ui.dp(activity, 8f);
            if (discover.getPaddingTop() != top) {
                discover.setPadding(discover.getPaddingLeft(), top,
                        discover.getPaddingRight(), discover.getPaddingBottom());
            }
            return;
        }
        if (tabMode != 0) {
            return;
        }
        String[] classes = {"ConversationListView", "ConversationRecyclerView"};
        for (String name : classes) {
            View list = Ui.findByClassContains(contentRoot, name);
            if (list != null && list.getPaddingTop() != inset) {
                list.setPadding(list.getPaddingLeft(), inset,
                        list.getPaddingRight(), list.getPaddingBottom());
                if (list instanceof ViewGroup) {
                    ((ViewGroup) list).setClipToPadding(false);
                }
            }
        }
    }

    private void clearAncestorPaddingForLists(View contentRoot) {
        if (contentRoot == null) {
            return;
        }
        String[] classes = {
                "ConversationListView",
                "PullDownListView",
                "WxRecyclerView",
                "RecyclerView"
        };
        for (String className : classes) {
            View list = Ui.findByClassContains(contentRoot, className);
            if (list == null) {
                continue;
            }
            View current = list;
            while (current != null && current != contentRoot) {
                if (current.getPaddingTop() != 0) {
                    current.setPadding(current.getPaddingLeft(), 0,
                            current.getPaddingRight(), current.getPaddingBottom());
                }
                if (current instanceof ViewGroup) {
                    ((ViewGroup) current).setClipToPadding(false);
                }
                android.view.ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        }
    }

    private void addChip(ViewGroup parent, GlassChipView chip, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(height, height);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        parent.addView(chip, 0, params);
    }

    private Drawable copyIcon(View root, boolean night) {
        View image = findImage(root);
        if (!(image instanceof ImageView)) {
            return null;
        }
        Drawable drawable = ((ImageView) image).getDrawable();
        if (drawable == null) {
            return null;
        }
        drawable = drawable.mutate();
        if (Build.VERSION.SDK_INT >= 21) {
            drawable.setTint(night ? Color.WHITE : 0xFF1A1A1A);
        }
        return drawable;
    }

    private View findImage(View root) {
        if (root == null) {
            return null;
        }
        if (root instanceof ImageView) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findImage(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void clickThrough(GlassChipView chip, final View target) {
        if (target == null) {
            return;
        }
        chip.setClickable(true);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                target.performClick();
            }
        });
    }

    /** Applies a live GUI change (native list / glass / wallpaper). */
    private void applyConfig() {
        boolean nativeList = HookConfig.nativeList(activity);
        // A colour change replaces the M3 scheme; rebuild the page so every
        // row picks up the new surface/primary tokens immediately.
        if (categoryPage != null && categoryPage.getParent() instanceof ViewGroup) {
            ((ViewGroup) categoryPage.getParent()).removeView(categoryPage);
            categoryPage = null;
            categoryPageFilled = false;
            lastIndexVersion = -1;
        }
        if (!nativeList) {
            installCategoryPage();
        }
        // The glass backdrop differs between native and custom list, so the
        // switch is rebuilt to sample whichever list is on screen.
        if (categoryBar != null && overlay != null) {
            overlay.removeView(categoryBar);
            categoryBar = null;
        }
        installCategoryBar();
        if (categoryPage != null && ConversationIndex.loaded()) {
            categoryPage.show(HomeConversationFilter.mode());
        }
        updateTabState();
        if (renderer != null) {
            renderer.requestUpdate();
        }
        applySystemBars();
        applyBottomTabTheme(null, Math.max(0, bottomTab));
    }

    /** Status/navigation bars follow the same theme as the switcher pill. */
    private void applySystemBars() {
        if (activity == null) {
            return;
        }
        try {
            com.wechatglass.m3.M3ColorScheme scheme =
                    com.wechatglass.m3.M3Theme.colors(activity);
            int color = scheme.primary;
            android.view.Window window = activity.getWindow();
            window.addFlags(android.view.WindowManager.LayoutParams
                    .FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(android.view.WindowManager.LayoutParams
                    .FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);
            int flags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(scheme.dark ? 0 : flags);
        } catch (Throwable ignored) {
        }
    }

    private void updateTabState() {
        int tab = bottomTab;
        if (pagerView != null) {
            try {
                tab = (Integer) de.robv.android.xposed.XposedHelpers
                        .callMethod(pagerView, "getCurrentItem");
            } catch (Throwable ignored) {
            }
        }
        int mode;
        if (tab >= 0) {
            // 0 = Weixin, 1 = Discover, 2 = Contacts, 3 = Me. Only the Weixin
            // page keeps a floating bar.
            mode = tab == 0 ? 0 : 1;
        } else {
            String text = titleView instanceof TextView
                    ? String.valueOf(((TextView) titleView).getText()) : "";
            String t = text.trim();
            if (t.equals("我") || t.equals("自分")
                    || t.equalsIgnoreCase("Me") || t.equalsIgnoreCase("Mine")) {
                mode = 1;
            } else if (t.contains("通讯录") || t.contains("通訊錄") || t.contains("連絡先")
                    || t.equalsIgnoreCase("Contacts")
                    || t.contains("发现") || t.contains("發現") || t.contains("発見")
                    || t.equalsIgnoreCase("Discover")) {
                mode = 1;
            } else {
                mode = 0;
            }
        }

        tabMode = mode;
        if (pages != null) {
            pages.show(tab);
        }
        // Status bar: transparent on the home tab only; the guard also rewrites
        // WeChat's own repaint calls (HomeUI.x / ka.W0) so the strip cannot snap
        // back to #EDEDED.
        
        if (tab <= 0) {
            applyStatusBar(false);
            // MainUI keeps a status-bar-height mask at the page top; clearing
            // its fill is what actually makes the bar read as transparent.
            paintStatusStrip();
        }
        if (actionBar != null) {
            actionBar.setVisibility(View.GONE);
            actionBar.setAlpha(0f);
        }
        if (toolbar != null) {
            toolbar.setVisibility(View.GONE);
        }
        boolean full = mode == 0;
        if (categoryBar != null) {
            categoryBar.setVisibility(
                    full && HomeConversationFilter.active() ? View.VISIBLE : View.GONE);
        }
        boolean nativeList = HookConfig.nativeList(activity);
        if (categoryPage != null) {
            boolean showPage = full && !nativeList;
            categoryPage.setVisibility(showPage ? View.VISIBLE : View.GONE);
            if (showPage) {
                categoryPage.bringToFront();
            }
        }
        if (homeListView != null) {
            // Keep WeChat's list laid out and visible: hiding it made the
            // ViewPager recycle pages and glitch. Alpha 0 keeps that layout
            // alive while letting our page (which sits above it) show through.
            homeListView.setVisibility(View.VISIBLE);
            homeListView.setAlpha(full && !nativeList ? 0f : 1f);
        }
        setChipVisible(leftChip, false);
        setChipVisible(searchChip, full);
        setChipVisible(plusChip, full);
        setChipVisible(scanChip, full);
        setChipVisible(titleChip, false);
        if (overlay != null) {
            overlay.setVisibility(full && !miniProgramShown ? View.VISIBLE : View.GONE);
        }
        if (!full) {
            setFrostEnabled(false, 0);
        }
        if (list != null) {
            list.setPadding(list.getPaddingLeft(), listTopInset(),
                    list.getPaddingRight(), listBaseBottom);
        }
    }

    /**
     * WeChat's own frosted layer is kept disabled: the bars are solid now, so
     * enabling it would reintroduce the blur we are removing.
     */
    private void configureNativeFrost(int actionBarBottomOnScreen) {
        setFrostEnabled(false, 0);
    }

    private void setFrostEnabled(boolean enabled, int topHeight) {
        if (frostedContent == null) {
            return;
        }
        try {
            // Only toggle WeChat's frosted layer; no blur radius/area config.
            frostedContent.getClass().getMethod("setFrostedEnabled", boolean.class)
                    .invoke(frostedContent, enabled);
            frostedContent.invalidate();
        } catch (Throwable throwable) {
            Log.w("WeChatGlassFrost", "frost toggle failed", throwable);
        }
    }

    private String localizeTitle(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            java.util.Locale locale = activity.getResources()
                    .getConfiguration().getLocales().get(0);
            if ("ja".equals(locale.getLanguage())
                    && ("Weixin".equalsIgnoreCase(text) || "WeChat".equalsIgnoreCase(text))) {
                return "ウィーシン";
            }
        } catch (Throwable ignored) {
        }
        return text;
    }

    private void setChipVisible(View chip, boolean visible) {
        if (chip != null) {
            chip.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void enableOverlay() {
        if (overlaySet || actionBar == null) {
            return;
        }
        ViewParent parent = actionBar.getParent();
        while (parent instanceof View) {
            if (parent.getClass().getName().contains("ActionBarOverlayLayout")) {
                try {
                    Method method = parent.getClass().getMethod("setOverlayMode", boolean.class);
                    method.invoke(parent, true);
                    View overlay = (View) parent;
                    overlay.requestLayout();
                    overlay.requestApplyInsets();
                    overlaySet = true;
                } catch (Throwable ignored) {
                }
                break;
            }
            parent = parent.getParent();
        }
        View content = decor.findViewById(android.R.id.content);
        if (content != null) {
            content.setPadding(content.getPaddingLeft(), 0,
                    content.getPaddingRight(), content.getPaddingBottom());
        }
    }

    /** EtE content, while the list keeps a top height limit via padding. */
    private void forceContentBehindTop() {
        View content = decor.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) content;
        if (group.getChildCount() == 0) {
            return;
        }
        View root = group.getChildAt(0);
        if (root.getClass().getName().contains("ActionBarOverlayLayout")) {
            return;
        }
        int top = root.getTop();
        if (top > 0 && group.getHeight() > 0) {
            ViewGroup.LayoutParams params = root.getLayoutParams();
            if (params != null && params.height != group.getHeight()) {
                params.height = group.getHeight();
                root.setLayoutParams(params);
            }
            if (root.getTranslationY() != -top) {
                root.setTranslationY(-top);
            }
        } else if (top == 0 && root.getTranslationY() != 0f) {
            root.setTranslationY(0f);
        }
    }

    private int listTopInset() {
        if (tabMode == 1) {
            return Ui.statusBarHeight(decor) + actionBarHeight;
        }
        if (tabMode != 0) {
            return 0;
        }
        if (legacyTopInset > 0) {
            return Ui.statusBarHeight(decor) + Ui.dp(activity, 8f);
        }
        int status = Ui.statusBarHeight(decor);
        int bar = actionBar == null ? 0 : actionBar.getHeight();
        if (bar <= 0) {
            bar = actionBarHeight;
        }
        return status + bar;
    }

    private void resolveHomeList() {
        if (homeListView != null || decor == null) {
            return;
        }
        View found = findVisibleList(decor);
        if (found instanceof android.widget.ListView) {
            homeListView = (android.widget.ListView) found;
            HomeConversationFilter.attach(homeListView);
        }
    }

    private static ViewGroup findVisibleList(View root) {
        java.util.ArrayList<View> found = new java.util.ArrayList<>();
        collectLists(root, found);
        ViewGroup best = null;
        int bestX = Integer.MAX_VALUE;
        int[] loc = new int[2];
        for (int i = 0; i < found.size(); i++) {
            View view = found.get(i);
            // Do not require isShown(): when the custom page is mounted the
            // native list is INVISIBLE, but it is still the view that paints
            // the screen underneath and the one the glass must sample.
            if (!(view instanceof ViewGroup) || view.getWidth() <= 0
                    || view.getHeight() <= 0) {
                continue;
            }
            view.getLocationOnScreen(loc);
            if (Math.abs(loc[0]) < bestX) {
                bestX = Math.abs(loc[0]);
                best = (ViewGroup) view;
            }
        }
        return best;
    }

    private static void collectLists(View view, java.util.List<View> out) {
        if (view == null) {
            return;
        }
        String name = view.getClass().getName();
        if (name.contains("ConversationListView") || name.contains("WxRecyclerView")) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLists(group.getChildAt(i), out);
            }
        }
    }

    private void setupList() {
        View content = decor.findViewById(android.R.id.content);
        if (content == null) {
            content = decor;
        }
        list = Ui.findByClassContains(content, "RecyclerView");
        if (list == null) {
            return;
        }
        // Force a true full-bleed list: WeChat's fragments often set a top
        // padding equal to the ActionBar height.
        listBaseTop = 0;
        listBaseBottom = list.getPaddingBottom();
        if (list instanceof ViewGroup) {
            ((ViewGroup) list).setClipToPadding(false);
        }
        if (list != null) {
            int[] ll = new int[2];
            list.getLocationOnScreen(ll);
            android.util.Log.i("WeChatGlass", "HL geom listTop=" + ll[1]
                    + " listH=" + list.getHeight()
                    + " padTop=" + list.getPaddingTop()
                    + " clipPad=" + ((ViewGroup) list).getClipToPadding()
                    + " overlayTop=" + (overlay == null ? -1 : overlay.getTop()));
        }
        list.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX,
                                       int oldScrollY) {
                if (renderer != null) {
                    renderer.requestUpdate();
                }
                if (glassLayer != null) {
                    glassLayer.invalidate();
                    if (++scrollLogCount % 30 == 0) {
                        android.util.Log.i("WeChatGlass", "HL scroll invalidate n=" + scrollLogCount);
                    }
                }
            }
        });
    }

    /**
     * Adds the 好友 / 群聊 / 微信 switch at the left end of the top bar. It is
     * created after setupList() so the liquid-glass capsule can sample the
     * conversation list that actually sits behind it.
     */
    private void installCategoryBar() {
        if (overlay == null || activity == null) {
            return;
        }
        if (categoryBar != null && categoryBar.getParent() == overlay) {
            return;
        }
        // The native list is hidden behind our page, so sampling it yields a
        // blank surface. When the custom page exists, the glass must sample it.
        View nativeList = findVisibleList(decor);
        if (!(nativeList instanceof android.widget.ListView) && homeListView != null) {
            nativeList = homeListView;
        }
        if (nativeList instanceof android.widget.ListView) {
            homeListView = (android.widget.ListView) nativeList;
            HomeConversationFilter.attach(homeListView);
        }
        resolveHomeList();
        if (nativeList == null) {
            nativeList = homeListView;
        }
        boolean nativeMode = HookConfig.nativeList(activity);
        // Sample the container that actually paints the list (the list's
        // parent); sampling a bare ListView sometimes yields an empty capture,
        // which looked like the switch had lost its glass.
        ViewGroup backdrop = null;
        if (nativeMode) {
            if (nativeList != null && nativeList.getParent() instanceof ViewGroup) {
                backdrop = (ViewGroup) nativeList.getParent();
            } else if (nativeList instanceof ViewGroup) {
                backdrop = (ViewGroup) nativeList;
            }
        } else {
            backdrop = categoryPage;
        }
        android.util.Log.i("WeChatGlass", "HL bar backdrop="
                + (backdrop == null ? "null" : backdrop.getClass().getSimpleName()));
        if (backdrop == null) {
            // The list is not laid out yet; retry instead of building a bar
            // without glass (that is what made the switch look glass-less).
            if (barAttempts++ < 15) {
                overlay.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        installCategoryBar();
                    }
                }, 150L);
            }
            return;
        }
        categoryBar = new HomeCategoryBar(activity, backdrop, Ui.isNight(activity),
                categoryLabels(), HomeConversationFilter.mode());
        categoryBar.setOnSelect(new HomeCategoryBar.OnSelect() {
            @Override
            public void onSelect(int mode) {
                HomeConversationFilter.setMode(mode);
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(activity, HomeCategoryBar.HEIGHT_DP));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = Ui.dp(activity, 16f);
        // Same top as the search / plus chips so the capsule is exactly level
        // with them even before the first layout pass.
        params.topMargin = Math.max(Ui.dp(activity, 4f),
                Ui.statusBarHeight(decor) + Ui.dp(activity, 4f));
        overlay.addView(categoryBar, params);
        android.util.Log.i("WeChatGlass", "HL category bar installed");
        HomeConversationFilter.refresh();
    }

    private boolean categoryPageFilled;
    private int lastIndexVersion = -1;
    private long lastIndexRefresh;
    private long lastWalletScan;
    private int tickCount;
    private static java.lang.ref.WeakReference<Object> bottomTabView;

    /**
     * Places our own conversation list on top of WeChat's list, at the same
     * bounds. WeChat's list is only made invisible, never detached, so its
     * adapter, scroll state and pull-down panel stay intact.
     */
    private void installCategoryPage() {
        if (homeListView == null || !(homeListView.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) homeListView.getParent();
        int index = parent.indexOfChild(homeListView);
        int status = Math.max(Ui.statusBarHeight(decor), Ui.statusBarHeight(activity));
        // Blank strip at the top of the custom list, sized for the floating
        // top bar (status bar + 40dp bar + 8dp breathing room).
        int top = status + Ui.dp(activity, 52f);
        int bottom = Ui.dp(activity, 92f);
        categoryPage = new HomeCategoryPage(activity, Ui.isNight(activity), top, bottom,
                new HomeCategoryPage.OpenChat() {
                    @Override
                    public void open(String username) {
                        openChat(username);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        triggerNativePullDown();
                    }
                },
                new HomeCategoryPage.OpenAction() {
                    @Override
                    public void open(String action) {
                        openEntry(action);
                    }
                },
                new HomeCategoryPage.LongPress() {
                    @Override
                    public boolean open(String username) {
                        return openNativeConversationMenu(username);
                    }
                });
        parent.addView(categoryPage, index + 1,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        homeListView.setVisibility(View.INVISIBLE);
        android.util.Log.i("WeChatGlass", "HL category page installed top=" + top
                + " status=" + status + " bottom=" + bottom);
    }

    /** Opens the WeChat page behind one of the top entry rows. */
    private void openEntry(String action) {
        if ("note".equals(action)) {
            if (nativeNoteRow != null) {
                Log.i(TAG, "HL note click row=" + nativeNoteRow.getClass().getName()
                        + " clickable=" + nativeNoteRow.isClickable());
                boolean handled = nativeNoteRow.callOnClick();
                if (!handled) {
                    nativeNoteRow.performClick();
                }
            } else {
                Log.i(TAG, "HL note row not resolved; native note entry unavailable");
            }
            return;
        }
        String className;
        if ("weapp".equals(action)) {
            className = "com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI";
        } else {
            className = "com.tencent.mm.plugin.webwx.ui.WebWXLogoutUI";
        }
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(activity, className);
            if ("weapp".equals(action)) {
                // Same scene the app itself uses when opening the launcher.
                intent.putExtra("extra_enter_scene", 11);
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            Log.e(TAG, "open entry failed: " + action, throwable);
        }
    }

    private View nativeDeviceRow;
    private View nativeNoteRow;
    private String nativeDeviceText;
    private String nativeNoteText;
    private int nativeRowTries;

    /**
     * Reuses WeChat's own header rows: {@code xq4.q} is the logged-in-device
     * line, and the pinned-note line is the other xq4 row that carries a text.
     * We only read their text and forward clicks, so behaviour stays native.
     */
    private void scanNativeRows() {
        if (decor == null || nativeRowTries > 10) {
            return;
        }
        nativeRowTries++;
        if (nativeRowTries == 1) {
            logXq4Rows(decor);
        }
        View device = findXq4(decor, "xq4.q");
        if (device != null) {
            nativeDeviceRow = device;
            String text = firstText(device);
            if (text != null && text.length() > 0) {
                nativeDeviceText = text;
            }
        }
        View note = findXq4Note(decor);
        if (note == null) {
            note = findClickableByDesc(decor, "メモ", "ノート", "笔记");
        }
        if (note == null) {
            note = findClickableByText(decor, "置顶笔记");
        }
        if (note != null) {
            nativeNoteRow = note;
            String text = firstText(note);
            if (text != null && text.length() > 0 && !"置顶笔记".equals(text)) {
                nativeNoteText = text;
            }
            // The note row exposes "メモ,<first line>" as its content
            // description; prefer that so the row shows the actual note.
            CharSequence desc = note.getContentDescription();
            if (desc != null) {
                String d = desc.toString();
                int comma = d.indexOf(',');
                if (comma >= 0 && comma + 1 < d.length()) {
                    nativeNoteText = d.substring(comma + 1).trim();
                } else if (d.length() > 2 && !d.contains("ピン留め")) {
                    nativeNoteText = d;
                }
            }
        }
        android.util.Log.i("WeChatGlass", "HL native rows device=" + nativeDeviceText
                + " note=" + nativeNoteText);
        if (categoryPage != null) {
            categoryPage.setHeaderTexts(nativeDeviceText, nativeNoteText);
        }
    }

    private static View findViewPager(View root) {
        if (root == null) {
            return null;
        }
        if (root.getClass().getName().contains("ViewPager")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewPager(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void logXq4Rows(View root) {
        if (root == null) {
            return;
        }
        String name = root.getClass().getName();
        if (name.startsWith("xq4.")) {
            android.util.Log.i("WeChatGlass", "HL xq4 " + name + " => " + firstText(root));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                logXq4Rows(group.getChildAt(i));
            }
        }
    }

    private View findXq4(View root, String exact) {
        if (root == null) {
            return null;
        }
        if (exact.equals(root.getClass().getName())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findXq4(group.getChildAt(i), exact);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private View findXq4Note(View root) {
        if (root == null) {
            return null;
        }
        String name = root.getClass().getName();
        if (name.startsWith("xq4.") && !"xq4.q".equals(name)
                && !"xq4.n0".equals(name) && !"xq4.m".equals(name)) {
            String text = firstText(root);
            if (text != null && text.length() > 2
                    && !text.contains("最近") && !text.contains("常用")) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findXq4Note(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds the clickable row whose content description names a note. */
    private View findClickableByDesc(View root, String... needles) {
        if (root == null || root == categoryPage) {
            return null;
        }
        CharSequence desc = root.getContentDescription();
        if (desc != null) {
            String d = desc.toString();
            for (String needle : needles) {
                if (d.contains(needle)) {
                    View cur = root;
                    for (int i = 0; i < 5 && cur != null; i++) {
                        if (cur.isClickable()) {
                            return cur;
                        }
                        cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                    }
                    return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableByDesc(group.getChildAt(i), needles);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds the clickable row around a label, skipping our own overlay page. */
    private View findClickableByText(View root, String needle) {
        if (root == null || root == categoryPage) {
            return null;
        }
        if (root instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) root).getText();
            if (text != null && text.toString().contains(needle)) {
                View cur = root;
                for (int i = 0; i < 5 && cur != null; i++) {
                    if (cur.isClickable()) {
                        return cur;
                    }
                    cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                }
                return root;
            }
        }
        CharSequence desc = root.getContentDescription();
        if (desc != null && desc.toString().contains(needle)) {
            View cur = root;
            for (int i = 0; i < 5 && cur != null; i++) {
                if (cur.isClickable()) {
                    return cur;
                }
                cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            }
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableByText(group.getChildAt(i), needle);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String firstText(View root) {
        if (root == null) {
            return null;
        }
        if (root instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) root).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = firstText(group.getChildAt(i));
                if (text != null && text.length() > 0) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * Long-press on our own row forwards to WeChat's conversation menu: fill the
     * native position for that username, then call the list's own long-click
     * listener. No database writes by us.
     */
    private boolean openNativeConversationMenu(String username) {
        try {
            if (!(homeListView instanceof android.widget.AbsListView)) {
                return false;
            }
            android.widget.AbsListView list = homeListView;
            android.widget.ListAdapter adapter = list.getAdapter();
            if (adapter == null) {
                return false;
            }
            int position = -1;
            for (int i = 0; i < adapter.getCount(); i++) {
                if (username.equals(HomeConversationFilter.talkerOf(adapter, i))) {
                    position = i;
                    break;
                }
            }
            if (position < 0) {
                Log.i(TAG, "no native row for " + username);
                return false;
            }
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            if (row == null) {
                list.setSelection(position);
                row = list.getChildAt(position - list.getFirstVisiblePosition());
            }
            Object listener = readObjectField(list, "mOnItemLongClickListener");
            if (listener instanceof android.widget.AdapterView.OnItemLongClickListener) {
                return ((android.widget.AdapterView.OnItemLongClickListener) listener)
                        .onItemLongClick(list, row, position, adapter.getItemId(position));
            }
            Log.i(TAG, "no native long-click listener");
            return false;
        } catch (Throwable throwable) {
            Log.w(TAG, "native menu failed", throwable);
            return false;
        }
    }

    private static Object readObjectField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
    private void openChat(String username) {        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(activity, "com.tencent.mm.ui.chatting.ChattingUI");
            intent.putExtra("Chat_User", username);
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            Log.e(TAG, "open chat failed: " + username, throwable);
        }
    }

    /**
     * Feeds a synthetic pull-down to WeChat's own conversation list so its
     * TaskBar (mini-program panel with the logged-in-device and pinned-note
     * rows) slides in by itself. The native list is invisible behind our page
     * but its touch pipeline still runs.
     */
    private long lastPullDownAt;

    private void triggerNativePullDown() {
        if (homeListView == null || decor == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPullDownAt < 1200L) {
            return;
        }
        lastPullDownAt = now;
        final View target = homeListView.getParent() instanceof View
                ? (View) homeListView.getParent() : homeListView;
        // Post instead of dispatching inside our own touch callback: WeChat's
        // pull-down runs a layout/animation pass, and doing that re-entrantly
        // froze the UI for a moment.
        decor.post(new Runnable() {
            @Override
            public void run() {
                try {
                    float x = target.getWidth() / 2f;
                    float y0 = Ui.dp(activity, 90f);
                    float y1 = y0 + Ui.dp(activity, 460f);
                    long start = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(
                            start, start, android.view.MotionEvent.ACTION_DOWN, x, y0, 0);
                    android.view.MotionEvent move = android.view.MotionEvent.obtain(
                            start, start + 80, android.view.MotionEvent.ACTION_MOVE, x, y1, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(
                            start, start + 160, android.view.MotionEvent.ACTION_UP, x, y1, 0);
                    target.dispatchTouchEvent(down);
                    target.dispatchTouchEvent(move);
                    target.dispatchTouchEvent(up);
                    down.recycle();
                    move.recycle();
                    up.recycle();
                    android.util.Log.i("WeChatGlass", "HL pull-down injected");
                } catch (Throwable throwable) {
                    Log.e(TAG, "pull-down injection failed", throwable);
                }
            }
        });
    }

    private int headerTextTries;

    /**
     * Reads the live rows of WeChat's own TaskBar header: the device-login
     * status line and the pinned-note line, so our page can show their real
     * text instead of a static label.
     */
    private void refreshHeaderTexts() {
        if (categoryPage == null || homeListView == null || headerTextTries > 8) {
            return;
        }
        headerTextTries++;
        try {
            Object adapter = homeListView.getAdapter();
            if (!(adapter instanceof android.widget.HeaderViewListAdapter)) {
                return;
            }
            View header = ((android.widget.HeaderViewListAdapter) adapter)
                    .getView(0, null, homeListView);
            if (header == null) {
                return;
            }
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts(header, texts, 0);
            if (texts.isEmpty()) {
                return;
            }
            String device = null;
            for (String text : texts) {
                if (text.contains("ログイン") || text.contains("登录") || text.contains("iPad")
                        || text.contains("Web")) {
                    device = text;
                    break;
                }
            }
            String note = null;
            if (device != null) {
                int index = texts.indexOf(device);
                if (index >= 0 && index + 1 < texts.size()) {
                    note = texts.get(index + 1);
                }
            }
            android.util.Log.i("WeChatGlass", "HL header device=" + device
                    + " note=" + note + " texts=" + texts);
            categoryPage.setHeaderTexts(device, note);
        } catch (Throwable throwable) {
            Log.w(TAG, "header texts failed", throwable);
        }
    }

    private void collectTexts(View view, java.util.List<String> out, int depth) {
        if (view == null || depth > 12) {
            return;
        }
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0) {
                out.add(text.toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTexts(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private String[] categoryLabels() {
        String language = "en";
        try {
            language = activity.getResources().getConfiguration().getLocales().get(0)
                    .getLanguage();
        } catch (Throwable ignored) {
        }
        if ("ja".equals(language)) {
            return new String[]{"友だち", "グループ", "ウィーシン"};
        }
        if ("zh".equals(language)) {
            return new String[]{"好友", "群聊", "微信"};
        }
        return new String[]{"Friends", "Groups", "Weixin"};
    }

    private boolean isNonHomeSurface() {
        if (decor == null) {
            return true;
        }
        String[] markers = {"AppBrandDesktopView", "AppBrandDesktopContainerView"};
        for (String marker : markers) {
            View view = Ui.findByClassContains(decor, marker);
            if (view != null && view.getVisibility() == View.VISIBLE
                    && view.getWidth() > 0 && view.getHeight() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * WeChat's home list can be pulled down into the mini-program / TaskBar
     * panel; that panel is the list's first header. While it is on screen the
     * floating top bar must stay out of the way, otherwise it draws over the
     * panel and the two fight over the same rows.
     */
    private boolean isMiniProgramSurface() {
        if (decor == null) {
            return false;
        }
        if (taskBarContainer == null || !taskBarContainer.isAttachedToWindow()) {
            taskBarContainer = Ui.findByClassContains(decor, "TaskBarContainer");
        }
        if (taskBarContainer != null && taskBarContainer.isShown()) {
            int[] loc = new int[2];
            taskBarContainer.getLocationOnScreen(loc);
            int screenHeight = decor.getHeight();
            // While the conversation list is showing, WeChat parks this whole
            // panel above the window (measured top ≈ -2141 on a 2400px screen);
            // it slides to exactly 0 when the mini-program page is fully open.
            // Require a full-height, fully-entered panel; a partially pulled
            // header (or merely scrolling to the top of the chat list) must not
            // hide the bar.
            return screenHeight > 0
                    && loc[1] >= -2
                    && taskBarContainer.getHeight() > screenHeight * 0.9f;
        }
        return false;
    }

    private int taskBarTop() {
        if (taskBarContainer == null || !taskBarContainer.isShown()) {
            return Integer.MIN_VALUE;
        }
        int[] loc = new int[2];
        taskBarContainer.getLocationOnScreen(loc);
        return loc[1];
    }

    /**
     * Only 首页 / 通讯录 / 发现 keep our floating bar. Anything else (服务号,
     * 朋友圈, WebView, sub-activities sharing LauncherUI) stays native.
     */
    private boolean isSupportedPage(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        return "Weixin".equalsIgnoreCase(t)
                || "WeChat".equalsIgnoreCase(t)
                || "微信".equals(t)
                || t.contains("通讯录") || t.contains("通訊錄") || t.contains("連絡先")
                || "Contacts".equalsIgnoreCase(t)
                || t.contains("发现") || t.contains("發現") || t.contains("発見")
                || "Discover".equalsIgnoreCase(t);
    }

    private void switchToNativePage() {
        nativeBarAllowed = true;
        if (overlay != null && overlay.getVisibility() != View.GONE) {
            overlay.setVisibility(View.GONE);
        }
        if (actionBar != null) {
            if (actionBar.getAlpha() != 1f) {
                actionBar.setAlpha(1f);
            }
            if (actionBar.getVisibility() != View.VISIBLE) {
                actionBar.setVisibility(View.VISIBLE);
            }
        }
        if (toolbar != null) {
            if (toolbar.getAlpha() != 1f) {
                toolbar.setAlpha(1f);
            }
            if (toolbar.getVisibility() != View.VISIBLE) {
                toolbar.setVisibility(View.VISIBLE);
            }
        }
    }

    private static final String[] EXCLUDED_SURFACES = {
            "brandservice", "serviceaccount", "sns", "webview", "appbrand"
    };

    /**
     * Second-level pages (服务号 / 朋友圈 / 小程序 / WebView) are detected
     * structurally: if any visible view carries one of these class names, the
     * floating bar must not run.
     */
    private boolean hasVisibleExcludedSurface(View root) {
        if (root == null) {
            return false;
        }
        if (root.isShown() && root.getWidth() > 0) {
            String name = root.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            for (String part : EXCLUDED_SURFACES) {
                if (name.contains(part)) {
                    return true;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleExcludedSurface(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateGeometry() {
        if (!installed && renderer == null) {
            return;
        }
        forceContentBehindTop();
        View contentRoot = decor.findViewById(android.R.id.content);
        clearTopPaddings(contentRoot);
        clearAncestorPaddingForLists(contentRoot);
        View freshTitle = findTitleView();
        if (freshTitle != null) {
            titleView = freshTitle;
        }
        applyListTopInset(contentRoot);
        hideLegacyBars(decor);
        removeTaskBarLine();
        removeTaskBarHeader();
        forceWrapperEtE();
        updateTabState();
        if (tabMode == 2 || actionBar == null) {
            return;
        }
        int width = actionBar.getWidth();
        if (width <= 0) {
            width = decor.getWidth();
        }
        int height = actionBar.getHeight();
        if (height > 0) {
            actionBarHeight = height;
        } else {
            height = actionBarHeight;
        }
        int chipHeight = Ui.dp(activity, 40f);
        int[] abLoc = new int[2];
        int[] childLoc = new int[2];
        actionBar.getLocationOnScreen(abLoc);
        configureNativeFrost(abLoc[1] + height);
        // Status insets are intentionally consumed globally, so query the
        // physical status bar height and keep the islands below it.
        int status = Math.max(Ui.statusBarHeight(decor), Ui.dp(activity, 24f));
        // The overlay now lives at the decor top (translation 0), so the
        // islands only have to clear the status bar. The old formula subtracted
        // the ActionBar's screen y — that view is GONE and WeChat can lay it
        // out at the bottom, which dragged the whole bar off-screen.
        int top = Math.max(Ui.dp(activity, 4f), status + Ui.dp(activity, 4f));
        top = Math.min(top, Math.max(0, height - chipHeight - Ui.dp(activity, 2f)));

        if (overlay != null) {
            int overlayHeight = Math.max(height,
                    top + chipHeight + Ui.dp(activity, 4f));
            ViewGroup.LayoutParams op = overlay.getLayoutParams();
            if (op != null && op.height != overlayHeight) {
                op.height = overlayHeight;
                overlay.setLayoutParams(op);
            }
            if (overlay.getTranslationY() != 0f) {
                overlay.setTranslationY(0f);
            }
            // HomeUI can add its FrostedContentView after our overlay, which
            // would cover the chips. Keep the overlay as the topmost decor
            // child — but only reorder when it actually is not on top, so a
            // geometry pass does not force a re-layout every time.
            ViewParent parent = overlay.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                if (group.getChildAt(group.getChildCount() - 1) != overlay) {
                    overlay.bringToFront();
                }
            }
        }

        if (actionBar != null) {
            actionBar.setAlpha(0f);
            if (tabMode != 2) {
                actionBar.setVisibility(View.GONE);
            }
        }
        if (toolbar != null && tabMode != 2) {
            toolbar.setVisibility(View.GONE);
        }

        // Right-hand side: plus, search, then the title. With the category
        // switch on the left, the title gets whatever room is left.
        int plusX = width - chipHeight - Ui.dp(activity, 8f);
        int searchX = plusX - chipHeight - Ui.dp(activity, 8f);
        // Scan sits at the left end of the right-hand group: scan | search | plus.
        int scanX = searchX - chipHeight - Ui.dp(activity, 8f);

        boolean withSwitch = categoryBar != null && tabMode == 0
                && categoryBar.getVisibility() == View.VISIBLE;
        int titleX;
        int titleW;
        if (withSwitch) {
            categoryBar.setNight(Ui.isNight(activity));
            categoryBar.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(chipHeight, View.MeasureSpec.EXACTLY));
            int switchW = categoryBar.getMeasuredWidth();
            if (switchW <= 0) {
                switchW = Ui.dp(activity, 160f);
            }
            int switchX = Ui.dp(activity, 20f);
            int available = searchX - switchX - Ui.dp(activity, 8f);
            switchW = Math.min(switchW, Math.max(Ui.dp(activity, 160f), available));
            FrameLayout.LayoutParams cp =
                    (FrameLayout.LayoutParams) categoryBar.getLayoutParams();
            if (cp == null) {
                cp = new FrameLayout.LayoutParams(switchW, chipHeight);
                cp.gravity = Gravity.TOP | Gravity.LEFT;
            }
            boolean changed = cp.width != switchW || cp.height != chipHeight
                    || cp.leftMargin != switchX
                    || cp.topMargin != top;
            cp.width = switchW;
            cp.height = chipHeight;
            cp.leftMargin = switchX;
            cp.topMargin = top;
            if (changed) {
                categoryBar.setLayoutParams(cp);
            }
            titleX = switchX + switchW + Ui.dp(activity, 8f);
            titleW = Math.min(searchX - Ui.dp(activity, 6f) - titleX,
                    Ui.dp(activity, 140f));
            if (titleW < Ui.dp(activity, 52f)) {
                titleW = Ui.dp(activity, 52f);
            }
        } else {
            if (titleView != null && titleView.getWidth() > 0) {
                titleView.getLocationOnScreen(childLoc);
                int rawX = childLoc[0] - abLoc[0];
                int rawW = titleView.getWidth();
                titleW = Math.max(Ui.dp(activity, 110f), rawW + Ui.dp(activity, 30f));
                titleX = rawX + rawW / 2 - titleW / 2;
            } else {
                titleW = Ui.dp(activity, 150f);
                titleX = (width - titleW) / 2;
            }
            titleX = clamp(titleX, Ui.dp(activity, 8f),
                    width - titleW - Ui.dp(activity, 4f));
            int maxTitle = searchX - Ui.dp(activity, 4f) - titleX;
            if (titleW > maxTitle) {
                titleW = Math.max(Ui.dp(activity, 80f), maxTitle);
            }
        }

        applyChip(titleChip, titleX, top, titleW, chipHeight, Ui.dp(activity, 20f));
        applyChip(searchChip, searchX, top, chipHeight, chipHeight, chipHeight / 2f);
        applyChip(plusChip, plusX, top, chipHeight, chipHeight, chipHeight / 2f);
        applyChip(scanChip, scanX, top, chipHeight, chipHeight, chipHeight / 2f);
        if (DEBUG_HIDE_OVERLAY && overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    /** Scan glyph drawn in code: no resources needed inside WeChat's process. */
    private android.graphics.drawable.Drawable scanIcon(boolean night) {
        int size = Ui.dp(activity, 22f);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, size / 11f));
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setColor(night ? 0xFFF2F2F2 : 0xFF1A1A1A);
        float inset = size * 0.16f;
        float len = size * 0.24f;
        float left = inset;
        float top = inset;
        float right = size - inset;
        float bottom = size - inset;
        canvas.drawLine(left, top, left + len, top, paint);
        canvas.drawLine(left, top, left, top + len, paint);
        canvas.drawLine(right - len, top, right, top, paint);
        canvas.drawLine(right, top, right, top + len, paint);
        canvas.drawLine(left, bottom - len, left, bottom, paint);
        canvas.drawLine(left, bottom, left + len, bottom, paint);
        canvas.drawLine(right, bottom - len, right, bottom, paint);
        canvas.drawLine(right - len, bottom, right, bottom, paint);
        canvas.drawLine(left + len * 0.4f, size / 2f, right - len * 0.4f, size / 2f, paint);
        return new android.graphics.drawable.BitmapDrawable(activity.getResources(), bitmap);
    }

    /** Opens WeChat's scanner page through its own activity. */
    private void openScan() {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(activity, "com.tencent.mm.plugin.scanner.ui.BaseScanUI");
            intent.putExtra("key_scan_scene", 0);
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            Log.w(TAG, "open scan failed", throwable);
            try {
                if (plusView != null) {
                    plusView.callOnClick();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyChip(GlassChipView chip, int x, int y, int width, int height, float radius) {
        if (chip == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) chip.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(width, height);
            params.gravity = Gravity.TOP | Gravity.LEFT;
        }
        boolean changed = params.width != width || params.height != height
                || params.leftMargin != x || params.topMargin != y;
        params.width = width;
        params.height = height;
        params.leftMargin = x;
        params.topMargin = y;
        if (changed) {
            chip.setLayoutParams(params);
        }
        chip.setGlass(radius, tint, border);
    }

    private void destroy() {
        installed = false;
        handler.removeCallbacksAndMessages(null);
        if (pages != null) {
            pages.destroy();
            pages = null;
        }
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
        categoryBar = null;
        homeListView = null;
        HomeConversationFilter.detach();
        if (actionBar != null) {
            actionBar.setVisibility(View.VISIBLE);
            actionBar.setAlpha(1f);
        }
        if (toolbar != null && toolbar != actionBar) {
            toolbar.setVisibility(View.VISIBLE);
            toolbar.setAlpha(1f);
        }
        if (list != null) {
            list.setPadding(list.getPaddingLeft(), listTopInset(),
                    list.getPaddingRight(), listBaseBottom);
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }
}

