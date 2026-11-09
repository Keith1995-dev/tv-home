package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Personal view: flights, packages, calendar and things needing attention, read from the Apps Script feed
 * in tools/personal (Gmail + Calendar, sorted out by Claude in your own Google account). The TV only ever sees
 * that summary, never your mail.
 */
final class PersonalPage extends FrameLayout {
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static final int ROWS = 3, ROWS_TOTAL = 7;
    private static final float AGENDA_UNITS = 9.6f; // calendar card height in rows; a day heading is 0.65 of a row

    private final String url;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private TextView headline, updated;
    private LinearLayout agenda, flights, packages, attention;
    private boolean loading;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    PersonalPage(Context c, String url) {
        super(c);
        this.url = url == null ? "" : url.trim();
        this.prefs = c.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
        this.density = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        build();
    }

    /** Shows the cached summary and fetches a new one when it's stale. */
    void start() {
        // A summary saved from a different feed link (or before the link was removed) isn't shown.
        if (!url.equals(prefs.getString("personal_url", ""))) {
            prefs.edit().remove("personal_json").remove("personal_at").putString("personal_url", url).apply();
        }
        String cached = prefs.getString("personal_json", null);
        if (cached != null) render(cached);
        else if (url.isEmpty()) renderSetup();
        long age = System.currentTimeMillis() - prefs.getLong("personal_at", 0);
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, Math.max(0, REFRESH_MS - age));
    }

    void stop() { handler.removeCallbacks(poll); }

    /** Called when the tab is opened: refresh if it's been a few minutes. */
    void shown() {
        if (System.currentTimeMillis() - prefs.getLong("personal_at", 0) > 3 * 60 * 1000L) fetch();
        else render(prefs.getString("personal_json", null)); // relative days may have moved on
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        LinearLayout cols = new LinearLayout(getContext());
        cols.setClipChildren(false);
        addView(cols, new LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        cols.addView(left, new LinearLayout.LayoutParams(dp(400), -1));

        TextView label = text(10, "sans-serif-medium", 0xB3FFFFFF);
        label.setText("PERSONAL");
        label.setLetterSpacing(0.25f);
        left.addView(label);
        headline = text(17, "sans-serif-light", Color.WHITE);
        headline.setMaxLines(2);
        headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headline.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        left.addView(headline, llp(-1, -2, 0, dp(4), 0, 0));
        updated = text(9, "sans-serif", 0x80FFFFFF);
        left.addView(updated, llp(-2, -2, 0, dp(4), 0, dp(14)));

        agenda = card(left, "CALENDAR", "📅");
        ((LinearLayout.LayoutParams) ((View) agenda.getParent()).getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) ((View) agenda.getParent()).getLayoutParams()).height = 0;

        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        LinearLayout.LayoutParams rlp = llp(0, -1, dp(20), 0, 0, 0);
        rlp.weight = 1;
        cols.addView(right, rlp);
        flights = card(right, "FLIGHTS", "✈️");
        packages = card(right, "PACKAGES", "📦");
        attention = card(right, "NEEDS ATTENTION", "❗");
        // Cards hug their rows (see rowBudget); the last one stretches so the column lines up with the calendar.
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) ((View) attention.getParent()).getLayoutParams();
        p.weight = 1;
        p.height = 0;
    }

    /** A liquid-glass card with a small caps title; returns the box its rows go in. */
    private LinearLayout card(LinearLayout parent, String title, String glyph) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(10));
        card.setBackground(new LiquidGlass(card, dp(20)));
        TextView t = text(8, "sans-serif-medium", 0x99FFFFFF);
        t.setText(glyph + "  " + title);
        t.setLetterSpacing(0.2f);
        card.addView(t);
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows, llp(-1, -2, 0, dp(6), 0, 0));
        parent.addView(card, llp(-1, -2, 0, parent.getChildCount() == 0 ? 0 : dp(12), 0, 0));
        return rows;
    }

    // ---------------------------------------------------------------- data

    private void fetch() {
        if (url.isEmpty() || loading) { if (url.isEmpty()) renderSetup(); return; }
        loading = true;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000); // Apps Script can take a few seconds to wake up
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                String json = bo.toString("UTF-8");
                if (json.trim().startsWith("<")) {
                    throw new java.io.IOException("Google wants a sign-in: set the web app's access to Anyone");
                }
                JSONObject o = new JSONObject(json);
                if (o.has("error") && !o.has("events")) throw new java.io.IOException(o.optString("error"));
                prefs.edit().putString("personal_json", json).putLong("personal_at", System.currentTimeMillis()).apply();
                handler.post(() -> render(json));
            } catch (Exception e) {
                android.util.Log.e("tvhome", "personal feed failed", e);
                String msg = e.getMessage();
                handler.post(() -> {
                    if (prefs.getString("personal_json", null) == null) renderError(msg);
                });
            } finally {
                loading = false;
            }
        }).start();
    }

    private void renderSetup() {
        clearRows();
        headline.setText("Connect Gmail and Calendar");
        updated.setText("Follow tools/personal/README.md, then add personal_feed_url to secrets.xml");
        empty(agenda, "Your events show up here");
        empty(flights, "Flights from your booking emails");
        empty(packages, "Orders on the way");
        empty(attention, "Bills, appointments and renewals");
    }

    private void renderError(String msg) {
        clearRows();
        headline.setText("Couldn't reach your feed");
        updated.setText(msg == null ? "" : msg);
    }

    private void render(String json) {
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            clearRows();
            JSONArray ev = o.optJSONArray("events"), fl = o.optJSONArray("flights"),
                    pk = o.optJSONArray("packages"), at = o.optJSONArray("attention");
            int left = renderAgenda(merged(ev, fl, pk, at));
            int[] rows = rowBudget(len(fl), len(pk), len(at));
            renderList(flights, fl, rows[0], "No upcoming flights", false);
            renderList(packages, pk, rows[1], "Nothing on the way", true);
            renderList(attention, at, rows[2], "All clear", false);
            headline.setText(summary(fl, pk, left));
            long upd = o.optLong("updated", 0);
            String err = o.optString("error", "");
            updated.setText((upd > 0 ? "Mail checked " + ago(upd) : "Mail not checked yet")
                    + (err.isEmpty() ? "" : "  ·  last check failed"));
        } catch (Exception e) {
            android.util.Log.e("tvhome", "personal render failed", e);
        }
    }

    private void clearRows() {
        for (LinearLayout l : new LinearLayout[]{agenda, flights, packages, attention}) l.removeAllViews();
    }

    /**
     * Calendar events plus what the mail turned up, as calendar entries: flights at their departure time, and
     * bills, deadlines, appointments and expected deliveries on their day. Covers the next two weeks.
     */
    private static List<JSONObject> merged(JSONArray ev, JSONArray fl, JSONArray pk, JSONArray at)
            throws org.json.JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; ev != null && i < ev.length(); i++) out.add(ev.getJSONObject(i));
        String today = day(0), last = day(14);
        for (int i = 0; fl != null && i < fl.length(); i++) {
            JSONObject f = fl.getJSONObject(i);
            addItem(out, "✈ " + f.optString("title"), f.optString("detail"), f.optString("time"), false, today, last);
            if (!out.isEmpty() && out.get(out.size() - 1).optString("title").startsWith("✈")) {
                out.get(out.size() - 1).put("flight", true);
            }
        }
        for (int i = 0; pk != null && i < pk.length(); i++) {
            JSONObject p = pk.getJSONObject(i);
            if ("delivered".equals(p.optString("status"))) continue;
            addItem(out, "📦 " + p.optString("title") + " arrives", p.optString("detail"), p.optString("time"),
                    true, today, last);
        }
        for (int i = 0; at != null && i < at.length(); i++) {
            JSONObject a = at.getJSONObject(i);
            String st = a.optString("status");
            String icon = "bill".equals(st) ? "💳 " : "appointment".equals(st) ? "📍 " : "⏰ ";
            addItem(out, icon + a.optString("title"), a.optString("detail"), a.optString("time"),
                    !"appointment".equals(st), today, last);
        }
        Collections.sort(out, (a, b) -> {
            int d = a.optString("day").compareTo(b.optString("day"));
            if (d != 0) return d;
            boolean x = a.optBoolean("allDay"), y = b.optBoolean("allDay");
            if (x != y) return x ? -1 : 1;
            return Long.compare(a.optLong("start"), b.optLong("start"));
        });
        return out;
    }

    private static void addItem(List<JSONObject> out, String title, String where, String iso, boolean allDay,
                                String today, String last) throws org.json.JSONException {
        long t = parseTime(iso);
        if (t < 0) return;
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
        if (d.compareTo(today) < 0 || d.compareTo(last) > 0) return;
        boolean dateOnly = allDay || iso.trim().length() <= 10;
        out.add(new JSONObject().put("title", title).put("location", where).put("start", t)
                .put("end", dateOnly ? t : t + 60 * 60 * 1000L).put("allDay", dateOnly).put("day", d)
                .put("mail", true));
    }

    /** What to keep when the card is full: flights and timed events, then mail reminders, then all-day events. */
    private static int priority(JSONObject e, String today) {
        if (e.optBoolean("flight")) return 4;
        if (!e.optBoolean("allDay")) return 3;
        if (e.optBoolean("mail")) return 2;
        return today.equals(e.optString("day")) ? 2 : 1;
    }

    /** Drops the least important, furthest-out entries until rows and day headings fit the card. */
    private static void fit(List<JSONObject> ev, String today) {
        while (units(ev) > AGENDA_UNITS && !ev.isEmpty()) {
            int drop = -1;
            for (int i = 0; i < ev.size(); i++) {
                JSONObject e = ev.get(i);
                if (drop < 0 || priority(e, today) < priority(ev.get(drop), today)
                        || priority(e, today) == priority(ev.get(drop), today)
                        && e.optString("day").compareTo(ev.get(drop).optString("day")) >= 0) {
                    drop = i;
                }
            }
            ev.remove(drop);
        }
    }

    private static float units(List<JSONObject> ev) {
        java.util.Set<String> days = new java.util.HashSet<>();
        for (JSONObject e : ev) days.add(e.optString("day"));
        return ev.size() + days.size() * 0.65f;
    }

    /** ISO 8601 with an offset ("2026-11-21T15:53-05:00"), or just a date; -1 if it's neither. */
    private static long parseTime(String iso) {
        if (iso == null || iso.trim().isEmpty()) return -1;
        try {
            return java.time.OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDate.parse(iso.trim().substring(0, Math.min(10, iso.trim().length())))
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException e2) {
                return -1;
            }
        }
    }

    /** Today, tomorrow, then the days after; returns how many of today's timed entries are still ahead. */
    private int renderAgenda(List<JSONObject> ev) throws org.json.JSONException {
        String today = day(0), tomorrow = day(1);
        long now = System.currentTimeMillis();
        int todayLeft = 0, shown = 0;
        for (JSONObject e : ev) {
            if (e.optString("day").equals(today) && !e.optBoolean("allDay") && e.optLong("end") > now) todayLeft++;
        }
        fit(ev, today);
        String lastDay = null;
        for (int i = 0; i < ev.size(); i++) {
            JSONObject e = ev.get(i);
            String d = e.optString("day");
            boolean allDay = e.optBoolean("allDay");
            if (!d.equals(lastDay)) {
                lastDay = d;
                TextView h = text(8, "sans-serif-medium", 0x80FFFFFF);
                h.setLetterSpacing(0.2f);
                h.setText(d.equals(today) ? "TODAY" : d.equals(tomorrow) ? "TOMORROW" : weekday(d).toUpperCase(Locale.getDefault()));
                agenda.addView(h, llp(-2, -2, 0, agenda.getChildCount() == 0 ? 0 : dp(8), 0, dp(3)));
            }
            boolean now_ = !allDay && e.optLong("start") <= now && e.optLong("end") > now;
            agenda.addView(eventRow(allDay ? "All day" : time(e.optLong("start")), e.optString("title"),
                    e.optString("location"), now_));
            shown++;
        }
        if (shown == 0) empty(agenda, "Nothing coming up in the next two weeks");
        return todayLeft;
    }

    private View eventRow(String when, String title, String where, boolean happening) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(10, "sans-serif-medium", happening ? 0xFF8FD4FF : 0xCCFFFFFF);
        t.setText(when);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(dp(64), -2));
        View bar = new View(getContext());
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(2));
        g.setColor(happening ? 0xFF8FD4FF : 0x66FFFFFF);
        bar.setBackground(g);
        row.addView(bar, llp(dp(3), dp(26), 0, 0, dp(10), 0));
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(12, "sans-serif", Color.WHITE);
        a.setText(title);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(a);
        if (!where.isEmpty()) {
            TextView b = text(9, "sans-serif", 0x80FFFFFF);
            b.setText(where);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(b);
        }
        row.addView(col, new LinearLayout.LayoutParams(-1, -2));
        row.setPadding(0, dp(3), 0, dp(3));
        return row;
    }

    private static int len(JSONArray a) { return a == null ? 0 : a.length(); }

    /** The right column fits about seven rows: two per card, then spare rows go to whichever lists are longer. */
    private static int[] rowBudget(int... n) {
        int[] rows = new int[n.length];
        int left = ROWS_TOTAL;
        for (int i = 0; i < n.length; i++) { rows[i] = Math.min(n[i], 2); left -= Math.max(1, rows[i]); }
        for (boolean grew = true; left > 0 && grew; ) {
            grew = false;
            for (int i = 0; i < n.length && left > 0; i++) {
                if (rows[i] < n[i] && rows[i] < ROWS) { rows[i]++; left--; grew = true; }
            }
        }
        return rows;
    }

    private void renderList(LinearLayout box, JSONArray items, int max, String none, boolean parcels) throws org.json.JSONException {
        int n = items == null ? 0 : items.length();
        if (n == 0) { empty(box, none); return; }
        for (int i = 0; i < Math.min(n, max); i++) {
            JSONObject x = items.getJSONObject(i);
            String status = x.optString("status");
            String icon = parcels ? parcelIcon(status) : "";
            int accent = "delayed".equals(status) || "changed".equals(status) ? 0xFFFFC266
                    : "delivered".equals(status) ? 0xFF7DDC8A : "out".equals(status) ? 0xFF8FD4FF : 0xCCFFFFFF;
            box.addView(itemRow(icon, x.optString("title"), x.optString("detail"), x.optString("when"), accent));
        }
        if (n > max) {
            TextView more = text(9, "sans-serif", 0x80FFFFFF);
            more.setText("+" + (n - max) + " more");
            box.addView(more, llp(-2, -2, 0, dp(2), 0, 0));
        }
    }

    private View itemRow(String icon, String title, String detail, String when, int accent) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        if (!icon.isEmpty()) {
            TextView i = text(14, "sans-serif", Color.WHITE);
            i.setText(icon);
            row.addView(i, llp(-2, -2, 0, 0, dp(10), 0));
        }
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(12, "sans-serif", Color.WHITE);
        a.setText(title);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(a);
        if (!detail.isEmpty()) {
            TextView b = text(9, "sans-serif", 0x99FFFFFF);
            b.setText(detail);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(b);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2);
        clp.weight = 1;
        row.addView(col, clp);
        TextView w = text(10, "sans-serif-medium", accent);
        w.setText(when);
        w.setSingleLine(true);
        row.addView(w, llp(-2, -2, dp(10), 0, 0, 0));
        return row;
    }

    private void empty(LinearLayout box, String msg) {
        TextView t = text(11, "sans-serif-light", 0x80FFFFFF);
        t.setText(msg);
        box.addView(t, llp(-2, -2, 0, dp(2), 0, 0));
    }

    private static String parcelIcon(String status) {
        switch (status) {
            case "delivered": return "✅";
            case "out": return "🚚";
            case "delayed": return "⚠️";
            default: return "📦";
        }
    }

    /** One line that says what matters most right now. */
    private static String summary(JSONArray fl, JSONArray pk, int todayLeft) throws org.json.JSONException {
        StringBuilder s = new StringBuilder();
        if (fl != null && fl.length() > 0) {
            // "Fort Lauderdale → Kochi" + "Thu 12 Nov, 8:15 PM" reads as "✈ Kochi on Thu 12 Nov".
            JSONObject f = fl.getJSONObject(0);
            String title = f.optString("title"), when = f.optString("when");
            int arrow = title.lastIndexOf('→');
            s.append("✈ ").append(arrow >= 0 ? title.substring(arrow + 1).trim() : title);
            int comma = when.indexOf(',');
            if (!when.isEmpty()) s.append(" on ").append(comma > 0 ? when.substring(0, comma) : when);
        }
        int out = 0, coming = 0;
        for (int i = 0; pk != null && i < pk.length(); i++) {
            String st = pk.getJSONObject(i).optString("status");
            if ("out".equals(st)) out++;
            else if (!"delivered".equals(st)) coming++;
        }
        String parcels = out > 0 ? out + (out == 1 ? " package" : " packages") + " out for delivery"
                : coming > 0 ? coming + (coming == 1 ? " package" : " packages") + " on the way" : "";
        if (!parcels.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(parcels);
        String cal = todayLeft == 0 ? "Nothing else on today" : todayLeft + (todayLeft == 1 ? " event" : " events") + " left today";
        s.append(s.length() > 0 ? "  ·  " : "").append(cal);
        return s.toString();
    }

    /**
     * The morning briefing's lines from the cached feed, as {icon, text}: today's calendar, flights in the next
     * two days, packages arriving, things due soon, and "on this day".
     */
    static List<String[]> briefingLines(String json) {
        List<String[]> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONObject o = new JSONObject(json);
            String today = day(0);
            long now = System.currentTimeMillis();
            SimpleDateFormat hm = new SimpleDateFormat("h:mm a", Locale.getDefault());
            List<JSONObject> all = merged(o.optJSONArray("events"), o.optJSONArray("flights"), null, null);
            StringBuilder cal = new StringBuilder();
            int n = 0;
            for (JSONObject e : all) {
                if (!today.equals(e.optString("day")) || !e.optBoolean("allDay") && e.optLong("end") < now) continue;
                n++;
                if (n <= 4) cal.append(cal.length() > 0 ? ",  " : "")
                        .append(e.optBoolean("allDay") ? "" : hm.format(new Date(e.optLong("start"))) + " ")
                        .append(e.optString("title"));
            }
            out.add(new String[]{"📅", n == 0 ? "Nothing on the calendar today"
                    : (n == 1 ? "1 thing today: " : n + " things today: ") + cal + (n > 4 ? "  …" : "")});
            JSONArray fl = o.optJSONArray("flights");
            for (int i = 0; fl != null && i < fl.length(); i++) {
                JSONObject f = fl.getJSONObject(i);
                long t = parseTime(f.optString("time"));
                if (t > now && t < now + 48 * 3600 * 1000L) {
                    out.add(new String[]{"✈️", f.optString("title") + "  ·  " + f.optString("when") + "\n" + f.optString("detail")});
                }
            }
            JSONArray pk = o.optJSONArray("packages");
            List<String> arriving = new ArrayList<>();
            int onWay = 0;
            for (int i = 0; pk != null && i < pk.length(); i++) {
                JSONObject p = pk.getJSONObject(i);
                String st = p.optString("status");
                long t = parseTime(p.optString("time"));
                if ("out".equals(st) || t > 0 && today.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t)))) {
                    arriving.add(p.optString("title"));
                } else if (!"delivered".equals(st)) onWay++;
            }
            if (!arriving.isEmpty() || onWay > 0) {
                out.add(new String[]{"📦", (arriving.isEmpty() ? "" : android.text.TextUtils.join(", ", arriving)
                        + " arriving today") + (arriving.isEmpty() || onWay == 0 ? "" : "  ·  ")
                        + (onWay == 0 ? "" : onWay + " more on the way")});
            }
            JSONArray at = o.optJSONArray("attention");
            for (int i = 0, shown = 0; at != null && i < at.length() && shown < 3; i++) {
                JSONObject a = at.getJSONObject(i);
                long t = parseTime(a.optString("time"));
                if (t > 0 && t > now + 3 * 24 * 3600 * 1000L) continue;
                out.add(new String[]{"⏰", a.optString("title") + "  ·  " + a.optString("when")
                        + (a.optString("detail").isEmpty() ? "" : "\n" + a.optString("detail"))});
                shown++;
            }
            String otd = o.optString("onThisDay");
            if (!otd.isEmpty()) out.add(new String[]{"✨", otd});
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- formatting

    private static String day(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private static String weekday(String ymd) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd);
            return new SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(d);
        } catch (java.text.ParseException e) {
            return ymd;
        }
    }

    private String time(long ms) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        return new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(ms));
    }

    private String ago(long ms) {
        long min = (System.currentTimeMillis() - ms) / 60000;
        return min < 1 ? "just now" : min < 60 ? min + " min ago" : "at " + time(ms);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return Math.round(v * density); }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
