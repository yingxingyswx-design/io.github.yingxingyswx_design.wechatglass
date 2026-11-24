package com.wechatglass;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wechatglass.m3.M3Theme;
import com.wechatglass.m3.M3Type;

import java.util.ArrayList;
import java.util.List;

/**
 * M3E single-column contacts page: entry rows read from the native header item
 * object, then A–Z sections built from rcontact.pyInitial (WeChat's own pinyin,
 * so 「パパ」sorts under C exactly like the native list). Clicking a row opens
 * the native profile page.
 */
class M3ContactsPage extends M3PageBase {

    private final ContactAdapter adapter = new ContactAdapter();
    private final List<Row> rows = new ArrayList<>();
    private final LinearLayout indexBar;
    private final List<String> sections = new ArrayList<>();

    M3ContactsPage(Context context, String title) {
        super(context, title);
        list.setAdapter(adapter);
        indexBar = new LinearLayout(context);
        indexBar.setOrientation(LinearLayout.VERTICAL);
        indexBar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                M3Theme.dp(context, 24f), ViewGroup.LayoutParams.MATCH_PARENT);
        barLp.gravity = Gravity.END;
        barLp.topMargin = topBar;
        barLp.bottomMargin = M3Theme.dp(context, 96f);
        addView(indexBar, barLp);
        indexBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (sections.isEmpty()) {
                    return false;
                }
                float ratio = event.getY() / Math.max(1, v.getHeight());
                int idx = (int) (ratio * sections.size());
                idx = Math.max(0, Math.min(sections.size() - 1, idx));
                scrollToSection(sections.get(idx));
                return true;
            }
        });
    }

    /** Rebuilds rows from the index; the native page supplies the entry rows. */
    void refresh(View pageRoot) {
        List<ConversationIndex.Entry> contacts = ConversationIndex.contacts();
        if (contacts == null) {
            contacts = new ArrayList<>();
        }
        List<Row> next = new ArrayList<>();
        sections.clear();
        for (EntryRow entry : collectEntries(pageRoot)) {
            Row row = new Row();
            row.entry = entry;
            next.add(row);
        }
        String current = null;
        for (ConversationIndex.Entry contact : contacts) {
            String section = sectionOf(contact);
            if (!section.equals(current)) {
                current = section;
                Row header = new Row();
                header.section = section;
                next.add(header);
                sections.add(section);
            }
            Row row = new Row();
            row.contact = contact;
            next.add(row);
        }
        if (!sameRows(next)) {
            rows.clear();
            rows.addAll(next);
            adapter.notifyDataSetChanged();
            rebuildIndexBar();
        }
    }

    private boolean sameRows(List<Row> next) {
        if (next.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            Row a = next.get(i);
            Row b = rows.get(i);
            if (a.section != null || b.section != null) {
                if (a.section == null || !a.section.equals(b.section)) {
                    return false;
                }
            } else if (a.contact != b.contact || a.entry != b.entry) {
                return false;
            }
        }
        return true;
    }

    private void rebuildIndexBar() {
        indexBar.removeAllViews();
        for (String section : sections) {
            TextView letter = new TextView(getContext());
            letter.setText(section);
            M3Type.apply(letter, M3Type.LABEL_SMALL, true);
            letter.setTextColor(scheme.primary);
            letter.setGravity(Gravity.CENTER);
            letter.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            indexBar.addView(letter);
        }
    }

    private void scrollToSection(String section) {
        for (int i = 0; i < rows.size(); i++) {
            if (section.equals(rows.get(i).section)) {
                list.setSelection(i);
                return;
            }
        }
    }

    private static String sectionOf(ConversationIndex.Entry contact) {
        String py = contact.sortKey();
        if (py == null || py.isEmpty()) {
            return "#";
        }
        char c = Character.toUpperCase(py.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }

    static final class EntryRow {
        String label;
        Drawable icon;
        View source;
    }

    /** The entry block (new friends / groups / tags / …) is one native item. */
    private List<EntryRow> collectEntries(View pageRoot) {
        List<EntryRow> entries = new ArrayList<>();
        View recycler = findList(pageRoot);
        if (!(recycler instanceof ViewGroup)) {
            return entries;
        }
        ViewGroup group = (ViewGroup) recycler;
        // The entry block (new friends / groups / tags / …) is the list's FIRST
        // item only. Walking every visible row duplicated contacts as entries.
        if (group.getChildCount() > 0) {
            collectEntryViews(group.getChildAt(0), entries, 0);
        }
        return entries;
    }

    private void collectEntryViews(View view, List<EntryRow> out, int depth) {
        if (view == null || depth > 4 || out.size() >= 8) {
            return;
        }
        if (depth > 0 && view.isClickable() && view.getHeight() > 0) {
            String text = firstText(view);
            if (text != null && text.length() > 0 && text.length() < 30) {
                EntryRow entry = new EntryRow();
                entry.label = text;
                entry.icon = firstIcon(view);
                entry.source = view;
                out.add(entry);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectEntryViews(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static String firstText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = firstText(group.getChildAt(i));
                if (found != null && !found.isEmpty()) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Drawable firstIcon(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) {
            return ((ImageView) view).getDrawable();
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

    static final class Row {
        String section;
        ConversationIndex.Entry contact;
        EntryRow entry;
    }

    private final class ContactAdapter extends BaseAdapter {
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
            if (row.section != null) {
                return sectionHeader(row.section);
            }
            if (row.entry != null) {
                return entryRow(row.entry);
            }
            return contactRow(row.contact);
        }
    }

    private View sectionHeader(String section) {
        TextView header = new TextView(getContext());
        header.setText(section);
        M3Type.apply(header, M3Type.TITLE_SMALL, true);
        header.setTextColor(scheme.onSurfaceVariant);
        header.setPadding(pad, M3Theme.dp(getContext(), 16f), pad,
                M3Theme.dp(getContext(), 4f));
        return header;
    }

    /**
     * Same capsule as the home list rows: a 16dp-radius card inset 20dp from
     * the sides and 3dp vertically, with an 8% press state layer.
     */
    private android.graphics.drawable.Drawable capsuleBackground() {
        Context context = getContext();
        int insetX = M3Theme.dp(context, 20f);
        int insetY = M3Theme.dp(context, 3f);
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.InsetDrawable(
                        com.wechatglass.m3.M3Drawable.fill(context,
                                com.wechatglass.m3.M3ColorScheme.mix(
                                        cardBg, scheme.onSurface, 0.08f),
                                com.wechatglass.m3.M3Shape.LARGE),
                        insetX, insetY, insetX, insetY));
        states.addState(new int[]{},
                new android.graphics.drawable.InsetDrawable(
                        com.wechatglass.m3.M3Drawable.fill(context, cardBg,
                                com.wechatglass.m3.M3Shape.LARGE),
                        insetX, insetY, insetX, insetY));
        return states;
    }

    private View entryRow(final EntryRow entry) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(M3Theme.dp(context, 64f));
        row.setPadding(pad, M3Theme.dp(context, 6f), pad, M3Theme.dp(context, 6f));
        row.setBackground(capsuleBackground());
        if (entry.icon != null) {
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(entry.icon);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    M3Theme.dp(context, 40f), M3Theme.dp(context, 40f));
            iconLp.rightMargin = M3Theme.dp(context, 16f);
            row.addView(icon, iconLp);
        }
        TextView label = new TextView(context);
        label.setText(entry.label);
        M3Type.apply(label, M3Type.TITLE_MEDIUM);
        label.setTextColor(scheme.onSurface);
        label.setSingleLine(true);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final EntryRow source = entry;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (source.source != null) {
                    source.source.performClick();
                }
            }
        });
        return row;
    }

    private View contactRow(final ConversationIndex.Entry contact) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(M3Theme.dp(context, 64f));
        row.setPadding(pad, M3Theme.dp(context, 6f), pad, M3Theme.dp(context, 6f));
        row.setBackground(capsuleBackground());

        ImageView avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundColor(scheme.surfaceContainerHighest);
        applyAvatarShape(avatar);
        AvatarLoader.load(avatar, contact.username);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(
                M3Theme.dp(context, 40f), M3Theme.dp(context, 40f));
        avatarLp.rightMargin = M3Theme.dp(context, 16f);
        row.addView(avatar, avatarLp);

        TextView name = new TextView(context);
        name.setText(contact.display);
        M3Type.apply(name, M3Type.TITLE_MEDIUM);
        name.setTextColor(scheme.onSurface);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProfile(contact.username);
            }
        });
        return row;
    }

    private android.view.ViewOutlineProvider outline;

    private void applyAvatarShape(ImageView avatar) {
        if (HookConfig.roundAvatarEnabled(getContext())) {
            avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        } else {
            if (outline == null) {
                final float radius = M3Theme.dp(getContext(), 8f);
                outline = new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                };
            }
            avatar.setOutlineProvider(outline);
        }
        avatar.setClipToOutline(true);
    }

    private void openProfile(String username) {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(getContext(), "com.tencent.mm.plugin.profile.ui.ContactInfoUI");
            intent.putExtra("Contact_User", username);
            getContext().startActivity(intent);
        } catch (Throwable throwable) {
            android.util.Log.w("WeChatGlass", "open profile failed: " + username, throwable);
        }
    }

    private static View findList(View root) {
        if (root instanceof AbsListView) {
            return root;
        }
        if (root.getClass().getName().contains("RecyclerView")) {
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
}
