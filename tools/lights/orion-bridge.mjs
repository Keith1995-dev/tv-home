// Matter bulb bridge for the TV's room moods: keeps a matter.js controller shell connected to the bulb and
// turns small HTTP calls from the TV into Matter commands. Runs on a computer on the same Wi-Fi.
//
//   node tools/lights/orion-bridge.mjs            (see tools/lights/README.md for setup)
//
// Every request needs ?key=<BRIDGE_KEY>. Calls:
//   GET /snapshot   remember the bulb's current state (on/off, level, colour) and return it as JSON
//   GET /set?on=1&level=0-254&hue=0-254&sat=0-254&mireds=153-500&t=tenths-of-a-second
//   GET /restore    put the bulb back as /snapshot found it
import { spawn } from 'node:child_process';
import http from 'node:http';
import { createRequire } from 'node:module';
import path from 'node:path';

const KEY = process.env.BRIDGE_KEY || '';
const PORT = Number(process.env.BRIDGE_PORT || 8766);
const NODE = process.env.MATTER_NODE || '5000';
const EP = '1';
const SHELL_DIR = process.env.MATTER_DIR || path.join(process.env.HOME, 'tv-home-tools/matter');
if (!KEY) { console.error('Set BRIDGE_KEY (the same key as in the TV\'s lights_bridge_url).'); process.exit(1); }

const shellJs = createRequire(path.join(SHELL_DIR, 'package.json'))
  .resolve('@matter/nodejs-shell/package.json').replace(/package\.json$/, 'dist/esm/app.js');
const shell = spawn(process.execPath, [shellJs, '0', `--netInterface=${process.env.NET_IF || 'en0'}`],
  { cwd: SHELL_DIR, stdio: ['pipe', 'pipe', 'pipe'] });
shell.on('exit', code => { console.error('matter shell exited', code); process.exit(1); });

// Lines from the shell, so a command can wait for the line that answers it.
let waiters = [];
function onLine(line) {
  for (const w of waiters) if (w.re.test(line)) { w.done(line); waiters = waiters.filter(x => x !== w); break; }
}
for (const s of [shell.stdout, shell.stderr]) {
  let buf = '';
  s.on('data', d => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\n')) >= 0) { onLine(buf.slice(0, i).replace(/\x1b\[[0-9;]*[A-Za-z]/g, '')); buf = buf.slice(i + 1); }
  });
}

// One command at a time; each waits for its answer line (or gives up after a few seconds).
let queue = Promise.resolve();
function run(cmd, re, ms = 6000) {
  const p = queue.then(() => new Promise(resolve => {
    const w = { re, done: resolve };
    waiters.push(w);
    setTimeout(() => { waiters = waiters.filter(x => x !== w); resolve(null); }, ms);
    shell.stdin.write(cmd + '\n');
  }));
  queue = p.catch(() => {});
  return p;
}
const invoke = (cluster, command, args) =>
  run(`commands ${cluster} ${command} ${args ? `'${JSON.stringify(args)}' ` : ''}${NODE} ${EP}`, /status: |Error|error/i);
async function read(cluster, attr) {
  const line = await run(`attributes ${cluster} read ${attr} ${NODE} ${EP}`, new RegExp(`Attribute value for ${attr} `, 'i'));
  if (!line) return null;
  const v = line.slice(line.lastIndexOf(': ') + 2).trim();
  return v === 'true' ? true : v === 'false' ? false : Number(v);
}

let connected = false;
async function connect() {
  // The shell is quiet once it has a session, so only an explicit error counts as a failure.
  const line = await run(`nodes connect ${NODE}`, /ready|Error/i, 30000);
  connected = !(line && /Error/i.test(line));
  console.log(connected ? `connecting to node ${NODE}` : `couldn't connect to node ${NODE}: ${line}`);
}
setTimeout(connect, 4000);
setInterval(() => { if (!connected) connect(); }, 60000);

const opts = { optionsMask: 0, optionsOverride: 0 };
let saved = null;

async function snapshot() {
  const on = await read('onoff', 'onoff');
  if (on === null) throw new Error('bulb not answering');
  saved = {
    on, level: await read('levelcontrol', 'currentlevel'), mode: await read('colorcontrol', 'colormode'),
    hue: await read('colorcontrol', 'currenthue'), sat: await read('colorcontrol', 'currentsaturation'),
    mireds: await read('colorcontrol', 'colortemperaturemireds'),
  };
  return saved;
}

async function set(q) {
  const t = Number(q.t ?? 10);
  if (q.on === '0') return invoke('onoff', 'off');
  if (q.on === '1') await invoke('onoff', 'on');
  if (q.level != null) await invoke('levelcontrol', 'movetolevel', { level: Number(q.level), transitionTime: t, ...opts });
  if (q.mireds != null) await invoke('colorcontrol', 'movetocolortemperature', { colorTemperatureMireds: Number(q.mireds), transitionTime: t, ...opts });
  else if (q.hue != null) await invoke('colorcontrol', 'movetohueandsaturation', { hue: Number(q.hue), saturation: Number(q.sat ?? 254), transitionTime: t, ...opts });
}

async function restore() {
  if (!saved) return;
  const s = saved;
  saved = null;
  if (!s.on) return invoke('onoff', 'off');
  await invoke('onoff', 'on');
  if (s.mode === 2 && s.mireds) await invoke('colorcontrol', 'movetocolortemperature', { colorTemperatureMireds: s.mireds, transitionTime: 15, ...opts });
  else if (s.hue != null) await invoke('colorcontrol', 'movetohueandsaturation', { hue: s.hue, saturation: s.sat ?? 254, transitionTime: 15, ...opts });
  if (s.level != null) await invoke('levelcontrol', 'movetolevel', { level: s.level, transitionTime: 15, ...opts });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const q = Object.fromEntries(url.searchParams);
  if (q.key !== KEY) { res.writeHead(403).end('{"error":"forbidden"}'); return; }
  delete q.key;
  console.log(new Date().toISOString().slice(11, 19), url.pathname, new URLSearchParams(q).toString());
  try {
    let out = { ok: true };
    if (url.pathname === '/snapshot') out = await snapshot();
    else if (url.pathname === '/set') await set(q);
    else if (url.pathname === '/restore') await restore();
    else { res.writeHead(404).end('{"error":"unknown"}'); return; }
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(out));
  } catch (e) {
    connected = false;
    res.writeHead(503, { 'content-type': 'application/json' }).end(JSON.stringify({ error: String(e.message || e) }));
  }
}).listen(PORT, '0.0.0.0', () => console.log(`bridge on :${PORT}`));
