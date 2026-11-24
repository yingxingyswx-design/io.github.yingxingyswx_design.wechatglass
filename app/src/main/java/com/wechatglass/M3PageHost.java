package com.wechatglass;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

/**
 * Mounts the three self-built M3E pages inside WeChat's own tab pages.
 *
 * <p>The pager already exposes the page objects as children; each page is a
 * {@code FrostedContentView}. We look for the native list object inside that
 * page and insert our page right above it, so ViewPager keeps owning layout,
 * scrolling and page recycling. Native pages are never hidden or detached, so
 * their adapters stay alive as the data and click source.
 */
final class M3PageHost {

    private static final String TAG = "WeChatGlassPages";

    private final Activity activity;
    private final View pager;
    private final M3PageBase[] pages = new M3PageBase[4];
    private int current = -1;

    M3PageHost(Activity activity, View pager) {
        this.activity = activity;
        this.pager = pager;
    }

    /** Called on every tab change; also retries until the page is populated. */
    void show(int tab) {
        if (pager == null || activity == null) {
            return;
        }
        // The three pages are part of the Material Design experiment: with the
        // switch off, WeChat's own pages must stay untouched.
        if (HookConfig.nativeList(activity)) {
            hideAll();
            current = -1;
            return;
        }
        current = tab;
        for (int i = 1; i <= 3; i++) {
            if (i != tab && pages[i] != null && pages[i].getVisibility() != View.GONE) {
                pages[i].setVisibility(View.GONE);
            }
        }
        if (tab < 1 || tab > 3) {
            return;
        }
        if (!mount(tab)) {
            retry(tab, 0);
        }
    }

    private void hideAll() {
        for (int i = 1; i <= 3; i++) {
            if (pages[i] != null && pages[i].getVisibility() != View.GONE) {
                pages[i].setVisibility(View.GONE);
            }
        }
    }

    private void retry(final int tab, final int attempt) {
        if (attempt > 24 || current != tab) {
            return;
        }
        activity.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (current == tab) {
                    if (!mount(tab)) {
                        retry(tab, attempt + 1);
                    }
                }
            }
        }, 250L);
    }

    private boolean mount(int tab) {
        View root = pageRoot(tab);
        if (root == null) {
            return false;
        }
        View nativeList = findList(root);
        ViewGroup container;
        if (nativeList != null && nativeList.getParent() instanceof ViewGroup) {
            container = (ViewGroup) nativeList.getParent();
        } else if (root instanceof ViewGroup) {
            // The page is still building (WeChat shows its own 「正在加载」).
            // Cover it with our page right away instead of leaving the native
            // loading state visible; the data pass follows.
            container = (ViewGroup) root;
        } else {
            return false;
        }
        M3PageBase page = pages[tab];
        if (page == null) {
            page = create(tab);
            if (page == null) {
                return false;
            }
            pages[tab] = page;
        }
        if (page.getParent() != container) {
            if (page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            int index = nativeList == null ? container.getChildCount()
                    : container.indexOfChild(nativeList) + 1;
            container.addView(page, index, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        page.setVisibility(View.VISIBLE);
        page.bringToFront();
        if (nativeList == null) {
            return false;
        }
        return refresh(tab, root);
    }

    private boolean refresh(int tab, View root) {
        M3PageBase page = pages[tab];
        if (page instanceof M3GridPage) {
            boolean ok = ((M3GridPage) page).bind(root);
            if (!ok) {
                retry(tab, 0);
            }
            return ok;
        }
        if (page instanceof M3ContactsPage) {
            ((M3ContactsPage) page).refresh(root);
            return true;
        }
        return true;
    }

    private M3PageBase create(int tab) {
        switch (tab) {
            case 1:
                return new M3ContactsPage(activity, label("通讯录", "連絡先", "Contacts"));
            case 2:
                return new M3GridPage(activity, label("发现", "発見", "Discover"),
                        M3GridPage.DISCOVER);
            case 3:
                return new M3GridPage(activity, label("我", "自分", "Me"), M3GridPage.ME);
            default:
                return null;
        }
    }

    /** Page titles are ours (not from WeChat), so they follow the UI language. */
    private String label(String zh, String ja, String en) {
        String language = "zh";
        try {
            language = activity.getResources().getConfiguration()
                    .getLocales().get(0).getLanguage();
        } catch (Throwable ignored) {
        }
        if ("ja".equals(language)) {
            return ja;
        }
        if ("zh".equals(language)) {
            return zh;
        }
        return en;
    }

    private View pageRoot(int tab) {
        if (!(pager instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) pager;
        if (tab < group.getChildCount()) {
            return group.getChildAt(tab);
        }
        return null;
    }

    private static View findList(View root) {
        if (root instanceof android.widget.AbsListView) {
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

    void destroy() {
        for (int i = 1; i <= 3; i++) {
            M3PageBase page = pages[i];
            if (page != null && page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            pages[i] = null;
        }
    }
}
