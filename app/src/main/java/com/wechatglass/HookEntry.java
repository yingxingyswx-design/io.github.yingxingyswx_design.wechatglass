package com.wechatglass;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Minimal hook surface: two WeChat UI classes (HomeUI.u, ChatFooter.r1), the
 * ActionBarContainer visibility guard, and the two UI shells' lifecycle.
 * No draw/status-bar/inset hooks are registered.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String WECHAT = "com.tencent.mm";
    private static final String CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";
    private static final String TAG = "WeChatGlass";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !WECHAT.equals(lpparam.packageName)) {
            return;
        }
        try {
            hookExactBarVisibility(lpparam);
            // 余额采样属于正式功能：按 GUI 开关生效，release 包同样可用。
            com.wechatglass.WalletBalanceCache.install(lpparam.classLoader);
            hookChatLifecycle(lpparam);
            hookHomeLifecycle(lpparam);
            hookResume(CHATTING_UI, lpparam, true);
            hookResume(LAUNCHER_UI, lpparam, false);
            hookHomeAdapterSetter(lpparam.classLoader);
            hookHomeBottomTab(lpparam.classLoader);
            if (WECHAT.equals(lpparam.processName)) {
                com.wechatglass.liquidtab.LiquidTabHook.install(lpparam.classLoader);
            }
            XposedBridge.log(TAG + " hooks installed for " + WECHAT);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " hook install failed: " + throwable);
        }
    }

    private static void hookChatLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookPanelBgMethods(lpparam.classLoader);
        XposedHelpers.findAndHookMethod(CHATTING_UI, lpparam.classLoader, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                ChatGlassHook.attach((Activity) param.thisObject);
                                ChatBubbleStyler.attach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " attach failed: " + throwable);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(CHATTING_UI, lpparam.classLoader,
                "onConfigurationChanged", Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                ChatGlassHook.attach((Activity) param.thisObject);
                                ChatBubbleStyler.attach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " config refresh failed: " + throwable);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(CHATTING_UI, lpparam.classLoader, "onPause",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            ChatBubbleStyler.detach((Activity) param.thisObject);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(CHATTING_UI, lpparam.classLoader, "onDestroy",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                ChatGlassHook.detach((Activity) param.thisObject);
                                ChatBubbleStyler.detach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " detach failed: " + throwable);
                        }
                    }
                });
    }

    private static void hookHomeLifecycle(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(LAUNCHER_UI, lpparam.classLoader, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                HomeGlassHook.attach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " home attach failed: " + throwable);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(LAUNCHER_UI, lpparam.classLoader, "onDestroy",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                HomeGlassHook.detach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " home detach failed: " + throwable);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(LAUNCHER_UI, lpparam.classLoader,
                "onConfigurationChanged", Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject instanceof Activity) {
                                HomeGlassHook.attach((Activity) param.thisObject);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + " home config failed: " + throwable);
                        }
                    }
                });
    }

    private static void hookResume(String className, XC_LoadPackage.LoadPackageParam lpparam,
                                   final boolean chat) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) {
                                    return;
                                }
                                Activity activity = (Activity) param.thisObject;
                                if (chat) {
                                    ChatGlassHook.reapplyStatusBar(activity);
                                    ChatBubbleStyler.attach(activity);
                                } else {
                                    HomeGlassHook.reapplyStatusBar(activity);
                                    com.wechatglass.liquidtab.LiquidGlassInstaller
                                            .scheduleInstall(activity);
                                }
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG + " resume failed: " + throwable);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " resume hook skipped for " + className);
        }
    }

    /**
     * WeChat's target-35 fix pushes the ActionBar container down by the status
     * bar height; cancel the margin right after WeChat sets it.
     */
    /**
     * WeChat sets the home conversation adapter from MainUI and can do it again
     * on resume, which would silently drop our filtered wrapper. Re-wrap right
     * after it hands the adapter to the ListView.
     */
    private static void hookHomeAdapterSetter(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("iq5.a1", cl, "Z",
                    android.widget.ListView.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object target = param.args[0];
                                if (target instanceof android.widget.ListView) {
                                    HomeConversationFilter.attach(
                                            (android.widget.ListView) target);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + " home filter hooked: iq5.a1.Z");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " home filter Z skipped: " + throwable);
        }
    }

    /**
     * Tracks the bottom tab through LauncherUIBottomTabView.setTo(int) so the
     * floating home bar follows the actual page instead of guessing from the
     * title text (the "Me" page has no title at all).
     */
    /** WeChat rebinds its own 9-patch on every text row; keep our bubble. */
    private static void hookNeatTextBackground(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.ui.widget.MMNeat7extView", cl,
                    "setBackgroundResource", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ChatBubbleStyler.isActive()
                                    && param.thisObject instanceof android.widget.TextView) {
                                ChatBubbleStyler.force((android.widget.TextView) param.thisObject);
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void hookNeatTextAttach(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.ui.widget.MMNeat7extView", cl,
                    "onAttachedToWindow", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ChatBubbleStyler.isActive()
                                    && param.thisObject instanceof android.widget.TextView) {
                                ChatBubbleStyler.attachText(
                                        (android.widget.TextView) param.thisObject);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static boolean textSetLogged;
private static boolean canvasTextLogged;
private static void hookHomeBottomTab(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.ui.LauncherUIBottomTabView", cl, "setTo",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object tab = param.args[0];
                                if (tab instanceof Integer) {
                                    HomeGlassHook.onBottomTab((Integer) tab);
                                    final int navIndex = (Integer) tab;
                                    HomeGlassHook.applyBottomTabTheme(
                                            param.thisObject, navIndex);
                                    // MainTabUI can re-apply its own green
                                    // text color right after setTo(); run once
                                    // more on the next frame to win that race.
                                    if (param.thisObject instanceof android.view.View) {
                                        ((android.view.View) param.thisObject).postDelayed(
                                                () -> HomeGlassHook.applyBottomTabTheme(
                                                        null, navIndex), 80L);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + " bottom tab hooked");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " bottom tab hook skipped: " + throwable);
        }
    }

    private static void hookHomeUiTopMargin(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.HomeUI",
                    lpparam.classLoader, "u", Activity.class, View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View view = (View) param.args[1];
                                if (view == null) {
                                    return;
                                }
                                if (view.getClass().getName().contains("ActionBarOverlayLayout")) {
                                    return;
                                }
                                ViewGroup.LayoutParams params = view.getLayoutParams();
                                if (params instanceof ViewGroup.MarginLayoutParams) {
                                    ViewGroup.MarginLayoutParams margins =
                                            (ViewGroup.MarginLayoutParams) params;
                                    if (margins.topMargin != 0) {
                                        margins.topMargin = 0;
                                        view.setLayoutParams(margins);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " HomeUI margin hook skipped: " + throwable);
        }
    }

    private static int readInt(Object target, String name) {
        try {
            Object value = XposedHelpers.getObjectField(target, name);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void hookChatFooterPanel(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter",
                    lpparam.classLoader, "r1", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object footer = param.thisObject;
                                int x2 = readInt(footer, "x2");
                                if (x2 != 2) return;
                                View contextView = (View) footer;
                                float xdpi = contextView.getResources()
                                        .getDisplayMetrics().xdpi;
                                int reduce = Math.max(1,
                                        Math.round(0.15f / 2.54f * xdpi));
                                int v2 = readInt(footer, "V2");
                                int t2 = readInt(footer, "T2");
                                v2 = Math.max(1, v2 - reduce);
                                t2 = Math.max(1, t2 - reduce);
                                XposedHelpers.setIntField(footer, "V2", v2);
                                XposedHelpers.setIntField(footer, "T2", t2);
                                Object z = XposedHelpers.getObjectField(footer, "z");
                                if (z instanceof View) {
                                    ViewGroup.LayoutParams lp =
                                            ((View) z).getLayoutParams();
                                    if (lp != null) {
                                        lp.height = t2;
                                        ((View) z).setLayoutParams(lp);
                                    }
                                }
                                Object panel = XposedHelpers.getObjectField(footer, "h");
                                if (panel != null) {
                                    XposedHelpers.callMethod(panel, "setPortHeighPx", v2);
                                    XposedHelpers.callMethod(panel, "C");
                                }
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG + " panel r1 failed: " + throwable);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " panel hook skipped: " + throwable);
        }
    }

    /**
     * Keep the native home ActionBar hidden. Only the container is hooked:
     * GONE on the parent hides the Toolbar/ActionMenuView as well.
     */
    private static void hookExactBarVisibility(final XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (((Integer) param.args[0]) != View.VISIBLE
                            || !(param.thisObject instanceof View)) {
                        return;
                    }
                    View view = (View) param.thisObject;
                    if (isLauncherContext(view.getContext())) {
                        param.args[0] = View.GONE;
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(
                    "androidx.appcompat.widget.ActionBarContainer",
                    lpparam.classLoader, "setVisibility", int.class, hook);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " visibility hook skipped: " + throwable);
        }
    }

    private static boolean isLauncherContext(android.content.Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context.getClass().getName().contains("LauncherUI")) {
                return true;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return context != null && context.getClass().getName().contains("LauncherUI");
    }

    private static boolean inPanel(android.view.View v) {
        String self = v.getClass().getSimpleName();
        if (self.contains("Smiley") || self.contains("Emoji")
                || self.contains("AppPanel") || self.contains("NestedBounce")
                || self.contains("ChatFooter")) {
            return true;
        }
        android.view.ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof android.view.View && guard++ < 12) {
            String cn = p.getClass().getSimpleName();
            if (cn.contains("Smiley") || cn.contains("Emoji") || cn.contains("AppPanel")
                    || cn.contains("NestedBounce") || cn.contains("ChatFooter")) {
                return true;
            }
            p = ((android.view.View) p).getParent();
        }
        return false;
    }
private static void hookPanelBgMethods(ClassLoader cl) { // hookPanelBgMethods
        // SmileyPanelImpl.setPanelBackgroundColor writes an opaque colour onto
        // the emoji content container (NestedBounceView) every time the panel
        // opens, which paints over the panel's liquid glass. The other names are
        // the tab-bar variants and older releases' API.
        String[] names = {"setEmojiPanelBackground", "setEmojiPanelTabBackground",
                "setPanelBackground", "setPanelBackgroundColor",
                "setTabBackgroundColor", "setTabBackgroundResource"};
        for (String n : names) {
            try {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                        "com.tencent.mm.view.SmileyPanelImpl", cl, n, int.class,
                        new de.robv.android.xposed.XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                p.setResult(null);
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
    }


    private static final java.util.HashSet<String> SEEN_BG = new java.util.HashSet<>();


    private static boolean SHADOW_HOOKED;
}
