#!/usr/bin/env python3
"""Phone remote + Claude assistant for the TV. Run via tools/remote.sh.

Serves a phone web page, talks to the TV over the Android TV Remote protocol (pairing code on the TV,
no ADB), and runs a Claude chat that can press keys / open apps. Replies are also pushed to the TV launcher.
"""
import asyncio
import json
import os
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

import anthropic
from aiohttp import web
from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
STATE = HERE / "state"
STATE.mkdir(exist_ok=True)
CERT, KEY = STATE / "cert.pem", STATE / "key.pem"
PIN_FILE = STATE / "pin.txt"
PORT = int(os.environ.get("REMOTE_PORT", "8080"))
MODEL = os.environ.get("CLAUDE_MODEL", "claude-sonnet-5-5")

TV_HOST = os.environ.get("TV_HOST") or (ROOT / "tools" / ".tv_host").read_text().strip()

APPS = {
    "youtube": "https://www.youtube.com",
    "netflix": "https://www.netflix.com/browse",
    "hotstar": "https://www.hotstar.com",
    "jiohotstar": "https://www.hotstar.com",
    "prime video": "https://app.primevideo.com",
    "prime": "https://app.primevideo.com",
    "apple tv": "https://tv.apple.com",
    "settings": "com.android.tv.settings",
}
KEYS = ["DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "BACK", "HOME", "MENU",
        "VOLUME_UP", "VOLUME_DOWN", "MUTE", "POWER", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS"]


def api_key() -> str:
    xml = (ROOT / "launcher" / "res" / "values" / "secrets.xml").read_text()
    m = re.search(r"sk-ant-[A-Za-z0-9_-]+", xml)
    if not m:
        sys.exit("No Claude API key found in launcher/res/values/secrets.xml")
    return m.group(0)


def load_pin() -> str:
    if not PIN_FILE.exists():
        PIN_FILE.write_text(f"{secrets.randbelow(10000):04d}")
    return PIN_FILE.read_text().strip()


PIN = load_pin()
claude = anthropic.AsyncAnthropic(api_key=api_key())
remote: AndroidTVRemote = None  # created at startup, inside the running event loop
status = {"state": "starting"}  # connected | needs_pairing | tv_unreachable | starting
feed = {"id": 0, "q": "", "text": "", "ts": 0}
history: list[dict] = []  # plain text turns only


async def ensure_connected() -> str:
    if status["state"] == "connected" and remote.is_on is not None:
        return status["state"]
    try:
        await remote.async_connect()
        remote.keep_reconnecting()
        status["state"] = "connected"
    except InvalidAuth:
        status["state"] = "needs_pairing"
    except (CannotConnect, ConnectionClosed, OSError):
        status["state"] = "tv_unreachable"
    return status["state"]


def send_key(key: str) -> str:
    if key not in KEYS:
        return f"unknown key {key}"
    remote.send_key_command(key)
    return "ok"


def open_app(app: str) -> str:
    pkg = APPS.get(app.strip().lower(), app.strip())
    remote.send_launch_app_command(pkg)
    return f"opened {pkg}"


# ------------------------------------------------------------------ Claude

TOOLS = [
    {"type": "web_search_20260209", "name": "web_search", "max_uses": 4,
     "user_location": {"type": "approximate", "country": "IN"}},
    {"name": "press_key", "description": "Press a button on the TV remote.",
     "input_schema": {"type": "object", "properties": {"key": {"type": "string", "enum": KEYS},
                                                       "times": {"type": "integer", "minimum": 1, "maximum": 10}},
                      "required": ["key"], "additionalProperties": False}},
    {"name": "open_app", "description": "Open an app on the TV by name (YouTube, Netflix, JioHotstar, Prime Video, "
     "Apple TV, Settings) or by Android package id.",
     "input_schema": {"type": "object", "properties": {"app": {"type": "string"}},
                      "required": ["app"], "additionalProperties": False}},
    {"name": "type_text", "description": "Type text into the TV's on-screen keyboard / search box (must already be focused).",
     "input_schema": {"type": "object", "properties": {"text": {"type": "string"}},
                      "required": ["text"], "additionalProperties": False}},
]

SYSTEM = (
    "You are the assistant on the user's living-room Android TV in India (a Vu TV). The user talks to you from "
    "their phone; your reply is shown on the TV screen and on the phone, so keep it short, plain and easy to read "
    "from a sofa (under about 60 words, no markdown). You can control the TV with tools: press_key, open_app, "
    "type_text. Use them when asked to do something on the TV, then confirm in one line. Use web_search for "
    "current information such as new Malayalam or Hindi OTT releases. If you are not sure what the user wants "
    "done on the TV, ask instead of guessing."
)


def run_tool(name: str, inp: dict) -> str:
    try:
        if status["state"] != "connected":
            return "TV is not connected"
        if name == "press_key":
            for _ in range(int(inp.get("times", 1))):
                r = send_key(inp["key"])
            return r
        if name == "open_app":
            return open_app(inp["app"])
        if name == "type_text":
            remote.send_text(inp["text"])
            return "typed"
    except Exception as e:  # noqa: BLE001 - report any TV error back to the model
        return f"error: {e}"
    return "unknown tool"


async def ask_claude(user_text: str) -> str:
    messages = history[-12:] + [{"role": "user", "content": user_text}]
    for _ in range(8):
        resp = await claude.messages.create(
            model=MODEL, max_tokens=2000, system=SYSTEM, tools=TOOLS, messages=messages,
            output_config={"effort": "low"},
        )
        if resp.stop_reason == "pause_turn":
            messages.append({"role": "assistant", "content": resp.content})
            continue
        calls = [b for b in resp.content if b.type == "tool_use"]
        if resp.stop_reason == "tool_use" and calls:
            messages.append({"role": "assistant", "content": resp.content})
            messages.append({"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": c.id, "content": run_tool(c.name, c.input)} for c in calls]})
            continue
        text = "".join(b.text for b in resp.content if b.type == "text").strip()
        return text or "Done."
    return "Sorry, that took too many steps."


# ------------------------------------------------------------------ web

@web.middleware
async def auth(request: web.Request, handler):
    if request.path.startswith("/api/") and request.path != "/api/feed":
        if request.headers.get("X-Pin", "") != PIN:
            return web.json_response({"error": "bad pin"}, status=401)
    return await handler(request)


async def index(_):
    return web.FileResponse(HERE / "index.html")


async def api_status(_):
    await ensure_connected()
    info = remote.device_info if isinstance(remote.device_info, dict) else {}
    return web.json_response({"state": status["state"], "app": remote.current_app, "model": info.get("model")})


async def api_pair_start(_):
    try:
        await remote.async_generate_cert_if_missing()
        await remote.async_start_pairing()
        return web.json_response({"ok": True})
    except (CannotConnect, ConnectionClosed, OSError) as e:
        return web.json_response({"error": f"can't reach TV: {e}"}, status=502)


async def api_pair_finish(request):
    code = (await request.json()).get("code", "").strip()
    try:
        await remote.async_finish_pairing(code)
    except (InvalidAuth, ConnectionClosed) as e:
        return web.json_response({"error": f"pairing failed: {e}"}, status=400)
    status["state"] = "starting"
    return web.json_response({"state": await ensure_connected()})


async def api_key(request):
    key = (await request.json()).get("key", "")
    if await ensure_connected() != "connected":
        return web.json_response({"error": status["state"]}, status=409)
    return web.json_response({"result": send_key(key)})


async def api_app(request):
    app = (await request.json()).get("app", "")
    if await ensure_connected() != "connected":
        return web.json_response({"error": status["state"]}, status=409)
    return web.json_response({"result": open_app(app)})


async def api_text(request):
    text = (await request.json()).get("text", "")
    if await ensure_connected() != "connected":
        return web.json_response({"error": status["state"]}, status=409)
    remote.send_text(text)
    return web.json_response({"result": "typed"})


async def api_chat(request):
    body = await request.json()
    msg = body.get("message", "").strip()
    if not msg:
        return web.json_response({"error": "empty"}, status=400)
    await ensure_connected()
    try:
        reply = await ask_claude(msg)
    except anthropic.APIError as e:
        return web.json_response({"error": f"Claude error: {getattr(e, 'message', e)}"}, status=502)
    history.extend([{"role": "user", "content": msg}, {"role": "assistant", "content": reply}])
    del history[:-24]
    if body.get("show_on_tv", True):
        feed.update(id=feed["id"] + 1, q=msg, text=reply, ts=time.time())
    return web.json_response({"reply": reply})


async def api_feed(request):
    return web.json_response(feed, headers={"Access-Control-Allow-Origin": "*"})


async def api_clear(_):
    feed.update(id=feed["id"] + 1, q="", text="", ts=time.time())
    return web.json_response({"ok": True})


async def on_start(app):
    global remote
    remote = AndroidTVRemote("Mac remote", str(CERT), str(KEY), TV_HOST)
    await remote.async_generate_cert_if_missing()
    asyncio.create_task(ensure_connected())


def lan_ip() -> str:
    for iface in ("en0", "en1"):
        r = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True)
        if r.stdout.strip():
            return r.stdout.strip()
    return "0.0.0.0"


def main():
    app = web.Application(middlewares=[auth])
    app.on_startup.append(on_start)
    app.add_routes([
        web.get("/", index), web.get("/api/status", api_status), web.get("/api/feed", api_feed),
        web.post("/api/pair/start", api_pair_start), web.post("/api/pair/finish", api_pair_finish),
        web.post("/api/key", api_key), web.post("/api/app", api_app), web.post("/api/text", api_text),
        web.post("/api/chat", api_chat), web.post("/api/clear", api_clear),
    ])
    ip = lan_ip()
    print(f"Phone remote: http://{ip}:{PORT}   PIN: {PIN}   (TV {TV_HOST})", flush=True)
    web.run_app(app, host=ip, port=PORT, print=None)


if __name__ == "__main__":
    main()
