package com.wechatglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Settings live in WeChat's own SharedPreferences, written by the hook when it
 * receives a broadcast from the module's settings UI. Cross-app file access is
 * blocked on modern Android, so the broadcast is the transport.
 */
final class HookConfig {

    static final String ACTION = "com.wechatglass.CONFIG";
    static final String ACTION_QUERY = "com.wechatglass.QUERY";
    static final String ACTION_STATE = "com.wechatglass.STATE";
    static final String PREFS = "wechatglass";
    static final String KEY_GLASS = "glass";
    static final String KEY_NATIVE_LIST = "native_list";
    static final String KEY_COLOR_MONET = "color_monet";
    static final String KEY_COLOR_SEED = "color_seed";
    static final String KEY_MESSAGE_TIME = "message_time";
    /** Media/sticker messages get a bubble (only meaningful under Material Design). */
    static final String KEY_MEDIA_BUBBLE = "media_bubble";
    /** Sender avatars are clipped to circles (only meaningful under Material Design). */
    static final String KEY_ROUND_AVATAR = "round_avatar";
    static final String KEY_BALANCE_CARD = "balance_card";
    static final int DEFAULT_SEED = 0xFF07C160;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static volatile Runnable onChange;

    private HookConfig() {
    }

    static void setOnChange(Runnable listener) {
        onChange = listener;
    }

    static void installReceiver(Context context) {
        if (context == null || !REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    if (ACTION_QUERY.equals(intent.getAction())) {
                        android.util.Log.i("WeChatGlassCfg", "QUERY received");
                        Intent reply = new Intent(ACTION_STATE);
                        reply.setPackage("io.github.yingxingyswx_design.wechatglass");
                        reply.putExtra(KEY_GLASS, ctx.getSharedPreferences(PREFS, 0)
                                .getBoolean(KEY_GLASS, true));
                        reply.putExtra(KEY_NATIVE_LIST, ctx.getSharedPreferences(PREFS, 0)
                                .getBoolean(KEY_NATIVE_LIST, true));
                        reply.putExtra(KEY_COLOR_MONET, ctx.getSharedPreferences(PREFS, 0)
                                .getBoolean(KEY_COLOR_MONET, true));
                        reply.putExtra(KEY_COLOR_SEED, ctx.getSharedPreferences(PREFS, 0)
                                .getInt(KEY_COLOR_SEED, DEFAULT_SEED));
                        reply.putExtra(KEY_MESSAGE_TIME, messageTimeEnabled(ctx));
                        reply.putExtra(KEY_MEDIA_BUBBLE, mediaBubbleEnabled(ctx));
                        reply.putExtra(KEY_ROUND_AVATAR, roundAvatarEnabled(ctx));
                        reply.putExtra(KEY_BALANCE_CARD, balanceCardEnabled(ctx));
                        ctx.sendBroadcast(reply);
                        android.util.Log.i("WeChatGlassCfg", "STATE sent");
                        return;
                    }
                    if (!ACTION.equals(intent.getAction())) {
                        return;
                    }
                    boolean glass = intent.getBooleanExtra(KEY_GLASS, true);
                    boolean nativeList = intent.getBooleanExtra(KEY_NATIVE_LIST, false);
                    boolean monet = intent.getBooleanExtra(KEY_COLOR_MONET, true);
                    int seed = intent.getIntExtra(KEY_COLOR_SEED, DEFAULT_SEED);
                    boolean messageTime = intent.getBooleanExtra(KEY_MESSAGE_TIME, false);
                    boolean mediaBubble = intent.getBooleanExtra(KEY_MEDIA_BUBBLE, false);
                    boolean roundAvatar = intent.getBooleanExtra(KEY_ROUND_AVATAR, false);
                    boolean balanceCard = intent.getBooleanExtra(KEY_BALANCE_CARD, false);
                    ctx.getSharedPreferences(PREFS, 0).edit()
                            .putBoolean(KEY_GLASS, glass)
                            .putBoolean(KEY_NATIVE_LIST, nativeList)
                            .putBoolean(KEY_COLOR_MONET, monet)
                            .putInt(KEY_COLOR_SEED, seed)
                            .putBoolean(KEY_MESSAGE_TIME, messageTime)
                            .putBoolean(KEY_MEDIA_BUBBLE, mediaBubble)
                            .putBoolean(KEY_ROUND_AVATAR, roundAvatar)
                            .putBoolean(KEY_BALANCE_CARD, balanceCard)
                            .apply();
                    com.wechatglass.m3.M3Theme.refresh();
                    android.util.Log.i("WeChatGlass", "config broadcast glass=" + glass
                            + " nativeList=" + nativeList + " monet=" + monet
                            + " seed=#" + Integer.toHexString(seed));
                    Runnable listener = onChange;
                    if (listener != null) {
                        listener.run();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            filter.addAction(ACTION_QUERY);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            android.util.Log.i("WeChatGlass", "config receiver registered");
        } catch (Throwable throwable) {
            android.util.Log.e("WeChatGlass", "config receiver failed", throwable);
        }
    }

    static boolean glassEnabled(Context context) {
        if (context == null) {
            return false;
        }
        boolean enabled = context.getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_GLASS, true);
        android.util.Log.i("WeChatGlass", "config glass=" + enabled);
        return enabled;
    }

    static boolean messageTimeEnabled(Context context) {
        // The broadcast writes WeChat's own prefs first. Prefer that value so
        // an async GUI .apply() cannot leave a stale true in XSharedPreferences.
        android.content.SharedPreferences hook =
                context.getSharedPreferences(PREFS, 0);
        if (hook.contains(KEY_MESSAGE_TIME)) {
            return hook.getBoolean(KEY_MESSAGE_TIME, false);
        }
        try {
            de.robv.android.xposed.XSharedPreferences module =
                    new de.robv.android.xposed.XSharedPreferences(
                            "io.github.yingxingyswx_design.wechatglass", "settings");
            module.reload();
            if (module.contains(KEY_MESSAGE_TIME)) {
                return module.getBoolean(KEY_MESSAGE_TIME, false);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean nativeList(Context context) {
        return context == null || context.getSharedPreferences(PREFS, 0)
                .getBoolean(KEY_NATIVE_LIST, true);
    }

    /** Media/sticker bubbles: on by default, independent of the list mode. */
    static boolean mediaBubbleEnabled(Context context) {
        return boolWithModuleFallback(context, KEY_MEDIA_BUBBLE, false);
    }

    /** Circular sender avatars: on by default, independent of the list mode. */
    static boolean balanceCardEnabled(Context context) {
        return boolWithModuleFallback(context, KEY_BALANCE_CARD, false);
    }

    static boolean roundAvatarEnabled(Context context) {
        return boolWithModuleFallback(context, KEY_ROUND_AVATAR, false);
    }

    /**
     * Hook prefs first (the broadcast writes them synchronously), then the
     * module's own preferences for a cold start before any broadcast arrived.
     */
    private static boolean boolWithModuleFallback(Context context, String key,
                                                  boolean fallback) {
        if (context == null) {
            return fallback;
        }
        android.content.SharedPreferences hook =
                context.getSharedPreferences(PREFS, 0);
        if (hook.contains(key)) {
            return hook.getBoolean(key, fallback);
        }
        try {
            de.robv.android.xposed.XSharedPreferences module =
                    new de.robv.android.xposed.XSharedPreferences(
                            "io.github.yingxingyswx_design.wechatglass", "settings");
            module.reload();
            if (module.contains(key)) {
                return module.getBoolean(key, fallback);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
