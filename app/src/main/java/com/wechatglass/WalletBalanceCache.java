package com.wechatglass;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Passive balance cache.
 *
 * <p>We never ask WeChat for the balance. When the user opens the 服务 page or
 * the wallet page by themselves, that page already renders the amount as plain
 * text (「钱包 ¥0.00」/「零钱 ¥0.00」). After the page is laid out we read that
 * text once and cache it in WeChat's own prefs, so the 我 page card can show the
 * last value the user actually saw.
 *
 * <p>Read-only: no arguments or results are modified, no DB writes, no network.
 */
final class WalletBalanceCache {

    private static final String TAG = "WeChatGlassWallet";
    static final String KEY_VALUE = "wallet_balance_value";
    static final String KEY_TIME = "wallet_balance_time";
    private static final String PREFS = "wechatglass";
    private static final String UI_HOST =
            "com.tencent.mm.framework.app.UIPageFragmentActivity";
    /** 我 → 服务：a full activity that already renders 「钱包 ¥0.00」. */
    private static final String MALL_UI =
            "com.tencent.mm.plugin.mall.ui.MallIndexUIv2";
    /** Amount with a currency symbol: always a balance, never a badge count. */
    private static final Pattern MONEY_SYMBOL =
            Pattern.compile("^[¥￥]\\s?[0-9][0-9,]*(\\.[0-9]{1,2})?$");
    /** Amount without a symbol: only accepted next to a wallet word, and it must
     *  look like money ("0.00"), so badges like "1" can never be cached. */
    private static final Pattern MONEY_PLAIN =
            Pattern.compile("^[0-9][0-9,]*\\.[0-9]{2}$");

    private WalletBalanceCache() {
    }

    static void install(ClassLoader cl) {
        hookResume(cl, UI_HOST);
        hookResume(cl, MALL_UI);
        android.util.Log.i(TAG, "wallet cache hooked");
    }

    /** Samples the page twice after resume: data is rendered asynchronously. */
    private static void hookResume(ClassLoader cl, String className) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof android.app.Activity)) {
                                    return;
                                }
                                final android.app.Activity activity =
                                        (android.app.Activity) param.thisObject;
                                // 采样是正式功能，但只在用户显式开启余额卡片时生效。
                                if (!HookConfig.balanceCardEnabled(activity)) {
                                    return;
                                }
                                final View decor = activity.getWindow().getDecorView();
                                decor.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        capture(activity, decor);
                                    }
                                }, 900L);
                                decor.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        capture(activity, decor);
                                    }
                                }, 2200L);
                            } catch (Throwable throwable) {
                                android.util.Log.w(TAG, "resume hook failed", throwable);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            android.util.Log.i(TAG, "skip " + className + ": " + throwable);
        }
    }

    /** Scans an on-screen page for 「钱包/零钱 + ¥金额」; caches only when found. */
    static void capture(Context context, View decor) {
        try {
            Texts texts = new Texts();
            collect(decor, texts, 0);
            // A "¥0.40" amount is enough on its own: WeChat renders the wallet
            // page in the app language, so matching 「钱包/零钱」 alone misses
            // English ("Balance") and any other locale.
            String value = texts.symbolMoney != null
                    ? texts.symbolMoney
                    : (texts.sawWalletWord ? texts.plainMoney : null);
            if (value == null) {
                return;
            }
            String previous = value(context);
            long previousTime = time(context);
            long now = System.currentTimeMillis();
            boolean changed = !value.equals(previous);
            // Same amount also refreshes the timestamp, but not more than once
            // every 20s to avoid writing prefs on every tick.
            if (changed || now - previousTime > 20_000L) {
                context.getSharedPreferences(PREFS, 0).edit()
                        .putString(KEY_VALUE, value)
                        .putLong(KEY_TIME, now)
                        .apply();
                if (changed) {
                    android.util.Log.i(TAG, "balance cached: " + value);
                }
            }
        } catch (Throwable throwable) {
            android.util.Log.w(TAG, "capture failed", throwable);
        }
    }

    /** Text of TextView and of WeChat's custom text views (via getText()). */
    private static CharSequence textOf(View view) {
        if (view instanceof TextView) {
            return ((TextView) view).getText();
        }
        try {
            Object value = de.robv.android.xposed.XposedHelpers
                    .callMethod(view, "getText");
            if (value instanceof CharSequence) {
                return (CharSequence) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    private static final class Texts {
        boolean sawWalletWord;
        String symbolMoney;
        String plainMoney;
        final StringBuilder sample = new StringBuilder();
    }

    private static void collect(View view, Texts out, int depth) {
        if (view == null || depth > 30) {
            return;
        }
        CharSequence raw = textOf(view);
        if (raw != null && raw.length() > 0 && raw.length() < 24) {
            String text = raw.toString().trim();
            if (text.contains("零钱") || text.contains("钱包")) {
                out.sawWalletWord = true;
            }
            if (out.sample.length() < 400) {
                out.sample.append(text).append('|');
            }
            if (out.symbolMoney == null && MONEY_SYMBOL.matcher(text).matches()) {
                out.symbolMoney = text;
            } else if (out.plainMoney == null && MONEY_PLAIN.matcher(text).matches()) {
                out.plainMoney = text;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    static String value(Context context) {
        try {
            return context.getSharedPreferences(PREFS, 0)
                    .getString(KEY_VALUE, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static long time(Context context) {
        try {
            return context.getSharedPreferences(PREFS, 0)
                    .getLong(KEY_TIME, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
