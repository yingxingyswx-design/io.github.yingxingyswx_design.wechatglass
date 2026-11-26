package com.wechatglass;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-ChattingUI controller.
 *
 * The chat is restructured into a real edge-to-edge screen:
 *   - ActionBar is moved out of the vertical layout and overlaid on the chat
 *     content frame.
 *   - ChatFooter is kept in ChattingScrollLayout but its top margin is made
 *     negative by its own height, so the message list gets the full height and
 *     the footer draws on top of it.
 *   - The RecyclerView gets top/bottom padding so messages do not hide behind
 *     either floating bar.
 *
 * Only view backgrounds, padding, margins and drawing are touched. No message,
 * network, account or payment classes are referenced.
 */
final class ChatGlassHook {

    private static final String TAG = "WeChatGlass";

    // Resource ids from WeChat 8.0.79. Used as hints; every lookup is validated
    // by view class before use.
    private static final int ID_ACTION_BAR_CONTAINER = 0x7f0a00d3;
    private static final int ID_ACTION_BAR_ROOT = 0x7f0a00d8;
    private static final int ID_BACK = 0x7f0a0157;
    private static final int ID_BACK_BTN = 0x7f0a0158;
    private static final int ID_TITLE = 0x7f0a6b98;
    private static final int ID_MENU = 0x7f0a184a;
    private static final int ID_MENU_INNER = 0x7f0a1849;
    private static final int ID_FOOTER = 0x7f0a4d21;
    private static final int ID_FOOTER_BOTTOM = 0x7f0a0f7e;
    private static final int ID_FOOTER_PANEL = 0x7f0a0f08;

    private static final Map<Activity, ChatGlassHook> INSTANCES = new WeakHashMap<>();

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int tint;
    private final int border;
    private final int chipTint;
    private final int chipBorder;

    private GlassRenderer renderer;
    private View decor;
    private View actionBar;
    private View toolbar;
    private View backView;
    private View titleView;
    private View menuView;
    private View footer;
    private View footerPanel;
    private View footerBottom;

    private View chatRoot;
    private View navFiller;
    private View statusScrim;
    private int normalPanelBaseHeight = -1;
    private int normalPanelParentBaseHeight = -1;
    private boolean normalPanelShrunk;
    private ViewGroup contentFrame;
    private View scrollLayout;
    private boolean footerGlassSet;
    private boolean tickErrorLogged;
    private View list;

    private int listBaseLeft;
    private int listBaseTop;
    private int listBaseRight;
    private int listBaseBottom;
    private boolean listBaseSaved;
    private int appliedTopPad = -1;
    private int appliedBottomPad = -1;

    private GlassChipView backChip;
    private GlassChipView titleChip;
    private GlassChipView menuChip;

    private boolean attached;
    private boolean supportActionBar;
    private int attempts;
    private int lastFooterHeight = -1;
    private int footerOriginalBottomMargin = Integer.MIN_VALUE;
    private int lastVisibleFooterHeight = -1;
    private boolean supportActionBarOverlaySet;

    // Original host of the reparented ActionBar, so destroy() can put it back.
    private ViewGroup actionBarOriginalParent;
    private int actionBarOriginalIndex = -1;
    private ViewGroup.LayoutParams actionBarOriginalParams;

    private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;
    private ViewTreeObserver.OnScrollChangedListener scrollListener;
    private long lastGeometrySignature = Long.MIN_VALUE;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            // Footer geometry depends on WeChat's own re-measure; keep the
            // 600ms refresh here so the input bar never drifts off-screen.
            try {
                applyEdgeLayout();
                updateTopGeometry();
            } catch (Throwable throwable) {
                // Keep the upkeep alive: one bad traversal used to end this
                // runnable for the rest of the chat session, because the
                // postDelayed below never ran. The panels then stopped being
                // styled and read as "glass works once, then stops".
                if (!tickErrorLogged) {
                    tickErrorLogged = true;
                    Log.e(TAG, "tick failed", throwable);
                }
            }
            handler.postDelayed(this, 600L);
        }
    };

    private void maybeUpdateGeometry() {
        long signature = geometrySignature();
        if (signature == lastGeometrySignature) {
            return;
        }
        lastGeometrySignature = signature;
        applyEdgeLayout();
        updateTopGeometry();
    }

    private long geometrySignature() {
        int decorWidth = decor == null ? 0 : decor.getWidth();
        int decorHeight = decor == null ? 0 : decor.getHeight();
        int barWidth = actionBar == null ? 0 : actionBar.getWidth();
        int barHeight = actionBar == null ? 0 : actionBar.getHeight();
        int barTop = actionBar == null ? 0 : actionBar.getTop();
        int footerHeight = footer == null ? 0 : footer.getHeight();
        int listPadding = list == null ? 0 : list.getPaddingTop();
        long signature = decorWidth;
        signature = signature * 31 + decorHeight;
        signature = signature * 31 + barWidth;
        signature = signature * 31 + barHeight;
        signature = signature * 31 + barTop;
        signature = signature * 31 + footerHeight;
        signature = signature * 31 + listPadding;
        return signature;
    }

    private ChatGlassHook(Activity activity) {
        this.activity = activity;
        boolean night = Ui.isNight(activity);
        // Solid WeChat white / dark grey. Opaque on purpose: the blur path is
        // gone, so every surface is a flat fill instead of a translucent tint.
        this.tint = night ? 0xFF2C2C2C : 0xFFFFFFFF;
        this.border = night ? 0x1AFFFFFF : 0x1A000000;
        this.chipTint = this.tint;
        this.chipBorder = this.border;
    }

    static void attach(Activity activity) {
        if (activity == null) {
            return;
        }
        ChatGlassHook existing = INSTANCES.get(activity);
        if (existing != null) {
            existing.destroy();
        }
        ChatGlassHook hook = new ChatGlassHook(activity);
        INSTANCES.put(activity, hook);
        hook.install();
    }

    static void detach(Activity activity) {
        ChatGlassHook hook = INSTANCES.remove(activity);
        if (hook != null) {
            hook.destroy();
        }
    }

    static void reapplyStatusBar(Activity activity) {
        ChatGlassHook hook = INSTANCES.get(activity);
        if (hook != null) {
            hook.applyStatusBar();
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
                    } else {
                        Log.w(TAG, "chat glass: views not found, give up");
                    }
                }
            }
        });
    }

    private boolean tryInstall() {
        if (attached) {
            return true;
        }
        if (decor == null || !decor.isAttachedToWindow()) {
            Log.i(TAG, "chat tryInstall decor attached=" + (decor != null && decor.isAttachedToWindow()));
            return false;
        }

        View f = decor.findViewById(ID_FOOTER);
        if (f == null || !f.getClass().getName().contains("ChatFooter")) {
            f = Ui.findByClassContains(decor, "ChatFooter");
        }
        View scroll = Ui.findByClass(decor,
                "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout");
        if (scroll == null) {
            scroll = Ui.findByClassContains(decor, "ChattingScrollLayout");
        }
        if (f == null || scroll == null) {
            Log.i(TAG, "chat tryInstall footer=" + f + " scroll=" + scroll);
            return false;
        }

        ViewParent contentParent = scroll.getParent();
        if (!(contentParent instanceof ViewGroup)) {
            Log.i(TAG, "chat tryInstall contentParent=" + contentParent);
            return false;
        }
        ViewGroup content = (ViewGroup) contentParent;
        ViewParent rootParent = content.getParent();
        if (!(rootParent instanceof ViewGroup)) {
            Log.i(TAG, "chat tryInstall rootParent=" + rootParent);
            return false;
        }

        chatRoot = (View) rootParent;
        clearChatBackgrounds();
        scrollLayout = scroll;
        contentFrame = content;
        footer = f;
        applyStatusBar();

        // Only the chat's own ActionBar may be moved. The Activity's AppCompat
        // ActionBar lives inside ActionBarOverlayLayout and must stay where it
        // is, otherwise ActionBarOverlayLayout.onMeasure() throws
        // ClassCastException (FrameLayout.LayoutParams -> its own params).
        View ab = chatRoot.findViewById(ID_ACTION_BAR_CONTAINER);
        if (ab == null || !ab.getClass().getName().contains("ActionBarContainer")) {
            ab = Ui.findByClassContains(chatRoot, "ActionBarContainer");
        }
        if (ab == null) {
            // No ActionBar inside the chat layout: WeChat is using the
            // Activity's support ActionBar. Use it for styling/chips, but never
            // move it (ActionBarOverlayLayout owns it).
            ab = decor.findViewById(ID_ACTION_BAR_CONTAINER);
            if (ab == null || !ab.getClass().getName().contains("ActionBarContainer")) {
                ab = Ui.findByClass(decor, "androidx.appcompat.widget.ActionBarContainer");
            }
            if (ab != null) {
                supportActionBar = true;
            }
        }
        actionBar = ab;
        Log.i(TAG, "actionBar=" + (ab == null ? "null" : ab.getClass().getName())
                + " parent=" + (ab == null || ab.getParent() == null ? "null"
                : ab.getParent().getClass().getName())
                + " support=" + supportActionBar
                + " h=" + (ab == null ? -1 : ab.getHeight()));
        toolbar = ab == null ? null : Ui.findByClass(ab, "androidx.appcompat.widget.Toolbar");
        dumpTopBars(decor, 0);
        if (toolbar == null) {
            toolbar = ab;
        }

        HookConfig.installReceiver(activity);
        renderer = new GlassRenderer(decor);
        renderer.setHiddenViews(actionBar, footer);
        renderer.setGlassEnabled(HookConfig.glassEnabled(activity));
        // The footer lives inside ChattingScrollLayout, so using that as the
        // backdrop would record the footer's own glass and recurse. Use the
        // content list instead, which never contains our surfaces.
        View chatContent = Ui.findByClassContains(scrollLayout, "ChattingContent");
        if (chatContent instanceof ViewGroup) {
            chatContentGroup = (ViewGroup) chatContent;
            renderer.setBackdrop(chatContentGroup);
        }
        renderer.addClient(footer);

        if (actionBar != null) {
            setupTopBar();
            renderer.addClient(backChip);
            renderer.addClient(titleChip);
            renderer.addClient(menuChip);
        }
        setupFooter();
        setupList();
        applyEdgeLayout();

        ViewTreeObserver observer = decor.getViewTreeObserver();
        globalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (!attached) {
                    return;
                }
                applyEdgeLayout();
            }
        };
        observer.addOnGlobalLayoutListener(globalLayoutListener);

        scrollListener = new ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                if (renderer != null) {
                    renderer.requestUpdate();
                }
            }
        };
        scrollLayout.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        attached = true;
        decor.postDelayed(new Runnable() {
            @Override public void run() { ensureFooterGlass(); }
        }, 1000L);
        updateTopGeometry();
        lastGeometrySignature = geometrySignature();
        handler.postDelayed(tick, 600L);
        renderer.requestUpdate();
        Log.i(TAG, "chat glass attached: content=" + contentFrame.getClass().getName()
                + " scroll=" + scrollLayout.getClass().getName());
        return true;
    }

    private void limitPanelHeight() {
        if (footerPanel == null) return;
        int target = Ui.dp(activity, 170f);
        ViewGroup.LayoutParams lp = footerPanel.getLayoutParams();
        if (lp != null && lp.height != target) {
            lp.height = target;
            footerPanel.setLayoutParams(lp);
        }
        try {
            java.lang.reflect.Field field = footerPanel.getClass()
                    .getDeclaredField("p0");
            field.setAccessible(true);
            field.setInt(footerPanel, target);
            footerPanel.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    private void clearChatBackgrounds() {
        String[] names = {
                "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout",
                "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout",
                "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"
        };
        for (String name : names) {
            View view = Ui.findByClass(decor, name);
            if (view != null) {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
    }

    private void dumpTopBars(View view, int depth) {
        if (view == null) return;
        String n = view.getClass().getName();
        if (n.contains("ActionBar") || n.contains("Toolbar") || n.contains("Title")) {
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            Log.i(TAG, "topbar depth=" + depth + " " + n
                    + " vis=" + view.getVisibility() + " h=" + view.getHeight()
                    + " y=" + loc[1] + " a=" + view.getAlpha()
                    + " parent=" + (view.getParent() == null ? "null"
                    : view.getParent().getClass().getName()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) dumpTopBars(g.getChildAt(i), depth + 1);
        }
    }

    private void extendChatToBottom() {
        if (decor == null) return;
        View wrapper = Ui.findByClass(decor,
                "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout");
        if (wrapper != null) {
            wrapper.setPadding(wrapper.getPaddingLeft(), 0,
                    wrapper.getPaddingRight(), 0);
        }
        if (navFiller != null) {
            navFiller.setVisibility(View.GONE);
        }
        if (chatRoot != null && chatRoot.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = chatRoot.getLayoutParams();
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                chatRoot.setLayoutParams(lp);
                chatRoot.requestLayout();
            }
        }
    }

    private void applyStatusBar() {
        try {
            // Status bar is transparent; WeChat's own edge-to-edge owns insets.
            activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        } catch (Throwable throwable) {
            Log.w(TAG, "status bar color failed", throwable);
        }
    }

    private void setupTopBar() {
        Ui.clearActionBarBackground(actionBar);
        Ui.transparent(toolbar);
        Ui.transparent(actionBar.findViewById(ID_ACTION_BAR_ROOT));
        toolbar.setBackground(null);
        actionBar.setBackground(null);

        backView = actionBar.findViewById(ID_BACK);
        if (backView == null) {
            backView = actionBar.findViewById(ID_BACK_BTN);
        }
        titleView = actionBar.findViewById(ID_TITLE);
        menuView = actionBar.findViewById(ID_MENU);
        if (menuView == null || menuView.getWidth() == 0) {
            View innerMenu = actionBar.findViewById(ID_MENU_INNER);
            if (innerMenu != null) {
                menuView = innerMenu;
            }
        }

        if (titleView == null) {
            View text = toolbar.findViewById(android.R.id.title);
            if (text == null) {
                text = toolbar.findViewById(android.R.id.text1);
            }
            if (text != null) {
                ViewGroup parent = text.getParent() instanceof ViewGroup
                        ? (ViewGroup) text.getParent() : null;
                titleView = parent != null ? parent : text;
            }
        }
        if (backView == null) {
            backView = Ui.findByClassContains(actionBar, "WeImageView");
        }
        // WeChat 8.0.79 draws its own white capsule behind the title; it would
        // sit on top of our glass, so hide the native title layer completely.
        if (titleView != null) {
            titleView.setAlpha(1f);
            if (titleView.getParent() instanceof View
                    && ((View) titleView.getParent()).getBackground() != null) {
                ((View) titleView.getParent()).setBackground(null);
            }
        }

        ViewGroup group = (ViewGroup) actionBar;
        group.setClipChildren(false); // enlarge visible range
        group.setClipToPadding(false);
        int chipHeight = Ui.dp(activity, 44f);
        backChip = new GlassChipView(activity, renderer, chipHeight / 2f, chipTint, chipBorder);
        titleChip = new GlassChipView(activity, renderer, Ui.dp(activity, 22f), chipTint, chipBorder);
        menuChip = new GlassChipView(activity, renderer, chipHeight / 2f, chipTint, chipBorder);

        addChip(group, backChip, chipHeight);
        addChip(group, titleChip, chipHeight);
        addChip(group, menuChip, chipHeight);
        renderer.addSkipTarget(backChip);
        renderer.addSkipTarget(titleChip);
        renderer.addSkipTarget(menuChip);
        startPillLoop();
        for (int ci = 0; ci < group.getChildCount(); ci++) {
            View cv = group.getChildAt(ci);
            int[] cl = new int[2];
            cv.getLocationOnScreen(cl);
            android.util.Log.i("WeChatGlass", "barChild[" + ci + "]=" + cv.getClass().getSimpleName()
                    + " ty=" + cv.getTranslationY() + " vis=" + cv.getVisibility()
                    + " at=" + cl[0] + "," + cl[1] + " " + cv.getWidth() + "x" + cv.getHeight());
            if (cv.getClass().getSimpleName().equals("Toolbar")) {
                cv.setBackground(null);
            }
        }
        for (int ci = 0; ci < group.getChildCount(); ci++) {
            View cv = group.getChildAt(ci);
        }
        android.util.Log.i("WeChatGlass", "topbar chipTop=" + titleChip.getTop()
                + " chipH=" + titleChip.getHeight()
                + " barH=" + actionBar.getHeight()
                + " transY=" + actionBar.getTranslationY()
                + " status=" + Ui.statusBarHeight(decor));
        if (actionBar.getParent() instanceof ViewGroup) {
            ((ViewGroup) actionBar.getParent()).setClipChildren(false);
        }
        actionBar.post(new Runnable() {
            @Override public void run() {
                android.util.Log.i("WeChatGlass", "topbar post top=" + titleChip.getTop()
                        + " h=" + titleChip.getHeight()
                        + " barH=" + actionBar.getHeight()
                        + " transY=" + actionBar.getTranslationY()
                        + " parentClip=" + (actionBar.getParent() instanceof ViewGroup
                        && ((ViewGroup) actionBar.getParent()).getClipChildren()));
            }
        });
        if (scrollLayout instanceof ViewGroup && HookConfig.glassEnabled(activity)) {
            ViewGroup tp = null;
            ViewGroup sl = (ViewGroup) scrollLayout;
            for (int i = 0; i < sl.getChildCount(); i++) {
                View ch = sl.getChildAt(i);
                if (ch.getClass().getName().contains("ChattingContent") && ch instanceof ViewGroup) {
                    int[] l = new int[2];
                    ch.getLocationOnScreen(l);
                    if (Math.abs(l[0]) < 10) { tp = (ViewGroup) ch; break; }
                }
            }
            if (tp != null) {
                View tmr = Ui.findByClassContains(tp, "RecyclerView");
                if (tmr instanceof ViewGroup) { tp = (ViewGroup) tmr; }
                renderer.setLayerMode(true);
                GlassLayerView layer = new GlassLayerView(activity, group);
                group.addView(layer, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, chipHeight));
                layer.setBackdrop(tp);
            }
        }

        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                updateTopGeometry();
            }
        };
        actionBar.addOnLayoutChangeListener(listener);
        if (toolbar != actionBar) {
            toolbar.addOnLayoutChangeListener(listener);
        }
        if (backView != null) {
            backView.addOnLayoutChangeListener(listener);
        }
        if (titleView != null) {
            titleView.addOnLayoutChangeListener(listener);
        }
        if (menuView != null) {
            menuView.addOnLayoutChangeListener(listener);
        }
    }

    private void addChip(ViewGroup parent, GlassChipView chip, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(height, height);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        parent.addView(chip, 0, params);
    }

    /**
     * Move the ActionBar out of the vertical chat layout and into the content
     * FrameLayout. Because it is added after ChattingScrollLayout it draws on
     * top, and the list/wallpaper can occupy the full height.
     */
    private void reparentActionBar() {
        if (supportActionBar) {
            return;
        }
        if (actionBar == null || contentFrame == null || chatRoot == null) {
            return;
        }
        if (!isDescendant(actionBar, chatRoot)) {
            return;
        }
        ViewParent parent = actionBar.getParent();
        if (parent == contentFrame) {
            return;
        }
        if (parent != null && parent.getClass().getName().contains("ActionBarOverlayLayout")) {
            // Never pull the support ActionBar out of its overlay host.
            return;
        }
        if (parent instanceof ViewGroup) {
            if (actionBarOriginalParent == null) {
                actionBarOriginalParent = (ViewGroup) parent;
                actionBarOriginalIndex = actionBarOriginalParent.indexOfChild(actionBar);
                actionBarOriginalParams = actionBar.getLayoutParams();
            }
            ((ViewGroup) parent).removeView(actionBar);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        contentFrame.addView(actionBar, params);
    }

    private static boolean isDescendant(View child, View ancestor) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * Ask AppCompat's ActionBarOverlayLayout to lay the content out behind the
     * support ActionBar instead of below it. This is the supported overlay
     * path and avoids moving the ActionBar out of its host.
     */
    private void enableSupportActionBarOverlay() {
        if (!supportActionBar || supportActionBarOverlaySet || actionBar == null) {
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
                    supportActionBarOverlaySet = true;
                } catch (Throwable throwable) {
                    Log.w(TAG, "setOverlayMode failed", throwable);
                }
                return;
            }
            parent = parent.getParent();
        }
    }

    /**
     * The support ActionBarOverlayLayout leaves a top inset on the content
     * frame (ChattingUILayout starts at y=actionbarHeight). Remove that inset
     * so the chat wallpaper/list really draw under the top bar.
     */
    private void forceContentBehindTop() {
        if (chatRoot == null) {
            return;
        }
        ViewParent parent = chatRoot.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) parent;
        if (group.getPaddingTop() != 0) {
            group.setPadding(group.getPaddingLeft(), 0,
                    group.getPaddingRight(), group.getPaddingBottom());
        }

        int top = chatRoot.getTop();
        if (top > 0 && group.getHeight() > 0) {
            ViewGroup.LayoutParams params = chatRoot.getLayoutParams();
            if (params != null && params.height != group.getHeight()) {
                params.height = group.getHeight();
                chatRoot.setLayoutParams(params);
            }
            if (chatRoot.getTranslationY() != -top) {
                chatRoot.setTranslationY(-top);
            }
        } else if (top == 0 && chatRoot.getTranslationY() != 0f) {
            chatRoot.setTranslationY(0f);
        }
    }

    private void dumpFooterEditors(View view, int depth) {
        if (view == null) return;
        String name = view.getClass().getName();
        if (name.contains("EditText") || name.contains("MMFlexEditText")) {
            Log.i(TAG, "editor depth=" + depth + " " + name
                    + " vis=" + view.getVisibility()
                    + " h=" + view.getHeight()
                    + " shown=" + view.isShown()
                    + " id=" + Integer.toHexString(view.getId())
                    + " parent=" + (view.getParent() == null ? "null"
                    : view.getParent().getClass().getName()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpFooterEditors(group.getChildAt(i), depth + 1);
            }
        }
    }

    private void dumpVisibleGeometry() {
        View input = footer == null ? null : footer.findViewById(0x7f0a0f81);
        View button = null;
        if (footer instanceof ViewGroup) {
            button = findVisibleButton((ViewGroup) footer);
        }
        int[] iLoc = new int[2];
        int[] bLoc = new int[2];
        int[] fLoc = new int[2];
        if (input != null) input.getLocationInWindow(iLoc);
        if (button != null) button.getLocationInWindow(bLoc);
        if (footer != null) footer.getLocationInWindow(fLoc);
        if (input instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) input;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                Log.i(TAG, "input child=" + c.getClass().getName()
                        + " top=" + c.getTop() + " h=" + c.getHeight()
                        + " padt=" + c.getPaddingTop() + " padb=" + c.getPaddingBottom()
                        + " bg=" + c.getBackground());
            }
        }
        if (input != null) {
            View p = (View) input.getParent();
            for (int i = 0; i < 5 && p != null; i++) {
                Log.i(TAG, "visible ancestor " + i + " " + p.getClass().getName()
                        + " h=" + p.getHeight() + " top=" + p.getTop()
                        + " vis=" + p.getVisibility());
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
        }
        if (input instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) input;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof android.widget.EditText) {
                    android.widget.EditText et = (android.widget.EditText) c;
                    Log.i(TAG, "edit metrics minH=" + et.getMinHeight()
                            + " lineH=" + et.getLineHeight()
                            + " lines=" + et.getLineCount()
                            + " gravity=" + et.getGravity()
                            + " fontPad=" + et.getIncludeFontPadding()
                            + " pad=" + et.getPaddingTop() + "/" + et.getPaddingBottom()
                            + " h=" + et.getHeight());
                }
            }
        }
        Log.i(TAG, "visible input=" + (input == null ? "null"
                : iLoc[1] + "/" + input.getHeight() + "/" + input.isShown())
                + " button=" + (button == null ? "null"
                : bLoc[1] + "/" + button.getHeight() + "/" + button.isShown())
                + " footer=" + fLoc[1] + "/" + (footer == null ? -1 : footer.getHeight()));
    }

    private View findVisibleButton(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("WeImageButton")
                    && child.isShown() && child.getHeight() > 0) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findVisibleButton((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean pillLoopStarted;
    private boolean bgLogged;
    private int wallHits;
    private boolean wallpaperTried;
    private boolean footerProbeLogged;
    private View footerLiquid;

    private String readConfig(String key) {
        try {
            Class<?> c = Class.forName("de.robv.android.xposed.XSharedPreferences");
            Object sp = c.getConstructor(String.class, String.class)
                    .newInstance("com.wechatglass", "settings");
            c.getMethod("reload").invoke(sp);
            Object v = c.getMethod("getString", String.class, String.class)
                    .invoke(sp, key, null);
            return (String) v;
        } catch (Throwable t) {
            android.util.Log.e("WeChatGlass", "CB xsp read fail " + key, t);
        }
        return null;
    }

    /**
     * Reads the wallpaper through the module's exported provider: the picker
     * URI grant belongs to the module app, not to WeChat's process.
     */
    private android.graphics.Bitmap wallpaperFromProvider() {
        try {
            android.database.Cursor cursor = activity.getContentResolver().query(
                    android.net.Uri.parse(
                            "content://io.github.yingxingyswx_design.wechatglass.config/wallpaper"),
                    null, null, null, null);
            if (cursor == null) {
                return null;
            }
            try {
                if (!cursor.moveToFirst()) {
                    return null;
                }
                byte[] data = cursor.getBlob(0);
                if (data == null || data.length == 0) {
                    return null;
                }
                return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            } finally {
                cursor.close();
            }
        } catch (Throwable throwable) {
            android.util.Log.w("WeChatGlass", "CB wallpaper provider fail", throwable);
            return null;
        }
    }

    private void loadWallpaper() {
        try {
            android.graphics.Bitmap picked = wallpaperFromProvider();
            if (picked == null) {
                String cfg = readConfig("wallpaper_uri");
                if (cfg != null && !cfg.isEmpty()) {
                    java.io.InputStream cin = activity.getContentResolver()
                            .openInputStream(android.net.Uri.parse(cfg));
                    picked = android.graphics.BitmapFactory.decodeStream(cin);
                    if (cin != null) cin.close();
                }
            }
            if (picked != null) {
                // An explicit pick in the module wins over WeChat's own setting.
                com.wechatglass.liquid.LiquidPanelFactory.setWallpaper(picked);
                android.util.Log.i("WeChatGlass", "CB wallpaper picked "
                        + picked.getWidth() + "x" + picked.getHeight());
                return;
            }
            if (applyChatBackground()) {
                return;
            }
            android.util.Log.i("WeChatGlass", "CB wallpaper none");
        } catch (Throwable t) {
            android.util.Log.e("WeChatGlass", "CB wallpaper fail", t);
        }
    }

    /**
     * Follows the background WeChat already rendered instead of asking the user
     * for a wallpaper: the chat page paints it with {@code ChattingImageBGView}
     * (an {@code ImageView} holding a {@link android.graphics.drawable.BitmapDrawable}),
     * or leaves a solid colour on {@code ChattingContent} when the user picked
     * none. Read-only and free of any WeChat API call.
     */
    private boolean applyChatBackground() {
        View content = chatContentGroup;
        if (content == null) {
            return false;
        }
        android.graphics.Bitmap bitmap = backgroundBitmap(content, 0, content);
        if (bitmap != null && !bitmap.isRecycled()) {
            com.wechatglass.liquid.LiquidPanelFactory.setWallpaper(bitmap);
            android.util.Log.i("WeChatGlass", "CB wallpaper auto image "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            return true;
        }
        android.graphics.drawable.Drawable bg = content.getBackground();
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            android.graphics.Bitmap solid = android.graphics.Bitmap.createBitmap(
                    2, 2, android.graphics.Bitmap.Config.ARGB_8888);
            solid.eraseColor(color);
            com.wechatglass.liquid.LiquidPanelFactory.setWallpaper(solid);
            android.util.Log.i("WeChatGlass", "CB wallpaper auto color #"
                    + Integer.toHexString(color));
            return true;
        }
        return false;
    }

    private android.graphics.Bitmap backgroundBitmap(View view, int depth, View content) {
        if (view == null || depth > 6) {
            return null;
        }
        if (view instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable drawable =
                    ((android.widget.ImageView) view).getDrawable();
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap bitmap =
                        ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()
                        && view.getWidth() >= content.getWidth() * 0.9f
                        && view.getHeight() >= content.getHeight() * 0.9f) {
                    return bitmap;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.graphics.Bitmap found =
                        backgroundBitmap(group.getChildAt(i), depth + 1, content);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void scanWallpaperConfig() {
        try {
            java.io.File prefs = new java.io.File(activity.getApplicationInfo().dataDir,
                    "shared_prefs");
            java.io.File[] fs = prefs.listFiles();
            if (fs != null) {
                for (java.io.File f : fs) {
                    String n = f.getName();
                    if (n.contains("background") || n.contains("wall") || n.contains("bg")) {
                        android.util.Log.i("WeChatGlass", "CB bgpref " + n + " " + f.length());
                    }
                }
            }
            String[] keys = {"chat_background", "background", "wallpaper", "chat_bg_path"};
            android.content.SharedPreferences sp = activity.getSharedPreferences(
                    "com.tencent.mm_preferences", 0);
            java.util.Map<String, ?> all = sp.getAll();
            for (String k : all.keySet()) {
                String lk = k.toLowerCase();
                if (lk.contains("bg") || lk.contains("wall") || lk.contains("background")) {
                    android.util.Log.i("WeChatGlass", "CB bgkey " + k + "=" + all.get(k));
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("WeChatGlass", "CB bgscan fail", t);
        }
    }

    private void scanWall(View v, int depth) {
        if (v == null || depth > 9 || wallHits >= 15) return;
        android.graphics.drawable.Drawable d = v.getBackground();
        boolean hit = d instanceof android.graphics.drawable.BitmapDrawable;
        if (hit) {
            wallHits++;
            android.util.Log.i("WeChatGlass", "CB wall " + v.getClass().getName()
                    + " " + v.getWidth() + "x" + v.getHeight() + " bg=" + d);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scanWall(g.getChildAt(i), depth + 1);
        }
    }

    /** Footer row detection in dp / screen-relative units, not raw pixels. */
    private static boolean looksLikeInputRow(View view) {
        if (view == null) {
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
        return width > screenWidth * 0.5f
                && height >= Ui.dp(view.getContext(), 32f)
                && height <= Ui.dp(view.getContext(), 80f);
    }

    private View findInputRow() {
        if (footer == null) return null;
        View best = null;
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(footer);
        while (!q.isEmpty()) {
            View v = q.poll();
            boolean edit = v.getClass().getSimpleName().contains("EditText");
            if (!edit && looksLikeInputRow(v)
                    && (best == null || v.getWidth() > best.getWidth())) {
                best = v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return best;
    }

    private android.widget.ImageView wallpaperLayer;
    private View wallpaperTarget;

    private String lastWallUri;
    private long lastWallCheck;
    private long lastWallMod = -1;
    private boolean wallProbeLogged;
    private boolean scopeLogged;

    private void syncWallpaperFromProvider() {
        long now = System.currentTimeMillis();
        if (now - lastWallCheck < 2000) return;
        lastWallCheck = now;
        try {
            java.io.File f = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Android/data/com.wechatglass/files/wallpaper.png");
            if (!wallProbeLogged) {
                wallProbeLogged = true;
                android.util.Log.i("WeChatGlass", "CB wall probe path=" + f.getAbsolutePath()
                        + " exists=" + f.exists() + " len=" + f.length()
                        + " extDir=" + android.os.Environment.getExternalStorageDirectory());
            }
            if (!f.exists()) return;
            long mod = f.lastModified();
            if (mod == lastWallMod) return;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) return;
            lastWallMod = mod;
            com.wechatglass.liquid.LiquidPanelFactory.setWallpaper(bmp);
            com.wechatglass.liquid.LiquidPanelFactory.enableCaptureWallpaper(true);
            View rvp = Ui.findByClassContains(decor, "ScrollControlRecyclerView");
            if (rvp instanceof ViewGroup) {
                com.wechatglass.liquid.LiquidPanelFactory
                        .setWallpaperEnabled((ViewGroup) rvp, true);
            }
            android.util.Log.i("WeChatGlass", "CB wall file " + bmp.getWidth() + "x" + bmp.getHeight());
        } catch (Throwable t) {
            android.util.Log.e("WeChatGlass", "CB wall file fail", t);
        }
    }

    private void ensureWallpaperLayer() { }

    private void ensureFooterLiquid() {
        if (decor == null || activity == null) return;
        if (!HookConfig.glassEnabled(activity)) return;
        View bar = findInputRow();
        if (!footerProbeLogged) {
            footerProbeLogged = true;
            android.util.Log.i("WeChatGlass", "CB footer probe footer=" + footer
                    + " " + (footer == null ? "" : footer.getWidth() + "x" + footer.getHeight())
                    + " row=" + bar
                    + (bar == null ? "" : " " + bar.getWidth() + "x" + bar.getHeight()));
        }
        if (bar == null) return;
        if (bar.getWidth() <= 0 || bar.getHeight() <= 0) return;
        View anchorChild = bar;
        android.view.ViewParent vp = bar.getParent();
        android.widget.FrameLayout frame = null;
        while (vp instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) vp;
            if (g instanceof android.widget.FrameLayout) { frame = (android.widget.FrameLayout) g; break; }
            anchorChild = g;
            vp = g.getParent();
        }
        if (frame == null) return;
        if (footerLiquid != null && footerLiquid.getParent() == frame) return;
        int[] bl = new int[2];
        int[] fl = new int[2];
        bar.getLocationOnScreen(bl);
        frame.getLocationOnScreen(fl);
        View bgv = Ui.findByClassContains(decor, "ScrollControlRecyclerView");
        if (!(bgv instanceof ViewGroup)) return;
        View lp = com.wechatglass.liquid.LiquidPanelFactory.create(activity,
                (ViewGroup) bgv,
                activity.getResources().getDisplayMetrics().density,
                Ui.isNight(activity));
        if (lp == null) return;
        lp.setTag(bar);
        footerLiquid = lp;
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                bar.getWidth(), bar.getHeight());
        flp.leftMargin = bl[0] - fl[0];
        flp.topMargin = bl[1] - fl[1];
        frame.addView(lp, Math.max(0, frame.indexOfChild(anchorChild)), flp);
        android.util.Log.i("WeChatGlass", "CB liquid footer " + bar.getWidth() + "x" + bar.getHeight());
    }

    private void removeLiquidPills() {
        ViewGroup host = decor instanceof ViewGroup ? (ViewGroup) decor : null;
        if (host == null) return;
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View c = host.getChildAt(i);
            if (c.getClass().getName().startsWith("com.wechatglass.liquid")) {
                host.removeViewAt(i);
            }
        }
    }

    private void startPillLoop() {
        if (pillLoopStarted || decor == null) return;
        pillLoopStarted = true;
        decor.postDelayed(new Runnable() {
            @Override public void run() {
                assertWallScope();
                ensureLiquidPills();
                ensureFooterLiquid();
                syncWallpaperFromProvider();
                ensureWallpaperLayer();
                if (decor != null) decor.postDelayed(this, 250);
            }
        }, 250);
        android.util.Log.i("WeChatGlass", "CB pill loop started");
    }

    private void assertWallScope() {
        if (activity == null || !activity.hasWindowFocus()) return;
        View rv = Ui.findByClassContains(decor, "ScrollControlRecyclerView");
        if (rv instanceof ViewGroup) {
            com.wechatglass.liquid.LiquidPanelFactory.enableCaptureWallpaper(true);
            if (!scopeLogged) {
                scopeLogged = true;
                android.util.Log.i("WeChatGlass", "CB scope chat ON "
                        + rv.getClass().getSimpleName() + "@" + System.identityHashCode(rv));
            }
        }
    }

    private View findInputRowLocal() {
        if (footer == null) return null;
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<>();
        q.add(footer);
        View best = null;
        while (!q.isEmpty()) {
            View v = q.poll();
            String cn = v.getClass().getSimpleName();
            if (!cn.contains("EditText") && looksLikeInputRow(v)
                    && (best == null || v.getWidth() > best.getWidth())) {
                best = v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return best;
    }
    /** ChattingContent: holds the chat wallpaper that the footer panels sit on. */
    private ViewGroup chatContentGroup;
    private boolean panelWasOpen;
    /** The input row carrying GlassFooterDrawable; its corners follow the panel. */
    private View inputRow;
    private long lastTreeDump;

    private void ensureLiquidPills() {
        if (decor == null || activity == null) return;
        if (!HookConfig.glassEnabled(activity)) {
            removeLiquidPills();
            return;
        }
        View bgView = Ui.findByClassContains(decor, "ScrollControlRecyclerView");
        if (!(bgView instanceof ViewGroup)) return;
        View bgParent = (bgView.getParent() instanceof ViewGroup)
                ? (View) bgView.getParent() : bgView;
        if (!wallpaperTried) { wallpaperTried = true; scanWall(decor, 0); loadWallpaper(); }
        if (!bgLogged && bgView instanceof View) {
            bgLogged = true;
            View v = (View) bgView;
            while (v != null) {
                android.util.Log.i("WeChatGlass", "CB bg chain " + v.getClass().getSimpleName()
                        + " bg=" + v.getBackground());
                android.view.ViewParent pp = v.getParent();
                v = (pp instanceof View) ? (View) pp : null;
            }
        }
        View[] chips = {backChip, titleChip, menuChip};
        boolean night = Ui.isNight(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        for (View ch : chips) {
            if (ch == null || ch.getWidth() <= 0 || ch.getHeight() <= 0) continue;
            if (!(ch.getParent() instanceof ViewGroup)) continue;
            ViewGroup host = (ViewGroup) ch.getParent();
            boolean exists = false;
            for (int i = 0; i < host.getChildCount(); i++) {
                if (host.getChildAt(i).getTag() == ch) { exists = true; break; }
            }
            if (exists) continue;
            View lp = com.wechatglass.liquid.LiquidPanelFactory.create(
                    activity,
                    chatContentGroup != null ? chatContentGroup : (ViewGroup) bgParent,
                    density, night);
            if (lp == null) continue;
            lp.setTag(ch);
            int ph = Math.max(1, ch.getHeight() - 3);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ch.getWidth(), ph);
            flp.leftMargin = ch.getLeft();
            flp.topMargin = ch.getTop() + 3;
            host.addView(lp, Math.max(0, host.indexOfChild(ch)), flp);
            android.util.Log.i("WeChatGlass", "CB liquid pill " + ch.getWidth() + "x" + ch.getHeight());
        }
    }

    private void ensureFooterGlass() {
        ViewGroup root = scrollLayout instanceof ViewGroup
                ? (ViewGroup) scrollLayout
                : (decor instanceof ViewGroup ? (ViewGroup) decor : null);
        if (footer == null || root == null) {
            android.util.Log.i("WeChatGlass", "footer glass skip set=" + footerGlassSet
                    + " footerNull=" + (footer == null)
                    + " rootNull=" + (root == null)
                    + " decorVG=" + (decor instanceof ViewGroup)
                    + " scrollCls=" + (scrollLayout == null ? "null"
                    : scrollLayout.getClass().getSimpleName()));
            return;
        }
        View rv = Ui.findByClassContains(root, "RecyclerView");
        if (!(rv instanceof ViewGroup)) {
            android.util.Log.i("WeChatGlass", "footer glass skip rv=null");
            return;
        }
        View edit = Ui.findByClassContains(decor, "EditText");
        View bar = null;
        View cur = edit;
        while (cur != null) {
            if (looksLikeInputRow(cur)) { bar = cur; break; }
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        if (bar == null || bar.getHeight() > Ui.dp(activity, 80f)) { android.util.Log.i("WeChatGlass", "footer glass skip bad h=" + (bar == null ? -1 : bar.getHeight())); decor.postDelayed(new Runnable() { @Override public void run() { ensureFooterGlass(); } }, 800L); return; }
        renderer.addSkipTarget(bar);
        renderer.addSkipTarget(footer);
        StringBuilder diag = new StringBuilder("anchor diag");
        View pv = bar;
        for (int k = 0; k < 4 && pv != null; k++) {
            int[] pl = new int[2];
            pv.getLocationOnScreen(pl);
            diag.append(' ').append(pv.getClass().getSimpleName())
                .append('[').append(pl[0]).append(',').append(pl[1]).append(']')
                .append(pl[0]).append('/').append(pv.getWidth()).append('x').append(pv.getHeight());
            pv = pv.getParent() instanceof View ? (View) pv.getParent() : null;
        }
        android.util.Log.i("WeChatGlass", diag.toString());
        // Only build the drawable once: re-creating it every pass threw away
        // its corner state and allocated on every frame.
        if (!(bar.getBackground() instanceof GlassFooterDrawable)) {
            bar.setBackground(new GlassFooterDrawable(bar, bar, (ViewGroup) rv));
            android.util.Log.i("WeChatGlass", "CL footer apply on "
                    + bar.getClass().getSimpleName() + " h=" + bar.getHeight());
            makeTranslucent(bar, 204);
        }
        inputRow = bar;
        footerGlassSet = true;
        android.util.Log.i("WeChatGlass", "footer glass set on " + bar.getClass().getSimpleName());
    }

    private static void makeTranslucent(View view, int alpha) {
        android.graphics.drawable.Drawable d = view.getBackground();
        if (d != null) { d.setAlpha(alpha); }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                makeTranslucent(g.getChildAt(i), alpha);
            }
        }
    }

    private void setupFooter() {
        Ui.transparent(footer);
        clearFooterBackgrounds();
        footerBottom = Ui.findByClassContains(footer, "ChatFooterBottom");
        if (footerBottom == null) {
            footerBottom = footer.findViewById(ID_FOOTER_BOTTOM);
        }
        footerPanel = Ui.findByClassContains(footer, "AppPanel");
        if (footerPanel == null) {
            footerPanel = footer.findViewById(ID_FOOTER_PANEL);
        }
        if (footerBottom != null) {
            Ui.transparent(footerBottom);
            removePanelSpacer(footerBottom);
        }
        Log.i(TAG, "footerBottom=" + (footerBottom == null ? "null"
                : footerBottom.getClass().getName())
                + " footerPanel=" + (footerPanel == null ? "null"
                : footerPanel.getClass().getName()));

        int bottomInset = Ui.dp(activity, 8f);
        footer.setPadding(footer.getPaddingLeft(), footer.getPaddingTop(),
                footer.getPaddingRight(), footer.getPaddingBottom() + bottomInset);
        footer.setBackground(new GlassBarDrawable(renderer, footer,
                Ui.dp(activity, 26f), tint, border, bottomInset));

        dumpFooterEditors(footer, 0);
        dumpVisibleGeometry();


        footer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.invalidate();
            }
        });
    }

    /**
     * Clears the opaque background chain from the footer root down to the
     * EditText without touching emoji/voice button backgrounds. Done by
     * structure so it survives small WeChat resource-id changes.
     */
    private void clearFooterBackgrounds() {
        if (footer instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) footer;
            if (group.getChildCount() > 0) {
                Ui.transparent(group.getChildAt(0));
            }
        }
        View edit = Ui.findByClassContains(decor, "EditText");
        View current = edit;
        while (current != null && current != footer) {
            if (current instanceof ViewGroup) {
                current.setBackground(null);
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    /**
     * t8.xml has an 8dp spacer View between the input row and the 400dp
     * ChatFooterBottom panel. Remove it so the plus panel is visually the same
     * continuous glass card as the input row.
     */
    private void removePanelSpacer(View panel) {
        ViewParent parent = panel.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(panel);
        if (index <= 0) {
            return;
        }
        View previous = group.getChildAt(index - 1);
        if (previous != null && previous.getClass() == View.class) {
            ViewGroup.LayoutParams params = previous.getLayoutParams();
            if (params != null && params.height <= Ui.dp(activity, 16f)) {
                previous.setVisibility(View.GONE);
                params.height = 0;
                previous.setLayoutParams(params);
            }
        }
    }

    /**
     * Clears opaque backgrounds on the big panel containers only. Buttons and
     * small icon tiles are intentionally left alone.
     */
    private void clearLargePanelBackgrounds(View root, int depth) {
        if (root == null || depth > 2 || footer == null) {
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            boolean button = root.getClass().getName().contains("Button");
            // depth 0 is the panel itself: its background is the glass we just
            // installed, and clearing it here re-installed it every frame.
            if (depth > 0 && !button && group.getBackground() != null
                    && !com.wechatglass.liquid.LiquidPanelFactory
                            .isPanelGlass(group.getBackground())
                    && group.getWidth() > footer.getWidth() * 0.6f
                    && group.getHeight() > Ui.dp(activity, 60f)) {
                group.setBackground(null);
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                clearLargePanelBackgrounds(group.getChildAt(i), depth + 1);
            }
        }
    }

    private void setupList() {
        list = Ui.findByClassContains(decor, "RecyclerView");
        if (list == null) {
            return;
        }
        listBaseLeft = list.getPaddingLeft();
        listBaseTop = list.getPaddingTop();
        listBaseRight = list.getPaddingRight();
        listBaseBottom = list.getPaddingBottom();
        listBaseSaved = true;
        if (list instanceof ViewGroup) {
            ((ViewGroup) list).setClipToPadding(false);
        }
        list.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX,
                                       int oldScrollY) {
                if (renderer != null) {
                    renderer.requestUpdate();
                }
            }
        });
    }

    /**
     * Applies the actual edge-to-edge geometry. Idempotent; called from the
     * ticker as heights settle and from global layout.
     */
    private void applyEdgeLayout() {
        // tick: re-apply top offset (WeChat resets translation on its layout)
        if (actionBar != null) {
            actionBar.setTranslationY(Ui.statusBarHeight(decor));
        }
        ensureFooterGlass();
        if (renderer == null) {
            return;
        }
        // Re-apply after WeChat's own edge-to-edge reset.
        applyStatusBar();
        extendChatToBottom();
        reparentActionBar();
        if (actionBar != null && actionBar.getParent() instanceof ViewGroup) {
            ViewGroup pv = (ViewGroup) actionBar.getParent();
            StringBuilder sb = new StringBuilder("CO order parent=" + pv.getClass().getSimpleName());
            for (int i = 0; i < pv.getChildCount(); i++) {
                View c = pv.getChildAt(i);
                sb.append(' ').append(i).append(':').append(c.getClass().getSimpleName())
                  .append('@').append(c.getVisibility());
            }
            android.util.Log.i("WeChatGlass", sb.toString());
        }
        // Status scrim disabled: it painted a white gradient over the status
        // bar and read as a duplicate top bar in screenshots.
        // ensureStatusScrim();
        if (actionBar != null) {
            actionBar.setTranslationY(Ui.statusBarHeight(decor));
        }
        // WeChat's own bottom inset shrinks the chat container by the status
        // bar height (107px on this device); stretch it back to full height.
        int decorHeight = decor == null ? 0 : decor.getHeight();
        if (decorHeight > 0 && scrollLayout != null
                && scrollLayout.getHeight() > 0
                && scrollLayout.getHeight() < decorHeight) {
            ViewGroup.LayoutParams scrollParams = scrollLayout.getLayoutParams();
            if (scrollParams != null && scrollParams.height != decorHeight) {
                scrollParams.height = decorHeight;
                scrollLayout.setLayoutParams(scrollParams);
            }
        }
        enableSupportActionBarOverlay();
        forceContentBehindTop();

        if (contentFrame != null && contentFrame.getParent() instanceof ViewGroup) {
            ViewGroup.LayoutParams params = contentFrame.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
                if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.topMargin != 0 || lp.bottomMargin != 0) {
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.topMargin = 0;
                    lp.bottomMargin = footerOriginalBottomMargin;
                    contentFrame.setLayoutParams(lp);
                }
            }
        }

        int footerHeight = footer == null ? 0 : footer.getHeight();
        if (footerHeight > 0 && footerHeight != lastFooterHeight) {
            lastFooterHeight = footerHeight;
            ViewGroup.LayoutParams params = footer.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
                if (footerOriginalBottomMargin == Integer.MIN_VALUE) {
                    footerOriginalBottomMargin = lp.bottomMargin;
                }
                // ChatFooter contains a 400dp hidden panel (emoji/more). Keep
                // the original negative bottom margin so that panel stays
                // below the screen, and only lift the visible input row.
                int visibleHeight = footerHeight + footerOriginalBottomMargin;
                if (visibleHeight <= 0) {
                    visibleHeight = footerHeight;
                }
                lastVisibleFooterHeight = visibleHeight;
                lp.topMargin = -visibleHeight;
                lp.bottomMargin = footerOriginalBottomMargin;
                lp.leftMargin = Ui.dp(activity, 10f);
                lp.rightMargin = Ui.dp(activity, 10f);
                footer.setLayoutParams(lp);

                if (footer.getBackground() instanceof GlassBarDrawable) {
                    ((GlassBarDrawable) footer.getBackground()).setBottomInset(
                            Math.max(0, footerHeight - visibleHeight) + Ui.dp(activity, 8f));
                }
            }
        }

        // WeChat resets these panel backgrounds when it opens the panel.
        if (footerBottom != null && footerBottom.getBackground() != null) {
            footerBottom.setBackground(null);
        }
        View emojiPanel = Ui.findByClassContains(footer, "EmojiPanelGroupView");
        if (emojiPanel != null && emojiPanel.getBackground() != null) {
            emojiPanel.setBackground(null);
        }
        View nestedBounce = Ui.findByClassContains(footer, "NestedBounceView");
        if (nestedBounce != null && nestedBounce.getBackground() != null) {
            nestedBounce.setBackground(null);
        }

        // When a panel is open WeChat slides the footer up and reveals it below
        // the input row. The panel carries its own liquid glass now, so the
        // footer's fill must stay behind the input row only: with the inset
        // dropped to 8dp it painted an opaque slab under the panel and the
        // panel's glass had nothing to show through.
        View smileyPanel = Ui.findByClassContains(footer, "SmileyPanelImpl");
        if (smileyPanel == null) {
            smileyPanel = Ui.findByClassContains(footer, "ChatFooterPanel");
        }
        // isShown() reports false for this panel even while it is painted (the
        // footer sits at a layout position off the bottom of the window and is
        // slid into view), so visibility has to be judged by flags and size.
        boolean emojiOpen = isLaidOutVisible(smileyPanel);
        boolean plusOpen = isLaidOutVisible(resolveFooterPanel());
        boolean anyPanelOpen = emojiOpen || plusOpen;
        if (footer.getBackground() instanceof GlassBarDrawable) {
            GlassBarDrawable glass = (GlassBarDrawable) footer.getBackground();
            if (footerHeight > 0 && lastVisibleFooterHeight > 0) {
                glass.setBottomInset(Math.max(0, footerHeight - lastVisibleFooterHeight)
                        + Ui.dp(activity, 8f));
            }
            if (plusOpen) {
                clearLargePanelBackgrounds(footerBottom, 0);
                clearLargePanelBackgrounds(footerPanel, 0);
            }
        }
        // One card: the input row squares its bottom off against the panel.
        if (inputRow != null && inputRow.getBackground() instanceof GlassFooterDrawable) {
            float r = inputRow.getHeight() / 2f;
            ((GlassFooterDrawable) inputRow.getBackground())
                    .setRadii(r, anyPanelOpen ? 0f : r);
        }

        applyListPadding(lastVisibleFooterHeight > 0 ? lastVisibleFooterHeight : footerHeight);
        applyInputHeight();
        scanPanels(true);
        styleEmojiPanel();
        styleFooterPanel();
        ensureNavFiller();
        debugDumpIfRequested(smileyPanel);
    }

    /** Periodic tree dump while debug.wxglass.panel.log=1; 1 per 2s. */
    private void debugDumpIfRequested(View emojiPanel) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTreeDump < 2000L) {
            return;
        }
        lastTreeDump = now;
        if (!"1".equals(prop("debug.wxglass.panel.log"))) {
            return;
        }
        dumpPanelChain(emojiPanel, emojiPanel != null && emojiPanel.isShown());
    }

    private static String prop(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int navigationBarHeight() {
        int nav = 0;
        android.view.WindowInsets insets = decor == null ? null : decor.getRootWindowInsets();
        if (insets != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                nav = insets.getSystemWindowInsetBottom();
            }
        }
        if (nav <= 0 && scrollLayout != null && decor != null) {
            nav = decor.getHeight() - scrollLayout.getHeight();
        }
        return nav;
    }

    private void ensureNavFiller() {
        if (!(decor instanceof ViewGroup)) return;
        int nav = navigationBarHeight();
        if (nav <= 0) {
            if (navFiller != null) navFiller.setVisibility(View.GONE);
            return;
        }
        if (navFiller == null) {
            View fill = new View(activity);
            fill.setClickable(false);
            fill.setFocusable(false);
            fill.setBackground(new GlassBarDrawable(renderer, fill, 0f,
                    tint, border, 0));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, nav, Gravity.BOTTOM);
            ((ViewGroup) decor).addView(fill, lp);
            navFiller = fill;
        } else {
            ViewGroup.LayoutParams lp = navFiller.getLayoutParams();
            if (lp.height != nav) {
                lp.height = nav;
                navFiller.setLayoutParams(lp);
            }
            navFiller.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The "+"/更多 panel: same liquid glass as the emoji panel. It used to get a
     * {@link GlassBarDrawable}, which is flat, and it was only ever called from
     * nowhere, so the panel itself was transparent over the footer's opaque
     * fill — a white slab.
     */
    private void styleFooterPanel() {
        logPanelStyle("plus", footerPanel);
        watchPanel(resolveFooterPanel());
        ensurePanelGlass(footerPanel);
    }

    private void clearPanelBackgrounds(View root, int depth) {
        if (root == null || depth > 6) return;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (depth > 0 && !root.getClass().getName().contains("Image")) {
                group.setBackground(null);
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                clearPanelBackgrounds(group.getChildAt(i), depth + 1);
            }
        }
    }

    /**
     * Gives the 笑脸/表情 panel the same liquid glass as the top islands.
     *
     * <p>It used to get a {@link GlassBarDrawable}, which is the flat path:
     * {@code GlassRenderer.setGlassEnabled} pins {@code glass=false}, so that
     * drawable paints {@link #tint} — an opaque white — over the panel and over
     * whatever sat behind it. Nothing about that could ever blur.
     *
     * <p>The panel is <em>in</em> the footer, below the message list, so no chat
     * content is behind it. Its backdrop is the chat wallpaper: WeChat's
     * wallpaper {@code ImageView} is the sibling of the list inside
     * {@code ChattingContent} and covers the whole chat area, which is exactly
     * the container the pills sample. See {@code PanelGlassDrawable}.
     */
    private void dumpPanelChain(View panel, boolean open) {
        android.util.Log.i(TAG, "CB panel chain open=" + open
                + " panel=" + (panel == null ? "null" : panel.getClass().getSimpleName()));
        if (panel != null) {
            View v = panel;
            for (int i = 0; i < 9 && v != null; i++) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                android.util.Log.i(TAG, "  chain[" + i + "] " + v.getClass().getSimpleName()
                        + " id=0x" + Integer.toHexString(v.getId())
                        + " top=" + v.getTop() + " left=" + v.getLeft()
                        + " y=" + v.getY() + " ty=" + v.getTranslationY()
                        + " size=" + v.getWidth() + "x" + v.getHeight()
                        + " vis=" + v.getVisibility() + " shown=" + v.isShown()
                        + " screen=" + loc[0] + "," + loc[1]
                        + " scrollY=" + v.getScrollY());
                ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
        }
        int[] cl = new int[2];
        if (chatContentGroup != null) {
            chatContentGroup.getLocationOnScreen(cl);
            android.util.Log.i(TAG, "  backdrop " + chatContentGroup.getClass().getSimpleName()
                    + " screen=" + cl[0] + "," + cl[1]
                    + " size=" + chatContentGroup.getWidth() + "x"
                    + chatContentGroup.getHeight()
                    + " scrollY=" + chatContentGroup.getScrollY());
        }
        // Every view under the footer that is big enough to be a panel, so a
        // second (or third) panel instance cannot hide from the styling pass.
        dumpPanelCandidates(footer, 0);
    }

    private void dumpPanelCandidates(View view, int depth) {
        if (view == null || depth > 12) return;
        // debug.wxglass.panel.tree=1 lists every descendant, not just the ones
        // big enough to be a panel: the opaque layer that hides the panel glass
        // is usually one of the small containers in between.
        boolean tree = "1".equals(prop("debug.wxglass.panel.tree"));
        boolean interesting = tree || (view.getHeight() > Ui.dp(activity, 40f)
                && view.getWidth() > footer.getWidth() * 0.5f);
        if (interesting) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            android.util.Log.i(TAG, "  cand[" + depth + "] "
                    + view.getClass().getSimpleName()
                    + " id=0x" + Integer.toHexString(view.getId())
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " screen=" + loc[0] + "," + loc[1]
                    + " vis=" + view.getVisibility() + " shown=" + view.isShown()
                    + " alpha=" + view.getAlpha()
                    + " bg=" + describeDrawable(view.getBackground())
                    + " fg=" + describeDrawable(view.getForeground()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpPanelCandidates(group.getChildAt(i), depth + 1);
            }
        }
    }

    /** Background/foreground descriptor: class plus the colour when it has one. */
    private static String describeDrawable(android.graphics.drawable.Drawable d) {
        if (d == null) {
            return "null";
        }
        String name = d.getClass().getSimpleName();
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return name + "#" + Integer.toHexString(
                        ((android.graphics.drawable.ColorDrawable) d).getColor());
            }
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                return name + "#" + Integer.toHexString(
                        ((android.graphics.drawable.GradientDrawable) d).getColor()
                                .getDefaultColor());
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    /**
     * A panel is "open" when it is laid out with a size and not GONE/INVISIBLE.
     * {@code isShown()} cannot be used: WeChat keeps the footer at a layout
     * position below the window and slides it in, so isShown() reports false for
     * a panel that is plainly on screen — gating on it silently skipped the
     * glass pass and left the panel transparent after the first re-open.
     */
    private static boolean isLaidOutVisible(View view) {
        return view != null
                && view.getVisibility() == View.VISIBLE
                && view.getWidth() > 0
                && view.getHeight() > 0
                && view.getParent() != null;
    }

    /**
     * The "+" panel is inflated lazily (ViewStub) and re-created by WeChat, so a
     * cached reference can point at a view that is no longer in the tree — the
     * panel then silently stops being styled and renders transparent. Re-resolve
     * whenever the cached view is gone or detached.
     */
    private View resolveFooterPanel() {
        if (footer != null && (footerPanel == null
                || !footerPanel.isAttachedToWindow()
                || footerPanel.getParent() == null)) {
            View found = Ui.findByClassContains(footer, "AppPanel");
            if (found == null) {
                found = footer.findViewById(ID_FOOTER_PANEL);
            }
            if (found != null && found != footerPanel) {
                footerPanel = found;
                android.util.Log.i(TAG, "footerPanel resolved "
                        + found.getClass().getName());
            }
        }
        return footerPanel;
    }

    private long lastPanelStyleLog;
    private long lastPanelScan;

    /**
     * Styles every panel instance in the footer, not just the first one found.
     *
     * <p>WeChat does not reuse a panel between opens: each open inflates a fresh
     * {@code AppPanel}/{@code SmileyPanelImpl}, and the previous one stays in the
     * tree (hidden). {@code Ui.findByClassContains} returns the first match, i.e.
     * permanently the stale instance, so only the first open of a session ever
     * showed glass. Walking the subtree and styling every instance fixes that,
     * whichever one WeChat decides to show.
     */
    private void scanPanels(boolean force) {
        if (footer == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastPanelScan < 48L) {
            return;
        }
        lastPanelScan = now;
        scanPanels(footer, 0);
    }

    private void scanPanels(View view, int depth) {
        if (view == null || depth > 9) {
            return;
        }
        String name = view.getClass().getSimpleName();
        if (name.contains("AppPanel") || name.contains("SmileyPanelImpl")
                || name.contains("ChatFooterPanel")) {
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                watchPanel(view);
                ensurePanelGlass(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scanPanels(group.getChildAt(i), depth + 1);
            }
        }
    }
    private final java.util.HashSet<View> watchedPanels = new java.util.HashSet<>();

    /**
     * Puts the liquid glass on a panel, and keeps it there.
     *
     * <p>WeChat clears a panel's background itself and re-creates the panel view
     * outright between opens (the log shows a different identity hash each
     * time). A 600ms poll reliably misses the moment it is visible, which is
     * exactly the "first open is glass, second open is not" bug. A pre-draw
     * listener re-asserts the drawable on the frame the panel is about to be
     * painted, so whoever cleared it cannot win.
     */
    private void watchPanel(final View panel) {
        if (panel == null || watchedPanels.contains(panel)) {
            return;
        }
        watchedPanels.add(panel);
        // A layout listener, not a pre-draw one: ensurePanelGlass() sets a
        // background, which requests layout. Doing that inside the draw pass
        // stalls the next traversal, and the chat page then stops refreshing
        // altogether. onLayoutChange fires exactly when the panel is laid out or
        // becomes visible, which is when the glass has to be there.
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                ensurePanelGlass(v);
            }
        });
        android.util.Log.i(TAG, "watch panel " + panel.getClass().getSimpleName());
    }

    private boolean ensurePanelGlass(View panel) {
        if (!isLaidOutVisible(panel)) {
            // Remember that this object is off screen, so the next open counts
            // as a transition even when WeChat reuses the same instance.
            panelVisibleSeen.put(panel, Boolean.FALSE);
            return false;
        }
        float radius = Ui.dp(activity, 26f);
        boolean night = Ui.isNight(activity);
        android.graphics.drawable.Drawable bg = panel.getBackground();
        if (com.wechatglass.liquid.LiquidPanelFactory.isPanelGlass(bg)) {
            com.wechatglass.liquid.LiquidPanelFactory.setPanelRadii(bg, 0f, radius);
            com.wechatglass.liquid.LiquidPanelFactory.setPanelTheme(bg, night);
            keepPanelPainting(panel, bg);
            return true;
        }
        android.graphics.drawable.Drawable glass =
                com.wechatglass.liquid.LiquidPanelFactory.createPanelGlass(
                        panel, chatContentGroup, night, 0f, radius);
        if (glass == null) {
            return false;
        }
        panel.setBackground(glass);
        android.util.Log.i(TAG, "panel glass set on " + panel.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(panel))
                + " drawable@" + Integer.toHexString(System.identityHashCode(glass))
                + " h=" + panel.getHeight());
        keepPanelPainting(panel, glass);
        return true;
    }

    /** Rate limit for the repaint watchdog's log line. */
    private long lastPanelPaintLog;
    /** Per-object visibility memory: WeChat hands us a fresh panel per open. */
    private final java.util.WeakHashMap<View, Boolean> panelVisibleSeen =
            new java.util.WeakHashMap<>();

    /**
     * Re-renders a panel the moment it becomes visible.
     *
     * <p>WeChat slides the footer up over a cached layer: the panel is drawn
     * during that slide, and afterwards the subtree stops being painted, so a
     * background applied after that first frame never reaches the screen. That
     * is the "glass on the first open only" bug, and it is object-based on
     * purpose — no class name is involved.
     *
     * <p>Only the open transition forces the pass. An idle watchdog was tried
     * and rejected: waiting for the drawable to go quiet delayed the glass by
     * up to 1.5s, which is plainly visible during the slide-in.
     */
    private void keepPanelPainting(View panel, android.graphics.drawable.Drawable bg) {
        Boolean seen = panelVisibleSeen.put(panel, Boolean.TRUE);
        if (seen != null && seen.booleanValue()) {
            return; // already visible last tick: nothing to repair
        }
        panel.invalidate();
        ViewParent parent = panel.getParent();
        if (parent instanceof View) {
            ((View) parent).invalidate();
            ViewParent up = ((View) parent).getParent();
            if (up instanceof View) {
                ((View) up).invalidate();
            }
        }
        // The capture may predate the current page content, so re-sample too.
        com.wechatglass.liquid.LiquidPanelFactory.invalidatePanelCapture(bg);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPanelPaintLog > 2000L) {
            lastPanelPaintLog = now;
            Log.i(TAG, "panel repaint on open, target@"
                    + Integer.toHexString(System.identityHashCode(panel)));
        }
    }

    /** Rate-limited state line so a panel that stops being styled is visible. */
    private void logPanelStyle(String tag, View panel) {
        if (!"1".equals(prop("debug.wxglass.panel.log"))) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPanelStyleLog < 900L) {
            return;
        }
        lastPanelStyleLog = now;
        android.util.Log.i(TAG, "style " + tag
                + " panel=" + (panel == null ? "null"
                : panel.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(panel)))
                + " vis=" + (panel == null ? -1 : panel.getVisibility())
                + " size=" + (panel == null ? 0 : panel.getWidth()) + "x"
                + (panel == null ? 0 : panel.getHeight())
                + " parent=" + (panel == null || panel.getParent() == null
                ? "null" : "ok")
                + " attached=" + (panel != null && panel.isAttachedToWindow())
                + " bg=" + (panel == null ? "null"
                : String.valueOf(panel.getBackground()).replace("com.wechatglass.", "")));
    }

    private void styleEmojiPanel() {
        if (footer == null) return;
        View panel = Ui.findByClassContains(footer, "SmileyPanelImpl");
        if (panel == null) {
            panel = Ui.findByClassContains(footer, "ChatFooterPanel");
        }
        logPanelStyle("emoji", panel);
        float radius = Ui.dp(activity, 26f);
        boolean open = isLaidOutVisible(panel);
        if (open != panelWasOpen) {
            panelWasOpen = open;
            android.util.Log.i(TAG, "CB panel open=" + open
                    + " cls=" + (panel == null ? "null" : panel.getClass().getSimpleName())
                    + " size=" + (panel == null ? 0 : panel.getWidth()) + "x"
                    + (panel == null ? 0 : panel.getHeight())
                    + " bg=" + (panel == null ? "null" : String.valueOf(panel.getBackground()))
                    + " backdrop=" + (chatContentGroup == null ? "null"
                    : chatContentGroup.getClass().getSimpleName())
                    + " night=" + Ui.isNight(activity));
            dumpPanelChain(panel, open);
        }
        if (panel == null || !open) {
            if (footer.getBackground() instanceof GlassBarDrawable) {
                ((GlassBarDrawable) footer.getBackground()).setRadii(radius, radius);
            }
            return;
        }
        watchPanel(panel);
        ensurePanelGlass(panel);
        if (footer.getBackground() instanceof GlassBarDrawable) {
            ((GlassBarDrawable) footer.getBackground()).setRadii(radius, 0f);
        }
        clearEmojiBackgrounds(panel, 0);
        View tabBar = Ui.findByClassContains(panel, "RecyclerView");
        if (tabBar != null) {
            tabBar.setBackground(null);
        }
        View nested = Ui.findByClassContains(panel, "NestedBounceView");
        if (nested != null) {
            nested.setBackground(null);
        }
        View emojiGrid = Ui.findByClassContains(panel, "EmojiPanelGroupView");
        if (emojiGrid != null) {
            emojiGrid.setBackground(null);
            ChatBubbleStyler.roundStickerPanel(emojiGrid, activity);
        }
    }

    private void clearEmojiBackgrounds(View root, int depth) {
        if (root == null || depth > 6) return;
        if (depth > 0 && !root.getClass().getName().contains("Image")) {
            root.setBackground(null);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearEmojiBackgrounds(group.getChildAt(i), depth + 1);
            }
        }
    }

    private void applyInputHeight() {
        if (footer == null) return;
        View input = footer.findViewById(0x7f0a0f81);
        if (input == null || !input.isShown() || input.getHeight() == 0) return;
        View editView = null;
        if (input instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) input;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.EditText && child.isShown()) {
                    editView = child;
                    break;
                }
            }
        }
        if (editView == null) return;
        android.widget.EditText edit = (android.widget.EditText) editView;
        int lines = Math.max(1, edit.getLineCount());
        int content = Math.max(1, edit.getLineHeight()) * lines;
        int minHeight = Ui.dp(activity, 32f);
        int pad = Ui.dp(activity, 12f);
        int target = Math.max(minHeight, content + pad);
        int extra = Math.max(0, target - content);
        int top = extra / 2;
        int bottom = extra - top;
        if (edit.getMinHeight() != target) edit.setMinHeight(target);
        if (edit.getPaddingTop() != top || edit.getPaddingBottom() != bottom) {
            edit.setPadding(edit.getPaddingLeft(), top, edit.getPaddingRight(), bottom);
        }
        ViewGroup.LayoutParams lp = input.getLayoutParams();
        if (lp != null && lp.height != target) {
            lp.height = target;
            input.setLayoutParams(lp);
        }
        View parent = (View) input.getParent();
        if (parent != null) {
            ViewGroup.LayoutParams pp = parent.getLayoutParams();
            if (pp != null && pp.height != target) {
                pp.height = target;
                parent.setLayoutParams(pp);
            }
        }
    }

    private void applyListPadding(int footerHeight) {
        if (list == null || !listBaseSaved) {
            return;
        }
        int actionBarHeight = actionBar == null ? 0 : actionBar.getHeight();
        int topPad = actionBarHeight + Ui.dp(activity, 6f);
        int bottomPad = Math.max(0, footerHeight) + Ui.dp(activity, 14f);
        int expectedTop = listBaseTop + topPad;
        int expectedBottom = listBaseBottom + bottomPad;
        if (list.getPaddingTop() == expectedTop
                && list.getPaddingBottom() == expectedBottom
                && list.getPaddingLeft() == listBaseLeft
                && list.getPaddingRight() == listBaseRight) {
            return;
        }
        appliedTopPad = topPad;
        appliedBottomPad = bottomPad;
        list.setPadding(listBaseLeft, expectedTop, listBaseRight, expectedBottom);
    }

    private void updateTopGeometry() {
        if (!attached || actionBar == null || actionBar.getWidth() <= 0
                || actionBar.getHeight() <= 0) {
            return;
        }

        int abWidth = actionBar.getWidth();
        int abHeight = actionBar.getHeight();
        int chipHeight = Ui.dp(activity, 44f);
        int padLeft = actionBar.getPaddingLeft();
        int padTop = actionBar.getPaddingTop();

        int[] abLoc = new int[2];
        int[] childLoc = new int[2];
        actionBar.getLocationOnScreen(abLoc);

        int contentTop = Ui.dp(activity, 4f);
        if (backView != null && backView.getHeight() > 0) {
            backView.getLocationOnScreen(childLoc);
            int backTop = childLoc[1] - abLoc[1] - padTop;
            contentTop = backTop + (backView.getHeight() - chipHeight) / 2;
        }
        // If the window is already drawing behind the status bar, keep the
        // islands below it; if WeChat already insets the ActionBar, this is 0.
        int minTopForStatus = Math.max(0, Ui.statusBarHeight(decor)
                + Ui.dp(activity, 4f) - abLoc[1]);
        int top = Math.max(contentTop, minTopForStatus);
        // Do not clamp the floating bar back into the status bar when the
        // ActionBar itself is shorter than status + chip.        

        int backX;
        if (backView != null && backView.getWidth() > 0) {
            backView.getLocationOnScreen(childLoc);
            backX = childLoc[0] - abLoc[0] - padLeft
                    + backView.getWidth() / 2 - chipHeight / 2;
        } else {
            backX = Ui.dp(activity, 8f);
        }
        backX = clamp(backX, Ui.dp(activity, 4f), abWidth - chipHeight - Ui.dp(activity, 4f));

        int titleX;
        int titleW;
        if (titleView != null && titleView.getWidth() > 0) {
            titleView.getLocationOnScreen(childLoc);
            int rawX = childLoc[0] - abLoc[0] - padLeft;
            int rawW = titleView.getWidth();
            titleW = Math.max(Ui.dp(activity, 120f), rawW + Ui.dp(activity, 28f));
            titleX = rawX + rawW / 2 - titleW / 2;
        } else {
            titleW = Math.min(Ui.dp(activity, 220f), abWidth - Ui.dp(activity, 120f));
            titleX = (abWidth - titleW) / 2;
        }
        titleX = clamp(titleX, backX + chipHeight + Ui.dp(activity, 4f),
                abWidth - titleW - Ui.dp(activity, 4f));

        int menuX;
        int menuW;
        if (menuView != null && menuView.getWidth() > 0) {
            menuView.getLocationOnScreen(childLoc);
            int rawX = childLoc[0] - abLoc[0] - padLeft;
            menuW = Math.max(chipHeight, menuView.getWidth() + Ui.dp(activity, 20f));
            menuX = rawX + menuView.getWidth() - menuW + Ui.dp(activity, 8f);
        } else {
            menuW = chipHeight;
            menuX = abWidth - menuW - Ui.dp(activity, 8f);
        }
        menuX = clamp(menuX, titleX + titleW + Ui.dp(activity, 4f),
                abWidth - menuW - Ui.dp(activity, 4f));

        int maxTitleW = menuX - Ui.dp(activity, 4f) - titleX;
        if (titleW > maxTitleW) {
            titleW = Math.max(Ui.dp(activity, 90f), maxTitleW);
        }
        if (titleX + titleW > abWidth - Ui.dp(activity, 4f)) {
            titleX = Math.max(Ui.dp(activity, 4f), abWidth - Ui.dp(activity, 4f) - titleW);
        }

        applyChip(backChip, backX, top, chipHeight, chipHeight, chipHeight / 2f);
        applyChip(titleChip, titleX, top, titleW, chipHeight, Ui.dp(activity, 22f));
        applyChip(menuChip, menuX, top, menuW, chipHeight, chipHeight / 2f);
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
        chip.setGlass(radius, chipTint, chipBorder);
    }

    /**
     * Solid-to-transparent status-bar gradient (white in light mode, black in
     * dark). Inserted before the floating top bar so chips stay on top.
     */
    private void ensureStatusScrim() {
        if (statusScrim != null || decor == null) {
            return;
        }
        boolean night = Ui.isNight(activity);
        int height = Ui.statusBarHeight(decor) + Ui.dp(activity, 8f);
        View scrim = new View(activity);
        scrim.setClickable(false);
        scrim.setFocusable(false);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                night
                        ? new int[]{0xFF000000, 0xD9000000, 0x00000000}
                        : new int[]{0xFFFFFFFF, 0xD9FFFFFF, 0x00FFFFFF}));
        int status = Ui.statusBarHeight(decor);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                status * 2 + Ui.dp(activity, 8f), Gravity.TOP);
        // Draw inside the floating top bar, shifted up to cover the status bar,
        // so it stays above chat content and below the chips.
        lp.topMargin = -status;
        ViewGroup parent = null;
        if (actionBar instanceof ViewGroup) {
            parent = (ViewGroup) actionBar;
            parent.setClipChildren(false);
            if (parent.getParent() instanceof ViewGroup) {
                ((ViewGroup) parent.getParent()).setClipChildren(false);
            }
        }
        if (parent != null) {
            parent.addView(scrim, 0, lp);
        } else if (contentFrame != null) {
            contentFrame.addView(scrim, lp);
        } else if (decor instanceof ViewGroup) {
            ((ViewGroup) decor).addView(scrim, lp);
        } else {
            return;
        }
        statusScrim = scrim;
    }

    private void destroy() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        if (globalLayoutListener != null && decor != null) {
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
            globalLayoutListener = null;
        }
        if (scrollListener != null && scrollLayout != null) {
            scrollLayout.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            scrollListener = null;
        }
        if (statusScrim != null && statusScrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) statusScrim.getParent()).removeView(statusScrim);
        }
        statusScrim = null;
        if (list != null && listBaseSaved) {
            list.setPadding(listBaseLeft, listBaseTop, listBaseRight, listBaseBottom);
        }
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        if (actionBar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) actionBar;
            if (backChip != null) {
                group.removeView(backChip);
            }
            if (titleChip != null) {
                group.removeView(titleChip);
            }
            if (menuChip != null) {
                group.removeView(menuChip);
            }
        }
        if (navFiller != null) {
            if (navFiller.getParent() instanceof ViewGroup) {
                ((ViewGroup) navFiller.getParent()).removeView(navFiller);
            }
            navFiller = null;
        }
        restoreActionBarParent();
    }

    /** Put the chat ActionBar back where WeChat had it before we reparented it. */
    private void restoreActionBarParent() {
        if (actionBar == null || actionBarOriginalParent == null) {
            return;
        }
        if (actionBar.getParent() == actionBarOriginalParent) {
            actionBarOriginalParent = null;
            return;
        }
        ViewParent current = actionBar.getParent();
        if (current instanceof ViewGroup) {
            ((ViewGroup) current).removeView(actionBar);
        }
        ViewGroup.LayoutParams lp = actionBarOriginalParams;
        int index = actionBarOriginalIndex;
        try {
            if (lp != null && index >= 0) {
                actionBarOriginalParent.addView(actionBar, index, lp);
            } else if (lp != null) {
                actionBarOriginalParent.addView(actionBar, lp);
            } else if (index >= 0) {
                actionBarOriginalParent.addView(actionBar, index);
            } else {
                actionBarOriginalParent.addView(actionBar);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "restore actionbar failed", throwable);
        }
        actionBarOriginalParent = null;
        actionBarOriginalParams = null;
        actionBarOriginalIndex = -1;
    }

    private void dumpTree(View view, int depth) {
        if (view == null || depth > 40) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(view.getClass().getName());
        int id = view.getId();
        if (id != View.NO_ID) {
            builder.append(" id=0x").append(Integer.toHexString(id));
        }
        builder.append(" ").append(view.getLeft()).append(',').append(view.getTop())
                .append(',').append(view.getRight()).append(',').append(view.getBottom());
        builder.append(" vis=").append(view.getVisibility());
        builder.append(" a=").append(view.getAlpha());
        builder.append(" ty=").append(view.getTranslationY());
        if (view.getBackground() != null) {
            builder.append(" bg=").append(view.getBackground().getClass().getSimpleName());
        }
        Log.i(TAG, builder.toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpTree(group.getChildAt(i), depth + 1);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }
}
