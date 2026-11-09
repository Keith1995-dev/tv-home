package com.mihir.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watch party: a tiny web server on the TV, only while the party screen is open. Friends scan the QR code, pick
 * moods and languages on their phones, and their phones then show Claude's three picks. Wi-Fi only; nothing
 * leaves the house except the moods, which go to Claude with the rest of the request.
 */
final class WatchParty {
    static final int PORT = 8765;
    private static final int MAX_GUESTS = 12, MAX_BODY = 4096;

    interface Listener { void onGuestsChanged(); }

    private final Map<String, JSONObject> guests = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Listener listener;
    private volatile ServerSocket server;
    private volatile String status = "waiting"; // waiting, picking, done
    private volatile JSONArray picks = new JSONArray();

    WatchParty(Listener listener) { this.listener = listener; }

    /** Starts listening; returns the link for the QR code, or null if the TV has no Wi-Fi/LAN address. */
    String start() {
        String ip = lanIp();
        if (ip == null) return null;
        try {
            ServerSocket s = new ServerSocket(PORT);
            s.setReuseAddress(true);
            server = s;
        } catch (java.io.IOException e) {
            return null;
        }
        Thread t = new Thread(this::acceptLoop, "watch-party");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + PORT + "/";
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        try { if (s != null) s.close(); } catch (java.io.IOException ignored) { }
    }

    List<JSONObject> guests() {
        synchronized (guests) { return new ArrayList<>(guests.values()); }
    }

    void setPicking() { status = "picking"; }

    void setPicks(JSONArray items) { picks = items; status = "done"; }

    void setFailed() { status = "waiting"; }

    /**
     * The TV's address on the home network: Wi-Fi or Ethernet, never a VPN tunnel. A private (192.168/10/172.16)
     * address wins; some routers hand out others (e.g. 100.x), so any Wi-Fi/Ethernet IPv4 address is the fallback.
     */
    static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("p2p")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null && (name.startsWith("wlan") || name.startsWith("eth"))) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    // ---------------------------------------------------------------- server

    private void acceptLoop() {
        while (server != null) {
            try {
                Socket s = server.accept();
                s.setSoTimeout(5000);
                new Thread(() -> handle(s)).start();
            } catch (java.io.IOException e) {
                if (server == null) return;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s; InputStream in = sock.getInputStream(); OutputStream out = sock.getOutputStream()) {
            String head = readHead(in);
            if (head == null) return;
            String[] first = head.split("\r\n", 2)[0].split(" ");
            if (first.length < 2) return;
            String method = first[0], path = first[1];
            if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                reply(out, 200, "text/html; charset=utf-8", PAGE);
            } else if (method.equals("GET") && path.equals("/state")) {
                reply(out, 200, "application/json", state().toString());
            } else if (method.equals("POST") && path.equals("/join")) {
                int len = 0;
                for (String h : head.split("\r\n")) {
                    if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                }
                if (len <= 0 || len > MAX_BODY) { reply(out, 400, "text/plain", "bad request"); return; }
                byte[] body = new byte[len];
                for (int got = 0, n; got < len && (n = in.read(body, got, len - got)) > 0; ) got += n;
                join(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                reply(out, 200, "application/json", state().toString());
            } else {
                reply(out, 404, "text/plain", "not found");
            }
        } catch (Exception ignored) { }
    }

    private void join(JSONObject j) throws org.json.JSONException {
        String id = j.optString("id").replaceAll("[^A-Za-z0-9]", "");
        if (id.isEmpty()) return;
        JSONObject g = new JSONObject()
                .put("name", clip(j.optString("name").trim(), 24))
                .put("moods", strings(j.optJSONArray("moods"), 6))
                .put("langs", strings(j.optJSONArray("langs"), 6))
                .put("avoid", clip(j.optString("avoid").trim(), 80));
        if (g.getString("name").isEmpty()) g.put("name", "Guest " + (guests.size() + 1));
        synchronized (guests) {
            if (!guests.containsKey(id) && guests.size() >= MAX_GUESTS) return;
            guests.put(id, g);
        }
        if (status.equals("done")) status = "waiting"; // someone changed their mind: ready to pick again
        listener.onGuestsChanged();
    }

    private JSONObject state() throws org.json.JSONException {
        JSONArray names = new JSONArray();
        for (JSONObject g : guests()) names.put(g.optString("name"));
        return new JSONObject().put("status", status).put("guests", names).put("picks", picks);
    }

    private static JSONArray strings(JSONArray a, int max) {
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length() && out.length() < max; i++) {
            String s = clip(a.optString(i).trim(), 24);
            if (!s.isEmpty()) out.put(s);
        }
        return out;
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private static String readHead(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 8192) return null;
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private static void reply(OutputStream out, int code, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    // ---------------------------------------------------------------- the page on guests' phones

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Watch party</title><style>"
            + "body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#0d0f14;color:#fff;padding:22px 18px 40px}"
            + "h1{font-weight:300;font-size:28px;margin:4px 0 2px}p{color:#9aa0ab;margin:0 0 18px;font-size:15px}"
            + "h2{font-size:12px;letter-spacing:.18em;color:#9aa0ab;font-weight:600;margin:22px 0 10px}"
            + "input{width:100%;box-sizing:border-box;font-size:17px;padding:13px 14px;border-radius:12px;border:1px solid #2a2f3a;background:#161a22;color:#fff}"
            + ".chips{display:flex;flex-wrap:wrap;gap:8px}"
            + ".chip{padding:10px 14px;border-radius:999px;background:#1b2029;border:1px solid #2a2f3a;font-size:15px;user-select:none}"
            + ".chip.on{background:#fff;color:#111;border-color:#fff}"
            + "button{margin-top:26px;width:100%;font-size:18px;font-weight:600;padding:15px;border:0;border-radius:14px;background:#fdb927;color:#111}"
            + ".card{background:#161a22;border-radius:16px;padding:14px 16px;margin-top:12px}.card b{font-size:17px}"
            + ".card small{display:block;color:#9aa0ab;margin-top:4px;font-size:14px}#done{display:none}"
            + "</style></head><body>"
            + "<div id=form><h1>🍿 Watch party</h1><p>Pick what you're in the mood for. The TV finds three films that suit everyone.</p>"
            + "<h2>YOUR NAME</h2><input id=name maxlength=24 placeholder='Name' autocomplete=given-name>"
            + "<h2>MOOD</h2><div class=chips id=moods></div>"
            + "<h2>LANGUAGE</h2><div class=chips id=langs></div>"
            + "<h2>NOT IN THE MOOD FOR</h2><input id=avoid maxlength=80 placeholder='e.g. horror, anything over 2 hours'>"
            + "<button id=go>I'm in</button></div>"
            + "<div id=done><h1>You're in 🎉</h1><p id=who></p><div id=picks></div>"
            + "<button id=edit style='background:#1b2029;color:#fff'>Change my picks</button></div>"
            + "<script>"
            + "const M=['😂 Funny','🥰 Feel-good','💥 Action','🕵️ Thriller','😱 Scary','🤯 Mind-bending','💘 Romance',"
            + "'😢 Emotional','🎭 Drama','🎨 Animated','🏆 A classic','🍿 Something light'],"
            + "L=['Malayalam','Hindi','English','Tamil','Telugu','Any'];"
            + "function chips(el,list){list.forEach(t=>{const c=document.createElement('span');c.className='chip';"
            + "c.textContent=t;c.onclick=()=>c.classList.toggle('on');document.getElementById(el).appendChild(c)})}"
            + "chips('moods',M);chips('langs',L);"
            + "let id;try{id=localStorage.getItem('wp')}catch(e){}"
            + "if(!id){id=Math.random().toString(36).slice(2,12);try{localStorage.setItem('wp',id)}catch(e){}}"
            + "const on=el=>[...document.querySelectorAll('#'+el+' .on')].map(c=>c.textContent);"
            + "document.getElementById('go').onclick=async()=>{"
            + "const b={id,name:document.getElementById('name').value,moods:on('moods'),langs:on('langs'),"
            + "avoid:document.getElementById('avoid').value};"
            + "try{await fetch('/join',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});"
            + "document.getElementById('form').style.display='none';document.getElementById('done').style.display='block';poll()}"
            + "catch(e){alert('Could not reach the TV. Are you on the same Wi-Fi?')}};"
            + "document.getElementById('edit').onclick=()=>{document.getElementById('form').style.display='block';"
            + "document.getElementById('done').style.display='none'};"
            + "let timer;async function poll(){clearTimeout(timer);try{const s=await (await fetch('/state')).json();"
            + "document.getElementById('who').textContent=s.status=='picking'?'Claude is picking films…':"
            + "s.status=='done'?'Tonight\\'s picks:':'Waiting for everyone: '+s.guests.join(', ');"
            + "const p=document.getElementById('picks');p.innerHTML='';"
            + "if(s.status=='done')s.picks.forEach(f=>{const d=document.createElement('div');d.className='card';"
            + "const t=document.createElement('b');t.textContent=f.title+(f.year?' ('+f.year+')':'');"
            + "const n=document.createElement('small');n.textContent=[f.platform,f.note].filter(Boolean).join(' · ');"
            + "d.append(t,n);p.append(d)})}catch(e){}timer=setTimeout(poll,3000)}"
            + "</script></body></html>";
}
