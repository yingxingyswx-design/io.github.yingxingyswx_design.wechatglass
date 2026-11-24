package com.wechatglass;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.wechatglass.m3.M3ColorScheme;
import com.wechatglass.m3.M3Drawable;
import com.wechatglass.m3.M3Shape;
import com.wechatglass.m3.M3Theme;
import com.wechatglass.m3.M3Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan B: our own conversation list, drawn over WeChat's list.
 *
 * <p>WeChat only creates a conversation row once a message exists, so friends
 * and groups without any chat history never appear. This page renders all of
 * them from {@link ConversationIndex}: rows with history show the last message
 * and time, rows without history show 无聊天记录. WeChat's own list and adapter
 * are left untouched — this view is simply placed on top of it, and tapping a
 * row opens that chat through WeChat's own activity (which also creates the
 * session only when the user opens it).
 */
final class HomeCategoryPage extends FrameLayout {

    static HomeCategoryPage current;

    interface OpenChat {
        void open(String username);
    }

    interface OpenAction {
        void open(String action);
    }

    /** Long-press forwards to WeChat's own conversation menu (no DB writes). */
    interface LongPress {
        boolean open(String username);
    }

    private final ListView list;
    private final EntryAdapter adapter = new EntryAdapter();
    private final int topInset;
    private final int bottomInset;
    private final M3ColorScheme scheme;
    private final int pageBg;
    private final int cardBg;
    private final OpenChat openChat;
    private final OpenAction openAction;
    private final LongPress longPress;

    HomeCategoryPage(Context context, boolean night, int topInset, int bottomInset,
                     OpenChat openChat, final Runnable onPullDown, OpenAction openAction,
                     LongPress longPress) {
        super(context);
        this.scheme = M3Theme.colors(context);
        // Sampled from Google Chat's light home: neutral page + card.
        // Same surfaces as the 通讯录 / 发现 / 我 pages so the four tabs share one
        // background instead of switching between a grey and a green-tinted page.
        this.pageBg = scheme.surface;
        this.cardBg = scheme.surfaceContainerLow;
        this.topInset = topInset;
        this.bottomInset = bottomInset;
        this.openChat = openChat;
        this.openAction = openAction;
        this.longPress = longPress;
        setBackgroundColor(pageBg);

        list = new ListView(context);
        list.setBackgroundColor(pageBg);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        // A real blank area at the top. ListView resets its own padding while
        // installing an adapter, so the space is a header item instead.
        View topSpace = new View(context);
        topSpace.setBackgroundColor(pageBg);
        topSpace.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, topInset));
        list.addHeaderView(topSpace, null, false);
        if (bottomInset > 0) {
            View bottomSpace = new View(context);
            bottomSpace.setBackgroundColor(pageBg);
            bottomSpace.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bottomInset));
            list.addFooterView(bottomSpace, null, false);
        }
        list.setAdapter(adapter);
        final float[] pullStart = new float[1];
        final boolean[] pullArmed = new boolean[1];
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        pullStart[0] = event.getRawY();
                        pullArmed[0] = list.getFirstVisiblePosition() == 0;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (pullArmed[0] && event.getRawY() - pullStart[0]
                                > Ui.dp(getContext(), 90f)) {
                            pullArmed[0] = false;
                            if (onPullDown != null) {
                                onPullDown.run();
                            }
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        pullArmed[0] = false;
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                // ListView counts the blank header (and the footer) in
                // position; the adapter only knows its own rows.
                int adapterPosition = position - list.getHeaderViewsCount();
                Row row = adapter.rowAt(adapterPosition);
                if (row == null) {
                    return;
                }
                if (row.action != null && openAction != null) {
                    openAction.open(row.action);
                    return;
                }
                if (row.entry != null && openChat != null) {
                    openChat.open(row.entry.username);
                }
            }
        });
        // A hard top limit: the list is pushed down by a fixed margin instead of
        // padding, because ListView resets its own padding when its adapter is
        // set. The blocker keeps that strip blank (the floating top bar lives
        // there) and stops rows from drawing underneath it.
        // Padding version: rows are clipped below the top inset instead of
        // being pushed by margins.
        addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        current = this;
    }


    private static final class Row {
        final ConversationIndex.Entry entry;
        final String label;
        final String action;

        Row(ConversationIndex.Entry entry) {
            this.entry = entry;
            this.label = null;
            this.action = null;
        }

        Row(String label) {
            this.entry = null;
            this.label = label;
            this.action = null;
        }

        Row(String label, String action) {
            this.entry = null;
            this.label = label;
            this.action = action;
        }
    }

    private String deviceText;
    private String noteText;
    private int currentMode = HomeConversationFilter.FRIEND;

    void setHeaderTexts(String device, String note) {
        if (equal(deviceText, device) && equal(noteText, note)) {
            return;
        }
        deviceText = device;
        noteText = note;
        show(currentMode);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Actions-only means the async index was not ready when show() ran. */
    boolean hasRows() {
        return adapter.getCount() > 3;
    }

    void show(int mode) {
        currentMode = mode;
        List<ConversationIndex.Entry> data;
        switch (mode) {
            case HomeConversationFilter.GROUP:
                data = ConversationIndex.groups();
                break;
            case HomeConversationFilter.SYSTEM:
                data = ConversationIndex.system();
                break;
            case HomeConversationFilter.FRIEND:
            default:
                data = ConversationIndex.friends();
                break;
        }
        List<ConversationIndex.Entry> pinned = new ArrayList<>();
        List<ConversationIndex.Entry> unpinned = new ArrayList<>();
        for (ConversationIndex.Entry entry : data) {
            if (entry.pinned) {
                pinned.add(entry);
            } else {
                unpinned.add(entry);
            }
        }
        // Newest first, like WeChat's own list. (The contact query comes back
        // in pinyin order, which is why the rows looked name-sorted.)
        java.util.Comparator<ConversationIndex.Entry> byTime =
                new java.util.Comparator<ConversationIndex.Entry>() {
                    @Override
                    public int compare(ConversationIndex.Entry a, ConversationIndex.Entry b) {
                        return Long.compare(b.time, a.time);
                    }
                };
        java.util.Collections.sort(pinned, byTime);
        java.util.Collections.sort(unpinned, byTime);

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("小程序", "weapp"));
        rows.add(new Row("已登入设备", "device"));
        rows.add(new Row(noteText != null && !noteText.isEmpty()
                ? noteText : "置顶笔记", "note"));
        if (!pinned.isEmpty()) {
            rows.add(new Row("置顶"));
            for (ConversationIndex.Entry entry : pinned) {
                rows.add(new Row(entry));
            }
        }
        if (!unpinned.isEmpty()) {
            rows.add(new Row("未置顶"));
            for (ConversationIndex.Entry entry : unpinned) {
                rows.add(new Row(entry));
            }
        }
        adapter.setData(rows);
        list.setSelection(0);
        if (getParent() instanceof ViewGroup) {
            bringToFront();
        }
    }

    void setPaddings(int top, int bottom) {
        list.setPadding(0, top, 0, bottom);
    }

    private final class EntryAdapter extends BaseAdapter {
        private List<Row> data = new ArrayList<>();

        void setData(List<Row> next) {
            data = next == null ? new ArrayList<Row>() : next;
            notifyDataSetChanged();
        }

        Row rowAt(int position) {
            return position >= 0 && position < data.size() ? data.get(position) : null;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            Row row = rowAt(position);
            return row == null ? null : row.entry;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rowAt(position);
            if (row == null) {
                return new View(getContext());
            }
            if (row.action != null) {
                return buildActionRow(row.label, row.action);
            }
            if (row.entry == null) {
                return buildHeader(convertView, row.label);
            }
            LinearLayout view;
            if (convertView instanceof LinearLayout
                    && convertView.getTag() instanceof Holder) {
                view = (LinearLayout) convertView;
            } else {
                view = buildRow();
            }
            bind(view, row.entry);
            return view;
        }
    }

    /** Entry row (小程序 / 已登入设备 / 置顶笔记); the label is one line, ellipsized. */
    private View buildActionRow(String label, String action) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(M3Theme.dp(context, 56f));
        int pad = M3Theme.dp(context, 16f);
        row.setPadding(pad, M3Theme.dp(context, 10f), pad, M3Theme.dp(context, 10f));
        row.setBackground(cardBackground(context));

        TextView text = new TextView(context);
        text.setText(label);
        M3Type.apply(text, M3Type.TITLE_MEDIUM);
        text.setTextColor(scheme.onSurface);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = M3Theme.dp(context, 20f);
        row.addView(text, textLp);

        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextSize(20f);
        chevron.setTextColor(scheme.onSurfaceVariant);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chevronLp.rightMargin = M3Theme.dp(context, 20f);
        row.addView(chevron, chevronLp);
        return row;
    }

    private View buildHeader(View convertView, String label) {
        TextView header;
        if (convertView instanceof TextView) {
            header = (TextView) convertView;
        } else {
            header = new TextView(getContext());
            header.setPadding(M3Theme.dp(getContext(), 16f), M3Theme.dp(getContext(), 16f),
                    M3Theme.dp(getContext(), 16f), M3Theme.dp(getContext(), 4f));
            header.setTextColor(scheme.onSurfaceVariant);
            header.setBackgroundColor(pageBg);
            header.setTextSize(14f);
            header.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        header.setText(label);
        return header;
    }

    private android.graphics.drawable.Drawable cardBackground(Context context) {
        int insetX = M3Theme.dp(context, 20f);
        int insetY = M3Theme.dp(context, 3f);
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.InsetDrawable(
                        M3Drawable.fill(context,
                                M3ColorScheme.mix(cardBg, scheme.onSurface, 0.08f),
                                M3Shape.LARGE),
                        insetX, insetY, insetX, insetY));
        states.addState(new int[]{},
                new android.graphics.drawable.InsetDrawable(
                        M3Drawable.fill(context, cardBg, M3Shape.LARGE),
                        insetX, insetY, insetX, insetY));
        return states;
    }

    private LinearLayout buildRow() {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(M3Theme.dp(context, 72f));
        int pad = M3Theme.dp(context, 16f);
        row.setPadding(pad, M3Theme.dp(context, 8f), pad, M3Theme.dp(context, 8f));
        row.setBackground(cardBackground(context));

        android.widget.ImageView avatar = new android.widget.ImageView(context);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(
                M3Theme.dp(context, 40f), M3Theme.dp(context, 40f));
        avatarLp.leftMargin = M3Theme.dp(context, 20f);
        avatarLp.rightMargin = M3Theme.dp(context, 16f);
        avatar.setBackgroundColor(scheme.surfaceContainerHighest);
        applyAvatarShape(avatar);
        row.addView(avatar, avatarLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, textsLp);

        TextView name = new TextView(context);
        name.setTextSize(16f);
        name.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setSingleLine(true);
        name.setTextColor(scheme.onSurface);
        texts.addView(name);

        TextView preview = new TextView(context);
        preview.setTextSize(14f);
        preview.setSingleLine(true);
        preview.setTextColor(scheme.onSurfaceVariant);
        texts.addView(preview);

        TextView time = new TextView(context);
        time.setTextSize(12f);
        time.setSingleLine(true);
        time.setTextColor(scheme.onSurfaceVariant);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = M3Theme.dp(context, 8f);
        timeLp.rightMargin = M3Theme.dp(context, 20f);
        row.addView(time, timeLp);

        TextView unread = new TextView(context);
        unread.setTextSize(11f);
        unread.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        unread.setTextColor(scheme.onError);
        unread.setGravity(Gravity.CENTER);
        unread.setMinWidth(M3Theme.dp(context, 20f));
        unread.setPadding(M3Theme.dp(context, 5f), 0, M3Theme.dp(context, 5f), 0);
        unread.setBackground(M3Drawable.fill(context, scheme.error, M3Shape.FULL));
        unread.setVisibility(View.GONE);
        LinearLayout.LayoutParams unreadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, M3Theme.dp(context, 20f));
        unreadLp.leftMargin = M3Theme.dp(context, 6f);
        unreadLp.rightMargin = M3Theme.dp(context, 20f);
        row.addView(unread, unreadLp);

        row.setTag(new Holder(avatar, name, preview, time, unread));
        return row;
    }

    private void bind(LinearLayout row, ConversationIndex.Entry entry) {
        Holder holder = (Holder) row.getTag();
        if (entry == null) {
            return;
        }
        final String username = entry.username;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (openChat != null) {
                    openChat.open(username);
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return longPress != null && longPress.open(username);
            }
        });
        holder.name.setText(entry.display);
        holder.avatar.setBackgroundColor(colorFor(entry.username));
        applyAvatarShape(holder.avatar);
        AvatarLoader.load(holder.avatar, entry.username);
        if (entry.hasConversation) {
            holder.preview.setText(entry.preview == null || entry.preview.isEmpty()
                    ? "[无消息]" : entry.preview);
            holder.time.setText(formatTime(entry.time));
        } else {
            holder.preview.setText("无聊天记录");
            holder.time.setText("");
        }
        if (entry.unread > 0) {
            holder.unread.setText(entry.unread > 99 ? "99+" : String.valueOf(entry.unread));
            holder.unread.setVisibility(View.VISIBLE);
        } else {
            holder.unread.setVisibility(View.GONE);
        }
    }

    private static final android.view.ViewOutlineProvider OVAL_OUTLINE =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            };

    /** Circular when on; WeChat's own rounded rectangle when off. */
    private android.view.ViewOutlineProvider roundedOutline;

    private void applyAvatarShape(android.widget.ImageView avatar) {
        if (avatar == null) {
            return;
        }
        if (HookConfig.roundAvatarEnabled(getContext())) {
            avatar.setOutlineProvider(OVAL_OUTLINE);
            avatar.setClipToOutline(true);
            return;
        }
        if (roundedOutline == null) {
            final float r = M3Theme.dp(getContext(), 8f);
            roundedOutline = new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            };
        }
        avatar.setOutlineProvider(roundedOutline);
        avatar.setClipToOutline(true);
    }

    private static String initial(String display) {
        if (display == null || display.isEmpty()) {
            return "?";
        }
        return display.substring(0, 1).toUpperCase();
    }

    private static int colorFor(String username) {
        int hash = username == null ? 0 : username.hashCode();
        int[] colors = {0xFF7E9CD8, 0xFFD88A7E, 0xFF7ED89A, 0xFFD8B77E,
                0xFF9A7ED8, 0xFF7ED8D0, 0xFFD87EAE};
        return colors[Math.abs(hash) % colors.length];
    }

    private static String formatTime(long time) {
        if (time <= 0) {
            return "";
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        if (now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) {
            return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        }
        return String.format("%02d/%02d", c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static final class Holder {
        final android.widget.ImageView avatar;
        final TextView name;
        final TextView preview;
        final TextView time;
        final TextView unread;

        Holder(android.widget.ImageView avatar, TextView name, TextView preview,
               TextView time, TextView unread) {
            this.avatar = avatar;
            this.name = name;
            this.preview = preview;
            this.time = time;
            this.unread = unread;
        }
    }
}
