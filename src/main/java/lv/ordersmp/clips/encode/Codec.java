package lv.ordersmp.clips.encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The H.264 encoders Clips knows, best first: the graphics card encoders (NVIDIA, AMD, Intel, Apple)
 * cost almost no game performance; the software encoder is the last resort. Every set of options
 * aims at the lowest delay and cost: no B-frames and one keyframe per second, so a clip can start
 * on any second.
 */
public enum Codec {
  NVENC("h264_nvenc", "NVIDIA NVENC", "rgba",
      "-preset", "p1", "-tune", "ll", "-rc", "vbr", "-zerolatency", "1", "-delay", "0"),
  AMF("h264_amf", "AMD AMF", "nv12",
      "-usage", "lowlatency", "-quality", "speed", "-rc", "vbr_peak"),
  QSV("h264_qsv", "Intel Quick Sync", "nv12",
      "-preset", "veryfast"),
  VIDEOTOOLBOX("h264_videotoolbox", "Apple VideoToolbox", "nv12",
      "-realtime", "1"),
  X264("libx264", "Software (x264)", "yuv420p",
      "-preset", "ultrafast", "-tune", "zerolatency");

  private final String ffmpegName;
  private final String label;
  private final String pixelFormat;
  private final String[] tuning;

  Codec(String ffmpegName, String label, String pixelFormat, String... tuning) {
    this.ffmpegName = ffmpegName;
    this.label = label;
    this.pixelFormat = pixelFormat;
    this.tuning = tuning;
  }

  /** The name players see in stats and the README. */
  public String label() {
    return label;
  }

  /** True for encoders that run on the graphics card. */
  public boolean hardware() {
    return this != X264;
  }

  /** Encoder options for ffmpeg at the given bitrate (bits per second) and frame rate. */
  public List<String> args(int bitrate, int fps) {
    List<String> args = new ArrayList<>(List.of("-c:v", ffmpegName, "-pix_fmt", pixelFormat));
    args.addAll(Arrays.asList(tuning));
    args.addAll(List.of(
        "-b:v", Integer.toString(bitrate),
        "-maxrate", Integer.toString(bitrate * 3 / 2),
        "-bufsize", Integer.toString(bitrate * 3 / 2),
        "-bf", "0",
        "-g", Integer.toString(fps),
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709"));
    return args;
  }

  /** All encoders in the order to try, with the player's preferred one first when it is known. */
  public static List<Codec> order(String preferred) {
    List<Codec> order = new ArrayList<>(List.of(values()));
    for (Codec codec : values()) {
      if (codec.ffmpegName.equalsIgnoreCase(preferred)) {
        order.remove(codec);
        order.addFirst(codec);
      }
    }
    return order;
  }
}
