# Personal view feed (Google Apps Script)

The launcher's **Personal** view shows your flights, packages, calendar and things that need attention. This script
runs in your own Google account, reads Gmail and Calendar, and serves a small summary that only the TV reads.

What leaves Google: the text of new emails from the last 10 days (minus Promotions, Social and Forums), plus trip
emails from the last 120 days, is sent to the Claude API to find flights, packages and to-dos. Each email is sent
once. Calendar events are not sent to Claude.

## Setup (about 5 minutes)

1. Open <https://script.google.com> signed in to your personal Google account → **New project**. Name it "TV Personal".
2. Replace everything in `Code.gs` with the contents of [Code.gs](Code.gs) and save.
3. **Project Settings** (gear) → scroll to **Script properties** → **Add script property**: name `CLAUDE_API_KEY`,
   value your Claude API key → **Save**.
4. Back in the editor, pick **setup** in the function menu and press **Run**. Google asks you to authorize
   Gmail, Calendar and external requests: review and allow (you sign in yourself). The log ends with the token.
5. **Deploy → New deployment** → type **Web app** → Execute as **Me**, Who has access **Anyone** → **Deploy**.
   Copy the **Web app URL**.
6. The TV link is that URL plus `?token=` and the token from the log, e.g.
   `https://script.google.com/macros/s/AKfy…/exec?token=3f9c…`. Open it in a browser to check you see JSON.
7. Put the link in `launcher/res/values/secrets.xml` as `personal_feed_url` (or paste it to Claude), then rebuild.

"Anyone" means anyone **with the full link including the token**; without the token the script answers
`forbidden`. If the link leaks, delete the `FEED_TOKEN` script property and run `setup` again for a new one.

## YouTube on the home screen (optional)

Hovering over YouTube in the dock shows the newest videos from channels you subscribe to. YouTube doesn't let
any app read your watch history, so this is the closest thing. To turn it on: in the Apps Script editor click
**Services +** in the left sidebar, pick **YouTube Data API v3**, **Add**, then run `refresh` once and allow access.

## Stremio "continue watching" (optional, not part of this script)

Hovering over Stremio shows what you were watching, with progress. It reads your Stremio library with your auth key
(a sign-in token, not your password): sign in at <https://web.stremio.com>, open the browser's developer console
(⌥⌘J in Chrome) and run `JSON.parse(localStorage.profile).auth.key`. Put the result in
`launcher/res/values/secrets.xml` as `stremio_auth_key` (or paste it to Claude) and rebuild. Signing out of Stremio on
the web invalidates the key.

## Changing it later

- Paste the new `Code.gs` and save. The 30-minute timer always runs the saved code, so changes to what it reads
  need no new deployment. Only a change to `doGet` (what the TV receives) needs **Deploy → Manage deployments →
  Edit → Version: New version**.
- Run `resetFeed` to forget everything and re-read the mail right away (do this after changing the searches).
- The deployment must be **Execute as: Me** and **Who has access: Anyone**. "Anyone with a Google Account"
  makes Google answer the TV with a sign-in page.
- Cost: only new mail goes to Claude, so it's usually a few small calls a day (`claude-sonnet-5-5`).
