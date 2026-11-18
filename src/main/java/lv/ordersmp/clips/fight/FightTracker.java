package lv.ordersmp.clips.fight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Finds fights from what the client sees, so automatic clips work on any server: a fight starts
 * with the first hit between the player and another player (bow shots count, since the shooter
 * is named as the cause), a player the player hit dies within a few seconds (a kill), or the
 * player dies during the fight (a death). Fights against several players at once stay one fight
 * for as long as hits keep coming; it is saved once the fighting stops, from the first hit minus a
 * lead-in to the last hit or kill plus a follow-up.
 */
public final class FightTracker {

  /** What a clip shows. */
  public enum Kind { KILL, DEATH, FIGHT }

  /**
   * A finished fight to save.
   *
   * @param kind kill when the player killed anyone, otherwise death
   * @param opponent the last player killed, or the one who killed the player
   * @param kills how many players the player killed in it
   * @param seconds clip length
   * @param skipSeconds how long ago the clip ends
   * @param events what happened, each timed in seconds before the clip's end
   */
  public record Moment(Kind kind, String opponent, int kills, int seconds, int skipSeconds, List<Mark> events) {}

  /**
   * One moment in a fight.
   *
   * @param type what happened
   * @param secondsBeforeEnd how long before the clip's end it happened
   * @param name the other player
   */
  public record Mark(ClipEvents.Type type, double secondsBeforeEnd, String name) {}

  private record Happened(ClipEvents.Type type, long at, String name) {}

  private record Opponent(String name, long lastHit) {}

  private static final long SECOND = 1_000_000_000L;
  private static final long FIGHT_ENDS = 10 * SECOND;
  private static final long KILL_CREDIT = 10 * SECOND;
  private static final long MERGE_KILLS = 15 * SECOND;
  private static final long DEATH_TAIL = 10 * SECOND;
  private static final long LONG_FIGHT = 20 * SECOND;
  private static final int MIN_SECONDS = 10;
  private static final int MAX_SECONDS = 300;

  private final LongSupplier clock;
  private final Consumer<Moment> save;
  private final Map<Integer, Opponent> opponents = new HashMap<>();
  private long fightStart;
  private long lastHit;
  private long lastMoment;
  private Kind kind;
  private String opponent;
  private int kills;
  private boolean died;
  private final List<Happened> happened = new ArrayList<>();
  private boolean killsOn = true;
  private boolean deathsOn = true;
  private boolean longFightsOn;
  private long leadIn = 5 * SECOND;
  private long followUp = 4 * SECOND;
  private boolean mergeKills = true;

  /**
   * @param clock current time in nanoseconds
   * @param save receives each fight worth a clip, on the client thread
   */
  public FightTracker(LongSupplier clock, Consumer<Moment> save) {
    this.clock = clock;
    this.save = save;
  }

  /**
   * Which fights become clips and how they are cut; all kinds off stops automatic clips.
   *
   * @param before seconds kept before the first hit
   * @param after seconds kept after the last kill or the death
   * @param oneClip kills close together make one clip; when false each kill is saved on its own
   */
  public void configure(boolean kills, boolean deaths, boolean longFights, int before, int after, boolean oneClip) {
    this.killsOn = kills;
    this.deathsOn = deaths;
    this.longFightsOn = longFights;
    this.leadIn = before * SECOND;
    this.followUp = after * SECOND;
    this.mergeKills = oneClip;
  }

  /** Seconds since the fight began, or -1 when no fight is being followed. */
  public int fightSeconds() {
    return fightStart == 0 ? -1 : (int) ((clock.getAsLong() - fightStart) / SECOND);
  }

  /** Kills in the current fight so far. */
  public int kills() {
    return kills;
  }

  /** The player hit another player, or the other player hit the player. */
  public void hit(int playerId, String name) {
    if (!killsOn && !deathsOn && !longFightsOn) {
      return;
    }
    long now = clock.getAsLong();
    if (fightStart == 0 || (lastMoment == 0 && now - lastHit > FIGHT_ENDS)) {
      reset();
      fightStart = now;
      happened.add(new Happened(ClipEvents.Type.FIRST_HIT, now, name));
    }
    lastHit = now;
    opponents.put(playerId, new Opponent(name, now));
  }

  /** A player died; it is a kill when the player hit them shortly before. */
  public void died(int playerId) {
    Opponent victim = opponents.remove(playerId);
    long now = clock.getAsLong();
    if (!killsOn || victim == null || now - victim.lastHit() > KILL_CREDIT) {
      return;
    }
    kills++;
    kind = Kind.KILL;
    opponent = victim.name();
    lastMoment = now;
    happened.add(new Happened(ClipEvents.Type.KILL, now, victim.name()));
  }

  /** The player died; {@code killer} is the last player who hit them, or null. */
  public void playerDied(String killer) {
    long now = clock.getAsLong();
    if (fightStart == 0 || now - lastHit > FIGHT_ENDS || (!deathsOn && kills == 0)) {
      return;
    }
    String by = killer == null ? lastOpponent() : killer;
    if (kind == null) {
      kind = Kind.DEATH;
      opponent = by;
    }
    died = true;
    lastMoment = now;
    happened.add(new Happened(ClipEvents.Type.DEATH, now, by));
  }

  /** Called every client tick: saves a fight once no further kill can join it. */
  public void tick() {
    if (lastMoment == 0) {
      long now = clock.getAsLong();
      if (fightStart == 0 || now - lastHit <= FIGHT_ENDS) {
        return;
      }
      if (longFightsOn && lastHit - fightStart >= LONG_FIGHT) {
        kind = Kind.FIGHT;
        opponent = lastOpponent();
        save(now, lastHit + followUp);
        return;
      }
      reset();
      return;
    }
    long now = clock.getAsLong();
    if (died ? now - lastMoment < followUp + DEATH_TAIL : waitsForMoreKills(now)) {
      return;
    }
    save(now, (died ? lastMoment : Math.max(lastMoment, lastHit)) + followUp);
  }

  /**
   * Whether a fight with a kill should wait before it is saved: for another kill to join it, or,
   * with separate clips, only for the seconds kept after the kill.
   */
  private boolean waitsForMoreKills(long now) {
    if (!mergeKills) {
      return now - lastMoment < followUp;
    }
    return now - lastMoment < MERGE_KILLS || now - lastHit < FIGHT_ENDS;
  }

  private void save(long now, long end) {
    long start = fightStart - leadIn;
    int seconds = (int) Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, (end - start + SECOND - 1) / SECOND));
    int skip = (int) Math.max(0, (now - end) / SECOND);
    List<Mark> marks = happened.stream()
        .map(event -> new Mark(event.type(), (end - event.at()) / (double) SECOND, event.name()))
        .toList();
    Moment moment = new Moment(kind, opponent == null ? "?" : opponent, kills, seconds, skip, marks);
    reset();
    save.accept(moment);
  }

  private String lastOpponent() {
    return opponents.values().stream().max((a, b) -> Long.compare(a.lastHit(), b.lastHit())).map(Opponent::name).orElse(null);
  }

  private void reset() {
    opponents.clear();
    happened.clear();
    fightStart = 0;
    lastHit = 0;
    lastMoment = 0;
    kind = null;
    opponent = null;
    kills = 0;
    died = false;
  }
}
