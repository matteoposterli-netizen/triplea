package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProDefensePlan;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.data.ProPurchaseOption;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.OperazioniCongiunte.Minaccia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Step C2, the computed defense budget of the "Pro+ Stratega LLM" player (never Hard nor the other
 * players): at the start of each turn of a strategist's nation, first what it costs to defend what
 * matters, then the rest goes to the strategy.
 *
 * <ul>
 *   <li>threat: the territories of the nation (and the alliance's capitals and factories it can
 *       reinforce) that the enemies moving before its next turn can take, all of them together
 *       (Hard's enemy attack options); the counterattack is the single estimate of the operations
 *       ({@link StimaUnica}: maximum on capitals and key factories, realistic elsewhere);
 *   <li>value in PU (same scale as the value of the alliance) and cost of the defense: the fewest
 *       units for {@code difesa.soglia_tenuta}, first reinforcements that arrive in the non-combat
 *       move, then purchases placeable in the territory's factory;
 *   <li>defend if irreplaceable (capital, LLM list) or value >= cost; give up if expendable (LLM
 *       list), cost > value or the territory cannot be held;
 *   <li>PUs to the strategy = min(PUs - defense PUs, {@code tetto_strategia_pct}% of the PUs); the
 *       rest to Hard.
 * </ul>
 *
 * <p>Values in {@code stratega-llm.json} ({@code difesa}), reasonable and not tuned.
 */
final class Difesa {

  /** Records of the defense per alliance: {"turni": [...]} (diary, outcomes, calibration). */
  static final String KEY = "Pro+ stratega llm difesa: ";

  /** Cap of the PUs to the strategy for the alliance (percent; autoplay, else the data file). */
  public static final String KEY_TETTO = "Pro+ stratega llm tetto strategia: ";

  /** The LLM's lists of the round for the alliance (irreplaceable / expendable territories). */
  static final String KEY_LISTE = "Pro+ stratega llm liste difesa: ";

  private static final int MAX_LISTA = 5;

  /** One threatened territory of the plan. */
  record Voce(
      Territory territorio,
      double valore,
      JsonObject componenti,
      double tenutaIniziale,
      double tenuta,
      double tenutaMassima,
      List<Unit> rinforzi,
      @Nullable ProPurchaseOption acquisto,
      int nAcquisti,
      int puAcquisti,
      double costo,
      boolean difendi,
      String motivo,
      String fonte,
      StimaUnica.Impegno impegno,
      double tuvContrattacco,
      List<Unit> presidio,
      List<String> nemici) {}

  /** The plan of a nation's turn. */
  record Piano(
      String nazione,
      int round,
      int pu,
      int puDifesa,
      int puStrategia,
      int puHard,
      double tettoPct,
      int taglioTetto,
      boolean tettoLimita,
      int costoRiserve,
      List<Voce> voci,
      List<String> nonValutati) {

    /** The purchases for the Pro purchase ({@link ProDefensePlan}). */
    ProDefensePlan perAcquisto() {
      final List<ProDefensePlan.Purchase> buy = new ArrayList<>();
      for (final Voce v : voci) {
        if (v.difendi() && v.acquisto() != null && v.nAcquisti() > 0) {
          buy.add(
              new ProDefensePlan.Purchase(
                  v.territorio().getName(), v.acquisto().getUnitType().getName(), v.nAcquisti()));
        }
      }
      return new ProDefensePlan("Stratega LLM, difesa di " + nazione, buy, puStrategia);
    }

    /** Units the plan keeps for the defense: the reinforcements and the defended garrisons. */
    Set<Unit> trattenute() {
      final Set<Unit> out = new LinkedHashSet<>();
      for (final Voce v : voci) {
        if (v.difendi()) {
          out.addAll(v.rinforzi());
          out.addAll(v.presidio());
        }
      }
      return out;
    }
  }

  private final ProData proData;
  private final ProOddsCalculator calc;
  private final GameState data;
  private final GameMap map;
  private final GamePlayer player;
  private final String alliance;
  private final int round;
  private final StrategaConfig cfg;
  private final OperazioniCongiunte op;
  private final Map<String, Double> memo = new HashMap<>();

  private Difesa(final ProData proData, final ProOddsCalculator calc, final String alliance) {
    this.proData = proData;
    this.calc = calc;
    this.data = proData.getData();
    this.map = data.getMap();
    this.player = proData.getPlayer();
    this.alliance = alliance;
    this.round = data.getSequence().getRound();
    this.cfg = StrategaConfig.get();
    this.op = OperazioniCongiunte.perStima(proData, calc, definizione());
  }

  // ===================================================================================== plan

  /**
   * The defense plan of {@code proData}'s nation now (real board, its purchase). {@code keep}: the
   * units the strategist's operations already hold (never assigned to the defense). Also settles
   * the outcome of the nation's previous plan and saves this one.
   */
  static Optional<Piano> calcola(
      final ProData proData, final ProOddsCalculator calc, final Collection<Unit> keep) {
    final GameState data = proData.getData();
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, proData.getPlayer());
    if (alliance.isEmpty() || !Stratega.gioca(data, proData.getPlayer())) {
      return Optional.empty();
    }
    final Difesa d = new Difesa(proData, calc, alliance.get());
    final List<String> settled = d.esitiPrecedenti();
    final Piano p = d.piano(keep);
    d.salva(p, settled);
    return Optional.of(p);
  }

  private Piano piano(final Collection<Unit> keep) {
    final int pu = player.getResources().getQuantity(games.strategy.triplea.Constants.PUS);
    final Liste liste = liste(data.getProperties(), alliance, round);
    final List<Territory> threatened = new ArrayList<>(minacciati());
    final StrategaCandidati values =
        new StrategaCandidati(
            proData, calc, alliance, List.copyOf(Stratega.nazioni(data.getProperties(), alliance)));
    final Map<Territory, JsonObject> parts = new HashMap<>();
    for (final Territory t : threatened) {
      parts.put(t, valore(values, t));
    }
    // irreplaceable first (capital, LLM), then by value
    threatened.sort(
        Comparator.comparingInt((Territory t) -> irrinunciabile(t, liste).isPresent() ? 0 : 1)
            .thenComparing(t -> -parts.get(t).get("totale_PU").getAsDouble())
            .thenComparing(Territory::getName));
    final List<String> skipped = new ArrayList<>();
    while (threatened.size() > cfg.maxTerritoriDifesa()) {
      skipped.add(threatened.remove(threatened.size() - 1).getName());
    }
    final Set<Unit> assigned = new LinkedHashSet<>(keep);
    assigned.addAll(StimaUnica.riservate(data, alliance, ""));
    int left = pu;
    final Map<String, Integer> production = new HashMap<>();
    final List<Voce> voci = new ArrayList<>();
    for (final Territory t : threatened) {
      final Voce v = voce(t, parts.get(t), liste, assigned, left, production);
      if (v == null) {
        continue;
      }
      voci.add(v);
      if (v.difendi()) {
        assigned.addAll(v.rinforzi());
        assigned.addAll(v.presidio());
        left -= v.puAcquisti();
        production.merge(t.getName(), v.nAcquisti(), Integer::sum);
      }
    }
    final int defense = pu - left;
    final double cap = tetto(data.getProperties(), alliance);
    final int capPu = (int) Math.floor(pu * cap / 100.0);
    final int strategy = Math.max(0, Math.min(pu - defense, capPu));
    final int cut = Math.max(0, pu - defense - capPu);
    final int reserves = costoRiserve();
    return new Piano(
        player.getName(),
        round,
        pu,
        defense,
        strategy,
        Math.max(0, pu - defense - strategy),
        cap,
        cut,
        cut > 0 && reserves > strategy,
        reserves,
        List.copyOf(voci),
        List.copyOf(skipped));
  }

  /** The value of {@code t} for the alliance in PU (same scale as the operations), with parts. */
  private JsonObject valore(final StrategaCandidati values, final Territory t) {
    final JsonObject o =
        values.valoreBersaglioPu(t, player.getName(), Math.max(1, cfg.tenutaRoundDefault()));
    final double factories = op.tuvOf(t.getMatches(Matches.unitCanProduceUnits()));
    double operations = 0;
    final List<String> deps = new ArrayList<>();
    for (final Definizione d : OperazioneCongiunta.dinamiche(data.getProperties(), alliance)) {
      final Registro reg = Registro.leggi(data.getProperties(), d.id());
      if (reg.isNuovo() || reg.stato().isFinale()) {
        continue;
      }
      if (d.raccoltaApre().contains(t.getName()) || d.raccoltaChiude().contains(t.getName())) {
        operations += d.valore();
        deps.add(d.id());
      }
    }
    o.addProperty("fabbriche_PU", Math.round(factories));
    o.addProperty("operazioni_dipendenti_PU", Math.round(operations));
    o.add("operazioni_dipendenti", StrategaCandidati.array(deps));
    o.addProperty(
        "totale_PU", Math.round(o.get("totale_PU").getAsDouble() + factories + operations));
    return o;
  }

  /**
   * The nation's own land territories, and the alliance's capitals and factories, that an enemy
   * land unit can reach before its next turn (all the enemies together, Hard's attack options); not
   * the targets the alliance's hold operations already defend.
   */
  private List<Territory> minacciati() {
    final Set<String> heldByOps = new LinkedHashSet<>();
    for (final Definizione d : OperazioneCongiunta.dinamiche(data.getProperties(), alliance)) {
      final Registro reg = Registro.leggi(data.getProperties(), d.id());
      if (reg.stato() == Stato.TENUTA && reg.json().has("tenuta_bersaglio")) {
        heldByOps.add(reg.json().get("tenuta_bersaglio").getAsString());
      }
    }
    final List<Territory> candidates = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (t.isWater() || heldByOps.contains(t.getName())) {
        continue;
      }
      if (t.isOwnedBy(player)) {
        candidates.add(t);
      } else if (ProPlusSettings.allianceOf(data, t.getOwner()).filter(alliance::equals).isPresent()
          && Matches.isTerritoryAllied(player).test(t)
          && (isCapital(t) || t.anyUnitsMatch(Matches.unitCanProduceUnits()))
          && !map.getNeighbors(t, 2).stream()
              .filter(x -> !x.isWater())
              .allMatch(x -> x.getMatches(Matches.unitIsOwnedBy(player)).isEmpty())) {
        candidates.add(t);
      }
    }
    if (candidates.isEmpty()) {
      return List.of();
    }
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptions(List.of(), candidates);
    final Set<String> real = StimaUnica.nemiciReali(data, player);
    final java.util.function.Predicate<Unit> enemyLand =
        Matches.unitIsLand()
            .and(Matches.unitIsNotInfrastructure())
            .and(u -> real.contains(u.getOwner().getName()));
    final List<Territory> out = new ArrayList<>();
    for (final Territory t : candidates) {
      final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
      if (max == null) {
        continue;
      }
      final boolean land =
          max.getMaxUnits().stream().anyMatch(enemyLand)
              || max.getMaxAmphibUnits().stream().anyMatch(enemyLand);
      if (land) {
        out.add(t);
      }
    }
    return out;
  }

  private boolean isCapital(final Territory t) {
    return TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false);
  }

  /**
   * The estimate of one threatened territory: hold now, the fewest units for the threshold
   * (reinforcements, then purchases), value against cost and the decision. Null if no part of the
   * counterattack reaches it.
   */
  private @Nullable Voce voce(
      final Territory t,
      final JsonObject parts,
      final Liste liste,
      final Set<Unit> assigned,
      final int pusLeft,
      final Map<String, Integer> production) {
    final Minaccia m = op.minaccia(t);
    final StimaUnica.Impegno imp = StimaUnica.impegno(data, alliance, t);
    final List<Unit> counter = StimaUnica.parte(op, t, m, imp.frazione());
    if (counter.isEmpty()) {
      return null;
    }
    final List<Unit> garrison = op.alliedDefendersOf(t);
    final List<Unit> help = new ArrayList<>(op.rinforziPossibili(t, player, assigned));
    help.removeAll(garrison);
    help.sort(
        Comparator.comparingDouble(
                (Unit u) ->
                    -(2.0 * u.getUnitAttachment().getHitPoints()
                            + u.getUnitAttachment().getDefense(u.getOwner()))
                        / Math.max(1, op.tuvOf(List.of(u))))
            .thenComparing(u -> u.getType().getName()));
    final List<Unit> sequence =
        new ArrayList<>(help.subList(0, Math.min(help.size(), cfg.maxUnitaDifesa())));
    final int moved = sequence.size();
    // purchases: only in the territory's own factory (placed units do not move before the enemy)
    final Optional<ProPurchaseOption> best = migliorDifensore();
    int buyable = 0;
    if (best.isPresent()
        && t.isOwnedBy(player)
        && ProMatches.territoryHasInfraFactoryAndIsOwnedLand(player).test(t)) {
      final int capacity =
          Math.max(
              0, TerritoryAttachment.getProduction(t) - production.getOrDefault(t.getName(), 0));
      buyable =
          Math.min(
              Math.min(capacity, pusLeft / Math.max(1, best.get().getCost())),
              Math.max(0, cfg.maxUnitaDifesa() - moved));
      if (buyable > 0) {
        sequence.addAll(best.get().getUnitType().createTemp(buyable, player));
      }
    }
    final double hold0 = hold(t, m, counter, garrison, List.of());
    final double holdMax = hold(t, m, counter, garrison, sequence);
    final double threshold = cfg.sogliaTenutaDifesa();
    int k = 0;
    if (hold0 < threshold && !sequence.isEmpty()) {
      if (holdMax < threshold) {
        k = sequence.size();
      } else {
        int lo = 1;
        int hi = sequence.size();
        while (lo < hi) {
          final int mid = (lo + hi) / 2;
          if (hold(t, m, counter, garrison, sequence.subList(0, mid)) >= threshold) {
            hi = mid;
          } else {
            lo = mid + 1;
          }
        }
        k = lo;
      }
    }
    final List<Unit> used = sequence.subList(0, k);
    final double holdNow = k == 0 ? hold0 : hold(t, m, counter, garrison, used);
    final List<Unit> reinforcements = List.copyOf(used.subList(0, Math.min(k, moved)));
    final int nBuy = Math.max(0, k - moved);
    final int puBuy = best.map(b -> b.getCost() * nBuy).orElse(0);
    final double cost = puBuy + (1 - holdNow / 100) * op.tuvOf(used);
    final double value = parts.get("totale_PU").getAsDouble();
    final Optional<String> must = irrinunciabile(t, liste);
    final Optional<String> expendable = sacrificabile(t, liste);
    final Decisione dec =
        decidi(must, expendable, hold0, holdMax, threshold, cfg.sogliaRitirata(), value, cost);
    final boolean defend = dec.difendi();
    final String why = dec.motivo();
    final String source = dec.fonte();
    final List<Unit> keepHere =
        garrison.stream()
            .filter(Matches.unitIsOwnedBy(player))
            .filter(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()))
            .toList();
    return new Voce(
        t,
        value,
        parts,
        hold0,
        defend ? holdNow : hold0,
        holdMax,
        defend ? reinforcements : List.of(),
        best.orElse(null),
        defend ? nBuy : 0,
        defend ? puBuy : 0,
        cost,
        defend,
        why,
        source,
        imp,
        op.tuvOf(counter),
        defend ? keepHere : List.of(),
        counter.stream().map(u -> u.getOwner().getName()).distinct().sorted().toList());
  }

  /**
   * Hold% of {@code t} for {@code proData}'s nation with {@code defenders}: the computed defense's
   * estimate (same counterattack and function as the strategist's holds, {@link
   * StimaUnica#tenuta(ProData, ProOddsCalculator, Definizione, Territory)}).
   */
  static double tenutaCon(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final Collection<Unit> defenders) {
    final String alliance =
        ProPlusSettings.allianceOf(proData.getData(), proData.getPlayer()).orElseThrow();
    final Difesa d = new Difesa(proData, calc, alliance);
    final Minaccia m = d.op.minaccia(t);
    final StimaUnica.Impegno imp = StimaUnica.impegno(d.data, alliance, t);
    final List<Unit> counter = StimaUnica.parte(d.op, t, m, imp.frazione());
    return StimaUnica.tenuta(proData, calc, t, m, counter, defenders);
  }

  /** A decision on a threatened territory: defend or give up, why, who decided. */
  record Decisione(boolean difendi, String motivo, String fonte) {}

  /**
   * The rule: irreplaceable (capital, LLM) = defend; expendable (LLM) = give up; already holding =
   * defend at no cost; cannot be held (maximum hold within {@code ritirata}) = give up; else defend
   * if the value is at least the cost.
   */
  static Decisione decidi(
      final Optional<String> must,
      final Optional<String> expendable,
      final double hold0,
      final double holdMax,
      final double threshold,
      final double ritirata,
      final double value,
      final double cost) {
    if (must.isPresent()) {
      return new Decisione(
          true,
          "irrinunciabile: " + must.get(),
          must.get().startsWith("capitale") ? "capitale" : "llm");
    }
    if (expendable.isPresent()) {
      return new Decisione(false, "sacrificabile: " + expendable.get(), "llm");
    }
    if (hold0 >= threshold) {
      return new Decisione(
          true,
          String.format(Locale.ROOT, "tiene gia' (%.0f%% >= %.0f%%)", hold0, threshold),
          "motore");
    }
    if (holdMax <= ritirata) {
      return new Decisione(
          false,
          String.format(
              Locale.ROOT, "indifendibile: tenuta massima %.0f%% <= %.0f%%", holdMax, ritirata),
          "motore");
    }
    return value >= cost
        ? new Decisione(
            true, String.format(Locale.ROOT, "valore %.0f >= costo %.0f PU", value, cost), "motore")
        : new Decisione(
            false,
            String.format(Locale.ROOT, "costo %.0f > valore %.0f PU", cost, value),
            "motore");
  }

  /** Hold% (memo by defenders' ids): the same estimate as the strategist's holds. */
  private double hold(
      final Territory t,
      final Minaccia m,
      final List<Unit> counter,
      final List<Unit> garrison,
      final List<Unit> extra) {
    final List<Unit> defenders = new ArrayList<>(garrison);
    extra.stream().filter(u -> !defenders.contains(u)).forEach(defenders::add);
    final String key =
        t.getName()
            + "|"
            + defenders.stream().map(u -> u.getId().toString()).sorted().toList()
            + "|"
            + counter.size();
    return memo.computeIfAbsent(
        key, x -> StimaUnica.tenuta(proData, calc, t, m, counter, defenders));
  }

  /** The land unit of best defense (hit points and defense) per PU the nation can buy. */
  private Optional<ProPurchaseOption> migliorDifensore() {
    return proData.getPurchaseOptions().getLandOptions().stream()
        .filter(o -> o.getCost() > 0 && o.getDefense() > 0 && o.getQuantity() == 1)
        .filter(o -> !Matches.unitTypeIsAaForAnything().test(o.getUnitType()))
        .max(
            Comparator.comparingDouble(
                    (ProPurchaseOption o) ->
                        (2.0 * o.getHitPoints() + o.getDefense()) / o.getCost())
                .thenComparing(o -> o.getUnitType().getName()));
  }

  private Optional<String> irrinunciabile(final Territory t, final Liste liste) {
    if (isCapital(t)) {
      return Optional.of("capitale");
    }
    return Optional.ofNullable(liste.irrinunciabili().get(t.getName()))
        .map(why -> "lista dell'LLM (" + why + ")");
  }

  private Optional<String> sacrificabile(final Territory t, final Liste liste) {
    return Optional.ofNullable(liste.sacrificabili().get(t.getName()))
        .map(why -> "lista dell'LLM (" + why + ")");
  }

  /** The cost of the reserves the operations ask this turn (cheapest unit of each order). */
  private int costoRiserve() {
    final Optional<ProPurchaseOrders> orders = OperazioniCongiunte.ordiniAcquisto(proData);
    if (orders.isEmpty()) {
      return 0;
    }
    int cost = 0;
    for (final ProPurchaseOrders.Mandatory m : orders.get().mandatory()) {
      final int unit =
          proData.getPurchaseOptions().getAllOptions().stream()
              .filter(o -> m.unitTypes().contains(o.getUnitType().getName()))
              .mapToInt(ProPurchaseOption::getCost)
              .min()
              .orElse(0);
      cost += unit * m.minimum();
    }
    return cost;
  }

  /** A hold definition of the nation for the estimates (never saved, never played). */
  private Definizione definizione() {
    final JsonObject o = new JsonObject();
    o.addProperty("id", OperazioneCongiunta.PREFISSO_STRATEGA + "difesa-" + player.getName());
    o.addProperty("nome", "difesa " + player.getName());
    o.addProperty("alleanza", alliance);
    o.addProperty(
        "bersaglio",
        map.getTerritories().stream()
            .filter(t -> !t.isWater())
            .findFirst()
            .map(Territory::getName)
            .orElse(""));
    o.addProperty("apre", player.getName());
    o.addProperty("chiude", player.getName());
    o.addProperty("round_min", round);
    o.addProperty("round_max", round);
    o.addProperty("valore", 1);
    o.addProperty("tetto_perdita", 0);
    o.addProperty("tolleranza_difensori", 0);
    o.addProperty("soglia_chiusura", 0);
    o.addProperty("max_rinvii", 0);
    final JsonObject h = new JsonObject();
    h.addProperty("round", Math.max(1, cfg.tenutaRoundDefault()));
    h.addProperty("soglia_rinforzi", cfg.sogliaTenutaDifesa());
    h.addProperty("soglia_ritirata", cfg.sogliaRitirata());
    h.addProperty("aerei_copertura", cfg.aereiCopertura());
    h.addProperty("quota_acquisti_nemici", cfg.quotaAcquistiNemici());
    h.addProperty("riserve", 0);
    o.add("tenuta", h);
    return OperazioneCongiunta.parse(StrategaCandidati.wrap(o)).get(0);
  }

  // =================================================================== cap and LLM lists

  /** The cap of the PUs to the strategy for {@code alliance} (percent). */
  static double tetto(final GameProperties props, final String alliance) {
    if (props.get(KEY_TETTO + alliance) instanceof String s) {
      try {
        return Math.max(0, Math.min(100, Double.parseDouble(s.trim())));
      } catch (final NumberFormatException e) {
        // the data file's value
      }
    }
    return StrategaConfig.get().tettoStrategiaPct();
  }

  /** The LLM's lists of a round: territory → short reason. */
  record Liste(Map<String, String> irrinunciabili, Map<String, String> sacrificabili) {
    static final Liste VUOTE = new Liste(Map.of(), Map.of());
  }

  static Liste liste(final GameProperties props, final String alliance, final int round) {
    if (!(props.get(KEY_LISTE + alliance) instanceof String s)) {
      return Liste.VUOTE;
    }
    try {
      final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
      if (o.get("round").getAsInt() != round) {
        return Liste.VUOTE;
      }
      return new Liste(mappa(o, "irrinunciabili"), mappa(o, "sacrificabili"));
    } catch (final RuntimeException e) {
      return Liste.VUOTE;
    }
  }

  private static Map<String, String> mappa(final JsonObject o, final String key) {
    final Map<String, String> m = new LinkedHashMap<>();
    if (o.has(key)) {
      for (final JsonElement e : o.getAsJsonArray(key)) {
        final JsonObject x = e.getAsJsonObject();
        m.put(x.get("territorio").getAsString(), x.get("motivazione").getAsString());
      }
    }
    return m;
  }

  /**
   * Saves the LLM's lists of the round for {@code alliance}: at most {@value #MAX_LISTA}
   * territories of the alliance each; a territory in both lists counts as irreplaceable; the
   * capitals are always irreplaceable. Returns what was kept (diary).
   */
  static JsonObject salvaListe(
      final GameState data, final String alliance, final int round, final JsonObject answer) {
    final JsonObject out = new JsonObject();
    out.addProperty("round", round);
    final JsonArray must = filtra(data, alliance, answer, "irrinunciabili", Set.of());
    final Set<String> mustNames = new LinkedHashSet<>();
    must.forEach(e -> mustNames.add(e.getAsJsonObject().get("territorio").getAsString()));
    out.add("irrinunciabili", must);
    out.add("sacrificabili", filtra(data, alliance, answer, "sacrificabili", mustNames));
    final GameProperties props = data.getProperties();
    props.set(KEY_LISTE + alliance, (Serializable) null);
    props.set(KEY_LISTE + alliance, out.toString());
    return out;
  }

  private static JsonArray filtra(
      final GameState data,
      final String alliance,
      final JsonObject answer,
      final String key,
      final Set<String> exclude) {
    final JsonArray out = new JsonArray();
    if (!answer.has(key) || !answer.get(key).isJsonArray()) {
      return out;
    }
    for (final JsonElement e : answer.getAsJsonArray(key)) {
      if (!e.isJsonObject() || !e.getAsJsonObject().has("territorio")) {
        continue;
      }
      final String name = e.getAsJsonObject().get("territorio").getAsString().trim();
      final Territory t = data.getMap().getTerritoryOrNull(name);
      if (t == null
          || t.isWater()
          || exclude.contains(name)
          || ProPlusSettings.allianceOf(data, t.getOwner()).filter(alliance::equals).isEmpty()
          || ("sacrificabili".equals(key)
              && TerritoryAttachment.get(t).map(TerritoryAttachment::isCapital).orElse(false))) {
        continue;
      }
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", name);
      final String why =
          e.getAsJsonObject().has("motivazione")
              ? e.getAsJsonObject().get("motivazione").getAsString()
              : "";
      x.addProperty("motivazione", why.length() > 160 ? why.substring(0, 160) : why);
      out.add(x);
      if (out.size() >= MAX_LISTA) {
        break;
      }
    }
    return out;
  }

  // ================================================================== execution (non-combat)

  /**
   * Non-combat move of the nation, before Hard: the plan's reinforcements go to their territories.
   * Returns the units the plan keeps (moved reinforcements and defended garrisons) and the lines.
   */
  static Esecuzione esegui(final ProData proData, final IMoveDelegate moveDel, final Piano plan) {
    final GameState data = proData.getData();
    final GameMap map = data.getMap();
    final GamePlayer player = proData.getPlayer();
    final Map<UUID, Territory> where = new HashMap<>();
    for (final Territory t : map.getTerritories()) {
      for (final Unit u : t.getUnits()) {
        where.put(u.getId(), t);
      }
    }
    final Set<Unit> kept = new LinkedHashSet<>();
    final List<String> lines = new ArrayList<>();
    int moved = 0;
    int failed = 0;
    for (final Voce v : plan.voci()) {
      if (!v.difendi()) {
        continue;
      }
      final Territory to = map.getTerritoryOrNull(v.territorio().getName());
      if (to == null) {
        continue;
      }
      for (final Unit planned : v.presidio()) {
        final Territory at = where.get(planned.getId());
        if (at != null && at.equals(to)) {
          at.getUnits().stream()
              .filter(u -> u.getId().equals(planned.getId()))
              .findFirst()
              .ifPresent(kept::add);
        }
      }
      final List<String> done = new ArrayList<>();
      for (final Unit planned : v.rinforzi()) {
        final Territory from = where.get(planned.getId());
        if (from == null) {
          failed++;
          continue;
        }
        final Optional<Unit> u =
            from.getUnits().stream().filter(x -> x.getId().equals(planned.getId())).findFirst();
        if (u.isEmpty()) {
          failed++;
          continue;
        }
        if (from.equals(to)) {
          kept.add(u.get());
          continue;
        }
        final Optional<Route> route =
            Matches.unitIsAir().test(u.get())
                ? map.getRoute(from, to, x -> true)
                : map.getRoute(
                    from, to, x -> !x.isWater() && Matches.isTerritoryAllied(player).test(x));
        if (route.isEmpty()
            || route.get().getMovementCost(u.get()).compareTo(u.get().getMovementLeft()) > 0) {
          failed++;
          continue;
        }
        final Optional<String> error =
            moveDel.performMove(new MoveDescription(List.of(u.get()), route.get()));
        if (error.isPresent()) {
          failed++;
          ProLogger.info("[Pro+] Difesa: " + u.get() + " non mossa in " + to + ": " + error.get());
          continue;
        }
        moved++;
        kept.add(u.get());
        done.add(u.get().getType().getName() + " da " + from.getName());
      }
      if (!done.isEmpty()) {
        lines.add("  difesa " + to.getName() + ": " + String.join(", ", done));
      }
    }
    lines.add(
        0,
        String.format(
            Locale.ROOT,
            "Difesa %s R%d (non combattimento): rinforzi mossi %d, non mossi %d, trattenute %d",
            player.getName(),
            data.getSequence().getRound(),
            moved,
            failed,
            kept.size()));
    return new Esecuzione(Set.copyOf(kept), List.copyOf(lines));
  }

  record Esecuzione(Set<Unit> trattenute, List<String> righe) {}

  // ===================================================================== records and diary

  /** The outcome of the nation's previous plan: each territory still the alliance's now. */
  private List<String> esitiPrecedenti() {
    final GameProperties props = data.getProperties();
    final JsonObject all = registro(props, alliance);
    final JsonArray turns = all.getAsJsonArray("turni");
    final List<String> out = new ArrayList<>();
    for (int i = turns.size() - 1; i >= 0; i--) {
      final JsonObject turn = turns.get(i).getAsJsonObject();
      if (!turn.get("nazione").getAsString().equals(player.getName())) {
        continue;
      }
      if (turn.get("round").getAsInt() >= round || turn.has("esito_round")) {
        break;
      }
      for (final JsonElement e : turn.getAsJsonArray("territori")) {
        final JsonObject x = e.getAsJsonObject();
        final Territory t = map.getTerritoryOrNull(x.get("territorio").getAsString());
        final boolean ours =
            t != null
                && ProPlusSettings.allianceOf(data, t.getOwner())
                    .filter(alliance::equals)
                    .isPresent();
        x.addProperty("esito_tenuto", ours);
        out.add(
            x.get("territorio").getAsString()
                + " ("
                + x.get("decisione").getAsString()
                + ", stima "
                + Math.round(x.get("tenuta_stimata_pct").getAsDouble())
                + "%): "
                + (ours ? "tenuto" : "PERSO"));
      }
      turn.addProperty("esito_round", round);
      break;
    }
    if (!out.isEmpty()) {
      props.set(KEY + alliance, (Serializable) null);
      props.set(KEY + alliance, all.toString());
    }
    return out;
  }

  static JsonObject registro(final GameProperties props, final String alliance) {
    if (props.get(KEY + alliance) instanceof String s) {
      try {
        final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
        if (o.has("turni")) {
          return o;
        }
      } catch (final RuntimeException e) {
        // a new record
      }
    }
    final JsonObject o = new JsonObject();
    o.add("turni", new JsonArray());
    return o;
  }

  private void salva(final Piano p, final List<String> settled) {
    final JsonObject turn = json(p);
    final GameProperties props = data.getProperties();
    final JsonObject all = registro(props, alliance);
    all.getAsJsonArray("turni").add(turn);
    props.set(KEY + alliance, (Serializable) null);
    props.set(KEY + alliance, all.toString());
    final List<String> lines = new ArrayList<>();
    lines.add("");
    lines.add(
        String.format(
            Locale.ROOT,
            "=== R%d %s - piano difensivo: PU %d = difesa %d + strategia %d + Hard %d"
                + " (tetto strategia %.0f%%%s) ===",
            p.round(),
            p.nazione(),
            p.pu(),
            p.puDifesa(),
            p.puStrategia(),
            p.puHard(),
            p.tettoPct(),
            p.tettoLimita()
                ? String.format(
                    Locale.ROOT,
                    ": LIMITA le riserve (%d PU chieste, %d tagliati)",
                    p.costoRiserve(),
                    Math.min(p.taglioTetto(), p.costoRiserve() - p.puStrategia()))
                : ""));
    if (!settled.isEmpty()) {
      lines.add("  esito del turno prima: " + String.join("; ", settled));
    }
    for (final Voce v : p.voci()) {
      lines.add(
          String.format(
              Locale.ROOT,
              "  %s %s: valore %.0f PU, costo %.0f PU, tenuta %.0f%% -> %.0f%% (max %.0f%%,"
                  + " contrattacco %.0f TUV, %s)%s%s - %s",
              v.difendi() ? "DIFESO" : "SACRIFICATO",
              v.territorio().getName(),
              v.valore(),
              v.costo(),
              v.tenutaIniziale(),
              v.tenuta(),
              v.tenutaMassima(),
              v.tuvContrattacco(),
              v.impegno().base(),
              v.rinforzi().isEmpty()
                  ? ""
                  : ", rinforzi " + OperazioniAnfibie.unitsText(v.rinforzi()),
              v.nAcquisti() > 0 && v.acquisto() != null
                  ? ", acquisti " + v.nAcquisti() + " " + v.acquisto().getUnitType().getName()
                  : "",
              v.motivo()));
    }
    if (!p.nonValutati().isEmpty()) {
      lines.add(
          "  minacciati non valutati (oltre il massimo): " + String.join(", ", p.nonValutati()));
    }
    turn.addProperty("tipo", "difesa");
    try {
      scriviDiario(lines, turn);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Difesa: diario non scritto: " + e);
    }
  }

  private void scriviDiario(final List<String> lines, final JsonObject rec) {
    games.strategy.triplea.ai.proplus.diario.DiaryRecorder.stratega(
        data,
        lines,
        rec,
        data instanceof games.strategy.engine.data.GameData gd
            ? StrategaDiario.riepilogo(gd)
            : null);
  }

  private static JsonObject json(final Piano p) {
    final JsonObject o = new JsonObject();
    o.addProperty("round", p.round());
    o.addProperty("nazione", p.nazione());
    o.addProperty("pu", p.pu());
    o.addProperty("pu_difesa", p.puDifesa());
    o.addProperty("pu_strategia", p.puStrategia());
    o.addProperty("pu_hard", p.puHard());
    o.addProperty("tetto_strategia_pct", p.tettoPct());
    o.addProperty("taglio_tetto", p.taglioTetto());
    o.addProperty("tetto_limita", p.tettoLimita());
    o.addProperty("costo_riserve", p.costoRiserve());
    o.addProperty(
        "pu_tagliati_dal_tetto",
        p.tettoLimita() ? Math.min(p.taglioTetto(), p.costoRiserve() - p.puStrategia()) : 0);
    final JsonArray ts = new JsonArray();
    for (final Voce v : p.voci()) {
      final JsonObject x = new JsonObject();
      x.addProperty("territorio", v.territorio().getName());
      x.addProperty("proprietario", v.territorio().getOwner().getName());
      x.addProperty("decisione", v.difendi() ? "difeso" : "sacrificato");
      x.addProperty("fonte", v.fonte());
      x.addProperty("motivo", v.motivo());
      x.addProperty("valore_PU", Math.round(v.valore()));
      x.add("valore_componenti", v.componenti());
      x.addProperty("costo_PU", Math.round(v.costo()));
      x.addProperty("tenuta_iniziale_pct", Math.round(v.tenutaIniziale()));
      x.addProperty("tenuta_stimata_pct", Math.round(v.tenuta()));
      x.addProperty("tenuta_massima_pct", Math.round(v.tenutaMassima()));
      x.addProperty("impegno", v.impegno().frazione());
      x.addProperty("base_impegno", v.impegno().base());
      x.addProperty("tuv_contrattacco", Math.round(v.tuvContrattacco()));
      x.add("nemici", StrategaCandidati.array(v.nemici()));
      x.addProperty("rinforzi", OperazioniAnfibie.unitsText(v.rinforzi()));
      x.addProperty("n_rinforzi", v.rinforzi().size());
      x.addProperty(
          "acquisti",
          v.nAcquisti() > 0 && v.acquisto() != null
              ? v.nAcquisti() + " " + v.acquisto().getUnitType().getName()
              : "");
      x.addProperty("pu_acquisti", v.puAcquisti());
      ts.add(x);
    }
    o.add("territori", ts);
    o.add("non_valutati", StrategaCandidati.array(p.nonValutati()));
    return o;
  }

  /** The last defense record of each nation of {@code alliance} (for the LLM's state). */
  static JsonArray ultimi(final GameProperties props, final String alliance) {
    final JsonArray turns = registro(props, alliance).getAsJsonArray("turni");
    final Map<String, JsonObject> last = new LinkedHashMap<>();
    for (final JsonElement e : turns) {
      last.put(e.getAsJsonObject().get("nazione").getAsString(), e.getAsJsonObject());
    }
    final JsonArray out = new JsonArray();
    for (final JsonObject turn : last.values()) {
      final JsonObject x = new JsonObject();
      x.add("round", turn.get("round"));
      x.add("nazione", turn.get("nazione"));
      x.add("pu_difesa", turn.get("pu_difesa"));
      x.add("pu_strategia", turn.get("pu_strategia"));
      final JsonArray ts = new JsonArray();
      for (final JsonElement e : turn.getAsJsonArray("territori")) {
        final JsonObject t = e.getAsJsonObject();
        final JsonObject y = new JsonObject();
        for (final String k :
            List.of(
                "territorio",
                "decisione",
                "valore_PU",
                "costo_PU",
                "tenuta_iniziale_pct",
                "tenuta_stimata_pct",
                "motivo")) {
          y.add(k, t.get(k));
        }
        ts.add(y);
      }
      x.add("territori", ts);
      out.add(x);
    }
    return out;
  }
}
