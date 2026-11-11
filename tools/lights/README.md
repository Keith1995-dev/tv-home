# Room moods

The **◐ Moods** button in the home screen's top bar sets every light in the room to a mood: Action, Romance,
Chill, Cozy, Party, Horror or Focus. A frosty wash of the mood's colours sweeps across the screen as it changes.
The mood stays until you pick another, press **Lights off**, or turn the TV off; then every light goes back
exactly as it was. Moods are defined in `RoomLights.MOODS` (launcher/src/com/mihir/tvhome/RoomLights.java).

Apple Home / Google Home / Alexa keep working as before.

## Govee lamps

- **LAN Control** (fast, no account): in the **Govee Home** app open each lamp → gear icon → turn on **LAN Control**.
  The TV finds them on the Wi-Fi and sets one colour and brightness per lamp.
- **Govee API key** (optional, for more): Govee Home → **Profile** → gear → **Apply for API Key**; it arrives by
  email. Put it in `launcher/res/values/secrets.xml` as `govee_api_key`. This unlocks segment colours and Govee's
  scenes through Govee's cloud. The Uplighter Floor Lamp (H60B0) uses it for its upward light (side and ripple
  sections: Aurora for Chill, Candlelight for Cozy, red/blue segments for Action) while its downward lamp, which
  is white only, switches off or to a warm or cool white. Govee allows about 10 commands a minute per lamp; a
  mood uses five or six.

## Matter bulbs (through a computer)

Matter bulbs only take commands from a Matter controller, so a computer on the same Wi-Fi runs one
(matter.js) as an extra controller next to Apple Home or Google Home. The bulb only follows along while that
computer is awake.

1. Install the controller (needs Node.js):

   ```bash
   mkdir -p ~/tv-home-tools/matter && cd ~/tv-home-tools/matter && npm init -y && npm install @matter/nodejs-shell
   ```

2. In the Home app, open the bulb → **Turn On Pairing Mode** and note the 11-digit code. Then pair it as node 5000:

   ```bash
   cd ~/tv-home-tools/matter && node node_modules/@matter/nodejs-shell/dist/esm/app.js 0 --netInterface=en0
   ```

   and at the `matter>` prompt: `commission pair --pairing-code <the code> 5000`, then `exit`.

3. Make a key and start the bridge (keep it running):

   ```bash
   openssl rand -hex 12 > ~/tv-home-tools/lights-bridge.key
   BRIDGE_KEY=$(cat ~/tv-home-tools/lights-bridge.key) node tools/lights/orion-bridge.mjs
   ```

4. Put `http://<computer's IP>:8766?key=<the key>` in `launcher/res/values/secrets.xml` as `lights_bridge_url` and
   rebuild.
