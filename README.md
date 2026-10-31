# ALGS View

Watch Apex Legends Global Series LAN events on a foldable Android phone: several Twitch streams
at once, freely arranged, with the team you care about followed from channel to channel.

**Unofficial.** Not made or endorsed by EA, Respawn, the ALGS or Twitch. No game art ships with the
app. It uses Twitch's private web API and the undocumented API behind algs.ea.com, either of which can change or break at any time.

## Install

Download the APK from the [latest release](https://github.com/mikeyyy0/algsview/releases/latest) and open it on your phone. Android 10 or newer. The app offers updates itself after that. Built and tested on a Pixel 10 Fold; other phones are untested.

## What it does

- As many feeds as the phone can decode: Main + strip, Grid, or your own saved layouts, with snap or free arranging.
- Follow a team: a feed tracks the team by the numbered channels' stream titles and moves by itself
  when the team changes channel.
- A volume icon on each feed moves the audio there. The map cam lives in a drawer under the main feed, or as
  a feed of its own: top rendition, no audio, and a pinch zoom it remembers.
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
