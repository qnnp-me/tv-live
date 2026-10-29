package me.qnnp.tvlive.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import me.qnnp.tvlive.R;

/**
 * 首启设置页（v1.0 §3.2 / §7）：brand + 「还没有频道源」+ 手机地址 / PIN / 二维码 +
 * 「在本机输入地址」。
 *
 * <p>批次 3b 接真实 IP/端口/PIN 与二维码：二维码内容 {@code http://ip:port/#t=token}
 * （token 由 Activity 每 10 分钟轮换）；无 WiFi 显示「请先连接 WiFi」，地址与二维码
 * 不可用，但仍可用「在本机输入地址」走有线/离线导入。</p>
 */
public final class SetupView extends FrameLayout {

    /** 本机地址提交；空/非法校验由 Activity 负责提示。 */
    public interface Listener {

        void onLocalUrl(String url);
    }

    private final LinearLayout urlBlock;
    private final FrameLayout qrBox;
    private final ImageView qrImage;
    private final TextView qrPlaceholder;
    private final TextView addressView;
    private final TextView pinView;
    private final TextView offlineView;
    private final TextView hintView;
    private final TextView noteView;
    private final ActionButton actionView;

    /** 二维码边长（像素），随屏幕宽度，目标约 320。 */
    private final int qrSize;
    /** 实际生成位图的边长（去掉白边，1:1 显示不缩放）。 */
    private final int qrPixelSize;

    private String controlAddress;
    private String controlPin = "";
    private String qrContent;
    private String shownQrContent;

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshNetwork();
        }
    };

    private Listener listener;
    private boolean networkRegistered;

    public SetupView(Context context) {
        super(context);
        setBackgroundColor(color(R.color.ink));

        int padSide = dimen(R.dimen.setup_pad_side);
        int padTop = dimen(R.dimen.setup_pad_top);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 手机窄屏（批次 5a）：单列、可滚动、二维码在下方居中；电视保持左右两列。
        boolean narrow = DeviceMode.isPhone(
                getResources().getConfiguration().smallestScreenWidthDp);
        int phonePad = dimen(R.dimen.phone_pad_side);

        TextView brand = new TextView(context);
        brand.setText(R.string.brand);
        brand.setTextColor(color(R.color.text));
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.brand_text));

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        inner.setGravity(narrow ? Gravity.TOP : Gravity.CENTER_VERTICAL);

        if (narrow) {
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setPadding(phonePad, phonePad, phonePad, phonePad);
            LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brandLp.bottomMargin = dimen(R.dimen.setup_sub_gap);
            inner.addView(brand, brandLp);
            scroll.addView(inner, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brandLp.gravity = Gravity.TOP | Gravity.LEFT;
            brandLp.leftMargin = padSide;
            brandLp.topMargin = padTop;
            addView(brand, brandLp);

            FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                    Math.round(screenWidth * 0.8f), ViewGroup.LayoutParams.WRAP_CONTENT);
            innerLp.gravity = Gravity.CENTER;
            addView(inner, innerLp);
        }

        // ------------------------------------------------------------- copy
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (narrow) {
            // 单列：占满可用宽度，纵向排列（不再和二维码分左右）。
            copyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            copyLp.rightMargin = dimen(R.dimen.setup_gap);
        }
        inner.addView(copy, copyLp);

        TextView title = new TextView(context);
        title.setText(R.string.setup_title);
        title.setTextColor(color(R.color.text));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_title_text));
        copy.addView(title, wrap());

        TextView sub = new TextView(context);
        sub.setText(R.string.setup_sub);
        sub.setTextColor(color(R.color.muted));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_sub_text));
        sub.setLineSpacing(0f, 1.55f);
        if (!narrow) {
            // 窄屏单列由布局宽度约束，不再按（旋转前的）屏幕宽写死，避免横屏裁字。
            sub.setMaxWidth(Math.round(screenWidth * 0.33f));
        }
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dimen(R.dimen.setup_sub_gap);
        copy.addView(sub, subLp);

        noteView = new TextView(context);
        noteView.setTextColor(color(R.color.tally));
        noteView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_note_text));
        noteView.setLineSpacing(0f, 1.4f);
        noteView.setVisibility(GONE);
        LinearLayout.LayoutParams noteLp = wrap();
        noteLp.topMargin = dimen(R.dimen.setup_sub_gap);
        copy.addView(noteView, noteLp);

        urlBlock = new LinearLayout(context);
        urlBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams urlBlockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlBlockLp.topMargin = dimen(R.dimen.setup_url_margin_top);
        copy.addView(urlBlock, urlBlockLp);
        urlBlock.addView(hairline(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout urlRow = new LinearLayout(context);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setPadding(0, dimen(R.dimen.setup_url_gap), 0, dimen(R.dimen.setup_url_gap));
        urlBlock.addView(urlRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addressView = new TextView(context);
        addressView.setText(R.string.setup_url_placeholder);
        addressView.setTextColor(color(R.color.text));
        addressView.setTypeface(Typeface.MONOSPACE);
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_url_text));
        addressView.setSingleLine(true);
        urlRow.addView(addressView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pinView = new TextView(context);
        pinView.setText(R.string.setup_pin_placeholder);
        pinView.setTextColor(color(R.color.tally));
        pinView.setTypeface(Typeface.MONOSPACE);
        pinView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_url_text));
        pinView.setSingleLine(true);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinLp.leftMargin = dimen(R.dimen.setup_gap);
        urlRow.addView(pinView, pinLp);

        urlBlock.addView(hairline(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        offlineView = new TextView(context);
        offlineView.setText(R.string.setup_offline);
        offlineView.setTextColor(color(R.color.tally));
        offlineView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_hint_text));
        offlineView.setVisibility(GONE);
        LinearLayout.LayoutParams offlineLp = wrap();
        offlineLp.topMargin = dimen(R.dimen.setup_sub_gap);
        copy.addView(offlineView, offlineLp);

        hintView = new TextView(context);
        hintView.setText(R.string.setup_hint);
        hintView.setTextColor(color(R.color.muted));
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_hint_text));
        hintView.setLineSpacing(0f, 1.5f);
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = dimen(R.dimen.setup_sub_gap);
        copy.addView(hintView, hintLp);

        actionView = new ActionButton(context);
        actionView.setText(R.string.setup_action_local);
        actionView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openLocalUrlDialog();
            }
        });
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dimen(R.dimen.setup_action_gap);
        copy.addView(actionView, actionLp);

        // --------------------------------------------------------------- qr
        LinearLayout qrColumn = new LinearLayout(context);
        qrColumn.setOrientation(LinearLayout.VERTICAL);
        qrColumn.setGravity(Gravity.CENTER_HORIZONTAL);

        qrBox = new FrameLayout(context);
        qrSize = narrow
                ? Math.min(300, Math.round(screenWidth * 0.6f))
                : Math.min(360, Math.round(screenWidth * 0.25f));
        int qrPad = dimen(R.dimen.setup_qr_pad);
        qrPixelSize = Math.max(160, qrSize - qrPad * 2);
        GradientDrawable qrBackground = new GradientDrawable();
        qrBackground.setColor(Color.WHITE);
        qrBackground.setCornerRadius(dimen(R.dimen.setup_qr_radius));
        qrBox.setBackground(qrBackground);
        qrBox.setPadding(qrPad, qrPad, qrPad, qrPad);

        qrPlaceholder = new TextView(context);
        qrPlaceholder.setText(R.string.setup_qr_placeholder);
        qrPlaceholder.setTextColor(0xFF7E908B);
        qrPlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_qr_label_text));
        qrPlaceholder.setGravity(Gravity.CENTER);
        qrBox.addView(qrPlaceholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        qrImage = new ImageView(context);
        qrImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        qrImage.setVisibility(GONE);
        qrBox.addView(qrImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        qrColumn.addView(qrBox, new LinearLayout.LayoutParams(qrSize, qrSize));

        TextView qrLabel = new TextView(context);
        qrLabel.setText(R.string.setup_qr_label);
        qrLabel.setTextColor(color(R.color.muted));
        qrLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_qr_label_text));
        qrLabel.setSingleLine(true);
        LinearLayout.LayoutParams qrLabelLp = wrap();
        qrLabelLp.topMargin = dimen(R.dimen.setup_qr_label_gap);
        qrColumn.addView(qrLabel, qrLabelLp);

        inner.addView(qrColumn, narrow
                ? centeredWrap()
                : wrap());

        refreshNetwork();
    }

    /** 窄屏单列：二维码块自身宽度收窄并水平居中。 */
    private LinearLayout.LayoutParams centeredWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dimen(R.dimen.setup_gap);
        return lp;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 初始加载失败原因等附加说明；null 隐藏。 */
    public void setNote(String note) {
        if (note == null || note.isEmpty()) {
            noteView.setText("");
            noteView.setVisibility(GONE);
        } else {
            noteView.setText(note);
            noteView.setVisibility(VISIBLE);
        }
    }

    /**
     * Activity 下发真实手机页信息：地址 {@code http://ip:port}、PIN、二维码内容
     * {@code http://ip:port/#t=token}。地址为 null 表示服务未启动/无 WiFi。
     */
    public void setControlInfo(String address, String pin, String qrContent) {
        boolean addressChanged = address == null ? controlAddress != null
                : !address.equals(controlAddress);
        this.controlAddress = address;
        this.controlPin = pin == null ? "" : pin;
        this.qrContent = qrContent;
        if (address == null) {
            shownQrContent = null;
            qrImage.setImageDrawable(null);
            qrImage.setVisibility(GONE);
        } else if (addressChanged || shownQrContent == null
                || !shownQrContent.equals(qrContent)) {
            updateQr();
        }
        addressView.setText(address == null
                ? getContext().getString(R.string.setup_url_placeholder)
                : address);
        pinView.setText(controlPin.isEmpty()
                ? getContext().getString(R.string.setup_pin_placeholder)
                : getContext().getString(R.string.setup_pin_value, controlPin));
        refreshNetwork();
    }

    /** 开关/显示前调用：根据当前网络状态刷新地址与二维码可用性。 */
    public void refreshNetwork() {
        boolean network = isNetworkAvailable(getContext());
        boolean online = network && controlAddress != null;
        urlBlock.setVisibility(online ? VISIBLE : GONE);
        qrBox.setAlpha(online ? 1f : 0.35f);
        if (!online) {
            qrImage.setVisibility(GONE);
            qrPlaceholder.setVisibility(VISIBLE);
            qrPlaceholder.setText(R.string.setup_qr_placeholder);
        } else if (qrContent != null && qrContent.equals(shownQrContent)) {
            // 网络恢复：复用已生成的二维码，不重复编码。
            qrImage.setVisibility(VISIBLE);
            qrPlaceholder.setVisibility(GONE);
        } else {
            updateQr();
        }
        offlineView.setVisibility(online ? GONE : VISIBLE);
        if (!online) {
            offlineView.setText(network
                    ? R.string.settings_control_offline : R.string.setup_offline);
        }
        if (online) {
            hintView.setText(R.string.setup_hint);
        } else if (network) {
            hintView.setText(R.string.setup_control_offline_hint);
        } else {
            hintView.setText(R.string.setup_offline_detail);
        }
    }

    /** 重新生成二维码（IP/端口/token 变化或网络恢复时）。 */
    private void updateQr() {
        if (qrContent == null || qrContent.isEmpty()) {
            qrImage.setImageDrawable(null);
            qrImage.setVisibility(GONE);
            qrPlaceholder.setVisibility(VISIBLE);
            shownQrContent = null;
            return;
        }
        android.graphics.Bitmap bitmap = QrCode.bitmap(qrContent, qrPixelSize);
        if (bitmap == null) {
            qrImage.setImageDrawable(null);
            qrImage.setVisibility(GONE);
            qrPlaceholder.setVisibility(VISIBLE);
            shownQrContent = null;
            return;
        }
        qrImage.setImageBitmap(bitmap);
        qrImage.setVisibility(VISIBLE);
        qrPlaceholder.setVisibility(GONE);
        shownQrContent = qrContent;
    }

    /** 突出「在本机输入地址」；首启页只有这一个可操作项。 */
    public void setActionFocused(boolean focused) {
        actionView.setFocused(focused);
    }

    /** OK 键入口。 */
    public void activate() {
        openLocalUrlDialog();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!networkRegistered) {
            try {
                getContext().registerReceiver(networkReceiver,
                        new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                networkRegistered = true;
            } catch (RuntimeException ignored) {
                // 注册失败只影响实时刷新，不影响首启布局。
            }
        }
        refreshNetwork();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (networkRegistered) {
            try {
                getContext().unregisterReceiver(networkReceiver);
            } catch (RuntimeException ignored) {
                // 已注销/进程回收，忽略。
            }
            networkRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 内部

    private void openLocalUrlDialog() {
        final EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setHint(R.string.setup_url_dialog_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(color(R.color.text));
        input.setHintTextColor(color(R.color.muted));
        int pad = dimen(R.dimen.setup_qr_pad);
        input.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.setup_url_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        submit(input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        input.requestFocus();
    }

    private void submit(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            Toast.makeText(getContext(), R.string.setup_url_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(getContext(), R.string.setup_url_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Listener target = listener;
        if (target != null) {
            target.onLocalUrl(url);
        }
    }

    /** 有任意可用网络（WiFi/有线）即可用手机设置页；TV 无移动网络。 */
    public static boolean isNetworkAvailable(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View hairline() {
        View line = new View(getContext());
        line.setBackgroundColor(color(R.color.hairline));
        return line;
    }

    private int dimen(int resId) {
        return Res.dimen(getContext(), resId);
    }

    private int color(int resId) {
        return Res.color(getContext(), resId);
    }
}
