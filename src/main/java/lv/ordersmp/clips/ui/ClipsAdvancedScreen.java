package lv.ordersmp.clips.ui;

import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.ClipsConfig;
import lv.ordersmp.clips.Recorder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Clips options, advanced page: every setting in one scrolling list in the Video Settings style,
 * grouped under headings. It shares its buttons with the quick page, so both always agree.
 */
public final class ClipsAdvancedScreen extends ClipsOptionsScreen {

  public ClipsAdvancedScreen(Screen parent, Recorder recorder) {
    super(parent, recorder, Component.translatable("ordersmp-clips.options.advanced.title"));
  }

  @Override
  protected void addOptions() {
    remember();
    ClipsConfig c = ClipsClient.settings();
    list.addHeader(Component.translatable("ordersmp-clips.options.recording"));
    list.addSmall(quality(c), frameRate(c));
    list.addSmall(keepLast(c), bitrate(c));
    list.addSmall(clipping(), recordingMode());
    list.addSmall(partLength(c), mark(c));
    list.addSmall(status(), keyBinds());
    list.addHeader(Component.translatable("ordersmp-clips.options.moments"));
    list.addSmall(kills(c), deaths(c));
    list.addBig(longFights(c));
    list.addSmall(beforeFirstHit(c), afterKill(c));
    list.addBig(closeKills(c));
    list.addHeader(Component.translatable("ordersmp-clips.options.sound"));
    addVolumes(c);
    list.addSmall(myVoice(c), playersIHear(c));
    list.addBig(recordingIcon());
    list.addHeader(Component.translatable("ordersmp-clips.options.storage"));
    list.addSmall(maxSize(c), deleteAfter(c));
    list.addSmall(openFolder(), deleteAll());
    list.addHeader(Component.translatable("ordersmp-clips.options.studio"));
    list.addSmall(studioLink(), sendToStudio(c));
  }
}
