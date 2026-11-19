package com.cocfarm.app;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.divider.MaterialDivider;

/** The pieces the screens are built from: a scrolling column, headed cards, text, dividers. */
final class Ui {
    private Ui() {
    }

    /** A scrolling column; the column is the scroll view's only child. */
    static NestedScrollView column(Context context) {
        NestedScrollView scroll = new NestedScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 32));
        scroll.addView(column);
        return scroll;
    }

    static LinearLayout inside(NestedScrollView scroll) {
        return (LinearLayout) scroll.getChildAt(0);
    }

    /** A heading, and a card under it to fill. */
    static LinearLayout card(LinearLayout column, String heading) {
        Context context = column.getContext();
        TextView title = new TextView(context);
        title.setTextAppearance(R.style.TextAppearance_COCFARM_Overline);
        title.setText(heading);
        title.setPadding(dp(context, 4), dp(context, 20), 0, dp(context, 8));
        column.addView(title);
        MaterialCardView card = new MaterialCardView(context);
        LinearLayout inside = new LinearLayout(context);
        inside.setOrientation(LinearLayout.VERTICAL);
        card.addView(inside);
        column.addView(card);
        return inside;
    }

    static TextView text(Context context, String value, int appearance, int colour) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextAppearance(appearance);
        view.setTextColor(context.getColor(colour));
        return view;
    }

    static View divider(Context context) {
        MaterialDivider divider = new MaterialDivider(context);
        divider.setDividerColor(context.getColor(R.color.cf_outline_variant));
        LinearLayout.LayoutParams spot = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        spot.setMarginStart(dp(context, 16));
        spot.setMarginEnd(dp(context, 16));
        divider.setLayoutParams(spot);
        return divider;
    }

    /** A file of COCFARM's folder, to another app (Android's share sheet). */
    static void share(Context context, java.io.File file, String type, String title) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(context, "com.cocfarm.app.files", file);
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND).setType(type)
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri(file.getName(), uri));  // the share sheet's preview reads it too
        context.startActivity(android.content.Intent.createChooser(send, title));
    }

    /** Buttons side by side, sharing the width. */
    static LinearLayout buttons(Context context, View... buttons) {
        LinearLayout row = new LinearLayout(context);
        row.setPadding(0, dp(context, 8), 0, 0);
        row.setBaselineAligned(false);
        for (int i = 0; i < buttons.length; i++) {
            // Side by side, all as tall as the tallest (a label on two lines made one taller).
            LinearLayout.LayoutParams room = buttons.length == 1
                    ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) room.setMarginStart(dp(context, 10));
            row.addView(buttons[i], room);
        }
        return row;
    }

    /** An outlined button with an icon. */
    static com.google.android.material.button.MaterialButton button(Context context, String label, int icon,
                                                                    View.OnClickListener act) {
        com.google.android.material.button.MaterialButton button = new com.google.android.material.button.MaterialButton(
                context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setIconResource(icon);
        button.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setOnClickListener(act);
        return button;
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
