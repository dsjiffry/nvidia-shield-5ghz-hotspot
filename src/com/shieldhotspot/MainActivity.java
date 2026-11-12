package com.shieldhotspot;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV screen (D-pad friendly, built in code). Left: settings. Right: live status.
 * Bottom-right: small non-focusable house promo.
 */
public final class MainActivity extends Activity {
    // Palette
    static final int BG_TOP = 0xFF0B1220, BG_BOTTOM = 0xFF131B2E, CARD = 0xFF182235, CARD_STROKE = 0xFF253149,
            FIELD = 0xFF0F1828, FIELD_STROKE = 0xFF33415C, ACCENT = 0xFF38BDF8, ACCENT_DIM = 0xFF0C3B57,
            TEXT = 0xFFF1F5F9, TEXT_2 = 0xFF94A3B8, TEXT_3 = 0xFF64748B,
            GREEN = 0xFF22C55E, AMBER = 0xFFF59E0B, RED = 0xFFEF4444, GRAY = 0xFF64748B;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::pollStatus;
    private boolean resumed, busy, showPass;
    private int band;

    private EditText ssid, pass;
    private TextView prefix, formMsg, pill, statusName, statusPass, rowBand, rowUpstream, rowClients, hint;
    private View dot, setupCard;
    private Button showBtn, band5, band24, startBtn, stopBtn;
    private Switch auto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        band = Hotspot.band(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM}));

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(dp(48), dp(27), dp(48), dp(20)); // Android TV overscan-safe margins
        root.addView(cols, new FrameLayout.LayoutParams(-1, -1));

        // ---------------- left column: header + settings ----------------
        android.widget.ScrollView leftScroll = new android.widget.ScrollView(this);
        leftScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setClipToPadding(false);
        LinearLayout left = vbox();
        left.setPadding(dp(4), dp(4), dp(4), dp(8)); // room for the focus scale-up
        leftScroll.addView(left, new android.widget.ScrollView.LayoutParams(-1, -2));
        cols.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 1.15f));

        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new WifiIcon(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = vbox();
        titles.setPadding(dp(16), 0, 0, 0);
        titles.addView(text("5GHz Wi-Fi Hotspot", 26, TEXT, true));
        titles.addView(text("Share wired internet over Wi-Fi. Devices connect directly, no proxy.", 13, TEXT_2, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        left.addView(header);

        LinearLayout form = card();
        LinearLayout.LayoutParams formLp = new LinearLayout.LayoutParams(-1, -2);
        formLp.topMargin = dp(14);
        left.addView(form, formLp);

        form.addView(label("Network name"));
        LinearLayout nameRow = hbox();
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        prefix = text(P2pOwner.TAG_PREFIX, 16, TEXT_2, false);
        prefix.setPadding(0, 0, dp(6), 0);
        nameRow.addView(prefix);
        ssid = field(Hotspot.ssid(this), false);
        nameRow.addView(ssid, new LinearLayout.LayoutParams(0, dp(42), 1f));
        form.addView(nameRow);

        form.addView(label("Password"));
        LinearLayout passRow = hbox();
        passRow.setGravity(Gravity.CENTER_VERTICAL);
        pass = field(Hotspot.pass(this), true);
        passRow.addView(pass, new LinearLayout.LayoutParams(0, dp(42), 1f));
        showBtn = button("Show", false);
        showBtn.setOnClickListener(v -> togglePassword());
        LinearLayout.LayoutParams showLp = new LinearLayout.LayoutParams(dp(92), dp(42));
        showLp.leftMargin = dp(10);
        passRow.addView(showBtn, showLp);
        form.addView(passRow);

        form.addView(label("Wi-Fi band"));
        LinearLayout bandRow = hbox();
        band5 = button("5 GHz  \u00b7  fast", false);
        band24 = button("2.4 GHz  \u00b7  long range", false);
        band5.setOnClickListener(v -> setBand(Hotspot.BAND_5));
        band24.setOnClickListener(v -> setBand(Hotspot.BAND_24));
        bandRow.addView(band5, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams b24 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        b24.leftMargin = dp(10);
        bandRow.addView(band24, b24);
        form.addView(bandRow);

        auto = new Switch(this);
        auto.setText("Turn on automatically when SHIELD starts");
        auto.setTextColor(TEXT);
        auto.setTextSize(15);
        auto.setChecked(Hotspot.autoStart(this));
        auto.setPadding(dp(4), dp(8), dp(12), dp(8));
        auto.setBackground(focusBg(0x00000000, 0x00000000, 10));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(-1, -2);
        autoLp.topMargin = dp(8);
        form.addView(auto, autoLp);

        LinearLayout actions = hbox();
        startBtn = button("Start hotspot", true);
        stopBtn = button("Stop", false);
        startBtn.setOnClickListener(v -> startHotspot());
        stopBtn.setOnClickListener(v -> stopHotspot());
        actions.addView(startBtn, new LinearLayout.LayoutParams(0, dp(48), 1.6f));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stopLp.leftMargin = dp(10);
        actions.addView(stopBtn, stopLp);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = dp(10);
        form.addView(actions, actLp);

        formMsg = text("", 13, AMBER, false);
        formMsg.setPadding(dp(2), dp(10), 0, 0);
        formMsg.setVisibility(View.GONE);
        form.addView(formMsg);

        // ---------------- right column: status + setup ----------------
        LinearLayout right = vbox();
        right.setPadding(dp(28), 0, 0, dp(64)); // bottom space keeps clear of the promo card
        cols.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout status = card();
        right.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pillRow = hbox();
        pillRow.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        dot.setBackground(round(GRAY, 0, 0, 999));
        pillRow.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        pill = text("Checking\u2026", 15, TEXT_2, true);
        pill.setPadding(dp(10), 0, 0, 0);
        pillRow.addView(pill);
        status.addView(pillRow);

        statusName = text("", 28, TEXT, true);
        statusName.setPadding(0, dp(14), 0, 0);
        statusName.setSingleLine();
        statusName.setEllipsize(TextUtils.TruncateAt.END);
        status.addView(statusName);
        statusPass = text("", 15, TEXT_2, false);
        statusPass.setPadding(0, dp(4), 0, dp(14));
        status.addView(statusPass);

        View div = new View(this);
        div.setBackgroundColor(CARD_STROKE);
        status.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));

        rowBand = detailRow(status, "Band");
        rowUpstream = detailRow(status, "Internet source");
        rowClients = detailRow(status, "Connected devices");

        hint = text("", 13, TEXT_3, false);
        hint.setPadding(0, dp(14), 0, 0);
        status.addView(hint);

        setupCard = card();
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(-1, -2);
        setupLp.topMargin = dp(16);
        right.addView(setupCard, setupLp);
        ((LinearLayout) setupCard).addView(text("One-time setup", 17, AMBER, true));
        TextView steps = text("1.  Settings \u203a Device Preferences \u203a About: select Build 7 times\n"
                + "2.  Settings \u203a Device Preferences \u203a Developer options: turn on Network debugging\n"
                + "3.  Come back and press Start hotspot, then choose Always allow on the prompt", 13, TEXT_2, false);
        steps.setPadding(0, dp(8), 0, 0);
        steps.setLineSpacing(dp(4), 1f);
        ((LinearLayout) setupCard).addView(steps);
        setupCard.setVisibility(View.GONE);

        // ---------------- bottom-right promo (not focusable) ----------------
        String promoTitle = getString(R.string.promo_title), promoText = getString(R.string.promo_text);
        if (!promoTitle.isEmpty()) {
            LinearLayout promo = vbox();
            promo.setBackground(round(0xCC111A2B, CARD_STROKE, 1, 10));
            promo.setPadding(dp(14), dp(8), dp(14), dp(8));
            promo.setFocusable(false);
            promo.addView(text(promoTitle, 12, TEXT_2, true));
            promo.addView(text(promoText, 11, TEXT_3, false));
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            plp.setMargins(0, 0, dp(28), dp(20));
            root.addView(promo, plp);
        }

        setContentView(root);
        applyBand();
        updatePassViews();
        startBtn.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    // ---------------- actions ----------------

    private void startHotspot() {
        String s = ssid.getText().toString().trim(), p = pass.getText().toString();
        int max = band == Hotspot.BAND_5 ? 22 : 32;
        if (s.isEmpty() || s.length() > max) { formMessage("Network name must be 1\u2013" + max + " characters.", RED); ssid.requestFocus(); return; }
        if (p.length() < 8 || p.length() > 63) { formMessage("Password must be 8\u201363 characters.", RED); pass.requestFocus(); return; }
        Hotspot.prefs(this).edit()
                .putString("ssid", s).putString("pass", p).putInt("band", band)
                .putBoolean("autostart", auto.isChecked()).putBoolean("enabled", true)
                .apply();
        formMessage("Starting\u2026 If a debugging prompt appears, choose \u201cAlways allow\u201d.", AMBER);
        setPill(AMBER, "Starting\u2026");
        run(() -> {
            Hotspot.startWatchdog(this, 60000, true);
            if (Hotspot.autoStart(this)) HotspotJob.scheduleAll(this); else HotspotJob.cancelAll(this);
            return null;
        });
    }

    private void stopHotspot() {
        Hotspot.prefs(this).edit().putBoolean("enabled", false).apply();
        HotspotJob.cancelAll(this);
        formMessage("Stopping\u2026", TEXT_2);
        run(() -> {
            Hotspot.stopWatchdog(this);
            return null;
        });
    }

    interface Task { Object call() throws Exception; }

    private void run(Task t) {
        busy = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(false);
        bg.submit(() -> {
            String err = null;
            boolean adbOff = false;
            try { t.call(); }
            catch (Hotspot.AdbOffException e) { adbOff = true; }
            catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fErr = err;
            final boolean fOff = adbOff;
            ui.post(() -> {
                busy = false;
                startBtn.setEnabled(true);
                stopBtn.setEnabled(true);
                if (fOff) {
                    setupCard.setVisibility(View.VISIBLE);
                    formMessage("Network debugging is off. Follow the one-time setup on the right.", AMBER);
                } else if (fErr != null) {
                    formMessage(fErr.contains("authoriz") || fErr.contains("timed out")
                            ? "Please approve the debugging prompt (tick \u201cAlways allow\u201d), then press Start again."
                            : "Couldn't start: " + fErr, RED);
                } else {
                    Hotspot.prefs(this).edit().putBoolean("adbReady", true).apply();
                    formMsg.setVisibility(View.GONE);
                }
                ui.removeCallbacks(poll);
                ui.postDelayed(poll, 1500);
            });
        });
    }

    private void pollStatus() {
        if (!resumed) return;
        if (busy) { ui.postDelayed(poll, 1500); return; }
        if (!Hotspot.adbReady(this)) {
            // First run: don't touch adb (that would pop the debugging prompt) until the user presses Start.
            render(new Hotspot.Status());
            return;
        }
        bg.submit(() -> {
            Hotspot.Status s = Hotspot.status(this);
            ui.post(() -> {
                render(s);
                if (resumed) ui.postDelayed(poll, 3000);
            });
        });
    }

    private void render(Hotspot.Status s) {
        boolean wanted = Hotspot.enabled(this);
        setupCard.setVisibility(s.adbOff ? View.VISIBLE : View.GONE);
        if (s.adbOff) setPill(AMBER, "Setup needed");
        else if (s.error != null) setPill(RED, "Can't reach system service");
        else if (s.running && s.up) setPill(GREEN, "On \u00b7 sharing internet");
        else if (s.running) setPill(AMBER, "Starting\u2026");
        else if (wanted) setPill(AMBER, "Restarting\u2026");
        else setPill(GRAY, "Off");

        boolean on = s.running && s.up;
        statusName.setText(on && !s.name.isEmpty() ? s.name : Hotspot.broadcastName(this));
        statusName.setTextColor(on ? TEXT : TEXT_2);
        updatePassViews();
        rowBand.setText(on ? s.bandLabel() : (band == Hotspot.BAND_5 ? "5 GHz" : "2.4 GHz"));
        rowUpstream.setText(on ? s.upstreamLabel() : "\u2014");
        rowClients.setText(on && s.clients >= 0 ? String.valueOf(s.clients) : "\u2014");
        hint.setText(on ? "On your phone or laptop, open Wi-Fi settings and join \u201c" + s.name + "\u201d."
                : s.adbOff ? "Network debugging lets this app manage Wi-Fi sharing without root."
                : "Press Start hotspot to share this SHIELD's Ethernet connection.");
    }

    // ---------------- small UI helpers ----------------

    private void togglePassword() {
        showPass = !showPass;
        int sel = pass.getSelectionEnd();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | (showPass
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        pass.setTypeface(Typeface.DEFAULT);
        pass.setSelection(Math.max(0, Math.min(sel, pass.length())));
        showBtn.setText(showPass ? "Hide" : "Show");
        updatePassViews();
    }

    private void updatePassViews() {
        String p = Hotspot.pass(this);
        if (p.isEmpty()) { statusPass.setText(""); return; }
        statusPass.setText("Password  " + (showPass ? p : "\u2022" + repeat('\u2022', Math.min(p.length(), 12) - 1)));
    }

    private void setBand(int b) {
        band = b;
        applyBand();
    }

    private void applyBand() {
        prefix.setVisibility(band == Hotspot.BAND_5 ? View.VISIBLE : View.GONE);
        styleSegment(band5, band == Hotspot.BAND_5);
        styleSegment(band24, band == Hotspot.BAND_24);
    }

    private void styleSegment(Button btn, boolean selected) {
        btn.setBackground(focusBg(selected ? ACCENT_DIM : FIELD, selected ? ACCENT : FIELD_STROKE, 10));
        btn.setTextColor(selected ? ACCENT : TEXT_2);
    }

    private void setPill(int color, String s) {
        dot.setBackground(round(color, 0, 0, 999));
        pill.setText(s);
        pill.setTextColor(color);
    }

    private void formMessage(String s, int color) {
        formMsg.setText(s);
        formMsg.setTextColor(color);
        formMsg.setVisibility(View.VISIBLE);
    }

    private TextView detailRow(LinearLayout parent, String name) {
        LinearLayout row = hbox();
        row.setPadding(0, dp(12), 0, 0);
        row.addView(text(name, 14, TEXT_3, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text("\u2014", 14, TEXT, true);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.3f));
        parent.addView(row);
        return v;
    }

    private EditText field(String value, boolean password) {
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_3);
        e.setTextSize(16);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        e.setTypeface(Typeface.DEFAULT);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setBackground(focusBg(FIELD, FIELD_STROKE, 10));
        return e;
    }

    private Button button(String s, boolean primary) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(primary ? 0xFF04121D : TEXT);
        btn.setBackground(primary ? focusBg(ACCENT, ACCENT, 12) : focusBg(0xFF223049, 0xFF2C3B57, 12));
        btn.setStateListAnimator(null);
        btn.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start());
        return btn;
    }

    /** Background with a bright ring when focused (TV D-pad visibility). */
    private Drawable focusBg(int fill, int stroke, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(fill, Color.WHITE, 3, radiusDp));
        s.addState(new int[]{-android.R.attr.state_enabled}, round(0xFF1A2335, 0xFF1A2335, 1, radiusDp));
        s.addState(new int[]{}, round(fill, stroke, 1, radiusDp));
        return s;
    }

    private GradientDrawable round(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = vbox();
        c.setBackground(round(CARD, CARD_STROKE, 1, 16));
        c.setPadding(dp(22), dp(14), dp(22), dp(18));
        return c;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, TEXT_2, true);
        t.setPadding(dp(2), dp(10), 0, dp(5));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        return t;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Wi-Fi glyph for the header. */
    static final class WifiIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        WifiIcon(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(ACCENT);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h * 0.82f;
            p.setStrokeWidth(w * 0.09f);
            p.setStyle(Paint.Style.STROKE);
            for (int i = 1; i <= 3; i++) {
                float rad = w * 0.18f * i;
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(r, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, w * 0.07f, p);
        }
    }
}
