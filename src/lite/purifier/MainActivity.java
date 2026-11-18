package lite.purifier;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;

/** Lightweight Xiaomi Air Purifier 4 Lite (zhimi.airp.rmb1) remote, via Xiaomi cloud. */
public class MainActivity extends Activity {
    static final int[][] PROPS = Cloud.PROPS;
    static final int ON = Cloud.ON, MODE = Cloud.MODE, FAV = Cloud.FAV, PM = Cloud.PM, HUM = Cloud.HUM, TEMP = Cloud.TEMP, FILTER = Cloud.FILTER, DAYS = Cloud.DAYS, LOCK = Cloud.LOCK, BRIGHT = Cloud.BRIGHT, BEEP = Cloud.BEEP, FAULT = Cloud.FAULT;
    static boolean isPurifier(JSONObject d) { return Cloud.isPurifier(d); }
    static int num(Object o) { return Cloud.num(o); }
    Cloud cloud;

    interface Task { void run() throws Exception; }

    final ExecutorService bg = Executors.newSingleThreadExecutor();
    SharedPreferences sp;
    LinearLayout root;

    static final int ACCENT = 0xFF3DDC97;
    static final String[] THEMES = {"Auto", "Dark", "Light"};
    int BG, CARD, FG, SUB, OFF, WARN;
    List<JSONObject> lastDevs;
    Map<String, Object[]> lastVals;

    void applyTheme() {
        int t = sp.getInt("theme", 0);
        boolean dark = t == 1 || (t == 0 && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        if (dark) { BG = 0xFF0E1215; CARD = 0xFF1A2026; FG = 0xFFECF0F3; SUB = 0xFF8C98A2; OFF = 0xFF2A323A; WARN = 0xFFF5C542; }
        else { BG = 0xFFF3F5F7; CARD = 0xFFFFFFFF; FG = 0xFF14181C; SUB = 0xFF68727C; OFF = 0xFFE4E8EC; WARN = 0xFF8A5A00; }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll.setBackgroundColor(BG);
    }

    void cycleTheme() {
        sp.edit().putInt("theme", (sp.getInt("theme", 0) + 1) % THEMES.length).apply();
        applyTheme();
        if (lastDevs != null) render(lastDevs, lastVals); else if (!sp.contains("token")) showLogin();
    }

    /** Very light tap feedback. */
    static void buzz(View v) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
    }
    int shownPm = 0;
    boolean animateIn = true;
    final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable auto = new Runnable() { public void run() { if (sp.contains("token")) refresh(); tick.postDelayed(this, 30000); } };
    ScrollView scroll;
    TextView pullHint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cloud = new Cloud(this);
        sp = cloud.sp;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll = new ScrollView(this);
        applyTheme();
        scroll.addView(root);
        scroll.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startY = -1;
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: startY = scroll.getScrollY() == 0 ? e.getRawY() : -1; break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (startY < 0) { if (scroll.getScrollY() == 0) startY = e.getRawY(); break; }
                        float pull = Math.max(0, e.getRawY() - startY);
                        root.setTranslationY(Math.min(pull / 2.5f, dp(90)));
                        if (pull > dp(140) && pullHint != null) pullHint.setText("Release to refresh");
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        boolean go = startY >= 0 && e.getRawY() - startY > dp(140);
                        root.animate().translationY(0).setDuration(200).start();
                        startY = -1;
                        if (go && sp.contains("token")) { if (pullHint != null) pullHint.setText("Refreshing…"); refresh(); }
                        break;
                }
                return false;
            }
        });
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (sp.contains("token")) {
            loading(); tick.post(auto);
            FilterCheck.schedule(this);
            if (android.os.Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("askedNotif", false)) {
                sp.edit().putBoolean("askedNotif", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        } else showLogin();
    }

    @Override protected void onPause() { super.onPause(); tick.removeCallbacks(auto); if (sp.contains("token")) Widget.poke(this); }

    // ---------- UI ----------

    void loading() {
        animateIn = true;
        root.removeAllViews();
        TextView t = label("Connecting…", 16, SUB);
        t.setGravity(Gravity.CENTER); t.setPadding(0, dp(200), 0, 0);
        root.addView(t);
    }

    void showLogin() {
        root.removeAllViews();
        lastDevs = null;

        // Logo: a small version of the PM ring
        LinearLayout logo = new LinearLayout(this);
        logo.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable lg = new android.graphics.drawable.GradientDrawable();
        lg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        lg.setColor(CARD); lg.setStroke(dp(5), ACCENT);
        logo.setBackground(lg);
        TextView pm = label("PM", 20, ACCENT); pm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        logo.addView(pm);
        LinearLayout logoWrap = row(); logoWrap.setGravity(Gravity.CENTER);
        logoWrap.addView(logo, new LinearLayout.LayoutParams(dp(84), dp(84)));
        root.addView(logoWrap, lp(-1, -2, dp(56), dp(16)));

        TextView title = center(label("Purifier Lite", 30, FG));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(center(label("Sign in with your Mi account to control your purifier from anywhere.", 14, SUB)), lp(-1, -2, dp(4), dp(8)));
        TextView err = label("", 14, WARN);
        err.setVisibility(View.GONE);
        err.setBackground(rounded(0x33F5C542, 14));
        err.setPadding(dp(14), dp(12), dp(14), dp(12));

        root.addView(err, lp(-1, -2, dp(8), 0));
        TextView web = pill("Sign in on Xiaomi page", true);
        web.setTextSize(17); web.setPadding(0, dp(16), 0, dp(16));
        web.setOnClickListener(v -> { buzz(v); err.setVisibility(View.GONE); showXiaomiLogin(err); });
        root.addView(web, lp(-1, -2, dp(12), 0));
        root.addView(center(label("Recommended. Works with verification codes.", 12, SUB)), lp(-1, -2, dp(6), 0));
        root.addView(center(label("or with email and password", 13, SUB)), lp(-1, -2, dp(18), 0));

        LinearLayout c = card();
        c.addView(label("Email, phone or Mi ID", 13, SUB));
        EditText user = field("you@example.com");
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        user.setText(sp.getString("lastUser", ""));
        c.addView(user);
        c.addView(label("Password", 13, SUB), lp(-1, -2, dp(8), 0));
        LinearLayout pr = row();
        EditText pass = field("Mi account password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setTypeface(android.graphics.Typeface.DEFAULT);
        pass.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        pr.addView(pass, new LinearLayout.LayoutParams(0, -2, 1));
        TextView eye = label("Show", 13, ACCENT);
        eye.setPadding(dp(14), dp(14), dp(4), dp(14));
        eye.setOnClickListener(v -> {
            boolean hidden = (pass.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setTypeface(android.graphics.Typeface.DEFAULT);
            pass.setSelection(pass.getText().length());
            eye.setText(hidden ? "Hide" : "Show");
        });
        pr.addView(eye);
        c.addView(pr);
        root.addView(c);


        TextView login = pill("Log in", false);
        login.setTextSize(17); login.setPadding(0, dp(16), 0, dp(16));
        Runnable go = () -> {
            String u = user.getText().toString().trim(), pw = pass.getText().toString();
            if (u.isEmpty() || pw.isEmpty()) { err.setText("Enter your email/phone and password."); err.setVisibility(View.VISIBLE); return; }
            buzz(login);
            login.setEnabled(false); login.setText("Signing in…"); login.setAlpha(0.7f);
            err.setVisibility(View.GONE);
            sp.edit().putString("lastUser", u).apply();
            bg.execute(() -> {
                try {
                    cloud.login(u, pw, "in"); // region is found automatically after login
                    ui(this::onResume);
                } catch (Exception e) {
                    android.util.Log.w("Purifier", "login", e);
                    String m = e instanceof IOException ? "Can't reach Xiaomi. Check your internet and try again." : e.getMessage();
                    ui(() -> { err.setText(m); err.setVisibility(View.VISIBLE); login.setEnabled(true); login.setText("Log in"); login.setAlpha(1f); scroll.smoothScrollTo(0, 0); });
                }
            });
        };
        login.setOnClickListener(v -> go.run());
        pass.setOnEditorActionListener((v, id, ev) -> { go.run(); return true; });
        root.addView(login, lp(-1, -2, dp(16), dp(20)));

        LinearLayout help = card();
        help.addView(label("Signed up with Google or Facebook?", 15, FG));
        help.addView(label("Your Mi account has no password yet. Sign in on Xiaomi's website with Google, open Security → Password, set one, then come back here.", 13, SUB), lp(-1, -2, dp(4), dp(10)));
        TextView open = pill("Set a password on account.xiaomi.com", false);
        open.setTextSize(14);
        open.setOnClickListener(v -> { buzz(v); startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.xiaomi.com"))); });
        help.addView(open);
        root.addView(help);

        root.addView(center(label("Your password goes only to Xiaomi and is never saved on this phone.", 12, SUB)), lp(-1, -2, dp(16), 0));
    }

    void refresh() {
        run(() -> {
            JSONArray list = cloud.devices();
            List<JSONObject> devs = new ArrayList<>();
            Map<String, Object[]> vals = new HashMap<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                devs.add(d);
                if (isPurifier(d) && d.optBoolean("isOnline")) {
                    Object[] pv = cloud.getProps(d.getString("did"));
                    if (pv[FILTER] instanceof Number) FilterCheck.notifyFilter(this, sp, num(pv[FILTER]));
                    vals.put(d.getString("did"), pv);
                }
            }
            ui(() -> render(devs, vals));
        });
    }

    void render(List<JSONObject> devs, Map<String, Object[]> vals) {
        lastDevs = devs; lastVals = vals;
        int y = scroll.getScrollY();
        root.removeAllViews();
        for (JSONObject d : devs) {
            String did = d.optString("did");
            Object[] v = vals.get(did);
            if (v != null) applyPending(did, v);
            boolean online = d.optBoolean("isOnline");

            // Header: name + actions
            LinearLayout head = row();
            LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
            names.addView(label(d.optString("name"), 20, FG));
            names.addView(label(online ? "Online" : "Offline", 13, online ? ACCENT : 0xFFFF6B6B));
            head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView theme = iconBtn(THEMES[sp.getInt("theme", 0)], this::cycleTheme);
            theme.setTextSize(14);
            theme.getLayoutParams().width = -2;
            theme.setPadding(dp(16), 0, dp(16), 0);
            head.addView(theme);
            root.addView(head, lp(-1, -2, 0, dp(8)));
            pullHint = label("Updated " + new java.text.SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(new Date())
                    + " · pull down to refresh", 12, SUB);
            root.addView(pullHint);

            if (!isPurifier(d)) { root.addView(label(d.optString("model") + " · not supported yet", 13, SUB)); continue; }
            if (!online) { root.addView(warning("Purifier is offline. Check its power and Wi‑Fi.")); continue; }
            if (v == null) continue;
            boolean on = Boolean.TRUE.equals(v[ON]);

            int fault = num(v[FAULT]);
            if (fault == 2) root.addView(warning("Fault: motor stopped. Unplug the purifier, check the fan, plug it back in."));
            if (fault == 3) root.addView(warning("Fault: air sensor not detected."));
            if (!on && num(v[PM]) > 35) root.addView(warning("Air quality is " + Cloud.pmLabel(num(v[PM])).toLowerCase() + " (PM2.5 " + v[PM] + "). Turn the purifier on."));
            if (v[FILTER] instanceof Number && num(v[FILTER]) <= 10) root.addView(warning("Filter at " + v[FILTER] + "%. Replace it soon."));

            // Hero: PM2.5 ring
            int pm = num(v[PM]);
            int pmColor = Cloud.pmColor(pm);
            String pmText = Cloud.pmLabel(pm);
            android.widget.FrameLayout hero = new android.widget.FrameLayout(this);
            PmRing ring = new PmRing(this, dp(108));
            hero.addView(ring, new android.widget.FrameLayout.LayoutParams(-1, -1));
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL); inner.setGravity(Gravity.CENTER);
            inner.addView(center(label("PM2.5", 14, SUB)));
            TextView big = label(String.valueOf(v[PM]), 64, FG); big.setTypeface(android.graphics.Typeface.create("sans-serif-light", 0));
            inner.addView(center(big));
            inner.addView(center(label("µg/m³ · " + pmText, 14, pmColor)));
            hero.addView(inner, new android.widget.FrameLayout.LayoutParams(dp(216), dp(216), Gravity.CENTER));
            ring.set(pm, pmColor, OFF, on, shownPm);
            // count the number up/down to the new reading
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(shownPm, pm).setDuration(animateIn ? 900 : 500);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> big.setText(String.valueOf(a.getAnimatedValue())));
            if (v[PM] instanceof Number) va.start();
            shownPm = pm;
            LinearLayout heroWrap = new LinearLayout(this); heroWrap.setGravity(Gravity.CENTER);
            hero.setOnClickListener(x -> { buzz(x); showPmScale(pm); });
            heroWrap.addView(hero, new LinearLayout.LayoutParams(-1, dp(300)));
            root.addView(heroWrap, lp(-1, -2, 0, dp(4)));

            // Stats
            LinearLayout stats = row();
            stats.addView(stat("Humidity", v[HUM] + "%"), new LinearLayout.LayoutParams(0, -2, 1));
            stats.addView(gap());
            stats.addView(stat("Temp", v[TEMP] + "°C"), new LinearLayout.LayoutParams(0, -2, 1));
            stats.addView(gap());
            stats.addView(stat("Filter", v[FILTER] + "% · " + v[DAYS] + "d"), new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(stats);

            // Power
            TextView power = pill(on ? "ON" : "OFF", on);
            power.setTextSize(20); power.setPadding(0, dp(18), 0, dp(18));
            power.setOnClickListener(x -> { buzz(x); power.setText(on ? "Turning off…" : "Turning on…"); set(did, PROPS[ON], !on); });
            root.addView(power, lp(-1, -2, dp(16), 0));

            // Mode + fan
            LinearLayout mc = card();
            mc.addView(label("Mode", 14, SUB));
            mc.addView(segmented(new String[]{"Auto", "Sleep", "Favorite"}, num(v[MODE]), i -> set(did, PROPS[MODE], i)));
            boolean fav = num(v[MODE]) == 2;
            if (fav) {
                LinearLayout fanRow = row();
                fanRow.addView(label("Fan speed", 14, SUB), new LinearLayout.LayoutParams(0, -2, 1));
                TextView fanVal = label(String.valueOf(v[FAV]), 14, FG);
                fanRow.addView(fanVal);
                mc.addView(fanRow, lp(-1, -2, dp(16), 0));
                SeekBar fan = new SeekBar(this);
                fan.setMax(14); fan.setProgress(num(v[FAV]));
                fan.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
                fan.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
                fan.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean u) { fanVal.setText(String.valueOf(p)); if (u) buzz(s); }
                    public void onStartTrackingTouch(SeekBar s) {}
                    public void onStopTrackingTouch(SeekBar s) { set(did, PROPS[FAV], s.getProgress()); }
                });
                mc.addView(fan, lp(-1, -2, dp(4), 0));
            } else {
                mc.addView(label((num(v[MODE]) == 0 ? "Auto" : "Sleep") + " sets the fan speed by itself. Choose Favorite to set it yourself.", 12, SUB), lp(-1, -2, dp(14), 0));
            }
            root.addView(mc);

            // Settings
            LinearLayout sc = card();
            sc.addView(label("Display", 14, SUB));
            sc.addView(segmented(new String[]{"Off", "Dim", "Bright"}, num(v[BRIGHT]), i -> set(did, PROPS[BRIGHT], i)));
            sc.addView(toggle("Child lock", v[LOCK], x -> set(did, PROPS[LOCK], x)), lp(-1, -2, dp(12), 0));
            sc.addView(toggle("Beep", v[BEEP], x -> set(did, PROPS[BEEP], x)));
            root.addView(sc);
        }
        if (devs.isEmpty()) {
            root.addView(label("No devices found on this Mi account.", 16, SUB));
        }
        TextView out = label("Log out", 14, SUB);
        out.setGravity(Gravity.CENTER); out.setPadding(0, dp(20), 0, dp(8));
        out.setOnClickListener(x -> { buzz(x); logout(); });
        root.addView(out, lp(-1, -2, 0, 0));
        root.addView(center(label("Made with Claude Code", 11, SUB)), lp(-1, -2, dp(4), dp(8)));
        scroll.post(() -> scroll.scrollTo(0, y));
        if (animateIn) {
            // first load: cards slide up and fade in, one after another
            for (int i = 0; i < root.getChildCount(); i++) {
                View ch = root.getChildAt(i);
                ch.setAlpha(0f); ch.setTranslationY(dp(24));
                ch.animate().alpha(1f).translationY(0).setStartDelay(i * 45L).setDuration(350)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            animateIn = false;
        }
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top; p.bottomMargin = bottom;
        return p;
    }

    android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius));
        return g;
    }

    TextView label(String s, int size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    View gap() { View g = new View(this); g.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1)); return g; }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 20));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setLayoutParams(lp(-1, -2, dp(14), 0));
        return c;
    }

    EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(SUB); e.setTextColor(FG); e.setSingleLine();
        e.setBackground(rounded(OFF, 12));
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        e.setLayoutParams(lp(-1, -2, dp(6), dp(6)));
        return e;
    }

    TextView pill(String s, boolean selected) {
        TextView t = label(s, 15, selected ? 0xFF0E1215 : FG);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setBackground(rounded(selected ? ACCENT : OFF, 14));
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    TextView iconBtn(String s, Runnable r) {
        TextView t = label(s, 22, FG);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(CARD, 22));
        t.setOnClickListener(v -> { buzz(v); r.run(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        p.leftMargin = dp(8);
        t.setLayoutParams(p);
        return t;
    }

    TextView warning(String s) {
        TextView t = label("⚠  " + s, 14, WARN);
        t.setBackground(rounded(0x33F5C542, 14));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setLayoutParams(lp(-1, -2, dp(10), 0));
        return t;
    }

    LinearLayout stat(String name, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 16));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(label(name, 12, SUB));
        c.addView(label(value, 17, FG));
        return c;
    }

    /** Xiaomi 4 Lite PM2.5 bands (µg/m³), with the current reading highlighted. */
    void showPmScale(int pm) {
        Object[][] bands = {
            {20, "0–20", "Excellent", Cloud.pmColor(0), "Clean air. Nothing to do."},
            {35, "21–35", "Good", Cloud.pmColor(30), "Fine for most people. Very sensitive people may notice it."},
            {55, "36–55", "Moderate", Cloud.pmColor(50), "Can bother kids, the elderly and people with asthma or heart/lung problems. Run the purifier."},
            {Integer.MAX_VALUE, "56+", "Poor", Cloud.pmColor(99), "Unhealthy. Keep windows shut and run the purifier on high."}};
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(20), dp(20), dp(12));
        c.setBackgroundColor(CARD);
        c.addView(label("PM2.5 levels", 20, FG));
        c.addView(label("Same colours as the light ring on your purifier (Xiaomi 4 Lite manual).", 13, SUB));
        c.addView(label("Now: " + pm + " µg/m³", 14, SUB), lp(-1, -2, dp(2), dp(8)));
        boolean marked = false;
        for (Object[] b : bands) {
            boolean here = !marked && pm <= (Integer) b[0];
            marked |= here;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            if (here) r.setBackground(rounded(OFF, 12));
            LinearLayout top = row();
            View dot = new View(this);
            dot.setBackground(rounded((Integer) b[3], 6));
            top.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
            TextView name = label("  " + b[2] + "  ·  " + b[1] + (here ? "   ← you are here" : ""), 15, FG);
            if (here) name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            top.addView(name);
            r.addView(top);
            r.addView(label((String) b[4], 13, SUB));
            c.addView(r, lp(-1, -2, dp(4), 0));
        }
        c.addView(label("WHO guideline: keep the daily average under 15 µg/m³.", 12, SUB), lp(-1, -2, dp(12), 0));
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).setView(sv).setPositiveButton("OK", null).create();
        d.show();
        d.getWindow().setBackgroundDrawable(rounded(CARD, 24));
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);
    }

    /** Xiaomi's own sign-in page in a WebView: handles verification codes/captchas; we only pick up its passToken. */
    void showXiaomiLogin(TextView err) {
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.removeAllCookies(null);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        d.setContentView(wv);
        d.setOnDismissListener(x -> { wv.stopLoading(); wv.destroy(); });
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            boolean done;
            @Override public void onPageFinished(android.webkit.WebView v, String url) { check(); }
            @Override public void doUpdateVisitedHistory(android.webkit.WebView v, String url, boolean reload) { check(); }
            void check() {
                String all = cm.getCookie("https://account.xiaomi.com");
                String pt = cookie(all, "passToken"), uid = cookie(all, "userId"), dev = cookie(all, "deviceId");
                if (done || pt == null || uid == null) return;
                done = true;
                d.dismiss();
                cm.removeAllCookies(null); // the app keeps its own session; don't leave the web one lying around
                loading();
                bg.execute(() -> {
                    try { cloud.loginWithPassToken(uid, pt, dev); ui(MainActivity.this::onResume); }
                    catch (Exception e) {
                        android.util.Log.w("Purifier", "web login", e);
                        ui(() -> { showLogin(); toast("Sign-in failed: " + e.getMessage()); });
                    }
                });
            }
        });
        wv.loadUrl("https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_locale=en_IN");
        d.show();
    }

    static String cookie(String all, String name) {
        if (all == null) return null;
        for (String c : all.split(";\\s*")) if (c.startsWith(name + "=") && c.length() > name.length() + 1) return c.substring(name.length() + 1);
        return null;
    }

    TextView center(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    void restyle(TextView t, boolean selected) {
        t.setTextColor(selected ? 0xFF0E1215 : FG);
        t.setBackground(rounded(selected ? ACCENT : OFF, 14));
    }

    LinearLayout segmented(String[] opts, int sel, java.util.function.IntConsumer f) {
        LinearLayout l = row();
        for (int i = 0; i < opts.length; i++) {
            int idx = i;
            TextView p = pill(opts[i], i == sel);
            p.setOnClickListener(v -> {
                buzz(v);
                if (idx == sel) return;
                for (int k = 0; k < l.getChildCount(); k++) restyle((TextView) l.getChildAt(k), k == idx);
                f.accept(idx);
            });
            LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lpp.leftMargin = dp(8);
            l.addView(p, lpp);
        }
        l.setLayoutParams(lp(-1, -2, dp(8), 0));
        return l;
    }

    LinearLayout toggle(String name, Object val, java.util.function.Consumer<Boolean> f) {
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, dp(8));
        r.addView(label(name, 16, FG), new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(Boolean.TRUE.equals(val));
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{ACCENT, SUB}));
        s.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{0x803DDC97, OFF}));
        s.setOnCheckedChangeListener((b, on) -> { buzz(b); f.accept(on); });
        r.addView(s);
        return r;
    }


    void ui(Runnable r) { runOnUiThread(r); }
    void toast(String s) { ui(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    void run(Task t) {
        bg.execute(() -> {
            try { t.run(); }
            catch (Cloud.AuthError e) { toast("Session expired, log in again"); ui(this::logout); }
            catch (Exception e) {
                android.util.Log.e("Purifier", "failed", e);
                ui(() -> { TextView err = warning("Error: " + e.getMessage()); root.addView(err, 0); });
            }
        });
    }

    void logout() {
        cloud.logout();
        showLogin();
    }

    // Xiaomi's cloud keeps reporting the old value for a few seconds after a change, so show what we set
    // until the device catches up (or 20s pass) instead of flipping the control back.
    final Map<String, Object[]> pending = new ConcurrentHashMap<>();

    void set(String did, int[] prop, Object value) {
        pending.put(did + prop[0] + "." + prop[1], new Object[]{value, System.currentTimeMillis()});
        run(() -> {
            cloud.setRaw(did, prop, value);
            Thread.sleep(1500);
            ui(this::refresh);
        });
    }

    void applyPending(String did, Object[] v) {
        for (int k = 0; k < PROPS.length; k++) {
            String key = did + PROPS[k][0] + "." + PROPS[k][1];
            Object[] p = pending.get(key);
            if (p == null) continue;
            boolean fresh = System.currentTimeMillis() - (Long) p[1] < 20000;
            if (!fresh || String.valueOf(p[0]).equals(String.valueOf(v[k]))) pending.remove(key);
            else v[k] = p[0];
        }
    }
}
