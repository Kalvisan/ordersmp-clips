# OrderSMP Clips

A lightweight Fabric mod that keeps the last minutes of your Minecraft play and saves them as a
video with one key. Recording runs on your graphics card's built-in video encoder, so it costs
almost nothing in FPS. It works in singleplayer and on any server.

> Status: early development (0.1). Recording, automatic kill clips, the editor and Studio upload
> work, with game sound; voice chat tracks are on the [roadmap](#roadmap).

## Features

- **Kills and deaths save themselves.** When you kill a player or die in a fight, the clip runs
  from 5 seconds before the first hit to 4 seconds after the last action. A fight against several
  players stays one clip for as long as the fighting goes on. Up to 10 extra seconds before and
  after are kept, and the editor opens trimmed to the fight, so you can widen it if it was cut too
  tightly. The list shows them as `Kill - Name` or `Death - Name`.

- **Always ready.** The last 2 minutes (adjustable) are kept in memory. Press **F8** and they are
  saved as an MP4 in your `clips` folder.
- **Light on your PC.** Frames are scaled and copied on the GPU without stalling the game, and
  encoded by your graphics card (NVIDIA NVENC, AMD AMF, Intel Quick Sync or Apple VideoToolbox).
  Nothing is written to disk until you save.
- **Built for your mods.** Uses only Minecraft's own rendering API instead of raw OpenGL, so it
  is designed to keep working with Sodium, Iris and the Vulkan renderer (being tested, see below).
- **Clear status.** A small `REC` mark in the corner shows whether recording is on, paused or
  needs attention. An optional overlay shows exactly what recording costs.
- **Any server.** Clips stay on your PC. Nothing leaves it unless you press **Upload to Studio**.
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
| **F6** | Start or stop Recording mode |
| **J** | Open the Clips screen |
| *(unbound)* | Show recording stats |

Keys can be changed in **Options > Controls > Key Binds > OrderSMP Clips**.

Clips are saved to `<instance>/clips/` and named by date and time, for example
`2026-11-18_21-14-03.mp4`.

### Two modes: Clipping and Recording

- **Clipping** (always on while you play) keeps the last minutes in memory. Nothing is written
  to disk until you press **F8**.
- **Recording** writes your whole session to disk. Press **F6**, or the camera button in the top
  left corner of the Clips screen, to start and stop it. It saves in parts of 10 minutes
  (`recordingPartMinutes`), so a crash loses at most the part being written; each part shows up
  in the clip list as soon as it is finished and can be edited and uploaded like any clip.
  Clipping and **F8** keep working while it runs. It stops by itself with a message when less
  than 2 GB is free. 10 minutes at 1080p take about 750 MB.

### The Clips screen

Press **J**, or **Esc** and the camera icon next to the other icons in the game menu. The screen
shows whether recording is on and lists your clips with a thumbnail, their type (sword, skull,
crossed swords, film strip), length, size and whether they are in Studio.

- **Search** by player, type or date, such as `Raven` or `18 Nov`.
- **Sort** by date, length, size or type; the arrow next to it flips the order.
- **Show** all clips, or only kills, deaths, fights, clips saved by hand, Recording parts, Kept
  clips or clips in Studio.
- **Watch and Edit** (or double-click) opens the editor; **Upload to Studio** sends the clip.
- **Keep** marks a clip so the storage limits never delete it. Without a mark, the list shows the
  days left when **Delete Unkept After** is set.
- **Import...** adds MP4 videos from your PC. You can also drop MP4 files on the window.

### Options

**Options** opens a quick page: quality, Keep Last (up to 10 minutes), Auto Clip, clipping on or
off, Recording mode, default volumes, Studio, Status and key binds. **Advanced...** has every
setting:

- **Recording:** frame rate, bitrate, recording parts and the recording mark (on, minimal or off).
- **Clip These Moments:** kills, deaths, long fights, seconds before the first hit and after the
  kill, and whether kills close together make one clip or one each.
- **Sound and Voice:** default volumes, and whether your voice and the players you hear are kept.
- **Storage:** a size limit and a number of days after which clips not marked Kept are deleted
  (both off by default), the clips folder, and Delete All.
- **Status...** shows whether recording works, the encoder and how much is ready to save.

Changes apply at once; a new quality, frame rate, bitrate or Keep Last starts the buffer again.
The recording icon above your head is shown but disabled, because it needs server support that
is not built yet.

### The editor

The editor plays the clip in game and keeps editing to the basics:

- **Play controls:** start, play/pause, end. **Space** plays, **Left** and **Right** move one
  second.
- **Trim:** set where the clip starts and ends with the sliders or by dragging the handles on the
  timeline.
- **Timeline markers:** an orange line with an icon marks the first hit, each kill and your death
  in automatic clips.
- **Cut Here** puts a cut at the playhead; **Remove Part** leaves out the part between two cuts
  (striped on the timeline) and **Keep Part** brings it back. **Reset All** undoes everything.
- **Audio tab:** one slider (0 to 200%) and a mute button per track: game sound, your voice and
  each player you heard. The preview has no sound yet; volumes apply when you save a new clip or
  upload, where the sound is mixed again from the tracks and the video is still copied.
- **Done** keeps your edits next to the clip; the video file itself is never changed.
- **Save as New Clip** (Upload tab) writes only the kept parts to a new MP4 next to the original.
  The video is copied, not re-encoded, so it takes a moment and keeps full quality. Because of
  that, a start point snaps back to the whole second before it.

### Upload to Studio

**Upload to Studio** (Upload tab in the editor) sends the clip, with your cuts, to your
[OrderSMP Studio](https://studio.ordersmp.net) library, where effects, captions and posting are
done.

- The first time, Clips links this PC to your Studio account: your browser opens Studio with a
  short code filled in, you press **Approve**, and the game continues by itself. You can also type
  the code on the Studio page from another device.
- The button shows live progress while the clip uploads, and becomes **Open in Studio** when it
  is done. A dropped connection resumes where it stopped; a failed upload says why and can be
  retried.
- Nothing is posted anywhere until you post it in Studio.
- **Unlink Studio** removes the link here and revokes it in Studio. You can also revoke it in
  Studio's settings. The link is kept in `<instance>/ordersmp-clips/studio.json`, outside
  `config/`, so it is not shared when you export a modpack.

Thumbnails, timeline strips and edits are kept in hidden `.thumbnails` and `.edits` folders inside
`<instance>/clips/`.

### The recording mark

The mark is drawn after each frame is captured, so you see it but your clips never show it.

| Mark | Meaning |
|---|---|
| Film strip, `CLIP` | Clipping; the last minutes are ready to save |
| Crossed swords, `Fight 0:12` | A fight is being followed for an automatic clip |
| Red skull, `Kill x1` | A kill happened; the clip saves when the fighting stops |
| Red dot, `REC 12:34` | Recording mode is writing everything to disk |
| Yellow, `Saving` | A clip is being written |
| Gray outline, `Paused` | Paused with F9 |
| Yellow, `Clips off` | Something is missing; a message explains what |

### Sound

Every clip gets the game's sound. Clips logs which sounds played, where and how loud, and mixes the
clip's sound when it is saved, from the same sound files, with distance and left and right like in
the game. Nothing extra runs while you play. The game's volume and a clip's volume are separate:
your sound sliders never change a clip, and clip volumes never change the game. The mixed
track is kept beside the clip in `.audio/` as a lossless file, and a mix is inside the MP4.

With [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) installed, your voice and
every player you hear get their own track too, named after the player. The defaults for game sound,
your voice and other players are in **Options**; each clip's editor changes them per track.

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
  "showHud": true,
  "studioUrl": "https://studio.ordersmp.net",
  "recordingPartMinutes": 10,
  "clipKills": true,
  "clipDeaths": true,
  "clipLongFights": false,
  "sendToStudio": false,
  "volumeGame": 100,
  "volumeMine": 100,
  "volumeOthers": 100,
  "markMinimal": false,
  "beforeSeconds": 5,
  "afterSeconds": 4,
  "closeKillsOneClip": true,
  "recordMyVoice": true,
  "recordOthers": true,
  "maxSizeGb": 0,
  "deleteAfterDays": 0
}
```

| Setting | Meaning |
|---|---|
| `bufferSeconds` | How much play is kept (10 to 600 seconds) |
| `fps` | Recording frame rate (15 to 60) |
| `maxHeight` | Largest video height; bigger windows are scaled down (360 to 2160) |
| `bitrateMbps` | Video bitrate; `0` picks one from the height (6, 10 or 16 Mbps) |
| `encoder` | Force an encoder such as `h264_nvenc`; empty picks the best working one |
| `ffmpegPath` | Full path to FFmpeg; empty searches the mod folder and `PATH` |
| `showHud` | Show the recording mark |
| `studioUrl` | The OrderSMP Studio that **Upload to Studio** sends clips to |
| `sendToStudio` | Upload new automatic clips to Studio by themselves (off by default) |
| `volumeGame`, `volumeMine`, `volumeOthers` | Default volume in percent of game sound, your voice and other players in new clips |
| `recordingPartMinutes` | Length of each file Recording mode writes (1 to 60 minutes) |
| `clipKills`, `clipDeaths`, `clipLongFights` | Which moments save themselves (also in **Options**) |
| `markMinimal` | Draw the recording mark as its icon only |
| `beforeSeconds`, `afterSeconds` | Seconds an automatic clip keeps before the first hit and after the kill or death (0 to 15) |
| `closeKillsOneClip` | Kills close together make one clip; `false` saves each kill on its own |
| `recordMyVoice`, `recordOthers` | Keep your Simple Voice Chat voice, and the voices you hear, in clips |
| `maxSizeGb` | Largest size of the clips folder; the oldest clips not marked Kept are deleted above it (`0` is no limit) |
| `deleteAfterDays` | Days after which clips not marked Kept are deleted (`0` keeps them) |

Memory use is roughly bitrate times buffer length: 2 minutes at 10 Mbps is about 150 MB, and 10 minutes
about 1.1 GB. When Java cannot reserve that much, Clips keeps a shorter buffer and says so in the log.

## Troubleshooting

- **"Clips needs FFmpeg"**: FFmpeg is not installed or not on `PATH`. Install it (see above) and
  restart the game.
- **"Clips cannot record"**: FFmpeg works, but none of its H.264 encoders started. Update your
  graphics driver, or set `"encoder": "libx264"` to use the software encoder.
- **Choppy clips on a slow PC**: lower `fps` to 30 or `maxHeight` to 720.
- Turn on **Show Recording Stats** to see the encoder in use, the video size, how much is held,
  and how much time recording takes on the render thread each frame.

## Known limitations

- Tested so far on Windows with an NVIDIA graphics card on the OpenGL renderer. AMD, Intel, macOS,
  Linux, the Vulkan renderer and shader packs are not tested yet; reports are welcome.
- Game sound is mixed from the sounds the game played, not recorded from your speakers, so it is
  close to what you heard but not identical: reverb and occlusion mods and sounds other mods play
  on their own are not in it. A sound the game does not play, because its slider or the master
  volume is at 0, is not in the clip either.

## Roadmap

- Exact fight starts and kills from the OrderSMP server, instead of what the client sees
- Simple Voice Chat voices as separate tracks with a volume per player
- Sound in the editor preview
- Voice distance and whispers as you heard them (voices are kept at full level for now)
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
