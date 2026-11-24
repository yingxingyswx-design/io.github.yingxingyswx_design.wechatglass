package com.wechatglass;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.wechatglass.m3.M3Theme;
import com.wechatglass.m3.M3Type;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * M3E 2-column card grid for the Discover and Me tabs.
 *
 * <p>Everything is read from the live Preference objects of the native page:
 * the title lives in the private field {@code h} (WeChat overrides getTitle()
 * to return null), the icon in {@code i} and the layout in {@code F}. Rows are
 * grouped by the section-gap objects, never by hard-coded indices. Clicks go
 * through {@code performItemClick} on the native list, i.e. WeChat's own path.
 */
class M3GridPage extends M3PageBase {

    static final int DISCOVER = 0;
    static final int ME = 1;

    private final int mode;
    private final GridAdapter adapter = new GridAdapter();
    private final List<Row> rows = new ArrayList<>();
    private View nativeList;
    private ListAdapter nativeAdapter;
    /** Last seen state of the balance-card switch, to rebuild on change. */
    private boolean lastBalanceEnabled;

    M3GridPage(Context context, String title, int mode) {
        super(context, title);
        this.mode = mode;
        list.setAdapter(adapter);
    }

    /** Reads the native page's adapter objects; safe to call repeatedly. */
    boolean bind(View pageRoot) {
        View found = findList(pageRoot);
        if (found == null) {
            return false;
        }
        ListAdapter adapter = null;
        try {
            Object value = de.robv.android.xposed.XposedHelpers.callMethod(found, "getAdapter");
            if (value instanceof ListAdapter) {
                adapter = (ListAdapter) value;
            }
        } catch (Throwable ignored) {
        }
        if (adapter == null || adapter.getCount() == 0) {
            return false;
        }
        if (found == nativeList && adapter == nativeAdapter && !rows.isEmpty()) {
            boolean balance = mode == ME
                    && HookConfig.balanceCardEnabled(getContext());
            if (balance != lastBalanceEnabled) {
                lastBalanceEnabled = balance;
                rebuild();
            }
            return true;
        }
        nativeList = found;
        nativeAdapter = adapter;
        rebuild();
        return true;
    }

    private void rebuild() {
        List<Cell> cells = new ArrayList<>();
        Object profile = null;
        int profilePos = -1;
        for (int pos = 0; pos < nativeAdapter.getCount(); pos++) {
            Object item = nativeAdapter.getItem(pos);
            if (item == null) {
                continue;
            }
            String type = item.getClass().getSimpleName();
            if (type.contains("SmallCategory")) {
                continue;
            }
            if (mode == ME && type.contains("AccountInfoPreference")) {
                profile = item;
                profilePos = pos;
                continue;
            }
            Cell cell = new Cell();
            cell.pref = item;
            cell.position = pos;
            cell.title = asString(readField(item, "h"));
            if (cell.title.trim().isEmpty()) {
                // Trailing placeholders / category rows carry no title; they are
                // what showed up as ghost cards before.
                continue;
            }
            Object titleRes = readField(item, "i");
            if (titleRes instanceof Integer) {
                cell.titleRes = (Integer) titleRes;
            }
            if (mode == ME && isPrimaryEntry(cell.title)) {
                cell.fullWidth = true;
            }
            cells.add(cell);
        }
        rows.clear();
        if (mode == ME && profile != null) {
            Row header = new Row();
            header.profile = profile;
            header.position = profilePos;
            rows.add(header);
        }
        if (mode == ME && HookConfig.balanceCardEnabled(getContext())) {
            // Amount card sits between the profile block and the entry grid.
            Row balance = new Row();
            balance.balance = true;
            rows.add(balance);
        }
        java.util.List<Cell> pending = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.fullWidth) {
                addPairs(rows, pending);
                Row row = new Row();
                row.left = cell;
                row.full = true;
                rows.add(row);
            } else {
                pending.add(cell);
            }
        }
        addPairs(rows, pending);
        lastBalanceEnabled = mode == ME && HookConfig.balanceCardEnabled(getContext());
        adapter.notifyDataSetChanged();
    }

    /** 「服务 / 支付」 gets its own full-width row above the grid. */
    private static boolean isPrimaryEntry(String title) {
        if (title == null) {
            return false;
        }
        String text = title.toLowerCase(java.util.Locale.ROOT);
        return text.contains("pay") || text.contains("支付")
                || text.contains("サービス") || text.contains("服务");
    }

    private void addPairs(List<Row> target, List<Cell> cells) {
        for (int i = 0; i < cells.size(); i += 2) {
            Row row = new Row();
            row.left = cells.get(i);
            if (i + 1 < cells.size()) {
                row.right = cells.get(i + 1);
            }
            target.add(row);
        }
        cells.clear();
    }

    private static final class Cell {
        Object pref;
        int position;
        String title;
        /** Field {@code i} is a title string id, not a drawable id. */
        int titleRes;
        Drawable icon;
        /** 「服务」 gets its own full-width row. */
        boolean fullWidth;
    }

    private static final class Row {
        Object profile;
        int position;
        Cell left;
        Cell right;
        boolean full;
        /** 「微信余额」卡片，位于资料卡与入口网格之间。 */
        boolean balance;
    }

    private final class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position < 0 || position >= rows.size()) {
                return new View(getContext());
            }
            Row row = rows.get(position);
            if (row.profile != null) {
                return spaced(profileCard(row), 3, 12);
            }
            if (row.balance) {
                return spaced(balanceCard(), 3, 12);
            }
            Context context = getContext();
            if (row.full) {
                LinearLayout single = new LinearLayout(context);
                single.setOrientation(LinearLayout.HORIZONTAL);
                single.setPadding(pad, M3Theme.dp(context, 3f), pad, M3Theme.dp(context, 3f));
                single.addView(card(row.left), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, M3Theme.dp(context, 64f)));
                return single;
            }
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(pad, M3Theme.dp(context, 3f), pad, M3Theme.dp(context, 3f));
            line.addView(card(row.left), weightParams());
            LinearLayout.LayoutParams rightLp = weightParams();
            if (row.right == null) {
                line.addView(new View(context), rightLp);
            } else {
                rightLp.leftMargin = gap;
                line.addView(card(row.right), rightLp);
            }
            return line;
        }
    }

    /** Wraps a full-width row with vertical breathing room between cards. */
    private View spaced(View content, int topDp, int bottomDp) {
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(getContext());
        wrap.setPadding(0, M3Theme.dp(getContext(), topDp), 0,
                M3Theme.dp(getContext(), bottomDp));
        wrap.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }
    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private View card(Cell cell) {
        if (cell == null) {
            return new View(getContext());
        }
        View view = mode == DISCOVER ? discoverCard(cell) : meCard(cell);
        final Cell target = cell;
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickNative(target.position);
            }
        });
        return view;
    }

    private View discoverCard(Cell cell) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        int padding = M3Theme.dp(context, 16f);
        card.setPadding(padding, padding, padding, padding);
        Drawable icon = iconFor(cell);
        if (icon != null) {
            card.addView(iconBox(icon, 48f, 28f));
        }
        TextView title = new TextView(context);
        title.setText(cell.title);
        M3Type.apply(title, M3Type.TITLE_MEDIUM);
        title.setTextColor(scheme.onSurface);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = icon == null ? 0 : M3Theme.dp(context, 12f);
        card.addView(title, lp);
        card.setMinimumHeight(M3Theme.dp(context, 132f));
        return card;
    }

    private View meCard(Cell cell) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(M3Theme.dp(context, 14f), 0, M3Theme.dp(context, 14f), 0);
        Drawable icon = iconFor(cell);
        if (icon != null) {
            card.addView(iconBox(icon, 40f, 22f));
        }
        TextView title = new TextView(context);
        title.setText(cell.title);
        M3Type.apply(title, M3Type.TITLE_MEDIUM);
        title.setTextColor(scheme.onSurface);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = icon == null ? 0 : M3Theme.dp(context, 12f);
        card.addView(title, lp);
        card.setMinimumHeight(M3Theme.dp(context, 64f));
        return card;
    }

    /**
     * M3E amount card: label, big tabular amount, last-sync caption and a
     * circular refresh button. The refresh only re-reads the local cache that
     * WeChat's own 服务/钱包 page produced while the user was looking at it.
     */
    private View balanceCard() {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        int padding = M3Theme.dp(context, 20f);
        card.setPadding(padding, padding, padding, padding);

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        top.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(context);
        label.setText("微信余额");
        M3Type.apply(label, M3Type.LABEL_LARGE, true);
        label.setTextColor(scheme.onSurfaceVariant);
        texts.addView(label);

        TextView amount = new TextView(context);
        M3Type.apply(amount, M3Type.DISPLAY_SMALL, true);
        amount.setTextColor(scheme.onSurface);
        amount.setSingleLine(true);
        LinearLayout.LayoutParams amountLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amountLp.topMargin = M3Theme.dp(context, 4f);
        texts.addView(amount, amountLp);

        TextView caption = new TextView(context);
        M3Type.apply(caption, M3Type.BODY_SMALL);
        caption.setTextColor(scheme.onSurfaceVariant);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionLp.topMargin = M3Theme.dp(context, 6f);
        texts.addView(caption, captionLp);

        TextView refresh = new TextView(context);
        refresh.setText("↻");
        refresh.setTextSize(22f);
        refresh.setGravity(Gravity.CENTER);
        refresh.setTextColor(scheme.primary);
        refresh.setBackground(com.wechatglass.m3.M3Drawable.fill(context,
                scheme.surfaceContainerHigh, com.wechatglass.m3.M3Shape.FULL));
        int button = M3Theme.dp(context, 40f);
        top.addView(refresh, new LinearLayout.LayoutParams(button, button));

        bindBalance(amount, caption);
        startAutoRefresh(card, amount, caption);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Re-reads the cached value only; never triggers a wallet query.
                bindBalance(amount, caption);
            }
        });
        return card;
    }

    /**
     * The value is produced by WeChat while the user is on the wallet page, so
     * the card has to follow the cache instead of only reading it once.
     */
    private void startAutoRefresh(final View card, final TextView amount,
                                  final TextView caption) {
        card.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!card.isAttachedToWindow()) {
                    return;
                }
                bindBalance(amount, caption);
                card.postDelayed(this, 2000L);
            }
        }, 2000L);
    }

    private void bindBalance(TextView amount, TextView caption) {
        Context context = getContext();
        String value = WalletBalanceCache.value(context);
        long time = WalletBalanceCache.time(context);
        if (value == null || value.isEmpty()) {
            amount.setText("—");
            caption.setText("打开一次微信「服务」页即可获取");
        } else {
            amount.setText(value);
            caption.setText(time > 0
                    ? "更新于 " + formatClock(time) + " · 打开服务页后自动刷新"
                    : "打开服务页后自动刷新");
        }
    }

    private static String formatClock(long time) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        boolean today = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR);
        if (today) {
            return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        }
        return String.format("%02d-%02d %02d:%02d", c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
    private View profileCard(Row row) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(cardBackground());
        int padding = M3Theme.dp(context, 16f);
        card.setPadding(padding, padding, padding, padding);

        ImageView avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable drawable = readAvatar(row.profile);
        String username = asString(readField(row.profile, "K"));
        if (drawable != null) {
            avatar.setImageDrawable(drawable);
        } else if (username.length() > 0) {
            AvatarLoader.load(avatar, username);
        }
        avatar.setBackgroundColor(scheme.surfaceContainerHighest);
        applyCircle(avatar);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(
                M3Theme.dp(context, 64f), M3Theme.dp(context, 64f));
        card.addView(avatar, avatarLp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = M3Theme.dp(context, 16f);
        card.addView(texts, textsLp);

        TextView name = new TextView(context);        name.setText(asString(readField(row.profile, "J")));
        M3Type.apply(name, M3Type.HEADLINE_SMALL, true);
        name.setTextColor(scheme.onSurface);
        name.setSingleLine(true);
        texts.addView(name);

        TextView alias = new TextView(context);
        String id = asString(readField(row.profile, "L"));
        alias.setText("Weixin ID: " + id);
        M3Type.apply(alias, M3Type.BODY_MEDIUM);
        alias.setTextColor(scheme.onSurfaceVariant);
        alias.setSingleLine(true);
        LinearLayout.LayoutParams aliasLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aliasLp.topMargin = M3Theme.dp(context, 4f);
        texts.addView(alias, aliasLp);

        final String selfUsername = asString(readField(row.profile, "K"));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // WeChat's own 「个人信息」 entry is CommonSettingsUI; the
                // AccountInfoPreference callback only handles its own views.
                openProfile(selfUsername);
            }
        });
        return card;
    }

    /** The native row view at an adapter position, or null. */
    private View clickNativeRowView(int position) {
        if (!(nativeList instanceof AbsListView)) {
            return null;
        }
        AbsListView list = (AbsListView) nativeList;
        int first = list.getFirstVisiblePosition();
        return list.getChildAt(position - first);
    }

    /** Clicks the native row view itself (preference rows own the listener). */
    private boolean clickNativeRow(int position) {
        if (!(nativeList instanceof AbsListView)) {
            android.util.Log.i("WeChatGlass", "profile row: no list");
            return false;
        }
        AbsListView list = (AbsListView) nativeList;
        int first = list.getFirstVisiblePosition();
        View row = list.getChildAt(position - first);
        android.util.Log.i("WeChatGlass", "profile row=" + (row == null ? "null"
                : row.getClass().getName())
                + " first=" + first
                + " clickable=" + (row != null && row.isClickable())
                + " listeners=" + (row != null && row.hasOnClickListeners())
                + " pos=" + list.getChildCount());
        if (row == null) {
            return false;
        }
        row.performClick();
        return true;
    }

    /** Opens WeChat's own 「个人信息」 page (CommonSettingsUI). */
    private void openProfile(String username) {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(getContext(),
                    "com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI");
            intent.putExtra("key_config_item", "SettingGroup_Main_PersonalInfo");
            getContext().startActivity(intent);
        } catch (Throwable throwable) {
            android.util.Log.w("WeChatGlass", "open profile failed", throwable);
        }
    }

    private Drawable readAvatar(Object preference) {
        Object[] fields = {readField(preference, "M"), readField(preference, "N")};
        for (Object value : fields) {
            if (value instanceof ImageView) {
                Drawable drawable = ((ImageView) value).getDrawable();
                if (drawable != null) {
                    return drawable;
                }
            }
        }
        return null;
    }

    private static final android.view.ViewOutlineProvider OVAL =
            new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            };

    private android.view.ViewOutlineProvider rounded;

    /** Follows the 「圆形头像」 switch: circle when on, 8dp rounded rect when off. */
    private void applyCircle(ImageView view) {
        if (HookConfig.roundAvatarEnabled(getContext())) {
            view.setOutlineProvider(OVAL);
        } else {
            if (rounded == null) {
                final float radius = M3Theme.dp(getContext(), 8f);
                rounded = new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                };
            }
            view.setOutlineProvider(rounded);
        }
        view.setClipToOutline(true);
    }

    /**
     * WeChat binds the icon into the row view itself; field {@code i} on the
     * Preference is a title string id, not a drawable. Ask the native adapter to
     * build the row once and take the icon out of it, then cache per cell.
     */
    private Drawable iconFor(Cell cell) {
        if (cell.icon != null) {
            return cell.icon;
        }
        if (!(nativeList instanceof ViewGroup) || nativeAdapter == null) {
            return null;
        }
        try {
            View row = nativeAdapter.getView(cell.position, null, (ViewGroup) nativeList);
            cell.icon = firstIcon(row);
        } catch (Throwable throwable) {
            android.util.Log.w("WeChatGlass", "icon extract failed", throwable);
        }
        return cell.icon;
    }

    private static Drawable firstIcon(View view) {
        if (view instanceof ImageView) {
            Drawable drawable = ((ImageView) view).getDrawable();
            if (drawable != null) {
                return drawable;
            }
        }
        // Some WeChat rows (e.g. 小店与卡包) paint the glyph as a View
        // background instead of an ImageView drawable.
        Drawable background = view.getBackground();
        if (background instanceof android.graphics.drawable.BitmapDrawable
                || isVector(background)) {
            return background;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Drawable found = firstIcon(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isVector(Drawable drawable) {
        return drawable != null && drawable.getClass().getName()
                .startsWith("android.graphics.drawable.VectorDrawable");
    }

    private boolean clickNative(int position) {
        if (!(nativeList instanceof AbsListView) || nativeAdapter == null) {
            return false;
        }
        AbsListView view = (AbsListView) nativeList;
        int first = view.getFirstVisiblePosition();
        View row = view.getChildAt(position - first);
        try {
            return view.performItemClick(row != null ? row : view, position,
                    nativeAdapter.getItemId(position));
        } catch (Throwable throwable) {
            android.util.Log.w("WeChatGlass", "performItemClick failed", throwable);
            return false;
        }
    }

    private static View findList(View root) {
        if (root instanceof AbsListView) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findList(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
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

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
