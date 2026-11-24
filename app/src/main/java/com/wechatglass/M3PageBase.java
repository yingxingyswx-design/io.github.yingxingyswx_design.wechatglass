package com.wechatglass;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.wechatglass.m3.M3ColorScheme;
import com.wechatglass.m3.M3Drawable;
import com.wechatglass.m3.M3Shape;
import com.wechatglass.m3.M3Theme;
import com.wechatglass.m3.M3Type;

/**
 * Shared shell for the three self-built M3E pages: page background, large top
 * app bar (112dp) and a list that starts below it. Everything is drawn by us;
 * the native page stays alive underneath as the data and click source.
 */
class M3PageBase extends FrameLayout {

    final M3ColorScheme scheme;
    final ListView list;
    final int pageBg;
    final int cardBg;
    final int pad;
    final int gap;
    final int topBar;

    M3PageBase(Context context, String title) {
        super(context);
        scheme = M3Theme.colors(context);
        pageBg = scheme.surface;
        cardBg = scheme.surfaceContainerLow;
        pad = M3Theme.dp(context, 16f);
        gap = M3Theme.dp(context, 16f);
        topBar = Ui.statusBarHeight(context) + M3Theme.dp(context, 112f);
        setBackgroundColor(pageBg);

        list = new ListView(context);
        list.setBackgroundColor(pageBg);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, M3Theme.dp(context, 96f));
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        listLp.topMargin = topBar;
        addView(list, listLp);

        TextView heading = new TextView(context);
        heading.setText(title);
        M3Type.apply(heading, M3Type.HEADLINE_LARGE, true);
        heading.setTextColor(scheme.onSurface);
        heading.setSingleLine(true);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = pad;
        titleLp.topMargin = Ui.statusBarHeight(context) + M3Theme.dp(context, 40f);
        addView(heading, titleLp);
    }

    /** Card background: surfaceContainerLow, 28dp radius, pressed state layer. */
    Drawable cardBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                M3Drawable.fill(getContext(),
                        M3ColorScheme.mix(cardBg, scheme.onSurface, 0.08f),
                        M3Shape.EXTRA_LARGE));
        states.addState(new int[]{},
                M3Drawable.fill(getContext(), cardBg, M3Shape.EXTRA_LARGE));
        return states;
    }

    /** Rounded icon container; the icon is forced to the theme's primary colour. */
    FrameLayout iconBox(Drawable icon, float boxDp, float iconDp) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(M3Drawable.fill(getContext(), scheme.surfaceContainerHigh,
                M3Shape.LARGE_INCREASED));
        ImageView image = new ImageView(getContext());
        int size = M3Theme.dp(getContext(), iconDp);
        if (icon != null) {
            image.setImageDrawable(monochrome(icon, scheme.primary, size));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        box.addView(image, lp);
        int boxSize = M3Theme.dp(getContext(), boxDp);
        box.setLayoutParams(new android.view.ViewGroup.LayoutParams(boxSize, boxSize));
        return box;
    }

    /**
     * WeChat's icon drawable ({@code oj5.d}) is a custom subclass that ignores
     * tint/colour filters, so the glyph is rasterised first and then flattened
     * with {@code SRC_IN}: alpha is kept, every colour becomes {@code color}.
     */
    Drawable monochrome(Drawable icon, int color, int size) {
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    Math.max(1, size), Math.max(1, size),
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            Drawable copy = icon.getConstantState() != null
                    ? icon.getConstantState().newDrawable() : icon;
            copy.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            copy.draw(canvas);
            canvas.drawColor(color, android.graphics.PorterDuff.Mode.SRC_IN);
            return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        } catch (Throwable throwable) {
            return icon;
        }
    }
}
