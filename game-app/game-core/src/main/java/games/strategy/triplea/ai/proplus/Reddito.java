package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Step R4, rule C "REDDITO E BONUS": the gain per round of taking a territory = PUs of the
 * territory + national objectives the nation obtains + national objectives it takes away from the
 * enemy, from the map's values written in the data file ({@link ConfigR4#puTerritori}, {@link
 * ConfigR4#obiettivi}). Used to break ties between targets of the same priority (C2).
 */
public final class Reddito {
  private Reddito() {}

  /** The three numbers of one alternative (C2). */
  public record Guadagno(String territorio, int pu, int bonusOttenuti, int bonusTolti) {
    public int totale() {
      return pu + bonusOttenuti + bonusTolti;
    }

    public JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("pu_territorio", pu);
      o.addProperty("bonus_ottenuti", bonusOttenuti);
      o.addProperty("bonus_tolti", bonusTolti);
      o.addProperty("guadagno_per_round", totale());
      return o;
    }
  }

  /** Step R5: the PUs the objective {@code id} gives now by ownership (0 = not active). */
  public static int valoreOra(final GameState data, final String id) {
    final Map<String, String> owners = new HashMap<>();
    for (final Territory x : data.getMap().getTerritories()) {
      if (!x.isWater()) {
        owners.put(x.getName(), x.getOwner().getName());
      }
    }
    final Map<String, String> alliances = new HashMap<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      alliances.put(p.getName(), ProPlusSettings.allianceOf(data, p).orElse(""));
    }
    for (final ConfigR4.Obiettivo o : ConfigR4.get().obiettivi()) {
      if (o.id().equals(id)) {
        return valore(o, owners, n -> alliances.getOrDefault(n, ""));
      }
    }
    return 0;
  }

  /** Gain of {@code nation} taking {@code t} on the real board. */
  public static Guadagno guadagno(
      final GameState data, final GamePlayer nation, final Territory t) {
    final Map<String, String> owners = new HashMap<>();
    for (final Territory x : data.getMap().getTerritories()) {
      if (!x.isWater()) {
        owners.put(x.getName(), x.getOwner().getName());
      }
    }
    final Map<String, String> alliances = new HashMap<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      alliances.put(p.getName(), ProPlusSettings.allianceOf(data, p).orElse(""));
    }
    final ConfigR4 cfg = ConfigR4.get();
    final int pu =
        cfg.puTerritori()
            .getOrDefault(
                t.getName(),
                TerritoryAttachment.get(t).map(TerritoryAttachment::getProduction).orElse(0));
    return guadagno(
        t.getName(), pu, nation.getName(), owners, n -> alliances.getOrDefault(n, ""), cfg);
  }

  /**
   * Pure rule: {@code owners} = territory → owner now; after the capture {@code territory} is
   * {@code nation}'s. Obtained = objectives of {@code nation} (after − before); taken away =
   * objectives of the nations of another (non empty) alliance (before − after).
   */
  static Guadagno guadagno(
      final String territory,
      final int pu,
      final String nation,
      final Map<String, String> owners,
      final Function<String, String> alliance,
      final ConfigR4 cfg) {
    final Map<String, String> after = new HashMap<>(owners);
    after.put(territory, nation);
    final String mine = alliance.apply(nation);
    int obtained = 0;
    int taken = 0;
    for (final ConfigR4.Obiettivo o : cfg.obiettivi()) {
      if (!o.territori().contains(territory)) {
        continue;
      }
      final int before = valore(o, owners, alliance);
      final int now = valore(o, after, alliance);
      if (o.nazione().equals(nation)) {
        obtained += now - before;
      } else {
        final String theirs = alliance.apply(o.nazione());
        if (!theirs.isEmpty() && !theirs.equals(mine)) {
          taken += before - now;
        }
      }
    }
    return new Guadagno(territory, pu, obtained, taken);
  }

  /** The PUs an objective gives with {@code owners} (0 for "altro": not a matter of ownership). */
  static int valore(
      final ConfigR4.Obiettivo o,
      final Map<String, String> owners,
      final Function<String, String> alliance) {
    if ("altro".equals(o.tipo())) {
      return 0;
    }
    int held = 0;
    for (final String t : o.territori()) {
      final String owner = owners.get(t);
      if (owner == null) {
        continue;
      }
      final boolean ok =
          o.diretto()
              ? owner.equals(o.nazione())
              : !alliance.apply(owner).isEmpty()
                  && alliance.apply(owner).equals(alliance.apply(o.nazione()));
      held += ok ? 1 : 0;
    }
    return "ciascuno".equals(o.tipo()) ? o.valore() * held : (held >= o.minimo() ? o.valore() : 0);
  }
}
