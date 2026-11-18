package lv.ordersmp.clips.encode;

import java.util.Arrays;

/**
 * Cuts ffmpeg's H.264 byte stream into whole frames. ffmpeg is told to start every frame with an
 * access-unit delimiter (NAL type 9), so a frame ends where the next delimiter starts; a frame that
 * contains an IDR slice (NAL type 5) is a keyframe where a clip may begin.
 */
final class AccessUnitSplitter {

  private static final int NAL_IDR = 5;
  private static final int NAL_AUD = 9;

  private final PacketRing ring;
  private byte[] buf = new byte[1 << 20];
  private int len;
  private int scanFrom;
  private boolean keyframe;

  AccessUnitSplitter(PacketRing ring) {
    this.ring = ring;
  }

  /** Adds bytes read from ffmpeg and passes every completed frame to the ring. */
  void feed(byte[] chunk, int count) {
    ensureCapacity(len + count);
    System.arraycopy(chunk, 0, buf, len, count);
    len += count;
    int i = scanFrom;
    while (i + 3 < len) {
      if (buf[i] != 0 || buf[i + 1] != 0 || buf[i + 2] != 1) {
        i++;
        continue;
      }
      int type = buf[i + 3] & 0x1F;
      if (type == NAL_IDR) {
        keyframe = true;
      }
      if (type == NAL_AUD && i > 1) {
        int start = buf[i - 1] == 0 ? i - 1 : i;
        emit(start);
        i = 3;
        continue;
      }
      i += 3;
    }
    scanFrom = Math.max(0, len - 3);
  }

  /** Passes the last frame on when ffmpeg closes its output. */
  void finish() {
    if (len > 0) {
      emit(len);
    }
  }

  private void emit(int end) {
    ring.add(buf, end, keyframe);
    keyframe = false;
    System.arraycopy(buf, end, buf, 0, len - end);
    len -= end;
  }

  private void ensureCapacity(int needed) {
    if (needed <= buf.length) {
      return;
    }
    buf = Arrays.copyOf(buf, Math.max(needed, buf.length * 2));
  }
}
