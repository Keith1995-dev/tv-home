# Movie lights

While something plays in another app on the TV, the lamps fade to a colour Claude picks for the title (Dune:
desert orange) and the room bulb goes off. Pause brings everything up to a warm 30% for a snack run; stopping, or
turning the TV off, puts every light back exactly as it was. Lights that were off when the film started are left
alone, and the lamps never end up brighter than you had them. Turn it off with `movie_lights` in `config.xml`.

Nothing here needs a cloud account or a hub, and Apple Home / Google Home / Alexa keep working as before.

## Govee lamps (direct from the TV)

In the **Govee Home** app open each lamp → gear icon → turn on **LAN Control**. That's all: the TV finds them on
the Wi-Fi by itself. Not every Govee model has LAN Control; those that don't can't be used.

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

The launcher also needs notification access to see what's playing; `launcher/build-and-install.sh` grants it.
