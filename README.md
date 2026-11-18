# OrderSMP Clips

A lightweight Fabric mod that keeps the last minutes of your Minecraft play and saves them as a
video with one key. Recording runs on your graphics card's built-in video encoder, so it costs
almost nothing in FPS. It works in singleplayer and on any server.

> Status: early development (0.1). The recorder and clip saving work; automatic clips on kills,
> voice chat tracks and the in-game clip editor are on the [roadmap](#roadmap).

## Features

- **Always ready.** The last 2 minutes (adjustable) are kept in memory. Press **F8** and they are
  saved as an MP4 in your `clips` folder.
- **Light on your PC.** Frames are scaled and copied on the GPU without stalling the game, and
  encoded by your graphics card (NVIDIA NVENC, AMD AMF, Intel Quick Sync or Apple VideoToolbox).
  Nothing is written to disk until you save.
- **Built for your mods.** Uses only Minecraft's own rendering API instead of raw OpenGL, so it
  is designed to keep working with Sodium, Iris and the Vulkan renderer (being tested, see below).
- **Clear status.** A small `REC` mark in the corner shows whether recording is on, paused or
  needs attention. An optional overlay shows exactly what recording costs.
- **Any server.** Nothing is sent anywhere. Clips stay on your PC.
- **Only while you play.** Nothing is recorded on the title screen or while loading.

## Requirements

| What | Version |
|---|---|
| Minecraft Java Edition | 26.3 |
| [Fabric Loader](https://fabricmc.net/use/installer/) | 0.20.1 or newer |
| [Fabric API](https://modrinth.com/mod/fabric-api) | for your Minecraft version |
| Java | 25 (bundled with the Minecraft launcher) |
| [FFmpeg](https://ffmpeg.org/download.html) | 6.0 or newer |

A graphics card from the last ten years is recommended. Without a hardware encoder Clips falls
back to a software encoder, which uses more CPU.

## Installation

1. Install Fabric Loader for Minecraft 26.3 with the official installer, or create a Fabric
   instance in your launcher (CurseForge, Modrinth App, Prism Launcher, MultiMC).
2. Download `ordersmp-clips-<version>.jar` from the
   [Releases](https://github.com/Kalvisan/ordersmp-clips/releases) page and put it in the `mods`
   folder of that instance, next to Fabric API.
3. Install FFmpeg:
   - **Windows:** `winget install Gyan.FFmpeg` in a terminal, or download a build from
     [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) and add its `bin` folder to `PATH`.
   - **macOS:** `brew install ffmpeg`.
   - **Linux:** `sudo apt install ffmpeg` (or your distribution's package).

   You can also place `ffmpeg` (or `ffmpeg.exe`) in `<instance>/ordersmp-clips/`, or set its path
   in the config file.
4. Start the game. The `REC` mark turns red once recording runs.

## Usage

| Key | Action |
|---|---|
| **F8** | Save the last minutes as a clip |
| **F9** | Pause or resume recording |
| **J** | Open the Clips screen |
| *(unbound)* | Show recording stats |

Keys can be changed in **Options > Controls > Key Binds > OrderSMP Clips**.

Clips are saved to `<instance>/clips/` and named by date and time, for example
`2026-11-18_21-14-03.mp4`.

### The Clips screen

Press **J**, or **Esc** and the camera icon next to the other icons in the game menu. The screen
shows whether recording is on and lists your clips, newest first, with a thumbnail, their length
and size. Select a clip and press **Watch and Edit** (or double-click it) to open the editor, open
the clips folder, or delete a clip. The dot in the top left corner pauses and resumes recording.

### The editor

The editor plays the clip in game and keeps editing to the basics:

- **Play controls:** start, play/pause, end. **Space** plays, **Left** and **Right** move one
  second.
- **Trim:** set where the clip starts and ends with the sliders or by dragging the handles on the
  timeline.
- **Cut Here** puts a cut at the playhead; **Remove Part** leaves out the part between two cuts
  (striped on the timeline) and **Keep Part** brings it back. **Reset All** undoes everything.
- **Done** keeps your edits next to the clip; the video file itself is never changed.
- **Save as New Clip** (Send tab) writes only the kept parts to a new MP4 next to the original.
  The video is copied, not re-encoded, so it takes a moment and keeps full quality. Because of
  that, a start point snaps back to the whole second before it.

Thumbnails, timeline strips and edits are kept in hidden `.thumbnails` and `.edits` folders inside
`<instance>/clips/`.

### The recording mark

| Mark | Meaning |
|---|---|
| Red dot, `REC` | Recording; the last minutes are ready to save |
| Yellow, `Saving` | A clip is being written |
| Gray outline, `Paused` | Paused with F9 |
| Yellow, `Clips off` | Something is missing; a message explains what |

## Configuration

Settings live in `<instance>/config/ordersmp-clips.json`, created on first start:

```json
{
  "bufferSeconds": 120,
  "fps": 60,
  "maxHeight": 1080,
  "bitrateMbps": 0,
  "encoder": "",
  "ffmpegPath": "",
  "showHud": true
}
```

| Setting | Meaning |
|---|---|
| `bufferSeconds` | How much play is kept (10 to 300 seconds) |
| `fps` | Recording frame rate (15 to 60) |
| `maxHeight` | Largest video height; bigger windows are scaled down (360 to 2160) |
| `bitrateMbps` | Video bitrate; `0` picks one from the height (6, 10 or 16 Mbps) |
| `encoder` | Force an encoder such as `h264_nvenc`; empty picks the best working one |
| `ffmpegPath` | Full path to FFmpeg; empty searches the mod folder and `PATH` |
| `showHud` | Show the recording mark |

Memory use is roughly bitrate times buffer length: 2 minutes at 10 Mbps is about 150 MB.

## Troubleshooting

- **"Clips needs FFmpeg"**: FFmpeg is not installed or not on `PATH`. Install it (see above) and
  restart the game.
- **"Clips cannot record"**: FFmpeg works, but none of its H.264 encoders started. Update your
  graphics driver, or set `"encoder": "libx264"` to use the software encoder.
- **Choppy clips on a slow PC**: lower `fps` to 30 or `maxHeight` to 720.
- Turn on **Show Recording Stats** to see the encoder in use, the video size, how much is held,
  and how much time recording takes on the render thread each frame.

## Known limitations

- The `REC` mark is part of the HUD, so it appears in your clips. Hide it with `"showHud": false`
  until clips are captured before the HUD overlays.
- Tested so far on Windows with an NVIDIA graphics card on the OpenGL renderer. AMD, Intel, macOS,
  Linux, the Vulkan renderer and shader packs are not tested yet; reports are welcome.
- Clips have no sound yet.

## Roadmap

- Automatic clips on kills and deaths, trimmed from the first hit
- Simple Voice Chat voices as separate tracks with a volume per player
- Sound in the editor: game sound and voice volumes per player
- Recording mode: film a whole session to disk instead of keeping the last minutes
- Optional upload to OrderSMP Studio
- Builds for each new Minecraft release

## Building from source

```bash
git clone https://github.com/Kalvisan/ordersmp-clips.git
cd ordersmp-clips
./gradlew build
```

The mod jar is written to `build/libs/`. `./gradlew runClient` starts a development client.
The build needs JDK 25 and treats every compiler warning as an error.

## Contributing

Issues and pull requests are welcome. Please keep changes small and focused, build with
`./gradlew build` before opening a pull request, and describe what you tested. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). OrderSMP Clips runs FFmpeg as a separate program and does not include it; FFmpeg
has its own license. Minecraft is a trademark of Mojang Studios; this project is not affiliated
with Mojang or Microsoft.
