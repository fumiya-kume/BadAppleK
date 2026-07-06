# BadAppleK [![Java CI with Gradle](https://github.com/fumiya-kume/BadAppleK/actions/workflows/gradle.yml/badge.svg)](https://github.com/fumiya-kume/BadAppleK/actions/workflows/gradle.yml)

`Bad Apple!!` ASCII Art Player for macOS Terminal, written in Kotlin.

[![IMAGE ALT TEXT HERE](https://user-images.githubusercontent.com/16269075/120477656-fc6f9e80-c3e6-11eb-84f6-79b4e2d1e101.gif)](https://youtu.be/Iv8jbo4KDFo)

Youtube: [https://www.youtube.com/watch?v=Iv8jbo4KDFo](https://www.youtube.com/watch?v=Iv8jbo4KDFo)

## Features

- **Instant playback** — starts about a second after launch. Frames are converted progressively while ffmpeg is still extracting the video, instead of preprocessing everything up front.
- **Fits your terminal** — the frame resolution is detected from the actual terminal size at startup.
- **Audio sync** — frames are scheduled on an absolute time base and dropped when rendering falls behind, so video never drifts from the music.
- **Flicker-free rendering** — only changed rows are rewritten each frame (diff rendering), the cursor is hidden during playback, and frames are wrapped in synchronized-output escapes to avoid tearing.

## Requirements

- macOS with a terminal that supports ANSI escape sequences (Terminal.app, iTerm2, etc.)
- [ffmpeg](https://ffmpeg.org/) on your `PATH` — `brew install ffmpeg`
- JDK 17 or later (Gradle is bundled via the wrapper)

## How to run

1. Clone the repository
2. Copy the `Bad Apple!!` video into the repository root and name it `movie.mp4`
3. Resize the terminal to the size you want — the video is rendered at that resolution
4. Run:

   ```sh
   ./gradlew run
   ```

## How it works

```
movie.mp4 ──ffmpeg──▶ gen/N.bmp ──converter thread──▶ ASCII frames ──player──▶ terminal
          └─ffmpeg──▶ audio.wav ─────────────────────────────────────audio──▶ speaker
```

1. **Extract** — ffmpeg runs in the background, scaling the video to the terminal size and writing each frame as a BMP at 30 fps. The audio track is extracted to a temporary WAV file.
2. **Convert** — a follower thread picks up each BMP as soon as it is fully written (ffmpeg writes sequentially, so frame *N* is complete once *N+1* exists) and maps pixel luminance onto an ASCII gradient. For every frame it pre-encodes two byte arrays: the full frame, and a diff that rewrites only the rows that changed since the previous frame via cursor addressing.
3. **Play** — once one second of frames is buffered, audio starts and each frame is written at its absolute target time with a single buffered write. Frames that are late or not yet converted are dropped to keep audio in sync; after a drop the next frame falls back to a full-frame redraw.

## Customization

Tweak the constants at the top of [`src/main/kotlin/main.kt`](src/main/kotlin/main.kt):

- `ASCII_CHARS` — the brightness gradient, from dark to bright
- `fps` — playback frame rate

## Troubleshooting

- **Nothing shows up** — check that `ffmpeg` is installed and `movie.mp4` exists in the repository root
- **No sound** — the video file probably has no audio track
- **Garbled output** — make sure the terminal window is not resized during playback

## Inspired by

- [https://github.com/mariiaan/CmdPlay](https://github.com/mariiaan/CmdPlay)
