package org.triplea.proplus.autoplay;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.delegate.Matches;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Step P12: what the block {@code p12} of {@code riepilogo.json} needs from the board, for every
 * configuration (Hard included): at the end of each round the Italian land units by region, the
 * Italian transports alive and lost in the round, the Italian warships, Italy's income, Egypt and
 * Gibraltar of the Axis, the six North African territories of the Axis, the German land units in
 * the West and in Italy.
 */
final class MonitorP12 {
  static final String ITALIA = "Italians";
  static final String GERMANIA = "Germans";

  static final List<String> REG_ITALIA =
      List.of("Southern Italy", "Northern Italy", "Sicily", "Sardinia");
  static final List<String> REG_NORD_AFRICA =
      List.of("Egypt", "Alexandria", "Tobruk", "Libya", "Tunisia", "Algeria", "Morocco");
  static final List<String> NORD_AFRICA_SEI =
      List.of("Alexandria", "Tobruk", "Libya", "Tunisia", "Algeria", "Morocco");
  static final List<String> REG_BALCANI_MO =
      List.of(
          "Yugoslavia",
          "Albania",
          "Greece",
          "Bulgaria",
          "Romania",
          "Slovakia Hungary",
          "Crete",
          "Turkey",
          "Syria",
          "Iraq",
          "Trans-Jordan",
          "Persia",
          "Northwest Persia",
          "Eastern Persia",
          "Saudi Arabia");

  /** The southern Russian front of the step (Bessarabia, Ukraine and beyond). */
  static final List<String> REG_SUD_RUSSIA =
      List.of(
          "Bessarabia",
          "Western Ukraine",
          "Ukraine",
          "Rostov",
          "Caucasus",
          "Volgograd",
          "Tambov",
          "Bryansk",
          "Kazakhstan",
          "Turkmenistan",
          "Samara",
          "Russia");

  static final List<String> REG_GIBILTERRA = List.of("Gibraltar");

  static final Map<String, List<String>> REGIONI =
      Map.of(
          "italia", REG_ITALIA,
          "nord_africa", REG_NORD_AFRICA,
          "balcani_mo", REG_BALCANI_MO,
          "sud_russia", REG_SUD_RUSSIA,
          "gibilterra", REG_GIBILTERRA);

  private final GameData data;
  private final Function<String, String> allianceOf;

  /** Round → Italian transports lost. */
  private final Map<Integer, Integer> trasportiPersi = new TreeMap<>();

  private Set<String> transports;

  MonitorP12(final GameData data, final Function<String, String> allianceOf) {
    this.data = data;
    this.allianceOf = allianceOf;
    this.transports = trasporti();
  }

  void afterStep(final int round) {
    final Set<String> now = trasporti();
    int lost = 0;
    for (final String id : transports) {
      if (!now.contains(id)) {
        lost++;
      }
    }
    if (lost > 0) {
      trasportiPersi.merge(round, lost, Integer::sum);
    }
    transports = now;
  }

  private Set<String> trasporti() {
    final Set<String> out = new HashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        continue;
      }
      for (final Unit u :
          t.getMatches(
              u ->
                  ITALIA.equals(u.getOwner().getName())
                      && Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u))) {
        out.add(u.getId().toString());
      }
    }
    return out;
  }

  JsonObject snapshot(final int round) {
    final JsonObject json = new JsonObject();
    final JsonObject reg = new JsonObject();
    int total = 0;
    int counted = 0;
    for (final var e : new TreeMap<>(REGIONI).entrySet()) {
      final int n = terra(ITALIA, e.getValue());
      reg.addProperty(e.getKey(), n);
      counted += n;
    }
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        total += t.getMatches(terra(ITALIA)::contains).size();
      }
    }
    reg.addProperty("altrove", Math.max(0, total - counted));
    reg.addProperty("in_mare", inMare(ITALIA));
    json.add("ita_terra", reg);
    json.addProperty("ita_trasporti", transports.size());
    json.addProperty("ita_trasporti_persi", trasportiPersi.getOrDefault(round, 0));
    json.addProperty("ita_navi", navi(ITALIA));
    final GamePlayer it = data.getPlayerList().getPlayerId(ITALIA);
    json.addProperty(
        "ita_reddito", it == null ? 0 : (int) Math.round(ProUtils.getPlayerProduction(it, data)));
    json.addProperty("egitto_asse", asse("Egypt"));
    json.addProperty("gibilterra_asse", asse("Gibraltar"));
    int six = 0;
    for (final String n : NORD_AFRICA_SEI) {
      if (asse(n)) {
        six++;
      }
    }
    json.addProperty("nord_africa_asse", six);
    json.addProperty("tedeschi_ovest", terra(GERMANIA, MonitorP6.OVEST));
    json.addProperty("tedeschi_italia", terra(GERMANIA, REG_ITALIA));
    return json;
  }

  private boolean asse(final String name) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    return t != null && "Axis".equals(allianceOf.apply(t.getOwner().getName()));
  }

  private List<Unit> terra(final String nation) {
    final List<Unit> out = new java.util.ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()) {
        out.addAll(
            t.getMatches(
                u ->
                    nation.equals(u.getOwner().getName())
                        && Matches.unitIsLand().test(u)
                        && Matches.unitIsNotInfrastructure().test(u)));
      }
    }
    return out;
  }

  private int terra(final String nation, final List<String> names) {
    int n = 0;
    for (final String name : names) {
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t != null) {
        n +=
            t.getMatches(
                    u ->
                        nation.equals(u.getOwner().getName())
                            && Matches.unitIsLand().test(u)
                            && Matches.unitIsNotInfrastructure().test(u))
                .size();
      }
    }
    return n;
  }

  private int inMare(final String nation) {
    int n = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        n +=
            t.getMatches(u -> nation.equals(u.getOwner().getName()) && Matches.unitIsLand().test(u))
                .size();
      }
    }
    return n;
  }

  private int navi(final String nation) {
    int n = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater()) {
        n +=
            t.getMatches(
                    u ->
                        nation.equals(u.getOwner().getName())
                            && Matches.unitIsSea().test(u)
                            && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u))
                .size();
      }
    }
    return n;
  }
}
