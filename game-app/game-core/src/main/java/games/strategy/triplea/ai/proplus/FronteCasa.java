package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Step P4, C ({@code russia_fronte}): three indications to Hard of a nation holding its home front.
 *
 * <ul>
 *   <li>C1: the own non-capital factories in the nation's home territories (original owner, found
 *       on the map) get a higher defense priority in Hard's non-combat move while owned and a
 *       higher attack priority while the enemy's (to take them back);
 *   <li>C2: an attack on enemy units in or next to a home territory with a favorable estimated
 *       exchange (TUV swing at least {@code scambio_minimo}) has lower win thresholds (desired /
 *       minimum) and is not dropped for Hard's defensive stance when the capital still holds the
 *       enemy's realistic attack after it ({@code soglia_tenuta_capitale});
 *   <li>C3: a damaged factory is repaired only as much as the placement the nation can afford this
 *       turn needs; the rest of the PUs is Hard's for units.
 * </ul>
 *
 * <p>No unit held, no purchase given: Hard still decides. The targets of C2 are computed at the
 * start of the turn (Hard plans its combat move at the purchase, on a copy).
 */
public final class FronteCasa {
  private FronteCasa() {}

  /** One counterattack target of the turn. */
  record Candidato(
      String territorio, double vittoria, double scambio, boolean capitaleRegge, double capitale) {}

  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    return IndicazioniP4.attivo(data, player, StrategaInterruttori.RUSSIA_FRONTE);
  }

  private static IndicazioniP4.Fronte cfg() {
    return IndicazioniP4.Config.get().fronte();
  }

  /** {@code t} is a home territory of {@code player} (original owner). */
  static boolean casa(final Territory t, final GamePlayer player) {
    return !t.isWater()
        && TerritoryAttachment.get(t)
            .flatMap(TerritoryAttachment::getOriginalOwner)
            .map(p -> p.getName().equals(player.getName()))
            .orElse(false);
  }

  /** C1: a home territory with a factory that is not a capital. */
  static boolean fabbricaAlFronte(final Territory t, final GamePlayer player) {
    return casa(t, player)
        && !TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false)
        && t.anyUnitsMatch(Matches.unitCanProduceUnits().and(Matches.unitIsInfrastructure()));
  }

  /** C1: the defense bonus of {@code t} (own front factory), else 0. */
  static double bonusDifesa(final ProData proData, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !attivo(proData.getData(), player)) {
      return 0;
    }
    return t.isOwnedBy(player) && fabbricaAlFronte(t, player) ? cfg().pesoDifesaFabbrica() : 0;
  }

  /** C1: the attack bonus of {@code t} (front factory held by an enemy), else 0. */
  static double bonusRipresa(final ProData proData, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !attivo(proData.getData(), player)) {
      return 0;
    }
    return !t.isOwnedBy(player) && player.isAtWar(t.getOwner()) && fabbricaAlFronte(t, player)
        ? cfg().pesoRipresaFabbrica()
        : 0;
  }

  // ===================================================================== C2

  /** C2: a candidate of the turn for {@code t}, if any. */
  static Optional<Candidato> candidato(final IndicazioniP4.Stato stato, final Territory t) {
    return Optional.ofNullable(stato.candidati.get(t.getName()));
  }

  /**
   * C2, at the start of the turn (real board, {@code proData} initialized): the attacks on enemy
   * units in or next to a home territory whose estimated exchange with the most units that can
   * attack is favorable, and whether the capital holds after each.
   */
  static void inizioTurno(
      final ProData proData, final ProOddsCalculator calc, final IndicazioniP4.Stato stato) {
    final GamePlayer player = proData.getPlayer();
    final GameData data = proData.getData();
    stato.candidati.clear();
    stato.fabbriche.clear();
    for (final Territory t : data.getMap().getTerritories()) {
      if (fabbricaAlFronte(t, player) && t.isOwnedBy(player)) {
        stato.fabbriche.add(t.getName());
      }
    }
    try {
      final ProTerritoryManager m = new ProTerritoryManager(calc, proData);
      m.populateAttackOptions();
      final Territory capital = proData.getMyCapital();
      for (final var e : m.getAttackOptions().getTerritoryMap().entrySet()) {
        final Territory t = e.getKey();
        if (t.isWater() || !vicinoACasa(data, t, player)) {
          continue;
        }
        final ProTerritory patd = e.getValue();
        final List<Unit> defenders = patd.getMaxEnemyDefenders(player);
        if (defenders.stream().noneMatch(u -> player.isAtWar(u.getOwner()))
            || patd.getMaxUnits().isEmpty()) {
          continue;
        }
        final ProBattleResult r =
            calc.estimateAttackBattleResults(
                proData, t, patd.getMaxUnits(), defenders, patd.getMaxBombardUnits());
        if (r.getTuvSwing() <= cfg().scambioMinimo()) {
          continue;
        }
        double held = 0;
        if (capital != null && capital.isOwnedBy(player)) {
          held = attaccoRealisticoCapitale(proData, calc, capital, patd.getMaxUnits());
        }
        stato.candidati.put(
            t.getName(),
            new Candidato(
                t.getName(),
                r.getWinPercentage(),
                r.getTuvSwing(),
                held <= cfg().sogliaTenutaCapitale(),
                held));
      }
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P4, contrattacchi: " + e);
    }
  }

  /** {@code t} is a home territory or next to one by land. */
  static boolean vicinoACasa(final GameState data, final Territory t, final GamePlayer player) {
    return casa(t, player) || !data.getMap().getNeighbors(t, x -> casa(x, player)).isEmpty();
  }

  /**
   * The enemy's realistic attack on the capital (win%) with the capital's defenders minus the ones
   * that may leave for the attack ({@code leaving}): the enemy units at war that can reach it, the
   * share the enemy commits to a capital (StimaUnica: all of it).
   */
  static double attaccoRealisticoCapitale(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory capital,
      final Set<Unit> leaving) {
    final GamePlayer player = proData.getPlayer();
    final ProTerritoryManager m = new ProTerritoryManager(calc, proData);
    m.populateEnemyAttackOptions(List.of(), List.of(capital));
    final ProTerritory max = m.getEnemyAttackOptions().getMax(capital);
    if (max == null) {
      return 0;
    }
    final List<Unit> all = new ArrayList<>(max.getMaxUnits());
    max.getMaxAmphibUnits().stream().filter(u -> !all.contains(u)).forEach(all::add);
    all.removeIf(u -> !player.isAtWar(u.getOwner()));
    if (all.isEmpty()) {
      return 0;
    }
    final double share =
        ProPlusSettings.allianceOf(proData.getData(), player)
            .map(
                a ->
                    StimaUnica.impegno(
                            proData.getData(), a, capital, StimaUnica.TipoBersaglio.TENUTA)
                        .frazione())
            .orElse(1.0);
    all.sort(
        Comparator.comparingInt((Unit u) -> -proData.getUnitValue(u.getType()))
            .thenComparing(u -> u.getId().toString()));
    final double total = all.stream().mapToInt(u -> proData.getUnitValue(u.getType())).sum();
    final List<Unit> counter = new ArrayList<>();
    double sum = 0;
    for (final Unit u : all) {
      if (sum >= total * share) {
        break;
      }
      counter.add(u);
      sum += proData.getUnitValue(u.getType());
    }
    final Set<Unit> gone = new HashSet<>(leaving);
    final List<Unit> defenders =
        capital
            .getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()))
            .stream()
            .filter(u -> !gone.contains(u))
            .toList();
    if (defenders.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, capital, counter, defenders, List.of())
        .getWinPercentage();
  }

  /** C2: the desired win% of {@code t} ({@code standard} if not a candidate). */
  static double sogliaDesiderata(
      final IndicazioniP4.Stato stato, final Territory t, final double standard) {
    return candidato(stato, t).isPresent()
        ? Math.min(standard, cfg().sogliaDesiderata())
        : standard;
  }

  /** C2: the minimum win% of {@code t} ({@code standard} if not a candidate). */
  static double sogliaMinima(
      final IndicazioniP4.Stato stato, final Territory t, final double standard) {
    return candidato(stato, t).isPresent() ? Math.min(standard, cfg().sogliaMinima()) : standard;
  }

  /** C2: the defensive stance does not drop {@code patd} (candidate and the capital holds). */
  static boolean tieneInDifensiva(final IndicazioniP4.Stato stato, final ProTerritory patd) {
    final boolean keep =
        candidato(stato, patd.getTerritory()).map(Candidato::capitaleRegge).orElse(false);
    if (keep && !stato.stanceTenuti.contains(patd.getTerritory().getName())) {
      stato.stanceTenuti.add(patd.getTerritory().getName());
    }
    return keep;
  }

  // ===================================================================== C3

  /**
   * C3 (pure rule): the smallest repair after which the factory can place every unit the PUs left
   * can buy (a repaired point costs 1 PU): capacity {@code max(0, full - (damage - r))} >= {@code
   * (pu - r) / cost}. The whole damage if no smaller repair is enough.
   */
  static int riparazione(final int damage, final int capacityFull, final int pu, final int cost) {
    if (damage <= 0 || cost <= 0) {
      return Math.max(0, damage);
    }
    for (int r = 0; r < damage; r++) {
      final int capacity = Math.max(0, capacityFull - (damage - r));
      final int units = Math.max(0, pu - r) / cost;
      if (capacity >= units) {
        return r;
      }
    }
    return damage;
  }

  /** C3: the repair of {@code factory} (the whole damage if the switch is off). */
  static int ripara(
      final ProData proData,
      final IndicazioniP4.Stato stato,
      final Unit factory,
      final int damage,
      final int pusRemaining) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !attivo(proData.getData(), player)) {
      return damage;
    }
    try {
      final Territory t = where(proData, factory);
      if (t == null) {
        return damage;
      }
      final int full = UnitUtils.getHowMuchCanUnitProduce(factory, t, false, true);
      final int now = UnitUtils.getHowMuchCanUnitProduce(factory, t, true, true);
      final int cost =
          proData.getPurchaseOptions().getLandOptions().stream()
              .filter(o -> o.getQuantity() > 0)
              .mapToInt(o -> (int) Math.ceil((double) o.getCost() / o.getQuantity()))
              .filter(c -> c > 0)
              .min()
              .orElse(0);
      final int r = riparazione(damage, full, pusRemaining, cost);
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", t.getName());
      o.addProperty("danno", damage);
      o.addProperty("capacita_ora", now);
      o.addProperty("capacita_piena", full);
      o.addProperty("pu", pusRemaining);
      o.addProperty("costo_unita", cost);
      o.addProperty("riparato", r);
      o.addProperty("risparmiato", damage - r);
      stato.riparazioni.add(o);
      return r;
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P4, riparazioni: " + e);
      return damage;
    }
  }

  private static @Nullable Territory where(final ProData proData, final Unit u) {
    final Territory t = proData.getUnitTerritory(u);
    if (t != null) {
      return t;
    }
    for (final Territory x : proData.getData().getMap().getTerritories()) {
      if (x.getUnits().contains(u)) {
        return x;
      }
    }
    return null;
  }

  // ===================================================================== register

  /** The row of the turn (after Hard's non-combat move: the battles are over). */
  static void registra(final GameState data, final GamePlayer player, final IndicazioniP4.Stato s) {
    final JsonObject p = new JsonObject();
    final JsonArray factories = new JsonArray();
    for (final Territory t : data.getMap().getTerritories()) {
      if (fabbricaAlFronte(t, player)) {
        final JsonObject o = new JsonObject();
        o.addProperty("territorio", t.getName());
        o.addProperty("proprietario", t.getOwner().getName());
        factories.add(o);
      }
    }
    p.add("fabbriche_al_fronte", factories);
    p.addProperty("fabbriche_possedute_inizio", s.fabbriche.size());
    final JsonArray attacks = new JsonArray();
    for (final Candidato c : s.candidati.values()) {
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", c.territorio());
      o.addProperty("vittoria_max_stimata", Math.round(c.vittoria()));
      o.addProperty("scambio_stimato", Math.round(c.scambio()));
      o.addProperty("capitale_regge", c.capitaleRegge());
      o.addProperty("attacco_realistico_capitale", Math.round(c.capitale()));
      if (s.priorita.containsKey(c.territorio())) {
        o.addProperty("priorita_hard", s.priorita.get(c.territorio()));
      }
      if (s.scartati.containsKey(c.territorio())) {
        o.addProperty("scartato", s.scartati.get(c.territorio()));
      }
      o.addProperty("tenuto_in_difensiva", s.stanceTenuti.contains(c.territorio()));
      final JsonObject att = s.attacchi.get(c.territorio());
      o.addProperty("attaccato", att != null);
      if (att != null) {
        o.add("attacco", att);
        final double win = att.get("vittoria_stimata").getAsDouble();
        o.addProperty("sotto_soglia_standard", win >= 0 && win < 65);
      }
      final Territory t = data.getMap().getTerritoryOrNull(c.territorio());
      o.addProperty("proprietario_fine_turno", t == null ? "?" : t.getOwner().getName());
      o.addProperty("preso", t != null && t.isOwnedBy(player));
      attacks.add(o);
    }
    p.add("contrattacchi", attacks);
    p.add("riparazioni", s.riparazioni);
    int repaired = 0;
    int saved = 0;
    for (final var e : s.riparazioni) {
      repaired += e.getAsJsonObject().get("riparato").getAsInt();
      saved += e.getAsJsonObject().get("risparmiato").getAsInt();
    }
    p.addProperty("pu_riparati", repaired);
    p.addProperty("pu_risparmiati", saved);
    IndicazioniP4.registra(
        data,
        data.getSequence().getRound(),
        player,
        "russia_fronte",
        "FRONTE",
        "fabbriche al fronte, contrattacchi con soglie piu' basse, riparazioni",
        p);
  }
}
