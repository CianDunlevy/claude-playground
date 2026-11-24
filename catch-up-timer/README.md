# Catch-Up Timer

A small offline web app (PWA) that reminds you to call or see the people in your life.

- `index.html`: the whole app (HTML, CSS, JS in one file, no libraries, no network calls)
- `manifest.webmanifest`, `sw.js`, `icons/`, `screenshots/`: the extra files that make it installable and work offline
- `tools/build-artifact.py`: produces the single-file artifact version from `index.html`

## Hosting on GitHub Pages

Repo Settings → Pages → Source: "Deploy from a branch" → pick the branch and `/ (root)` → Save.
The app is then at **https://ciandunlevy.github.io/claude-playground/catch-up-timer/**.

On Android: open that link in Chrome and tap **Install** in the app's header (or ⋮ → Install app).
Long-press the home-screen icon for an **Add person** shortcut.

When you change files, bump `CACHE` in `sw.js`; installed copies pick up changes the next time they open online.
Build the single-file artifact version with `python3 tools/build-artifact.py out.html`.

## Data format (version 1)

Stored in `localStorage` under `catchup.v1`, and exported in the same shape:

```json
{
  "format": "catch-up-timer",
  "version": 1,
  "exportedAt": "2026-11-22T10:00:00.000Z",
  "people": [
    {
      "id": "uuid",
      "name": "Dave",
      "intervalDays": 30,
      "note": "Ask about his new job",
      "lastCaughtUp": 1763805600000,
      "history": [1763805600000, 1761210000000],
      "createdAt": 1761210000000
    }
  ]
}
```

- All times are Unix epoch milliseconds (UTC). Kotlin: `Instant.ofEpochMilli(x)`.
- `history` is newest first, max 30 entries, one per calendar day.
- Import also accepts ISO-8601 strings for any timestamp, a bare array of people, and `lastContact` in place of `lastCaughtUp` (backups from the other Catch-Up version).
- Days elapsed = difference between the local calendar dates of `lastCaughtUp` and today, so DST and time zones never shift a count.
- Status: overdue when days left < 0; due soon when days left ≤ 20% of the interval (min 1, max 7); otherwise fine.
