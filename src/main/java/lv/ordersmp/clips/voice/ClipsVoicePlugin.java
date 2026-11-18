package lv.ordersmp.clips.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import java.util.UUID;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.audio.VoiceLog;

/**
 * Simple Voice Chat plugin: hands the player's microphone and every voice they hear to the
 * {@link VoiceLog}, so clips get one track per speaker. Voice chat loads this class itself through
 * its {@code voicechat} entrypoint; without voice chat installed it is never loaded, and Clips
 * works without voices.
 */
public final class ClipsVoicePlugin implements VoicechatPlugin {

  @Override
  public String getPluginId() {
    return ClipsClient.MOD_ID;
  }

  @Override
  public void registerEvents(EventRegistration registration) {
    registration.registerEvent(ClientSoundEvent.class, event -> {
      VoiceLog voices = ClipsClient.voiceLog();
      if (voices != null) {
        voices.spoke(event.getRawAudio());
      }
    });
    registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event -> heard(event.getId(), event.getRawAudio()));
    registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, event -> heard(event.getId(), event.getRawAudio()));
    registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, event -> heard(event.getId(), event.getRawAudio()));
  }

  private static void heard(UUID speaker, short[] samples) {
    VoiceLog voices = ClipsClient.voiceLog();
    if (voices == null || speaker == null) {
      return;
    }
    voices.heard(speaker, samples);
  }
}
