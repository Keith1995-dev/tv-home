# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with Apple aerial videos, your calendar and deliveries, a
continue-watching strip and Claude-picked things to watch. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

Built on [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home), which did the
debloat tooling and the first launcher.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. Plain Android, no Gradle, no background services |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, team, backgrounds, bedtime hours |
| [launcher/secrets.example.xml](launcher/secrets.example.xml) | Template for your keys and home city; the build copies it to `res/values/secrets.xml`, which git ignores |
| [tools/personal/](tools/personal/) | Google Apps Script that turns your Gmail and Calendar into the Personal view |
| [tools/gen-backgrounds.py](tools/gen-backgrounds.py) | Regenerates the aerial video and still image lists |
| [tools/remote.sh](tools/remote.sh), [tools/update-server.sh](tools/update-server.sh) | Phone remote, and a server for the launcher's Update button |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left; small icon buttons top-right for **Inputs**, **Wi-Fi** and
  **Settings**. Weather in °C with °F beside it, world clocks and your team's next game.
- **Entertainment / Personal / Work** tabs. Personal shows your calendar with flights, deliveries
  and bill due dates mixed in, plus Packages and Needs Attention cards
  ([setup](tools/personal/README.md)).
- A dock of your favourite apps with an **All Apps** drawer (recents first; add or remove dock apps
  from it). Hovering Stremio or YouTube opens a small continue-watching strip above the dock.
- **Discover**: Claude's picks and top 10s for your streaming apps. Mark titles as seen, refresh,
  or press **Ask** and say "new Malayalam movies this year"; each pick opens in Stremio.
- **Watch party**: friends scan a QR code on the TV, pick their mood and languages on their phones,
  and Claude picks three films for the whole group. The picks show in Discover and on their phones.
- **Over whatever you're watching**: a banner when a package is delivered (from the Personal feed),
  and during a live game a small score ticker in the corner. Game day also tints the home screen in
  your team's colours, and `game_app` in `config.xml` can switch to the game at tip-off.
- **Backgrounds**: Apple TV aerial videos (landscapes, cities, space, underwater), with paintings,
  Google Earth View and NASA images between clips. Older TVs get the bundled nature photos.
- Small touches: a fact line that changes every 5 minutes, a morning briefing, a live-game alert,
  and bedtime mode after 11 PM (dimmed screen, big clock, sleep timer).

Change the dock or features: edit `config.xml`, then run `launcher/build-and-install.sh`. Features
that need a key (Claude, Personal, Stremio) stay off until you add it to `res/values/secrets.xml`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Hisense Android TV (Android 12). Add yours to `examples/`.

## Credits

Started from [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) by Mihir Gupta,
extended by Keith Percy. Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
