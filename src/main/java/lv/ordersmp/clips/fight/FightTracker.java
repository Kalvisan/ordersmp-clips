package lv.ordersmp.clips.fight;

import java.util.HashMap;
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
  public enum Kind { KILL, DEATH }

  /**
   * A finished fight to save.
   *
   * @param kind kill when the player killed anyone, otherwise death
   * @param opponent the last player killed, or the one who killed the player
   * @param kills how many players the player killed in it
   * @param seconds clip length
   * @param skipSeconds how long ago the clip ends
   */
  public record Moment(Kind kind, String opponent, int kills, int seconds, int skipSeconds) {}

  private record Opponent(String name, long lastHit) {}

  private static final long SECOND = 1_000_000_000L;
  private static final long LEAD_IN = 5 * SECOND;
  private static final long FOLLOW_UP = 4 * SECOND;
  private static final long FIGHT_ENDS = 10 * SECOND;
  private static final long KILL_CREDIT = 10 * SECOND;
  private static final long MERGE_KILLS = 15 * SECOND;
  private static final long DEATH_TAIL = 10 * SECOND;
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
  private boolean killsOn = true;
  private boolean deathsOn = true;

  /**
   * @param clock current time in nanoseconds
   * @param save receives each fight worth a clip, on the client thread
   */
  public FightTracker(LongSupplier clock, Consumer<Moment> save) {
    this.clock = clock;
    this.save = save;
  }

  /** Which fights become clips; both off stops automatic clips. */
  public void configure(boolean kills, boolean deaths) {
    this.killsOn = kills;
    this.deathsOn = deaths;
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
    if (!killsOn && !deathsOn) {
      return;
    }
    long now = clock.getAsLong();
    if (fightStart == 0 || (lastMoment == 0 && now - lastHit > FIGHT_ENDS)) {
      reset();
      fightStart = now;
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
  }

  /** The player died; {@code killer} is the last player who hit them, or null. */
  public void playerDied(String killer) {
    long now = clock.getAsLong();
    if (fightStart == 0 || now - lastHit > FIGHT_ENDS || (!deathsOn && kills == 0)) {
      return;
    }
    if (kind == null) {
      kind = Kind.DEATH;
      opponent = killer == null ? lastOpponent() : killer;
    }
    died = true;
    lastMoment = now;
  }

  /** Called every client tick: saves a fight once no further kill can join it. */
  public void tick() {
    if (lastMoment == 0) {
      if (fightStart != 0 && clock.getAsLong() - lastHit > FIGHT_ENDS) {
        reset();
      }
      return;
    }
    long now = clock.getAsLong();
    if (died ? now - lastMoment < FOLLOW_UP + DEATH_TAIL : now - lastMoment < MERGE_KILLS || now - lastHit < FIGHT_ENDS) {
      return;
    }
    long end = (died ? lastMoment : Math.max(lastMoment, lastHit)) + FOLLOW_UP;
    long start = fightStart - LEAD_IN;
    int seconds = (int) Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, (end - start + SECOND - 1) / SECOND));
    int skip = (int) Math.max(0, (now - end) / SECOND);
    Moment moment = new Moment(kind, opponent == null ? "?" : opponent, kills, seconds, skip);
    reset();
    save.accept(moment);
  }

  private String lastOpponent() {
    return opponents.values().stream().max((a, b) -> Long.compare(a.lastHit(), b.lastHit())).map(Opponent::name).orElse(null);
  }

  private void reset() {
    opponents.clear();
    fightStart = 0;
    lastHit = 0;
    lastMoment = 0;
    kind = null;
    opponent = null;
    kills = 0;
    died = false;
  }
}
