# ALGS View

Watch Apex Legends Global Series LAN events on a foldable Android phone: up to four Twitch streams
at once, freely arranged, with the team you care about followed from channel to channel.

**Unofficial.** Not made or endorsed by EA, Respawn, the ALGS or Twitch. No game art ships with the
app. It uses Twitch's private web API and the undocumented API behind algs.ea.com, either of which can change or break at any time.

## What it does

- Up to 4 feeds: Main + strip, Grid, or your own saved layouts, with snap or free arranging.
- Follow a team: a feed tracks the team by the numbered channels' stream titles and moves by itself
  when the team changes channel.
- Tap a feed and its audio comes on. The map cam never takes audio, always asks for the top
  rendition, and zooms with a pinch in the main spot.
- Ad breaks hold the last frame, the way Streamlink's `--twitch-disable-ads` does. Ads are not
  fetched or played, and subscriber status is not spoofed.
- Your own map screenshots as an overlay, with coloured pins. Apex has no public live position data.
- Scores: the current set's standings, live, from the API behind algs.ea.com, over the feeds.
- Cover-screen and laptop (half-folded) modes, pop-out, Continue watching with your layout.

No sign-in, no analytics. Crash reports are opt-in: you read and submit them yourself.

## Building

Needs JDK 17 and the Android SDK.

```
./gradlew assembleDebug
```

Release builds read signing details from `keystore.properties`, which is not in the repository.

Derived from Apexview (MIT). See LICENSE.
