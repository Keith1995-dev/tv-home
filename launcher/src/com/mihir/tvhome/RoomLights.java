package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Room moods: pick one on the home screen and every light in the room takes its colours until you pick another,
 * press Lights off, or turn the TV off. Then each light goes back exactly as it was before the first mood.
 *
 * Govee lamps are driven directly over the Wi-Fi with their "LAN Control" protocol (UDP JSON). A Matter bulb goes
 * through tools/lights/orion-bridge.mjs on a computer (lights_bridge_url), since Matter needs a controller.
 */
final class RoomLights {
    private static final String TAG = "tvhome";
    private static final int GOVEE_SEND = 4003, GOVEE_REPLY = 4002, GOVEE_SCAN = 4001;
    private static final String UPLIGHTER = "H60B0";
    private static final String CLOUD = "https://openapi.api.govee.com/router/api/v1/";

    /**
     * id, name,
     * the uplighter's upward light: "alt:#a,#b" (segments alternate), "split:#a,#b" (lower half, upper half),
     * "scene:<Govee scene name>" or "off"; its brightness %; its downward lamp: "off" or kNNNN (a white);
     * the other Govee lamps' colour and brightness %; the bulb's colour and brightness % (or "off");
     * the two colours the home screen shows for the mood.
     * Colours are #RRGGBB, or kNNNN for a white at NNNN Kelvin.
     */
    static final String[][] MOODS = {
            {"action", "Action", "alt:#FF1A1A,#1F4DFF", "90", "off", "#1F4DFF", "55", "#1F4DFF", "35", "#FF1A1A", "#1F4DFF"},
            {"romance", "Romance", "split:#FF3D9A,#A447FF", "60", "off", "#A447FF", "35", "#FF5FA8", "20", "#FF3D9A", "#A447FF"},
            {"chill", "Chill", "scene:Aurora", "70", "off", "#2B4BFF", "30", "#12D6C2", "20", "#19E68C", "#2B4BFF"},
            {"cozy", "Cozy", "scene:Candlelight", "60", "k2700", "#FF7A1F", "35", "k2200", "30", "#FFB46B", "#FF7A1F"},
            {"party", "Party", "scene:Dance Party", "100", "off", "#20E5FF", "85", "#8A2BFF", "60", "#FF20D0", "#20E5FF"},
            {"horror", "Horror", "scene:Ghost", "40", "off", "#3A0060", "12", "off", "0", "#B0000A", "#3A0060"},
            {"focus", "Focus", "off", "80", "k5000", "k4000", "60", "k5000", "80", "#EEF2FF", "#FFE9D2"},
    };

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private DatagramSocket gs;
    // How things were before the first mood (network thread only); null when no mood is on.
    private Map<String, JSONObject> before;
    private final Map<String, String> skuOf = new LinkedHashMap<>();
    private volatile String mood;
    private volatile int scale = 100; // the panel's Brightness: % of each mood's own brightness

    RoomLights(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
    }

    String mood() { return mood; }

    static String[] find(String id) {
        for (String[] m : MOODS) if (m[0].equals(id)) return m;
        return null;
    }

    /** null or an unknown id = lights off: everything back as it was. */
    void set(String id) {
        String[] m = id == null ? null : find(id);
        mood = m == null ? null : id;
        net.execute(() -> {
            if (m == null) { restore(); return; }
            if (before == null) snapshot();
            // The uplighter first: through Govee's cloud its upward light can take segment colours and scenes.
            JSONObject up = cloudDevice(UPLIGHTER);
            boolean cloudDone = up != null && uplighterMood(up, m);
            for (String ip : before.keySet()) {
                if (ip.equals("bulb") || ip.equals("cloud")) continue;
                boolean isUp = UPLIGHTER.equals(skuOf.get(ip));
                if (isUp && cloudDone) continue;
                String colour = isUp ? m[9] : m[5];
                int pct = scaled(isUp ? m[3] : m[6]);
                for (int round = 0; round < 2; round++) { // UDP can drop a message: say it twice
                    goveeSend(ip, "turn", json("value", 1));
                    goveeColour(ip, colour);
                    goveeBrightness(ip, pct);
                    sleep(250);
                }
            }
            if (before.containsKey("bulb")) {
                if (m[7].equals("off")) { // fade down, then off
                    bridge("/set?level=1&t=15");
                    sleep(1700);
                    bridge("/set?on=0");
                } else {
                    bridge("/set?on=1&level=" + Math.round(scaled(m[8]) * 2.54f) + bulbColour(m[7]) + "&t=15");
                }
            }
            android.util.Log.e(TAG, "mood " + id + " on " + before.size() + " light(s), uplighter " + (cloudDone ? "via cloud" : "via LAN"));
        });
    }

    private int scaled(String pct) { return Math.max(1, Integer.parseInt(pct) * scale / 100); }

    /** Brightness for the running mood, 10–100% of each light's own level for it; kept for later moods too. */
    void brightness(int pct) {
        scale = Math.max(10, Math.min(100, pct));
        String[] m = mood == null ? null : find(mood);
        if (m == null) return;
        net.execute(() -> {
            if (before == null) return;
            JSONObject up = cloudDevice(UPLIGHTER);
            boolean cloudUp = up != null && upSet(up, "devices.capabilities.range", "brightness", scaled(m[3]));
            for (String ip : before.keySet()) {
                if (ip.equals("bulb") || ip.equals("cloud")) continue;
                boolean isUp = UPLIGHTER.equals(skuOf.get(ip));
                if (isUp && cloudUp) continue;
                for (int round = 0; round < 2; round++) { goveeBrightness(ip, scaled(isUp ? m[3] : m[6])); sleep(200); }
            }
            if (before.containsKey("bulb") && !m[7].equals("off")) bridge("/set?level=" + Math.round(scaled(m[8]) * 2.54f) + "&t=8");
            android.util.Log.e(TAG, "mood " + mood + " brightness " + scale + "%");
        });
    }

    /** The uplighter's three sections: upward (side + ripple) colours or scene, downward (bottom) white or off. */
    private boolean uplighterMood(JSONObject dev, String[] m) {
        String up = m[2];
        boolean upOn = !up.equals("off"), downOn = !m[4].equals("off");
        // Only what changes: Govee allows about 10 commands a minute per lamp.
        boolean ok = upSet(dev, "devices.capabilities.on_off", "powerSwitch", 1)
                && upSet(dev, "devices.capabilities.toggle", "sideLightToggle", upOn ? 1 : 0)
                && upSet(dev, "devices.capabilities.toggle", "rippleLightToggle", upOn ? 1 : 0)
                && upSet(dev, "devices.capabilities.toggle", "bottomLightToggle", downOn ? 1 : 0)
                && upSet(dev, "devices.capabilities.range", "brightness", scaled(m[3]));
        if (!ok) return false;
        if (downOn) cloud(dev, "devices.capabilities.color_setting", "colorTemperatureK", Integer.parseInt(m[4].substring(1)));
        if (up.startsWith("scene:")) {
            Object v = sceneValue(dev, up.substring(6));
            return v != null && cloud(dev, "devices.capabilities.dynamic_scene", "lightScene", v);
        }
        if (up.startsWith("alt:") || up.startsWith("split:")) {
            String[] c = up.substring(up.indexOf(':') + 1).split(",");
            boolean alt = up.startsWith("alt:");
            org.json.JSONArray first = new org.json.JSONArray(), second = new org.json.JSONArray();
            for (int i = 0; i < 8; i++) ((alt ? i % 2 == 0 : i < 4) ? first : second).put(i);
            return segments(dev, first, c[0]) && segments(dev, second, c[1]);
        }
        return true;
    }

    // What the uplighter's switches and brightness are now, as far as we know (from the snapshot, then our own
    // commands), so unchanged ones aren't sent again.
    private final Map<String, Integer> upKnown = new LinkedHashMap<>();

    private boolean upSet(JSONObject dev, String type, String instance, int value) {
        Integer known = upKnown.get(instance);
        if (known != null && known == value) return true;
        if (!cloud(dev, type, instance, value)) return false;
        upKnown.put(instance, value);
        return true;
    }

    private boolean segments(JSONObject dev, org.json.JSONArray which, String hex) {
        try {
            return cloud(dev, "devices.capabilities.segment_color_setting", "segmentedColorRgb",
                    new JSONObject().put("segment", which).put("rgb", android.graphics.Color.parseColor(hex) & 0xFFFFFF));
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    private void snapshot() {
        before = new LinkedHashMap<>();
        for (Map.Entry<String, String> lamp : goveeLamps().entrySet()) {
            skuOf.put(lamp.getKey(), lamp.getValue());
            JSONObject s = goveeStatus(lamp.getKey());
            if (s != null) before.put(lamp.getKey(), s);
        }
        JSONObject up = cloudDevice(UPLIGHTER);
        JSONObject state = up == null ? null : cloudState(up);
        if (state != null) {
            before.put("cloud", state);
            upKnown.clear();
            for (String k : new String[]{"powerSwitch", "sideLightToggle", "rippleLightToggle", "bottomLightToggle", "brightness"})
                if (state.has(k)) upKnown.put(k, state.optInt(k));
        }
        JSONObject b = bridgeJson("/snapshot");
        if (b != null) before.put("bulb", b);
    }

    private void restore() {
        if (before == null) return;
        JSONObject state = before.get("cloud"), up = state == null ? null : cloudDevice(UPLIGHTER);
        boolean cloudUp = state != null && up != null;
        for (Map.Entry<String, JSONObject> e : before.entrySet()) {
            if (e.getKey().equals("bulb") || e.getKey().equals("cloud")) continue;
            // The uplighter goes back through the cloud below: its LAN colour command drives the colour sections,
            // so "white" would switch the upward light on and the white-only downward lamp off.
            if (cloudUp && UPLIGHTER.equals(skuOf.get(e.getKey()))) continue;
            String ip = e.getKey();
            JSONObject s = e.getValue();
            JSONObject col = s.optJSONObject("color");
            int k = s.optInt("colorTemInKelvin");
            for (int round = 0; round < 2; round++) { // twice, in case a message is dropped
                if (k > 0) goveeColour(ip, "k" + k);
                else if (col != null) goveeColour(ip, String.format("#%02X%02X%02X", col.optInt("r"), col.optInt("g"), col.optInt("b")));
                goveeBrightness(ip, s.optInt("brightness", 100));
                if (s.optInt("onOff") == 0) goveeSend(ip, "turn", json("value", 0));
                sleep(300);
            }
        }
        if (cloudUp) {
            // Its three sections on or off as they were, then its brightness (and its white, if it had one).
            for (String t : new String[]{"sideLightToggle", "rippleLightToggle", "bottomLightToggle"})
                if (state.has(t)) upSet(up, "devices.capabilities.toggle", t, state.optInt(t));
            if (state.optInt("brightness") > 0) upSet(up, "devices.capabilities.range", "brightness", state.optInt("brightness"));
            if (state.optInt("colorTemperatureK") > 0)
                cloud(up, "devices.capabilities.color_setting", "colorTemperatureK", state.optInt("colorTemperatureK"));
            if (state.optInt("powerSwitch", 1) == 0) upSet(up, "devices.capabilities.on_off", "powerSwitch", 0);
        }
        if (before.containsKey("bulb")) bridgeJson("/restore");
        before = null;
        android.util.Log.e(TAG, "mood off: lights restored");
    }

    // ---------------------------------------------------------------- Govee cloud (govee_api_key)

    // Only for what the lamps can't do over the LAN, such as the uplighter's upward segments and its scenes.
    // Govee allows about 10 commands a minute per lamp, so a mood sends five or six.

    /** {sku, device} for the first lamp of that model on the account, remembered after the first lookup. */
    private JSONObject cloudDevice(String sku) {
        String key = ctx.getString(R.string.govee_api_key).trim();
        if (key.isEmpty()) return null;
        try {
            JSONObject cached = new JSONObject(prefs.getString("govee_cloud", "{}"));
            if (cached.has(sku)) return cached.getJSONObject(sku);
            org.json.JSONArray devs = new JSONObject(http("GET", CLOUD + "user/devices", null)).getJSONArray("data");
            for (int i = 0; i < devs.length(); i++) {
                JSONObject d = devs.getJSONObject(i);
                cached.put(d.getString("sku"), new JSONObject().put("sku", d.getString("sku")).put("device", d.getString("device")));
            }
            prefs.edit().putString("govee_cloud", cached.toString()).apply();
            return cached.optJSONObject(sku);
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee cloud devices: " + e.getMessage());
            return null;
        }
    }

    private boolean cloud(JSONObject dev, String type, String instance, Object value) {
        try {
            JSONObject body = new JSONObject().put("requestId", String.valueOf(System.nanoTime())).put("payload",
                    new JSONObject().put("sku", dev.getString("sku")).put("device", dev.getString("device"))
                            .put("capability", new JSONObject().put("type", type).put("instance", instance).put("value", value)));
            JSONObject r = new JSONObject(http("POST", CLOUD + "device/control", body.toString()));
            if (r.optInt("code") == 200) return true;
            android.util.Log.e(TAG, "govee cloud " + instance + ": " + r.optString("msg"));
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee cloud " + instance + ": " + e.getMessage());
        }
        return false;
    }

    /** instance → value for everything the cloud reports about the lamp. */
    private JSONObject cloudState(JSONObject dev) {
        try {
            JSONObject body = new JSONObject().put("requestId", "state").put("payload",
                    new JSONObject().put("sku", dev.getString("sku")).put("device", dev.getString("device")));
            org.json.JSONArray caps = new JSONObject(http("POST", CLOUD + "device/state", body.toString()))
                    .getJSONObject("payload").getJSONArray("capabilities");
            JSONObject out = new JSONObject();
            for (int i = 0; i < caps.length(); i++) {
                JSONObject c = caps.getJSONObject(i);
                Object v = c.getJSONObject("state").opt("value");
                if (v != null && !"".equals(v)) out.put(c.getString("instance"), v);
            }
            return out;
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee cloud state: " + e.getMessage());
            return null;
        }
    }

    /** The value Govee wants for a scene by its name in the Govee app, e.g. "Aurora" (the list is cached). */
    private Object sceneValue(JSONObject dev, String name) {
        try {
            String cacheKey = "govee_scenes_" + dev.getString("sku");
            String json = prefs.getString(cacheKey, null);
            if (json == null) {
                JSONObject body = new JSONObject().put("requestId", "scenes").put("payload",
                        new JSONObject().put("sku", dev.getString("sku")).put("device", dev.getString("device")));
                json = new JSONObject(http("POST", CLOUD + "device/scenes", body.toString()))
                        .getJSONObject("payload").getJSONArray("capabilities").getJSONObject(0)
                        .getJSONObject("parameters").getJSONArray("options").toString();
                prefs.edit().putString(cacheKey, json).apply();
            }
            org.json.JSONArray options = new org.json.JSONArray(json);
            for (int i = 0; i < options.length(); i++)
                if (options.getJSONObject(i).optString("name").equalsIgnoreCase(name)) return options.getJSONObject(i).get("value");
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee scenes: " + e.getMessage());
        }
        return null;
    }

    private String http(String method, String url, String body) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Govee-API-Key", ctx.getString(R.string.govee_api_key).trim());
        c.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        return in == null ? "{}" : readAll(in);
    }

    // ---------------------------------------------------------------- Govee LAN control

    private DatagramSocket goveeSocket() throws java.io.IOException {
        if (gs == null || gs.isClosed()) {
            gs = new DatagramSocket(null);
            gs.setReuseAddress(true);
            gs.bind(new InetSocketAddress(GOVEE_REPLY));
        }
        return gs;
    }

    /** ip → model for every lamp that answers a scan (LAN Control on); the last list if none answer. */
    private Map<String, String> goveeLamps() {
        Map<String, String> lamps = new LinkedHashMap<>();
        try {
            DatagramSocket s = goveeSocket();
            byte[] scan = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}".getBytes(StandardCharsets.UTF_8);
            for (int round = 0; round < 2; round++) {
                s.send(new DatagramPacket(scan, scan.length, InetAddress.getByName("239.255.255.250"), GOVEE_SCAN));
                long end = System.currentTimeMillis() + 1200;
                s.setSoTimeout(300);
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
                        s.receive(p);
                        JSONObject m = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8))
                                .getJSONObject("msg");
                        if ("scan".equals(m.optString("cmd")))
                            lamps.put(p.getAddress().getHostAddress(), m.getJSONObject("data").optString("sku"));
                    } catch (java.net.SocketTimeoutException ignored) { }
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee scan: " + e.getMessage());
        }
        try {
            if (!lamps.isEmpty()) prefs.edit().putString("govee_lamps", new JSONObject(lamps).toString()).apply();
            else {
                JSONObject saved = new JSONObject(prefs.getString("govee_lamps", "{}"));
                for (java.util.Iterator<String> it = saved.keys(); it.hasNext(); ) {
                    String ip = it.next();
                    lamps.put(ip, saved.getString(ip));
                }
            }
        } catch (org.json.JSONException ignored) { } // an old comma-separated list: scan again next time
        return lamps;
    }

    private JSONObject goveeStatus(String ip) {
        try {
            DatagramSocket s = goveeSocket();
            goveeSend(ip, "devStatus", new JSONObject());
            s.setSoTimeout(1500);
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                s.receive(p);
                if (!p.getAddress().getHostAddress().equals(ip)) continue;
                JSONObject m = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)).getJSONObject("msg");
                if ("devStatus".equals(m.optString("cmd"))) return m.getJSONObject("data");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee status " + ip + ": " + e.getMessage());
        }
        return null;
    }

    private void goveeSend(String ip, String cmd, JSONObject data) {
        try {
            byte[] b = new JSONObject().put("msg", new JSONObject().put("cmd", cmd).put("data", data))
                    .toString().getBytes(StandardCharsets.UTF_8);
            goveeSocket().send(new DatagramPacket(b, b.length, InetAddress.getByName(ip), GOVEE_SEND));
        } catch (Exception e) {
            android.util.Log.e(TAG, "govee " + cmd + " " + ip + ": " + e.getMessage());
        }
    }

    /** #RRGGBB, or kNNNN for a white at NNNN Kelvin. */
    private void goveeColour(String ip, String c) {
        try {
            JSONObject d;
            if (c.startsWith("k")) {
                d = new JSONObject().put("color", new JSONObject().put("r", 0).put("g", 0).put("b", 0))
                        .put("colorTemInKelvin", Integer.parseInt(c.substring(1)));
            } else {
                int rgb = android.graphics.Color.parseColor(c);
                d = new JSONObject().put("color", new JSONObject().put("r", (rgb >> 16) & 255).put("g", (rgb >> 8) & 255)
                        .put("b", rgb & 255)).put("colorTemInKelvin", 0);
            }
            goveeSend(ip, "colorwc", d);
        } catch (org.json.JSONException ignored) { }
    }

    private void goveeBrightness(String ip, int pct) {
        goveeSend(ip, "brightness", json("value", Math.max(1, Math.min(100, pct))));
    }

    // ---------------------------------------------------------------- the Matter bulb, through the bridge

    /** The bridge's query for a colour: Matter hue and saturation run 0-254; whites go by mireds. */
    private static String bulbColour(String c) {
        if (c.startsWith("k")) return "&mireds=" + Math.round(1_000_000f / Integer.parseInt(c.substring(1)));
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(c), hsv);
        return "&hue=" + Math.round(hsv[0] / 360f * 254) + "&sat=" + Math.round(hsv[1] * 254);
    }

    private JSONObject bridgeJson(String path) {
        String base = ctx.getString(R.string.lights_bridge_url).trim();
        if (base.isEmpty()) return null;
        try {
            int q = base.indexOf('?');
            String url = (q < 0 ? base : base.substring(0, q)).replaceAll("/+$", "") + path
                    + (path.contains("?") ? "&" : "?") + (q < 0 ? "" : base.substring(q + 1));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return null;
            return new JSONObject(readAll(c.getInputStream()));
        } catch (Exception e) {
            android.util.Log.e(TAG, "lights bridge " + path + ": " + e.getMessage()); // the computer may be asleep
            return null;
        }
    }

    private void bridge(String path) { bridgeJson(path); }

    private static JSONObject json(String k, Object v) {
        try { return new JSONObject().put(k, v); } catch (org.json.JSONException e) { return new JSONObject(); }
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
