# Room moods

The **◐ Moods** button in the home screen's top bar opens **Room Ambience**: Action, Romance, Chill, Cozy, Party,
Horror and Focus, plus **Lights off**. The running mood glows with a check; the remote's focus is a separate white
ring, and only OK changes the lights. A mood stays until you pick another or turn the TV off (then every light goes
back as it was). **Lights off** switches every light off and stays off; pick it again (it then says **Lights on**)
to bring them back as they were. **Brightness** (Left / Right) scales the running mood on every light.

| Mood | Uplighter (upper + middle, lower) | Other Govee lamps | Bulb |
|---|---|---|---|
| Action | red / blue alternating segments | blue | blue |
| Romance | rose / deep magenta halves | rose | candle-warm 2200K, low |
| Chill | Govee's Aurora scene | deep blue | dim cyan |
| Cozy | Candlelight scene, warm white downlight | amber | warm 2200K |
| Party | Dance Party scene | cyan | violet |
| Horror | crimson / near-black pattern, dim | dark red | faint red |
| Focus | light green / warm yellow, 5000K downlight | warm yellow | soft green-tinted white, bright |

Moods are defined in `RoomLights.MOODS` (launcher/src/com/mihir/tvhome/RoomLights.java).
Apple Home / Google Home / Alexa keep working as before.

## Govee lamps

- **LAN Control** (fast, no account): in the **Govee Home** app open each lamp → gear icon → turn on **LAN Control**.
  The TV finds them on the Wi-Fi and sets one colour and brightness per lamp.
- **Govee API key** (optional, for more): Govee Home → **Profile** → gear → **Apply for API Key**; it arrives by
  email. Put it in `launcher/res/values/secrets.xml` as `govee_api_key`. This unlocks segment colours and Govee's
  scenes through Govee's cloud. The Uplighter Floor Lamp (H60B0) has three sections: the upper "ripple" light
  that throws patterns on the ceiling, the middle "side" glow (8 colour segments) and the lower white-only
  downlight. Moods drive the first two through the cloud and switch the downlight off or to a white; with the
  downlight on, the lamp turns its ripple off by itself. Its LAN colour command drives the colour sections, so
  the uplighter is never restored over the LAN. Govee allows about 10 commands a minute per lamp: a mood sends
  only what changes, and the uplighter retries every 20 seconds if Govee turns it away. A lamp that drops off the
  Wi-Fi while off is woken through the cloud when the lights come back on.

## Matter bulbs

Matter bulbs only take commands from a Matter controller. A computer pairs the bulb once with matter.js (an extra
controller next to Apple Home or Google Home); then the TV takes over that pairing and drives the bulb itself
(`MatterBulb.java`), so nothing else needs to stay on. Steps 1–2 pair it, step 3 hands it to the TV.

1. Install the controller (needs Node.js):

   ```bash
   mkdir -p ~/tv-home-tools/matter && cd ~/tv-home-tools/matter && npm init -y && npm install @matter/nodejs-shell
   ```

2. In the Home app, open the bulb → **Turn On Pairing Mode** and note the 11-digit code. Then pair it as node 5000:

   ```bash
   cd ~/tv-home-tools/matter && node node_modules/@matter/nodejs-shell/dist/esm/app.js 0 --netInterface=en0
   ```

   and at the `matter>` prompt: `commission pair --pairing-code <the code> 5000`, then `exit`.

3. Copy the pairing into the launcher's secrets.xml (`matter_bulb`) and rebuild. Give the bulb's addresses: its
   IPv6 link-local address (stable; the matter.js shell logs it as `udp://[fe80::…%en0]:5540` when it connects)
   and its IPv4 address as a fallback:

   ```bash
   python3 tools/lights/matter-creds.py 5000 fe80::xxxx:xxxx:xxxx:xxxx 192.168.1.50
   ```

   That's it: the TV opens its own encrypted session with the bulb when a mood starts. The matter.js storage in
   `~/.matter/shell-0` holds the pairing's keys; keep it (or secrets.xml) to rebuild later.

**Or keep a computer in between** (the older way, instead of step 3): make a key and start the bridge, and keep it
running:

   ```bash
   openssl rand -hex 12 > ~/tv-home-tools/lights-bridge.key
   BRIDGE_KEY=$(cat ~/tv-home-tools/lights-bridge.key) node tools/lights/orion-bridge.mjs
   ```

   Then put `http://<computer's IP>:8766?key=<the key>` in `launcher/res/values/secrets.xml` as `lights_bridge_url`,
   leave `matter_bulb` empty, and rebuild.
