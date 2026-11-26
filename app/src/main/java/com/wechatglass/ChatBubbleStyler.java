package com.wechatglass;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.wechatglass.m3.M3ColorScheme;
import com.wechatglass.m3.M3Shape;
import com.wechatglass.m3.M3Theme;
import com.wechatglass.m3.M3Type;

import java.util.ArrayList;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Styles only real chat-message items.
 *
 * <p>ChattingItemContainer gives the inflated message layout the id
 * 0x7f0a0f8a (R.id.bkj); the text inside is bkl. bkj itself is added with
 * MATCH_PARENT, so the bubble is applied to the tight parent of bkl instead.
 */
final class ChatBubbleStyler {

    private static final int ID_BKJ = 0x7f0a0f8a;
    private static final int ID_BKL = 0x7f0a0f8d;

    private static final WeakHashMap<android.app.Activity, Session> SESSIONS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, BubbleDrawable> APPLIED =
            new WeakHashMap<>();
    private static final WeakHashMap<View, ViewState> ORIGINAL = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Boolean> DECORATED =
            new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> ROUNDED_STICKERS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, android.view.ViewOutlineProvider>
            STICKER_OUTLINES = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> AVATAR_CHAIN_LOGGED =
            new WeakHashMap<>();
    private static final WeakHashMap<View, String> DIAGNOSTIC_SNAPSHOTS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, String> APPLIED_DIAGNOSTICS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, TextView> MESSAGE_TIME_LABELS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, ViewGroup> MESSAGE_TIME_HOSTS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, MessageTimePadding> MESSAGE_TIME_PADDING =
            new WeakHashMap<>();
    private static final WeakHashMap<View, CachedMessageTime> MESSAGE_TIME_CACHE =
            new WeakHashMap<>();
    private static final WeakHashMap<View, String> MESSAGE_TIME_SOURCES =
            new WeakHashMap<>();
    private static final WeakHashMap<View, String> TIME_DIAGNOSTICS =
            new WeakHashMap<>();
    private static final WeakHashMap<TextView, Integer> MESSAGE_TIME_BG =
            new WeakHashMap<>();
    private static final int MAX_TIME_GRAPH_OBJECTS = 160;
    private static final int MAX_DIAGNOSTIC_ITEMS = 48;
    private static final int MAX_DIAGNOSTIC_VIEWS = 32;
    private static final int MAX_DIAGNOSTIC_CANDIDATES = 12;
    private static int avatarChainDiag;
    private static final android.view.ViewOutlineProvider CIRCLE_OUTLINE =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            };
    private static volatile boolean active;
    private static int diagnosticItemsLogged;
    // The 600ms poller asks for the switch once per visible row; the hook side
    // may have to fall back to XSharedPreferences (file IO) when the broadcast
    // never arrived, so the answer is cached for a second.
    private static boolean messageTimeSwitchOn;
    private static long messageTimeSwitchCheckedAt;

    private static final class Session {
        final android.os.Handler handler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        Runnable poller;
        boolean enabled;
    }

    private static final class TimeSearch {
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long bestMillis;
        int bestRank = Integer.MAX_VALUE;
        int objectsVisited;
        String bestPath = "-";
        final ArrayList<String> modelPaths = new ArrayList<>();
        final ArrayList<String> timeFields = new ArrayList<>();

        void consider(long value, int depth, String path) {
            long millis = normalizeTimestamp(value);
            String lower = path.toLowerCase(Locale.ROOT);
            if ((lower.contains("time") || lower.contains("date")
                    || lower.contains("create") || lower.contains("send"))
                    && timeFields.size() < 12) {
                timeFields.add(path + (millis > 0 ? "=valid" : "=out-of-range"));
            }
            if (millis <= 0) {
                return;
            }
            int hint = lower.contains("createtime") ? 5
                    : lower.contains("msgtime") || lower.contains("sendtime") ? 4
                    : lower.contains("timestamp") || lower.contains("date") ? 3
                    : lower.contains("time") ? 2 : 0;
            int rank = depth * 10 - hint;
            if (rank < bestRank) {
                bestMillis = millis;
                bestRank = rank;
                bestPath = path;
            }
        }
    }

    private static final class CachedMessageTime {
        int bindingSignature;
        long scannedAt;
        long millis;
        String path = "-";
        int objectsVisited;
        final ArrayList<String> modelPaths = new ArrayList<>();
        final ArrayList<String> timeFields = new ArrayList<>();
    }

    private static final class MessageTimePadding {
        final int left;
        final int top;
        final int right;
        final int bottom;
        int extraTop;
        int extraBottom;

        MessageTimePadding(View view) {
            left = view.getPaddingLeft();
            top = view.getPaddingTop();
            right = view.getPaddingRight();
            bottom = view.getPaddingBottom();
        }
    }

    private static final class ViewState {
        final android.graphics.drawable.Drawable background;
        final android.content.res.ColorStateList backgroundTint;
        final android.graphics.PorterDuff.Mode backgroundTintMode;
        final android.graphics.drawable.Drawable foreground;
        final int visibility;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final int paddingStart;
        final int paddingEnd;
        final boolean paddingRelative;
        final boolean clipToOutline;
        final android.view.ViewOutlineProvider outlineProvider;
        final boolean clipChildren;
        final android.graphics.drawable.Drawable imageDrawable;
        final android.content.res.ColorStateList textColors;
        final float textSize;
        final float letterSpacing;
        final float lineSpacingExtra;
        final float lineSpacingMultiplier;
        final boolean includeFontPadding;
        final android.graphics.Typeface typeface;
        final Integer reflectedTextColor;

        ViewState(View view) {
            background = view.getBackground();
            backgroundTint = android.os.Build.VERSION.SDK_INT >= 21
                    ? view.getBackgroundTintList() : null;
            backgroundTintMode = android.os.Build.VERSION.SDK_INT >= 21
                    ? view.getBackgroundTintMode() : null;
            foreground = android.os.Build.VERSION.SDK_INT >= 23
                    ? view.getForeground() : null;
            visibility = view.getVisibility();
            paddingLeft = view.getPaddingLeft();
            paddingTop = view.getPaddingTop();
            paddingRight = view.getPaddingRight();
            paddingBottom = view.getPaddingBottom();
            paddingStart = view.getPaddingStart();
            paddingEnd = view.getPaddingEnd();
            paddingRelative = view.isPaddingRelative();
            clipToOutline = view.getClipToOutline();
            outlineProvider = view.getOutlineProvider();
            clipChildren = view instanceof ViewGroup
                    && ((ViewGroup) view).getClipChildren();
            imageDrawable = view instanceof android.widget.ImageView
                    ? ((android.widget.ImageView) view).getDrawable() : null;
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                textColors = text.getTextColors();
                textSize = text.getTextSize();
                letterSpacing = text.getLetterSpacing();
                lineSpacingExtra = text.getLineSpacingExtra();
                lineSpacingMultiplier = text.getLineSpacingMultiplier();
                includeFontPadding = text.getIncludeFontPadding();
                typeface = text.getTypeface();
                reflectedTextColor = null;
            } else {
                textColors = null;
                textSize = 0f;
                letterSpacing = 0f;
                lineSpacingExtra = 0f;
                lineSpacingMultiplier = 1f;
                includeFontPadding = true;
                typeface = null;
                reflectedTextColor = readReflectedTextColor(view);
            }
        }

        void restore(View view) {
            view.setBackground(background);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                view.setBackgroundTintList(backgroundTint);
                view.setBackgroundTintMode(backgroundTintMode);
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                view.setForeground(foreground);
            }
            view.setVisibility(visibility);
            if (paddingRelative) {
                view.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);
            } else {
                view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            }
            if (view instanceof ViewGroup) {
                ((ViewGroup) view).setClipChildren(clipChildren);
            }
            view.setOutlineProvider(outlineProvider);
            view.setClipToOutline(clipToOutline);
            if (view instanceof android.widget.ImageView) {
                android.widget.ImageView image = (android.widget.ImageView) view;
                android.graphics.drawable.Drawable current = image.getDrawable();
                if (current instanceof RoundedRectClipDrawable) {
                    image.setImageDrawable(
                            ((RoundedRectClipDrawable) current).getWrappedDrawable());
                } else if (imageDrawable != null && current instanceof OvalClipDrawable
                        && ((OvalClipDrawable) current).wraps(imageDrawable)) {
                    image.setImageDrawable(imageDrawable);
                }
            }
            if (view instanceof TextView && textColors != null) {
                TextView text = (TextView) view;
                text.setTextColor(textColors);
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
                text.setLetterSpacing(letterSpacing);
                text.setLineSpacing(lineSpacingExtra, lineSpacingMultiplier);
                text.setIncludeFontPadding(includeFontPadding);
                text.setTypeface(typeface);
            } else if (reflectedTextColor != null) {
                try {
                    view.getClass().getMethod("setTextColor", int.class)
                            .invoke(view, reflectedTextColor);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Integer readReflectedTextColor(View view) {
        try {
            Object value = view.getClass().getMethod("getTextColor").invoke(view);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ChatBubbleStyler() {
    }

    static boolean isActive() {
        return active;
    }

    static void attach(final android.app.Activity activity) {
        if (activity == null || SESSIONS.containsKey(activity)) {
            return;
        }
        final Session session = new Session();
        SESSIONS.put(activity, session);
        session.poller = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    detach(activity);
                    return;
                }
                try {
                    boolean enabled = !HookConfig.nativeList(activity);
                    session.enabled = enabled;
                    updateActive();
                    if (enabled) {
                        View decor = activity.getWindow() == null
                                ? null : activity.getWindow().getDecorView();
                        View list = decor == null ? null
                                : Ui.findByClassContains(decor, "ScrollControlRecyclerView");
                        if (list instanceof ViewGroup) {
                            ViewGroup group = (ViewGroup) list;
                            for (int i = 0; i < group.getChildCount(); i++) {
                                styleItem(group.getChildAt(i), activity);
                            }
                        }
                    } else {
                        restoreOriginalViews();
                    }
                } catch (Throwable ignored) {
                }
                session.handler.postDelayed(this, 600L);
            }
        };
        session.handler.postDelayed(session.poller, 300L);
    }

    static void force(TextView text) {
    }

    static void attachText(TextView text) {
    }

    static void mark(TextView text) {
    }

    static void detach(android.app.Activity activity) {
        if (activity != null) {
            Session session = SESSIONS.remove(activity);
            if (session != null && session.poller != null) {
                session.handler.removeCallbacks(session.poller);
            }
        }
        restoreOriginalViews();
        updateActive();
    }

    private static void updateActive() {
        active = false;
        for (Session session : SESSIONS.values()) {
            if (session != null && session.enabled) {
                active = true;
                return;
            }
        }
    }

    private static void remember(View view) {
        if (view != null && !ORIGINAL.containsKey(view)) {
            ORIGINAL.put(view, new ViewState(view));
        }
    }

    private static void restoreOriginalViews() {
        for (java.util.Map.Entry<View, ViewState> entry : ORIGINAL.entrySet()) {
            View view = entry.getKey();
            ViewState state = entry.getValue();
            if (view != null && state != null) {
                try {
                    state.restore(view);
                } catch (Throwable ignored) {
                }
            }
        }
        ORIGINAL.clear();
        for (java.util.Map.Entry<View, TextView> entry : MESSAGE_TIME_LABELS.entrySet()) {
            View item = entry.getKey();
            TextView label = entry.getValue();
            ViewGroup host = MESSAGE_TIME_HOSTS.get(item);
            if (host == null && item instanceof ViewGroup) {
                host = (ViewGroup) item;
            }
            if (host != null && label != null) {
                try {
                    host.getOverlay().remove(label);
                } catch (Throwable ignored) {
                }
            }
        }
        MESSAGE_TIME_LABELS.clear();
        MESSAGE_TIME_HOSTS.clear();
        MESSAGE_TIME_PADDING.clear();
        MESSAGE_TIME_CACHE.clear();
        MESSAGE_TIME_SOURCES.clear();
        // Force the next attach to re-read the switch instead of reusing the
        // cached answer from the previous chat screen.
        messageTimeSwitchCheckedAt = 0L;
        TIME_DIAGNOSTICS.clear();
        APPLIED.clear();
        ROUNDED_STICKERS.clear();
        STICKER_OUTLINES.clear();
        MEDIA_WATCHERS.clear();
        MEDIA_TARGET.clear();
        // Detach the media bubble ring views we added to WeChat's rows.
        for (View ring : MEDIA_BUBBLE.values()) {
            if (ring != null && ring.getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) ring.getParent()).removeView(ring);
                } catch (Throwable ignored) {
                }
            }
        }
        MEDIA_BUBBLE.clear();
        ROW_BUBBLE.clear();
        OUR_BUBBLES.clear();
        DIAGNOSTIC_SNAPSHOTS.clear();
        APPLIED_DIAGNOSTICS.clear();
        diagnosticItemsLogged = 0;
    }

    private static void styleItem(View item, Context context) {
        if (item == null) {
            return;
        }
        if ("1".equals(prop("debug.wxglass.media.tree"))) {
            dumpMediaTree(item, 0);
        }
        boolean roundAvatarOn = HookConfig.roundAvatarEnabled(context);
        boolean mediaBubbleOn = HookConfig.mediaBubbleEnabled(context);
        // Sticker messages do not necessarily contain text, so apply their
        // visual treatment before looking for a text-bubble target.
        roundStickers(item, context, 0);
        View avatar = findAvatar(item);
        if (roundAvatarOn) {
            roundChatAvatars(item, avatar);
        } else {
            // Toggled off while the chat is open: put the avatar's own shape back
            // instead of leaving the circle we applied on an earlier pass.
            restoreAvatar(avatar);
        }
        View target = findBubbleTarget(item, item, avatar, context, null, 0);
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0
                || target.getVisibility() != View.VISIBLE) {
            View media = findMediaBubbleContainer(item, avatar, context);
            View card = media == null
                    ? findRichCardObject(item, avatar, null, context) : null;
            View selected = card == null ? media : card;
            String branch = card != null ? "rich-hit:no-text-target"
                    : media != null ? "media-hit:no-text-target" : "miss:no-text-target";
            logRuntimeDetection(item, avatar, target, null, selected,
                    branch, context, selected == null);
            if (media != null) {
                if (mediaBubbleOn) {
                    styleMediaBubble(media, item, avatar, context);
                } else {
                    clearMediaBubble(media);
                }
            } else if (card != null) {
                styleRichCard(card, item, null, avatar, context);
            }
            updateMessageTime(item, selected, avatar, context);
            return;
        }
        if (containsDiagnosticMedia(item, avatar, 0)) {
            View media = findMediaBubbleContainer(item, avatar, context);
            if (media != null) {
                logRuntimeDetection(item, avatar, target, null, media,
                        "media-hit", context, false);
                if (mediaBubbleOn) {
                    styleMediaBubble(media, item, avatar, context);
                } else {
                    clearMediaBubble(media);
                }
                updateMessageTime(item, media, avatar, context);
                return;
            }
            View richCard = findRichCardObject(item, avatar, target, context);
            if (richCard != null) {
                logRuntimeDetection(item, avatar, target, null, richCard,
                        "rich-hit", context, false);
                styleRichCard(richCard, item, target, avatar, context);
                updateMessageTime(item, richCard, avatar, context);
                return;
            }
        }
        View parent = target.getParent() instanceof View
                ? (View) target.getParent() : null;
        View bubble = parent != null && parent != item ? parent : null;
        if (bubble instanceof ViewGroup
                && containsSenderName((ViewGroup) bubble, target)) {
            // Group chats put the sender name inside the same container as the
            // message. Bubbling that container paints the name too, so wrap only
            // the content view instead.
            bubble = target;
        }
        if (bubble == null) {
            bubble = item.findViewById(ID_BKJ);
        }
        String simpleFailure = bubble == null ? "bubble-missing"
                : bubble == item ? "bubble-is-row"
                : simpleBubbleFailure(item, bubble, target, avatar);
        if (simpleFailure != null) {
            View card = findRichCardObject(item, avatar, target, context);
            logRuntimeDetection(item, avatar, target, bubble, card,
                    card == null ? "miss:" + simpleFailure
                            : "rich-hit;simple-reject:" + simpleFailure,
                    context, card == null);
            if (card != null) {
                styleRichCard(card, item, target, avatar, context);
            }
            updateMessageTime(item, card != null ? card : bubble != item ? bubble : target,
                    avatar, context);
            return;
        }
        logRuntimeDetection(item, avatar, target, bubble, null,
                containsDiagnosticMedia(item, avatar, 0)
                        ? "simple-hit;media-present" : "simple-hit",
                context, containsDiagnosticMedia(item, avatar, 0));

        int screen = context.getResources().getDisplayMetrics().widthPixels;
        int[] bl = new int[2];
        bubble.getLocationOnScreen(bl);
        // Use the actual bubble container, not the text child, so the tail
        // side follows the message alignment.
        // The avatar is always on the opposite side of the sender and its
        // center never depends on message length. Fall back to margins only
        // when an item has no avatar (system/call rows).
        boolean outgoing = isOutgoingByAvatar(avatar, screen);
        if (avatar == null) {
            int rightMargin = screen - (bl[0] + bubble.getWidth());
            outgoing = bl[0] > rightMargin;
        }
        M3ColorScheme scheme = M3ColorScheme.forContext(context, true);
        int color = scheme.primary;
        int textColor = scheme.onPrimary;

        View cur = target;
        while (cur != null && cur != bubble) {
            if (cur.getBackground() != null) {
                remember(cur);
                cur.setBackground(null);
            }
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }

        float radius = M3Theme.dpF(context, M3Shape.LARGE_INCREASED);
        float tail = M3Theme.dpF(context, 7f);
        BubbleDrawable shape = new BubbleDrawable(color, radius, tail, outgoing);
        BubbleDrawable previous = APPLIED.get(bubble);
        if (previous != null && bubble.getBackground() == previous
                && previous.matches(color, radius, tail, outgoing)) {
            updateMessageTime(item, bubble, avatar, context);
            return;
        }
        remember(bubble);
        bubble.setBackground(shape);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            bubble.setBackgroundTintList(null);
        }
        shape.setBounds(0, 0, bubble.getWidth(), bubble.getHeight());
        bubble.invalidate();
        APPLIED.put(bubble, shape);
        bubble.setPadding(M3Theme.dp(context, 14f), M3Theme.dp(context, 10f),
                M3Theme.dp(context, 14f), M3Theme.dp(context, 10f));
        bubble.requestLayout();
        if (bubble.getParent() instanceof ViewGroup) {
            ViewGroup bubbleParent = (ViewGroup) bubble.getParent();
            remember(bubbleParent);
            bubbleParent.setClipChildren(false);
        }
        if (bubble instanceof ViewGroup) {
            remember(bubble);
            ((ViewGroup) bubble).setClipChildren(false);
        }
        if (bubble != target) {
            remember(target);
            target.setBackground(null);
            target.setPadding(0, 0, 0, 0);
        }
        if (target instanceof TextView) {
            remember(target);
            ((TextView) target).setTextColor(textColor);
            M3Type.apply((TextView) target, M3Type.BODY_LARGE);
        } else {
            // MMNeat7extView extends View, not TextView.
            try {
                remember(target);
                target.getClass().getMethod("setTextColor", int.class)
                        .invoke(target, textColor);
            } catch (Throwable ignored) {
            }
        }
        logAppliedSurface("text", bubble);
        updateMessageTime(item, bubble, avatar, context);
    }

    private static boolean messageTimeEnabled(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - messageTimeSwitchCheckedAt < 1000L) {
            return messageTimeSwitchOn;
        }
        messageTimeSwitchCheckedAt = now;
        messageTimeSwitchOn = HookConfig.messageTimeEnabled(context);
        return messageTimeSwitchOn;
    }

    private static void updateMessageTime(View item, View anchor, View avatar,
                                          Context context) {
        // The settings switch is the only gate. Turning it off has to undo the
        // work as well: the label lives in the row's overlay and the row carries
        // an extra top padding, so returning early without cleanup would leave
        // the pills on screen until the chat is reopened. This also skips the
        // expensive binding-signature / model-graph scan while disabled.
        if (context == null || !messageTimeEnabled(context)) {
            removeMessageTime(item);
            return;
        }
        try {
            updateMessageTimeInternal(item, anchor, avatar, context);
        } catch (Throwable error) {
            String rowClass = item == null ? "-" : item.getClass().getName();
            String anchorClass = anchor == null ? "-" : anchor.getClass().getName();
            StackTraceElement where = error.getStackTrace().length == 0
                    ? null : error.getStackTrace()[0];
            android.util.Log.e("WeChatGlass", "CB time update failed row=" + rowClass
                    + " anchor=" + anchorClass + " error=" + error.getClass().getName()
                    + " at=" + (where == null ? "-" : where.toString()));
            removeMessageTime(item);
        }
    }

    private static void updateMessageTimeInternal(View item, View anchor, View avatar,
                                                  Context context) {
        // Keep each label in its message row. A RecyclerView overlay is
        // stationary while child rows scroll, which makes labels lag and stack.
        ViewGroup overlayHost = item instanceof ViewGroup ? (ViewGroup) item : null;
        if (overlayHost == null || anchor == null || anchor == item
                || anchor.getWidth() <= 0 || anchor.getHeight() <= 0
                || anchor.getVisibility() != View.VISIBLE || !anchor.isAttachedToWindow()
                || !isDescendantOf(anchor, overlayHost)) {
            removeMessageTime(item);
            return;
        }
        int bindingSignature = messageBindingSignature(item);
        long now = android.os.SystemClock.uptimeMillis();
        CachedMessageTime cached = MESSAGE_TIME_CACHE.get(item);
        if (cached == null || cached.bindingSignature != bindingSignature
                || now - cached.scannedAt > 30000L) {
            TimeSearch scanned = new TimeSearch();
            collectMvvmViewModels(item, scanned, 0);
            collectMessageTime(item, scanned);
            cached = new CachedMessageTime();
            cached.bindingSignature = bindingSignature;
            cached.scannedAt = now;
            cached.millis = scanned.bestMillis;
            cached.path = scanned.bestPath;
            cached.objectsVisited = scanned.objectsVisited;
            cached.modelPaths.addAll(scanned.modelPaths);
            cached.timeFields.addAll(scanned.timeFields);
            MESSAGE_TIME_CACHE.put(item, cached);
        }
        if (cached.millis <= 0) {
            TimeSearch missing = new TimeSearch();
            missing.objectsVisited = cached.objectsVisited;
            missing.modelPaths.addAll(cached.modelPaths);
            missing.timeFields.addAll(cached.timeFields);
            logMissingMessageTime(item, anchor, missing);
            removeMessageTime(item);
            return;
        }
        TIME_DIAGNOSTICS.remove(item);
        TextView label = MESSAGE_TIME_LABELS.get(item);
        if (label == null) {
            label = new TextView(context);
            label.setSingleLine(true);
            label.setIncludeFontPadding(false);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setTextColor(0xFF1F1F1F);
            label.setBackground(null);
            label.setPadding(0, 0, 0, 0);
            label.setClickable(false);
            label.setLongClickable(false);
            label.setFocusable(false);
            label.setFocusableInTouchMode(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setElevation(0f);
            overlayHost.getOverlay().add(label);
            MESSAGE_TIME_LABELS.put(item, label);
            MESSAGE_TIME_HOSTS.put(item, overlayHost);
        } else if (MESSAGE_TIME_HOSTS.get(item) != overlayHost) {
            ViewGroup oldHost = MESSAGE_TIME_HOSTS.get(item);
            if (oldHost != null) {
                oldHost.getOverlay().remove(label);
            }
            overlayHost.getOverlay().add(label);
            MESSAGE_TIME_HOSTS.put(item, overlayHost);
        }
        String formattedTime = formatMessageTime(cached.millis);
        boolean textChanged = !formattedTime.contentEquals(label.getText());
        if (textChanged) {
            label.setText(formattedTime);
        }
        applyTimePill(label, item, context);
        if (textChanged || label.getMeasuredWidth() <= 0 || label.getMeasuredHeight() <= 0
                || label.getMeasuredWidth() > overlayHost.getWidth()) {
            label.measure(View.MeasureSpec.makeMeasureSpec(overlayHost.getWidth(),
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(overlayHost.getHeight(),
                            View.MeasureSpec.AT_MOST));
        }
        int labelWidth = label.getMeasuredWidth();
        int labelHeight = label.getMeasuredHeight();
        android.graphics.Rect anchorBounds = new android.graphics.Rect(
                0, 0, anchor.getWidth(), anchor.getHeight());
        overlayHost.offsetDescendantRectToMyCoords(anchor, anchorBounds);
        int gap = M3Theme.dp(context, 2f);
        MessageTimePadding padding = MESSAGE_TIME_PADDING.get(item);
        if (padding == null) {
            padding = new MessageTimePadding(item);
            MESSAGE_TIME_PADDING.put(item, padding);
        }
        // The label sits *under* the bubble: group chats print the sender name
        // directly above the bubble, so a label on top used to cover that name.
        int anchorBottom = anchorBounds.bottom + padding.extraBottom;
        int neededBottom = Math.max(0, anchorBottom + gap + labelHeight
                - (item.getHeight() - padding.bottom));
        int extraBottom = Math.max(padding.extraBottom, neededBottom);
        int extraTop = 0;
        if (extraBottom != padding.extraBottom || extraTop != padding.extraTop) {
            remember(item);
            padding.extraBottom = extraBottom;
            padding.extraTop = extraTop;
            item.setPadding(padding.left, padding.top + extraTop,
                    padding.right, padding.bottom + extraBottom);
            item.requestLayout();
            final View timeAnchor = anchor;
            final View timeAvatar = avatar;
            item.post(new Runnable() {
                @Override
                public void run() {
                    if (item.isAttachedToWindow()) {
                        updateMessageTime(item, timeAnchor, timeAvatar, context);
                    }
                }
            });
        }
        boolean outgoing = avatar != null
                ? isOutgoingByAvatar(avatar, context.getResources().getDisplayMetrics().widthPixels)
                : anchorBounds.centerX()
                > context.getResources().getDisplayMetrics().widthPixels / 2;
        int x = outgoing
                ? anchorBounds.right - labelWidth : anchorBounds.left;
        int y = anchorBounds.bottom + gap;
        if (y + labelHeight > overlayHost.getHeight()) {
            y = Math.max(0, overlayHost.getHeight() - labelHeight);
        }
        x = Math.max(0, Math.min(x, Math.max(0, overlayHost.getWidth() - labelWidth)));
        y = Math.max(0, Math.min(y, Math.max(0, overlayHost.getHeight() - labelHeight)));
        if (label.getLeft() != x || label.getTop() != y
                || label.getRight() != x + labelWidth
                || label.getBottom() != y + labelHeight) {
            label.layout(x, y, x + labelWidth, y + labelHeight);
            label.invalidate();
        }

        String source = item.getClass().getName() + ":" + cached.path
                + ":anchor=" + anchor.getClass().getName()
                + ":host=" + overlayHost.getClass().getName()
                + ":binding=" + bindingSignature;
        if (!source.equals(MESSAGE_TIME_SOURCES.get(item))) {
            MESSAGE_TIME_SOURCES.put(item, source);
            android.util.Log.i("WeChatGlass", "CB time source=" + source
                    + " row=" + item.getClass().getName()
                    + " label=" + label.getWidth() + "x" + label.getHeight());
        }
    }

    private static void logMissingMessageTime(View item, View anchor,
                                              TimeSearch search) {
        StringBuilder models = new StringBuilder();
        for (int i = 0; i < search.modelPaths.size(); i++) {
            if (i > 0) models.append('|');
            models.append(search.modelPaths.get(i));
        }
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < search.timeFields.size(); i++) {
            if (i > 0) fields.append('|');
            fields.append(search.timeFields.get(i));
        }
        String signature = "row=" + item.getClass().getName()
                + ",anchor=" + (anchor == null ? "-" : anchor.getClass().getName())
                + ",objects=" + search.objectsVisited
                + ",models=" + models + ",timeFields=" + fields;
        if (!signature.equals(TIME_DIAGNOSTICS.get(item))) {
            TIME_DIAGNOSTICS.put(item, signature);
            android.util.Log.i("WeChatGlass", "CB time missing " + signature);
        }
    }

    /** Cheaply detects RecyclerView rebinding without traversing model fields. */
    private static int messageBindingSignature(View root) {
        if (root == null) {
            return 0;
        }
        long signature = 0x9e3779b97f4a7c15L;
        ArrayList<View> pending = new ArrayList<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty() && visited < 256) {
            View view = pending.remove(pending.size() - 1);
            visited++;
            signature = signature * 31 + view.getId();
            signature = signature * 31 + view.getClass().getName().hashCode();
            signature = signature * 31 + System.identityHashCode(view.getTag());
            if (view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                signature = signature * 31 + (text == null ? 0 : text.hashCode());
            }
            Class<?> type = view.getClass();
            while (type != null && type != Object.class
                    && !"com.tencent.mm.mvvm.MvvmView".equals(type.getName())) {
                type = type.getSuperclass();
            }
            if (type != null && type != Object.class) {
                try {
                    Object model = view.getClass().getMethod("getViewModel").invoke(view);
                    signature = signature * 31 + System.identityHashCode(model);
                } catch (Throwable ignored) {
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                signature = signature * 31 + group.getChildCount();
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    pending.add(group.getChildAt(i));
                }
            }
        }
        signature = signature * 31 + visited;
        return (int) (signature ^ (signature >>> 32));
    }

    private static void collectMvvmViewModels(View view, TimeSearch search, int depth) {
        if (view == null || depth > 18
                || search.objectsVisited >= MAX_TIME_GRAPH_OBJECTS) {
            return;
        }
        Class<?> cursor = view.getClass();
        boolean mvvmView = false;
        while (cursor != null && cursor != Object.class) {
            if ("com.tencent.mm.mvvm.MvvmView".equals(cursor.getName())) {
                mvvmView = true;
                break;
            }
            cursor = cursor.getSuperclass();
        }
        if (mvvmView) {
            try {
                Object model = view.getClass().getMethod("getViewModel").invoke(view);
                if (model != null) {
                    scanTimeObject(model, search, 0,
                            "viewModel:" + view.getClass().getName(), true);
                }
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectMvvmViewModels(group.getChildAt(i), search, depth + 1);
            }
        }
    }

    private static void applyTimePill(TextView label, View item, Context context) {
        boolean dark = isDarkTheme(context, item);
        int pill = dark ? 0xFF2C2C2C : 0xFFF2F2F2;
        int text = dark ? 0xFFE6E6E6 : 0xFF1F1F1F;
        Integer previous = MESSAGE_TIME_BG.get(label);
        if (previous == null || previous.intValue() != pill) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(pill);
            bg.setCornerRadius(dp(10));
            label.setBackground(bg);
            label.setPadding(dp(8), dp(3), dp(8), dp(3));
            MESSAGE_TIME_BG.put(label, Integer.valueOf(pill));
        }
        if (label.getCurrentTextColor() != text) {
            label.setTextColor(text);
        }
    }

    private static boolean isDarkTheme(Context context, View item) {
        if (Ui.isNight(context)) {
            return true;
        }
        View v = item;
        for (int i = 0; i < 8 && v != null; i++) {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                if (android.graphics.Color.alpha(c) > 0) {
                    double lum = 0.2126 * android.graphics.Color.red(c)
                            + 0.7152 * android.graphics.Color.green(c)
                            + 0.0722 * android.graphics.Color.blue(c);
                    return lum < 115;
                }
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private static void removeMessageTime(View item) {
        TextView label = MESSAGE_TIME_LABELS.remove(item);
        ViewGroup host = MESSAGE_TIME_HOSTS.remove(item);
        MESSAGE_TIME_SOURCES.remove(item);
        MessageTimePadding padding = MESSAGE_TIME_PADDING.remove(item);
        if (padding != null && item != null) {
            item.setPadding(padding.left, padding.top, padding.right, padding.bottom);
        }
        if (host == null && item instanceof ViewGroup) {
            host = (ViewGroup) item;
        }
        if (label != null && host != null) {
            try {
                host.getOverlay().remove(label);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String formatMessageTime(long millis) {
        Calendar message = Calendar.getInstance();
        message.setTimeInMillis(millis);
        Calendar today = Calendar.getInstance();
        boolean sameDay = message.get(Calendar.ERA) == today.get(Calendar.ERA)
                && message.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && message.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(sameDay ? "HH:mm:ss" : "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date(millis));
    }

    private static void collectMessageTime(View item, TimeSearch search) {
        collectTimeFromView(item, search, 0);
        View current = item;
        View recyclerChild = item;
        while (current != null) {
            if (isRecyclerViewObject(current)) {
                try {
                    java.lang.reflect.Method getHolder = current.getClass().getMethod(
                            "getChildViewHolder", View.class);
                    Object holder = getHolder.invoke(current, recyclerChild);
                    scanTimeObject(holder, search, 0, "holder", true);
                } catch (Throwable ignored) {
                }
                break;
            }
            View parent = current.getParent() instanceof View
                    ? (View) current.getParent() : null;
            if (parent == null) {
                break;
            }
            recyclerChild = current;
            current = parent;
        }
    }

    private static boolean isRecyclerViewObject(View view) {
        Class<?> type = view == null ? null : view.getClass();
        while (type != null && type != Object.class) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static void collectTimeFromView(View view, TimeSearch search, int depth) {
        if (view == null || depth > 16 || search.objectsVisited >= MAX_TIME_GRAPH_OBJECTS) {
            return;
        }
        scanTimeObject(view.getTag(), search, 0, "view.tag", true);
        scanTimeObject(view, search, 0, "view." + view.getClass().getName(), true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTimeFromView(group.getChildAt(i), search, depth + 1);
            }
        }
    }

    private static void scanTimeObject(Object value, TimeSearch search, int depth,
                                       String path, boolean root) {
        if (value == null || depth > 8 || search.objectsVisited >= MAX_TIME_GRAPH_OBJECTS) {
            return;
        }
        if (value instanceof Number) {
            search.consider(((Number) value).longValue(), depth, path);
            return;
        }
        if (value instanceof Date) {
            search.consider(((Date) value).getTime(), depth, path);
            return;
        }
        if (value instanceof Calendar) {
            search.consider(((Calendar) value).getTimeInMillis(), depth, path);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof CharSequence || value instanceof Boolean
                || value instanceof Character || value instanceof Enum
                || value instanceof Class || value instanceof Context
                || value instanceof android.graphics.drawable.Drawable
                || value instanceof View && !root) {
            return;
        }
        if (search.seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        search.objectsVisited++;
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 24);
            for (int i = 0; i < length; i++) {
                scanTimeObject(Array.get(value, i), search, depth + 1,
                        path + "[" + i + "]", false);
            }
            return;
        }
        if (value instanceof Iterable) {
            int count = 0;
            for (Object entry : (Iterable<?>) value) {
                if (count >= 24) break;
                scanTimeObject(entry, search, depth + 1, path + "[]", false);
                count++;
            }
            return;
        }
        if (value instanceof java.util.Map) {
            int count = 0;
            for (Object entry : ((java.util.Map<?, ?>) value).values()) {
                if (count >= 24) break;
                scanTimeObject(entry, search, depth + 1, path + "{}", false);
                count++;
            }
            return;
        }
        String className = type.getName();
        boolean supportedClass = isApplicationModelClass(className)
                || className.startsWith("androidx.recyclerview.widget.RecyclerView$");
        if (!supportedClass && !root) {
            return;
        }
        if (supportedClass && search.modelPaths.size() < 16) {
            String modelPath = path + "<" + className + ">";
            if (!search.modelPaths.contains(modelPath)) {
                search.modelPaths.add(modelPath);
            }
        }
        scanTimestampGetters(value, search, depth, path);
        Class<?> cursor = type;
        int fieldCount = 0;
        while (cursor != null && cursor != Object.class && fieldCount < 48
                && search.objectsVisited < MAX_TIME_GRAPH_OBJECTS) {
            String ownerName = cursor.getName();
            if (!(isApplicationModelClass(ownerName)
                    || ownerName.startsWith("androidx.recyclerview.widget.RecyclerView$"))) {
                break;
            }
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                break;
            }
            ArrayList<Field> orderedFields = new ArrayList<>();
            Collections.addAll(orderedFields, fields);
            Collections.sort(orderedFields, new Comparator<Field>() {
                @Override
                public int compare(Field left, Field right) {
                    return fieldScanPriority(right) - fieldScanPriority(left);
                }
            });
            for (Field field : orderedFields) {
                if (fieldCount++ >= 48 || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object child = field.get(value);
                    String childPath = path + "." + field.getName();
                    if (child instanceof Number) {
                        search.consider(((Number) child).longValue(), depth + 1, childPath);
                    } else if (child instanceof Date) {
                        search.consider(((Date) child).getTime(), depth + 1, childPath);
                    } else if (child instanceof Calendar) {
                        search.consider(((Calendar) child).getTimeInMillis(), depth + 1, childPath);
                    } else {
                        scanTimeObject(child, search, depth + 1, childPath, false);
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }
    }

    private static boolean isApplicationModelClass(String name) {
        return name != null && !name.startsWith("java.")
                && !name.startsWith("javax.") && !name.startsWith("android.")
                && !name.startsWith("androidx.") && !name.startsWith("kotlin.")
                && !name.startsWith("kotlinx.") && !name.startsWith("dalvik.")
                && !name.startsWith("sun.") && !name.startsWith("org.");
    }

    private static int fieldScanPriority(Field field) {
        String name = field.getName().toLowerCase(Locale.ROOT);
        String type = field.getType().getName().toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.contains("create") || name.contains("send") || name.contains("time")) score += 12;
        if (name.contains("msg") || name.contains("message") || name.contains("item")
                || name.contains("data") || name.contains("model") || name.contains("info")
                || name.contains("param") || name.contains("record")) score += 5;
        if (type.contains("storage.e9") || type.contains("msginfo")
                || type.contains("ym5.") || type.contains("fo5.")) score += 12;
        if (type.contains("context") || type.contains("activity")
                || type.contains("manager") || type.contains("controller")) score -= 12;
        return score;
    }

    private static void scanTimestampGetters(Object value, TimeSearch search,
                                             int depth, String path) {
        String[] names = {"getCreateTime", "getMsgTime", "getSendTime", "getTimestamp"};
        for (String name : names) {
            try {
                java.lang.reflect.Method method = value.getClass().getMethod(name);
                if (method.getParameterTypes().length != 0) continue;
                Class<?> resultType = method.getReturnType();
                if (resultType != long.class && resultType != Long.class
                        && resultType != int.class && resultType != Integer.class
                        && !Date.class.isAssignableFrom(resultType)) continue;
                Object result = method.invoke(value);
                if (result instanceof Number) {
                    search.consider(((Number) result).longValue(), depth + 1,
                            path + "." + name + "()");
                } else if (result instanceof Date) {
                    search.consider(((Date) result).getTime(), depth + 1,
                            path + "." + name + "()");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static long normalizeTimestamp(long value) {
        long now = System.currentTimeMillis();
        long lowerSeconds = 946684800L;
        long upperSeconds = now / 1000L + 86400L;
        if (value >= lowerSeconds && value <= upperSeconds) {
            return value * 1000L;
        }
        long lowerMillis = 946684800000L;
        long upperMillis = now + 86400000L;
        if (value >= lowerMillis && value <= upperMillis) {
            return value;
        }
        if (value >= lowerMillis * 1000L && value <= upperMillis * 1000L) {
            return value / 1000L;
        }
        return 0L;
    }

    /**
     * Emit bounded, text-free diagnostics for the live message objects. The
     * poller runs every 600 ms, so identical view trees are logged only once.
     */
    private static void logRuntimeDetection(View item, View avatar, View target,
                                            View bubble, View card, String branch,
                                            Context context, boolean explainCards) {
        ArrayList<String> views = new ArrayList<>();
        collectDiagnosticViews(item, avatar, target, bubble, card, 0, views);
        String summary = "branch=" + branch
                + " item=" + objectName(item)
                + " avatar=" + objectName(avatar)
                + " target=" + objectName(target)
                + " bubble=" + objectName(bubble)
                + " card=" + objectName(card)
                + " textCount=" + countVisibleTextObjects(item, target, 0)
                + " imageCount=" + countVisibleContentImages(item, avatar, 0)
                + " interactive=" + hasInteractiveContent(item, item, target, 0)
                + " surface=" + hasBackgroundSurface(item, avatar, 0);
        String signature = summary + views.toString();
        if (signature.equals(DIAGNOSTIC_SNAPSHOTS.get(item))) {
            return;
        }
        DIAGNOSTIC_SNAPSHOTS.put(item, signature);
        if (diagnosticItemsLogged >= MAX_DIAGNOSTIC_ITEMS) {
            return;
        }
        diagnosticItemsLogged++;
        android.util.Log.i("WeChatGlass", "CB detect " + summary);
        for (int i = 0; i < views.size(); i++) {
            android.util.Log.i("WeChatGlass", "CB object " + i + " " + views.get(i));
        }
        if (explainCards) {
            int[] logged = {0};
            logCardCandidateReasons(item, item, avatar, target, context, 0, logged);
        }
    }

    private static void collectDiagnosticViews(View view, View avatar, View target,
                                              View bubble, View card, int depth,
                                              ArrayList<String> out) {
        if (view == null || depth > 10 || out.size() >= MAX_DIAGNOSTIC_VIEWS) {
            return;
        }
        String background = view.getBackground() == null ? "-"
                : view.getBackground().getClass().getSimpleName();
        String foreground = android.os.Build.VERSION.SDK_INT < 23
                || view.getForeground() == null ? "-"
                : view.getForeground().getClass().getSimpleName();
        int children = view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0;
        String role = (view == avatar ? "A" : "")
                + (view == target ? "T" : "")
                + (view == bubble ? "B" : "")
                + (view == card ? "C" : "");
        out.add("depth=" + depth + " class=" + view.getClass().getName()
                + " object=" + Integer.toHexString(System.identityHashCode(view))
                + " id=0x" + Integer.toHexString(view.getId())
                + " at=" + view.getLeft() + "," + view.getTop()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " bg=" + background + " fg=" + foreground
                + " visible=" + (view.getVisibility() == View.VISIBLE)
                + " click=" + view.isClickable()
                + " longClick=" + view.isLongClickable()
                + " children=" + children + " role=" + role);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount()
                    && out.size() < MAX_DIAGNOSTIC_VIEWS; i++) {
                collectDiagnosticViews(group.getChildAt(i), avatar, target, bubble,
                        card, depth + 1, out);
            }
        }
    }

    private static void logCardCandidateReasons(View item, View view, View avatar,
                                                View target, Context context,
                                                int depth, int[] logged) {
        if (view == null || depth > 18 || logged[0] >= MAX_DIAGNOSTIC_CANDIDATES
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return;
        }
        if (view != item && view != avatar && view instanceof ViewGroup
                && view.getWidth() >= M3Theme.dp(context, 48f)
                && view.getHeight() >= M3Theme.dp(context, 24f)) {
            int textCount = countVisibleTextObjects(view, target, 0);
            int imageCount = countVisibleContentImages(view, avatar, 0);
            boolean interactive = view.isClickable() || view.isLongClickable()
                    || hasInteractiveContent(view, view, target, 0);
            boolean background = hasBackgroundSurface(view, avatar, 0);
            boolean targetInside = target == null || isDescendantOf(target, view);
            boolean avatarInside = isDescendantOf(avatar, view);
            String reason = cardCandidateRejection(item, view, target, avatar,
                    context, textCount, imageCount, background,
                    targetInside, avatarInside);
            android.util.Log.i("WeChatGlass", "CB candidate class="
                    + view.getClass().getName()
                    + " object=" + Integer.toHexString(System.identityHashCode(view))
                    + " id=0x" + Integer.toHexString(view.getId())
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " text=" + textCount + " image=" + imageCount
                    + " interactive=" + interactive + " surface=" + background
                    + " targetInside=" + targetInside
                    + " avatarInside=" + avatarInside + " result=" + reason);
            logged[0]++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount()
                    && logged[0] < MAX_DIAGNOSTIC_CANDIDATES; i++) {
                logCardCandidateReasons(item, group.getChildAt(i), avatar, target,
                        context, depth + 1, logged);
            }
        }
    }

    private static String cardCandidateRejection(View item, View view, View target,
                                                 View avatar, Context context,
                                                 int textCount, int imageCount,
                                                 boolean background,
                                                 boolean targetInside,
                                                 boolean avatarInside) {
        if (avatarInside) return "contains-avatar";
        if (view.getWidth() < M3Theme.dp(context, 88f)
                || view.getHeight() < M3Theme.dp(context, 36f)) {
            return "too-small";
        }
        if (view.getWidth() > item.getWidth() * 96 / 100
                || view.getHeight() > item.getHeight() * 98 / 100) {
            return "too-large";
        }
        boolean cardContent = textCount >= 2;
        if (!cardContent) return "insufficient-content";
        if (imageCount > 2) return "too-many-images";
        if (!background) return "no-background";
        return "would-match";
    }

    private static String objectName(View view) {
        return view == null ? "-" : view.getClass().getName() + "#"
                + Integer.toHexString(System.identityHashCode(view)) + "/"
                + view.getWidth() + "x" + view.getHeight();
    }

    private static boolean containsDiagnosticMedia(View view, View avatar, int depth) {
        if (view == null || view == avatar || isDescendantOf(view, avatar) || depth > 18
                || isOurBubble(view)
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return false;
        }
        if (view.getWidth() > 0 && view.getHeight() > 0
                && (view instanceof android.widget.ImageView
                || view instanceof android.view.SurfaceView
                || view instanceof android.view.TextureView
                || isStickerView(view))) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsDiagnosticMedia(group.getChildAt(i), avatar, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View findMediaBubbleContainer(View item, View avatar,
                                                Context context) {
        View[] media = {null};
        long[] largestArea = {0L};
        findLargestMediaVisual(item, avatar, context, 0, media, largestArea);
        View visual = media[0];
        if (visual == null) {
            return null;
        }
        // A sticker sits inside an off-centre wrapper (RTChattingEmojiView wraps
        // a narrower, side-aligned ChattingEmojiView together with a progress
        // bar), so a bubble on that wrapper is too wide and sticks out past the
        // sticker. Anchor the bubble to the innermost sticker instead; the
        // outset, not the wrapper, supplies the room around it.
        if (isStickerView(visual)) {
            View inner = innermostSticker(visual);
            return inner != null ? inner : visual;
        }

        View best = null;
        int bestExtra = Integer.MAX_VALUE;
        // A container whose child fills it exactly leaves no room for the bubble
        // along that axis: the tail band is painted at the top of the bubble and
        // is then covered by the media, which is why such a bubble reads as
        // missing. Only containers with slack on both axes may own the bubble.
        int minSlackX = M3Theme.dp(context, 8f);
        int minSlackY = M3Theme.dp(context, 16f);
        View fallback = null;
        int fallbackExtra = Integer.MAX_VALUE;
        View current = visual.getParent() instanceof View
                ? (View) visual.getParent() : null;
        while (current != null && current != item) {
            boolean mirrorsVisual = current instanceof ViewGroup
                    && ((ViewGroup) current).getChildCount() == 1
                    && ((ViewGroup) current).getChildAt(0) == visual
                    && current.getWidth() == visual.getWidth()
                    && current.getHeight() == visual.getHeight();
            if (!mirrorsVisual && current instanceof ViewGroup && current != avatar
                    && !isDescendantOf(avatar, current)
                    && current.getWidth() >= visual.getWidth()
                    && current.getHeight() >= visual.getHeight()
                    && current.getWidth() <= item.getWidth() * 96 / 100
                    && current.getHeight() <= item.getHeight() * 98 / 100
                    && current.getWidth() - visual.getWidth()
                    <= M3Theme.dp(context, 120f)
                    && current.getHeight() - visual.getHeight()
                    <= M3Theme.dp(context, 120f)) {
                int extra = current.getWidth() - visual.getWidth()
                        + current.getHeight() - visual.getHeight();
                if (current.getWidth() - visual.getWidth() >= minSlackX
                        && current.getHeight() - visual.getHeight() >= minSlackY
                        && extra < bestExtra) {
                    best = current;
                    bestExtra = extra;
                }
                if (extra < fallbackExtra) {
                    fallback = current;
                    fallbackExtra = extra;
                }
            }
            current = current.getParent() instanceof View
                    ? (View) current.getParent() : null;
        }
        return best != null ? best : fallback != null ? fallback : visual;
    }

    private static void findLargestMediaVisual(View view, View avatar,
                                               Context context, int depth,
                                               View[] best, long[] bestArea) {
        if (view == null || view == avatar || isDescendantOf(view, avatar) || depth > 20
                || isOurBubble(view)
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        boolean mediaVisual = false;
        if (width > 0 && height > 0) {
            if (view instanceof android.widget.ImageView) {
                mediaVisual = width >= M3Theme.dp(context, 72f)
                        && height >= M3Theme.dp(context, 56f);
            } else if (view instanceof android.view.SurfaceView
                    || view instanceof android.view.TextureView) {
                mediaVisual = width >= M3Theme.dp(context, 72f)
                        && height >= M3Theme.dp(context, 56f);
            } else if (isStickerView(view)) {
                mediaVisual = true;
            } else if (!(view instanceof ViewGroup) && !(view instanceof TextView)
                    && width >= M3Theme.dp(context, 120f)
                    && height >= M3Theme.dp(context, 72f)) {
                mediaVisual = true;
            }
        }
        if (mediaVisual) {
            long area = (long) width * height;
            if (area > bestArea[0]) {
                best[0] = view;
                bestArea[0] = area;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findLargestMediaVisual(group.getChildAt(i), avatar, context,
                        depth + 1, best, bestArea);
            }
        }
    }

    private static int dp(float value) {
        return Math.round(value * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
    }

    private static void roundAvatar(View avatar) {
        if (avatar == null || avatar.getWidth() <= 0 || avatar.getHeight() <= 0) {
            return;
        }
        boolean outlineChanged = false;
        if (avatar.getOutlineProvider() != CIRCLE_OUTLINE) {
            remember(avatar);
            avatar.setOutlineProvider(CIRCLE_OUTLINE);
            outlineChanged = true;
        }
        if (!avatar.getClipToOutline()) {
            remember(avatar);
            avatar.setClipToOutline(true);
            outlineChanged = true;
        }
        if (outlineChanged) {
            avatar.invalidateOutline();
        }
        if (avatar instanceof android.widget.ImageView) {
            android.widget.ImageView image = (android.widget.ImageView) avatar;
            android.graphics.drawable.Drawable drawable = image.getDrawable();
            if (drawable != null && !(drawable instanceof OvalClipDrawable)) {
                remember(avatar);
                image.setImageDrawable(new OvalClipDrawable(drawable));
            }
        }
    }

    /** Locate and clip the avatar object by its geometry inside this message row. */
    private static void roundChatAvatars(View item, View avatar) {
        if (avatar == null) {
            return;
        }
        roundAvatar(avatar);
        if (avatarChainDiag < 4
                && AVATAR_CHAIN_LOGGED.put(avatar, Boolean.TRUE) == null) {
            avatarChainDiag++;
            logAvatarObjectChain(item, avatar);
        }

    }

    /** Undoes the circular clip when the round-avatar setting is turned off. */
    private static void restoreAvatar(View avatar) {
        if (avatar == null) {
            return;
        }
        ViewState state = ORIGINAL.get(avatar);
        if (state == null) {
            return;
        }
        try {
            state.restore(avatar);
        } catch (Throwable ignored) {
        }
        ORIGINAL.remove(avatar);
    }

    /** Log object identity and geometry only; never inspect or emit chat text. */
    private static void logAvatarObjectChain(View item, View avatar) {
        View current = avatar;
        int depth = 0;
        while (current != null && depth < 10) {
            int[] screen = new int[2];
            current.getLocationOnScreen(screen);
            View parent = current.getParent() instanceof View
                    ? (View) current.getParent() : null;
            android.graphics.drawable.Drawable background = current.getBackground();
            String bg = "none";
            if (background instanceof android.graphics.drawable.ColorDrawable) {
                bg = "color:#" + Integer.toHexString(
                        ((android.graphics.drawable.ColorDrawable) background).getColor());
            } else if (background instanceof android.graphics.drawable.GradientDrawable) {
                bg = "gradient";
            } else if (background != null) {
                bg = "set alpha=" + background.getAlpha();
            }
            int children = current instanceof ViewGroup
                    ? ((ViewGroup) current).getChildCount() : 0;
            android.util.Log.i("WeChatGlass", "CB avatar chain depth=" + depth
                    + " object="
                    + Integer.toHexString(System.identityHashCode(current))
                    + " parent=" + (parent == null ? "none"
                    : Integer.toHexString(System.identityHashCode(parent)))
                    + " bounds=" + current.getLeft() + "," + current.getTop()
                    + "," + current.getWidth() + "x" + current.getHeight()
                    + " screen=" + screen[0] + "," + screen[1]
                    + " bg=" + bg
                    + " fg=" + (current.getForeground() != null)
                    + " clip=" + current.getClipToOutline()
                    + " oval=" + (current.getOutlineProvider() == CIRCLE_OUTLINE)
                    + " children=" + children);
            if (current == item) {
                break;
            }
            current = parent;
            depth++;
        }
    }

    private static int dp(float density, float value) {
        return Math.round(density * value);
    }

    /** Round rendered stickers in a visible message or sticker grid. */
    static void roundStickerPanel(View view, Context context) {
        roundStickers(view, context, 0, true);
    }

    private static void roundStickers(View view, Context context, int depth) {
        roundStickers(view, context, depth, false);
    }

    private static void roundStickers(View view, Context context, int depth,
                                      boolean panelThumbnails) {
        if (view == null || depth > 16) {
            return;
        }
        boolean panelThumbnail = panelThumbnails
                && isPanelStickerThumbnail(view, context);
        if (isStickerView(view) || panelThumbnail) {
            final float radius = M3Theme.dpF(context, panelThumbnail ? 12f : 20f);
            boolean firstClip = ROUNDED_STICKERS.put(view, Boolean.TRUE) == null;

            // ImageViews are rounded through their drawable instead of clipToOutline.
            // clipToOutline would also clip the media bubble's grown background and
            // tail, which are drawn past the sticker's bounds — the same flag cannot
            // serve both, and this is what made the sticker corners turn square.
            // Animated drawables (GIF stickers) stay in the ImageView's own pipeline:
            // wrapping them adds a drawable layer on every frame and the animation
            // stutters, so those are rounded by the view outline instead.
            boolean roundedByDrawable = false;
            if (view instanceof android.widget.ImageView) {
                android.widget.ImageView image = (android.widget.ImageView) view;
                android.graphics.drawable.Drawable drawable = image.getDrawable();
                boolean alreadyRounded = drawable instanceof RoundedRectClipDrawable
                        && ((RoundedRectClipDrawable) drawable).hasRadius(radius);
                boolean animated = drawable instanceof android.graphics.drawable.Animatable;
                if (drawable != null && !alreadyRounded && !animated) {
                    if (drawable instanceof RoundedRectClipDrawable) {
                        drawable = ((RoundedRectClipDrawable) drawable).getWrappedDrawable();
                    }
                    remember(view);
                    image.setImageDrawable(new RoundedRectClipDrawable(drawable, radius));
                    roundedByDrawable = true;
                }
            }

            if (roundedByDrawable) {
                if (view.getClipToOutline()) {
                    remember(view);
                    view.setClipToOutline(false);
                }
            } else {
                // Custom emoji views with no drawable to wrap fall back to the
                // outline clip.
                android.view.ViewOutlineProvider stickerOutline = STICKER_OUTLINES.get(view);
                if (stickerOutline == null) {
                    stickerOutline = new android.view.ViewOutlineProvider() {
                        @Override
                        public void getOutline(View target, android.graphics.Outline outline) {
                            outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radius);
                        }
                    };
                    STICKER_OUTLINES.put(view, stickerOutline);
                }
                boolean outlineChanged = false;
                if (view.getOutlineProvider() != stickerOutline) {
                    remember(view);
                    view.setOutlineProvider(stickerOutline);
                    outlineChanged = true;
                }
                if (!view.getClipToOutline()) {
                    remember(view);
                    view.setClipToOutline(true);
                    outlineChanged = true;
                }
                if (outlineChanged) {
                    view.invalidateOutline();
                    view.invalidate();
                }
            }
            if (firstClip) {
                android.util.Log.i("WeChatGlass", "CB rounded sticker "
                        + (panelThumbnails ? "panel " : "message ")
                        + view.getClass().getName() + " " + view.getWidth()
                        + "x" + view.getHeight() + " drawable=" + roundedByDrawable);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                roundStickers(group.getChildAt(i), context, depth + 1,
                        panelThumbnails);
            }
        }
    }

    private static boolean isPanelStickerThumbnail(View view, Context context) {
        if (!(view instanceof android.widget.ImageView)
                || view.getVisibility() != View.VISIBLE
                || ((android.widget.ImageView) view).getDrawable() == null) {
            return false;
        }
        int minSide = M3Theme.dp(context, 44f);
        if (view.getWidth() < minSide || view.getHeight() < minSide) {
            return false;
        }
        float aspect = view.getWidth() / (float) view.getHeight();
        return aspect >= 0.62f && aspect <= 1.62f;
    }

    private static boolean isStickerView(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() < dp(32)
                || view.getHeight() < dp(32)) {
            return false;
        }
        String name = view.getClass().getName();
        return name.contains("MMAnimateView")
                || name.contains("EmojiImageView")
                || name.contains("EmojiView")
                || name.contains("GifImageView");
    }

    /** The deepest sticker view under {@code view}, or {@code view} itself. */
    private static View innermostSticker(View view) {
        View best = view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (isStickerView(c)) {
                    View deeper = innermostSticker(c);
                    if (deeper != null) {
                        best = deeper;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isOutgoingByAvatar(View avatar, int screen) {
        if (avatar == null) {
            return false;
        }
        int[] loc = new int[2];
        avatar.getLocationOnScreen(loc);
        return loc[0] + avatar.getWidth() / 2 > screen / 2;
    }

    private static View findAvatar(View item) {
        if (item == null || item.getWidth() <= 0) {
            return null;
        }
        AvatarSearch search = new AvatarSearch(item);
        findAvatar(item, search, 0);
        return search.best;
    }

    private static final class AvatarSearch {
        final int[] itemLocation = new int[2];
        final int itemWidth;
        final float density;
        View best;
        float bestScore = Float.MAX_VALUE;

        AvatarSearch(View item) {
            item.getLocationOnScreen(itemLocation);
            itemWidth = item.getWidth();
            density = item.getResources().getDisplayMetrics().density;
        }
    }

    private static void findAvatar(View view, AvatarSearch search, int depth) {
        if (view == null || depth > 24 || view.getVisibility() != View.VISIBLE
                || view.getAlpha() <= 0f) {
            return;
        }
        if (view instanceof android.widget.ImageView
                && view.getWidth() > 0 && view.getHeight() > 0) {
            float width = view.getWidth();
            float height = view.getHeight();
            float sizeDp = (width + height) / (2f * search.density);
            float aspect = width / height;
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float leftGapDp = (location[0] - search.itemLocation[0]) / search.density;
            float rightGapDp = (search.itemLocation[0] + search.itemWidth
                    - location[0] - width) / search.density;
            float edgeGapDp = Math.min(leftGapDp, rightGapDp);
            if (sizeDp >= 24f && sizeDp <= 70f
                    && aspect >= 0.84f && aspect <= 1.19f
                    && edgeGapDp >= -4f && edgeGapDp <= 64f) {
                float score = Math.abs(sizeDp - 40f)
                        + Math.abs(aspect - 1f) * 80f
                        + Math.max(0f, edgeGapDp) * 2f;
                if (score < search.bestScore) {
                    search.best = view;
                    search.bestScore = score;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findAvatar(group.getChildAt(i), search, depth + 1);
            }
        }
    }

    private static View findBubbleTarget(View view, View item, View avatar,
                                        Context context, View best, int bestScore) {
        if (view == null || avatar == null) {
            // No avatar means this row is a system notice (recall, time, 拍一拍…),
            // never a message bubble.
            return best;
        }
        boolean neat = view.getClass().getName().contains("MMNeat7extView")
                || view.getClass().getName().contains("NeatTextView");
        if (neat && view.getVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0
                && isCompactMessageText(view, item, avatar, context)) {
            int score = bubbleTargetScore(view);
            if (score > bestScore) {
                best = view;
                bestScore = score;
            }
        }
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0
                    && tv.getVisibility() == View.VISIBLE
                    && tv.getWidth() > 0 && tv.getHeight() > 0
                    && isCompactMessageText(tv, item, avatar, context)
                    && !isChromeText(cs.toString())
                    && !isSenderNameLabel(tv)) {
                int score = bubbleTargetScore(tv);
                if (score > bestScore) {
                    best = tv;
                    bestScore = score;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findBubbleTarget(group.getChildAt(i), item, avatar,
                        context, best, bestScore);
                if (found != null) {
                    best = found;
                    bestScore = bubbleTargetScore(found);
                }
            }
        }
        return best;
    }

    /**
     * Group chats print the sender's nickname above the bubble. It is a plain
     * {@link TextView} sitting next to the real content view (a MMNeat7extView /
     * emoji / image / video view), so it must never be treated as message text.
     */
    /** True when {@code group} holds the sender name next to the content view. */
    private static boolean containsSenderName(ViewGroup group, View target) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == target || !(child instanceof TextView)) {
                continue;
            }
            if (isSenderNameLabel((TextView) child)) {
                return true;
            }
        }
        return false;
    }
    private static boolean isSenderNameLabel(TextView candidate) {
        String own = candidate.getClass().getName();
        if (own.contains("MMNeat7extView") || own.contains("NeatTextView")) {
            return false;
        }
        android.view.ViewParent parent = candidate.getParent();
        if (!(parent instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == candidate) {
                continue;
            }
            String name = child.getClass().getName();
            if (name.contains("MMNeat7extView") || name.contains("Emoji")
                    || name.contains("Image") || name.contains("Video")
                    || name.contains("Record") || name.contains("Voice")) {
                return true;
            }
        }
        return false;
    }
    private static boolean isCompactMessageText(View candidate, View item,
                                                View avatar, Context context) {
        if (candidate == item || candidate == avatar
                || isDescendantOf(candidate, avatar)) {
            return false;
        }
        int candidateWidth = candidate.getWidth();
        int candidateHeight = candidate.getHeight();
        View parent = candidate.getParent() instanceof View
                ? (View) candidate.getParent() : null;
        int depth = 0;
        while (parent != null && parent != item && depth++ < 10) {
            if (parent instanceof ViewGroup && parent != avatar
                    && !isDescendantOf(avatar, parent)
                    && parent.getWidth() >= candidateWidth
                    && parent.getHeight() >= candidateHeight
                    && parent.getWidth() < item.getWidth() * 92 / 100
                    && parent.getHeight() < item.getHeight() * 98 / 100
                    && parent.getWidth() - candidateWidth
                    <= M3Theme.dp(context, 160f)
                    && parent.getHeight() - candidateHeight
                    <= M3Theme.dp(context, 96f)) {
                return true;
            }
            parent = parent.getParent() instanceof View
                    ? (View) parent.getParent() : null;
        }
        return false;
    }

    private static int bubbleTargetScore(View view) {
        if (view == null) {
            return 0;
        }
        String name = view.getClass().getName();
        if (name.contains("MMNeat7extView") || name.contains("NeatTextView")) {
            int score = view.getWidth() * view.getHeight() * 2;
            return view.isClickable() || view.isLongClickable() ? score / 4 : score;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            int score = view.getWidth() * Math.max(1, text == null ? 1 : text.length());
            return view.isClickable() || view.isLongClickable() ? score / 4 : score;
        }
        int score = view.getWidth() * view.getHeight() * 2;
        return view.isClickable() || view.isLongClickable() ? score / 4 : score;
    }

    /**
     * Only reshape a compact, simple text/call bubble. Rich message cards such
     * as transfers and red packets expose several text/image/action objects;
     * leave those objects and their ancestors entirely native.
     */
    private static String simpleBubbleFailure(View item, View bubble,
                                              View target, View avatar) {
        if (!isDescendantOf(target, bubble) || bubble.getWidth() <= 0
                || bubble.getHeight() <= 0) {
            return "target-not-in-sized-bubble";
        }
        int extra = dp(48f);
        if (bubble.getWidth() > target.getWidth() + dp(96f)
                || bubble.getHeight() > target.getHeight() + extra) {
            return "bubble-larger-than-target";
        }
        int textCount = countVisibleTextObjects(bubble, target, 0);
        if (textCount != 1) {
            return "bubble-text-count-" + textCount;
        }
        if (hasInteractiveContent(bubble, bubble, target, 0)) {
            return "nested-interactive-content";
        }

        View ancestor = bubble.getParent() instanceof View
                ? (View) bubble.getParent() : null;
        int depth = 0;
        while (ancestor != null && ancestor != item && depth++ < 8) {
            if (countVisibleTextObjects(ancestor, target, 0) > 2
                    || countVisibleContentImages(ancestor, avatar, 0) > 1) {
                return "ancestor-has-rich-content";
            }
            ancestor = ancestor.getParent() instanceof View
                    ? (View) ancestor.getParent() : null;
        }
        return null;
    }

    private static View findRichCardObject(View item, View avatar, View target,
                                          Context context) {
        return findRichCardObject(item, item, avatar, target, context, 0);
    }

    private static View findRichCardObject(View item, View view, View avatar,
                                          View target, Context context, int depth) {
        if (view == null || view == avatar || depth > 18
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return null;
        }
        boolean cardCandidate = view != item && view instanceof ViewGroup
                && !isDescendantOf(avatar, view)
                && view.getWidth() >= M3Theme.dp(context, 88f)
                && view.getHeight() >= M3Theme.dp(context, 36f)
                && view.getWidth() <= item.getWidth() * 96 / 100
                && view.getHeight() <= item.getHeight() * 98 / 100;
        if (cardCandidate) {
            int textCount = countVisibleTextObjects(view, target, 0);
            int imageCount = countVisibleContentImages(view, avatar, 0);
            boolean cardContent = textCount >= 2;
            cardCandidate = cardContent && imageCount <= 2
                    && hasBackgroundSurface(view, avatar, 0);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRichCardObject(item, group.getChildAt(i), avatar,
                        target, context, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return cardCandidate ? view : null;
    }

    private static boolean hasBackgroundSurface(View view, View avatar, int depth) {
        if (view == null || view == avatar || depth > 16
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return false;
        }
        if (view.getBackground() != null) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasBackgroundSurface(group.getChildAt(i), avatar, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void styleRichCard(View card, View item, View textTarget,
                                      View avatar, Context context) {
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        boolean outgoing;
        if (avatar != null) {
            outgoing = isOutgoingByAvatar(avatar, screen);
        } else {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            outgoing = loc[0] > screen - (loc[0] + card.getWidth());
        }

        // Transaction/red-packet cards always use the selected dark-palette
        // primary role, regardless of the current page brightness.
        M3ColorScheme cardScheme = M3ColorScheme.forContext(context, true);
        int color = cardScheme.primary;
        int textColor = cardScheme.onPrimary;
        float radius = M3Theme.dpF(context, M3Shape.LARGE_INCREASED);
        float tail = M3Theme.dpF(context, 7f);
        BubbleDrawable shape = new BubbleDrawable(color, radius, tail, outgoing);
        BubbleDrawable previous = APPLIED.get(card);
        boolean alreadyApplied = previous != null && card.getBackground() == previous
                && previous.matches(color, radius, tail, outgoing);
        if (!alreadyApplied) {
            remember(card);
            card.setBackground(shape);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                card.setBackgroundTintList(null);
            }
            shape.setBounds(0, 0, card.getWidth(), card.getHeight());
            APPLIED.put(card, shape);
            if (card instanceof ViewGroup) {
                ((ViewGroup) card).setClipChildren(false);
            }
            if (card.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) card.getParent();
                remember(parent);
                parent.setClipChildren(false);
            }
        }
        applyCardTheme(card, card, avatar, textTarget, color, textColor, 0);
        logAppliedSurface("card", card);
    }

    /** Removes the bubble painted on a media view (the setting was turned off). */
    private static void clearMediaBubble(View media) {
        if (media == null) {
            return;
        }
        if (media.getBackground() instanceof BubbleDrawable) {
            media.setBackground(null);
            media.invalidate();
        }
        APPLIED.remove(media);
    }

    private static void styleMediaBubble(View media, View item, View avatar,
                                         Context context) {
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        boolean outgoing;
        if (avatar != null) {
            outgoing = isOutgoingByAvatar(avatar, screen);
        } else {
            int[] loc = new int[2];
            media.getLocationOnScreen(loc);
            outgoing = loc[0] > screen - (loc[0] + media.getWidth());
        }
        M3ColorScheme scheme = M3ColorScheme.forContext(context, true);
        int color = scheme.primary;
        float radius = M3Theme.dpF(context, M3Shape.LARGE_INCREASED);
        float tail = M3Theme.dpF(context, 7f);
        // The bubble is the media's own background, grown past the media's bounds
        // by ~5.9% on each side (≈ +25% area). No view is added to the row, so
        // WeChat's layout is untouched and the media stays exactly where it is —
        // this is what keeps it centred and stops the "shifted up" regression the
        // sibling-view approach introduced.
        float outsetX = media.getWidth() * 0.059f;
        float outsetY = media.getHeight() * 0.059f;
        BubbleDrawable shape = new BubbleDrawable(color, radius, tail, outgoing,
                outsetX, outsetY);
        clearMediaVisualBackgrounds(media, media, avatar, context, 0);
        // roundStickers re-enables clipToOutline every pass; it clips the grown
        // bubble (and the tail above the media) back to the media's bounds, so it
        // must be cleared here every pass, before the early-return below.
        if (media.getClipToOutline()) {
            remember(media);
            media.setClipToOutline(false);
        }
        View wrap = media.getParent() instanceof View ? (View) media.getParent() : null;
        if (wrap != null && wrap.getClipToOutline()) {
            remember(wrap);
            wrap.setClipToOutline(false);
        }
        BubbleDrawable previous = APPLIED.get(media);
        if (previous != null && media.getBackground() == previous
                && previous.matches(color, radius, tail, outgoing, outsetX, outsetY)) {
            return;
        }
        remember(media);
        media.setBackground(shape);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            media.setBackgroundTintList(null);
        }
        shape.setBounds(0, 0, media.getWidth(), media.getHeight());
        media.invalidate();
        APPLIED.put(media, shape);
        unclipMediaAncestors(media, item);
        watchMedia(media, item, avatar, context);
        logAppliedSurface("media", media);
    }

    /** Lets the grown media bubble draw past its own bounds up to the row. */
    private static void unclipMediaAncestors(View media, View item) {
        // Unclip from the media up to and including the row, then one level
        // above it (the list) so the grown corners are never cut by a clip.
        View v = media;
        int guard = 0;
        while (v != null && guard++ < 8) {
            if (v instanceof ViewGroup && ((ViewGroup) v).getClipChildren()) {
                remember(v);
                ((ViewGroup) v).setClipChildren(false);
            }
            if (v == item) {
                android.view.ViewParent up = v.getParent();
                View list = up instanceof View ? (View) up : null;
                if (list instanceof ViewGroup && ((ViewGroup) list).getClipChildren()) {
                    remember(list);
                    ((ViewGroup) list).setClipChildren(false);
                }
                break;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
    }

    /** Media containers we keep a layout watcher on, so bubbles re-apply on load. */
    private static final WeakHashMap<View, View.OnLayoutChangeListener> MEDIA_WATCHERS =
            new WeakHashMap<>();
    /** The media object each row currently has its bubble on; keyed by the row. */
    private static final WeakHashMap<View, View> MEDIA_TARGET = new WeakHashMap<>();
    /** The bubble ring view for each media object. */
    private static final WeakHashMap<View, View> MEDIA_BUBBLE = new WeakHashMap<>();
    /** The ring currently attached to each row (for recycled-row cleanup). */
    private static final WeakHashMap<View, View> ROW_BUBBLE = new WeakHashMap<>();
    /** Our own ring views, so the media scans never mistake them for media. */
    private static final WeakHashMap<View, Boolean> OUR_BUBBLES = new WeakHashMap<>();

    private static boolean isOurBubble(View view) {
        return view != null && OUR_BUBBLES.containsKey(view);
    }

    private static String prop(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Full subtree dump of a message row, gated by debug.wxglass.media.tree=1. */
    private static void dumpMediaTree(View view, int depth) {
        if (view == null || depth > 9) {
            return;
        }
        android.graphics.drawable.Drawable bg = view.getBackground();
        StringBuilder sb = new StringBuilder("MT ");
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(view.getClass().getSimpleName())
                .append(" id=0x").append(Integer.toHexString(view.getId()))
                .append(" pos=").append(view.getLeft()).append(',').append(view.getTop())
                .append(" ty=").append(view.getTranslationY())
                .append(' ').append(view.getWidth()).append('x').append(view.getHeight())
                .append(" vis=").append(view.getVisibility())
                .append(" clip=").append(view.getClipToOutline())
                .append(" bg=").append(bg == null ? "null"
                        : bg.getClass().getSimpleName() + "@"
                        + Integer.toHexString(System.identityHashCode(bg)));
        android.util.Log.i("WeChatGlass", sb.toString());
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpMediaTree(g.getChildAt(i), depth + 1);
            }
        }
    }

    /**
     * The media (image/video/sticker) is bound asynchronously: its container's
     * size — and sometimes the container object itself — changes after the poll
     * styled it, which is the timing window where a bubble applied to a
     * placeholder never covers the settled media. Re-assert the bubble the
     * moment the container lays out again, instead of waiting up to 600ms (or
     * missing a swapped view until the next pass).
     */
    private static void watchMedia(final View media, final View item,
                                   final View avatar, final Context context) {
        if (media == null || MEDIA_WATCHERS.containsKey(media)) {
            return;
        }
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                media.post(new Runnable() {
                    @Override
                    public void run() {
                        styleMediaBubble(media, item, avatar, context);
                    }
                });
            }
        };
        MEDIA_WATCHERS.put(media, listener);
        media.addOnLayoutChangeListener(listener);
    }

    private static void clearMediaVisualBackgrounds(View view, View root,
                                                     View avatar, Context context,
                                                     int depth) {
        if (view == null || view == avatar || isDescendantOf(view, avatar)
                || depth > 18 || view.getVisibility() != View.VISIBLE
                || view.getAlpha() <= 0f) {
            return;
        }
        if (view != root && isMediaVisual(view, context)
                && view.getBackground() != null) {
            remember(view);
            view.setBackground(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearMediaVisualBackgrounds(group.getChildAt(i), root, avatar,
                        context, depth + 1);
            }
        }
    }

    private static boolean isMediaVisual(View view, Context context) {
        if (isOurBubble(view)) {
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        return view instanceof android.widget.ImageView
                && width >= M3Theme.dp(context, 72f)
                && height >= M3Theme.dp(context, 56f)
                || view instanceof android.view.SurfaceView
                && width >= M3Theme.dp(context, 72f)
                && height >= M3Theme.dp(context, 56f)
                || view instanceof android.view.TextureView
                && width >= M3Theme.dp(context, 72f)
                && height >= M3Theme.dp(context, 56f)
                || isStickerView(view);
    }

    private static void logAppliedSurface(String kind, View view) {
        if (view == null) {
            return;
        }
        android.graphics.drawable.Drawable background = view.getBackground();
        android.graphics.drawable.Drawable foreground = android.os.Build.VERSION.SDK_INT < 23
                ? null : view.getForeground();
        String childState = "-";
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            StringBuilder children = new StringBuilder();
            for (int i = 0; i < group.getChildCount() && i < 8; i++) {
                View child = group.getChildAt(i);
                if (i > 0) children.append('|');
                android.graphics.drawable.Drawable childBg = child.getBackground();
                children.append(child.getClass().getSimpleName())
                        .append('@').append(child.getWidth()).append('x')
                        .append(child.getHeight()).append("/bg=")
                        .append(childBg == null ? "-" : childBg.getClass().getSimpleName())
                        .append("/vis=").append(child.getVisibility());
            }
            childState = children.toString();
        }
        String signature = kind + ":bg="
                + (background == null ? "-" : background.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(background)))
                + ",color=" + (background instanceof BubbleDrawable
                ? "#" + Integer.toHexString(((BubbleDrawable) background).fillColor()) : "-")
                + ",bounds=" + (background == null ? "-" : background.getBounds().toShortString())
                + ",fg=" + (foreground == null ? "-"
                : foreground.getClass().getSimpleName())
                + ",tint=" + (android.os.Build.VERSION.SDK_INT < 21
                || view.getBackgroundTintList() == null ? "-" : "set")
                + ",clip=" + view.getClipToOutline()
                + ",attached=" + view.isAttachedToWindow()
                + ",size=" + view.getWidth() + "x" + view.getHeight()
                + ",children=" + childState;
        if (signature.equals(APPLIED_DIAGNOSTICS.get(view))) {
            return;
        }
        APPLIED_DIAGNOSTICS.put(view, signature);
        android.util.Log.i("WeChatGlass", "CB applied " + signature);
    }

    private static void applyCardTheme(View view, View root, View avatar,
                                       View textTarget, int backgroundColor,
                                       int textColor, int depth) {
        if (view == null || view == avatar || depth > 18
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return;
        }
        if (view != root && view.getBackground() != null
                && android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList tint = view.getBackgroundTintList();
            if (tint == null || tint.getDefaultColor() != backgroundColor) {
                remember(view);
                view.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(backgroundColor));
            }
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getCurrentTextColor() != textColor) {
                remember(text);
                text.setTextColor(textColor);
            }
        } else if (view == textTarget) {
            remember(view);
            ViewState original = ORIGINAL.get(view);
            if (original != null && original.reflectedTextColor != null) {
                try {
                    view.getClass().getMethod("setTextColor", int.class)
                            .invoke(view, textColor);
                } catch (Throwable ignored) {
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyCardTheme(group.getChildAt(i), root, avatar, textTarget,
                        backgroundColor, textColor, depth + 1);
            }
        }
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        View current = view;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent() instanceof View
                    ? (View) current.getParent() : null;
        }
        return false;
    }

    private static int countVisibleTextObjects(View view, View target, int depth) {
        if (view == null || depth > 16 || view.getVisibility() != View.VISIBLE
                || view.getAlpha() <= 0f) {
            return 0;
        }
        int count = 0;
        if (view == target) {
            count++;
        } else if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && !isChromeText(text.toString())) {
                count++;
            }
        }
        if (count >= 3 || !(view instanceof ViewGroup)) {
            return count;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && count < 3; i++) {
            count += countVisibleTextObjects(group.getChildAt(i), target, depth + 1);
        }
        return Math.min(count, 3);
    }

    private static int countVisibleContentImages(View view, View avatar, int depth) {
        if (view == null || view == avatar || isDescendantOf(view, avatar) || depth > 16
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return 0;
        }
        int count = view instanceof android.widget.ImageView
                && view.getWidth() > 0 && view.getHeight() > 0 ? 1 : 0;
        if (count >= 2 || !(view instanceof ViewGroup)) {
            return count;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && count < 2; i++) {
            count += countVisibleContentImages(group.getChildAt(i), avatar, depth + 1);
        }
        return Math.min(count, 2);
    }

    private static boolean hasInteractiveContent(View view, View root,
                                                  View target, int depth) {
        if (view == null || depth > 16 || view.getVisibility() != View.VISIBLE
                || view.getAlpha() <= 0f) {
            return false;
        }
        if (view != root && view != target
                && (view.isClickable() || view.isLongClickable())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasInteractiveContent(group.getChildAt(i), root, target, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View contentContainer(View item, TextView text) {
        View parent = text.getParent() instanceof View ? (View) text.getParent() : null;
        View best = parent;
        while (parent != null && parent != item) {
            String n = parent.getClass().getSimpleName();
            if (n.contains("Avatar") || n.contains("Mask") || n.contains("CheckBox")) {
                break;
            }
            if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                best = parent;
            }
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        return best;
    }

    /** Prefer the text's tight parent, never the full-width bkj row. */
    private static View tightParent(TextView text, Context context, int screen) {
        View parent = text.getParent() instanceof View
                ? (View) text.getParent() : null;
        if (parent instanceof ViewGroup
                && parent.getWidth() > 0 && parent.getHeight() > 0
                && parent.getWidth() <= screen * 86 / 100
                && parent.getHeight() <= text.getHeight()
                + M3Theme.dp(context, 48f)) {
            return parent;
        }
        return text;
    }

    private static TextView findMessageText(View view) {
        return findBest(view, null, 0);
    }

    private static TextView findBest(View view, TextView best, int bestScore) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0
                    && tv.getVisibility() == View.VISIBLE
                    && tv.getWidth() > 0 && tv.getHeight() > 0
                    && !isChromeText(cs.toString())) {
                int score = tv.getWidth() * Math.max(1, cs.length());
                if (score > bestScore) {
                    return tv;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findBest(group.getChildAt(i), best, bestScore);
                if (found != null) {
                    best = found;
                    bestScore = found.getWidth() * Math.max(1,
                            found.getText() == null ? 1 : found.getText().length());
                }
            }
        }
        return best;
    }

    static boolean isChromeText(String s) {
        return s.matches("\\d{1,2}:\\d{2}") || s.matches("\\d{1,2}/\\d{1,2}")
                || s.contains("昨日") || s.contains("星期")
                || s.contains("メッセージはもうありません")
                || s.contains("以下は新しいメッセージです");
    }

    private static TextView findText(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0
                    && tv.getVisibility() == View.VISIBLE
                    && tv.getWidth() > 0 && tv.getHeight() > 0) {
                return tv;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
