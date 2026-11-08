package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.io.Serializable;
import java.lang.reflect.Field;
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
import java.util.function.Predicate;
import org.triplea.java.collections.IntegerMap;

/**
 * Strategic contract v1: the strategic state given to the decider (input JSON), compact (goal: at
 * most ~5,000 tokens). It only reads the game: round and turn order, VCs per alliance with names
 * and trend, PUs and income per nation, land/sea/air TUV per front and alliance, capitals,
 * factories and contested VCs, the plan in force with phase, milestones and state, the plans
 * admitted with their scores, the enemy reading, the amphibious operations with their numbers, the
 * main threats, the events since the previous round, the tools available and the guard rails. See
 * proplus/strategia/contratto-v1.md.
 *
 * <p>Estimates here are quick (no battle simulation, so no dice are used): the enemy strength on a
 * territory is {@link ProBattleUtils#estimateStrengthDifference} (0-100, above 55 = probable
 * conquest for Hard), the expected loss of a fleet the same estimate × its TUV.
 */
public final class Ingresso {
  /** Game property of the last report of each operation: {@code "Pro+ rapporti: <alliance>"}. */
  static final String REPORTS_PREFIX = "Pro+ rapporti: ";

  /** Threats listed. */
  static final int MINACCE = 5;

  /** Battles listed. */
  static final int BATTAGLIE = 5;

  private Ingresso() {}

  /** The strategic state of the alliance of {@code ctx} (input JSON v1). */
  public static JsonObject of(final Decisore.Contesto ctx) {
    final GameState data = ctx.data();
    final String alliance = ctx.alleanza();
    final Strategia strategia = ctx.strategia();
    final List<String> alliances = ProPlusSettings.configurableAlliances(data);
    final String enemy = alliances.stream().filter(a -> !a.equals(alliance)).findFirst().orElse("");
    final Map<String, String> allianceOf = new HashMap<>();
    alliances.forEach(
        a ->
            data.getAllianceTracker()
                .getPlayersInAlliance(a)
                .forEach(p -> allianceOf.put(p.getName(), a)));

    final JsonObject json = new JsonObject();
    json.addProperty("versione", Direttive.VERSIONE);
    json.addProperty("alleanza", alliance);
    json.addProperty("nemico", enemy);
    json.addProperty("round", ctx.round());
    json.add("ordine_turni", turnOrder(data));
    final JsonObject nations = new JsonObject();
    alliances.forEach(a -> nations.add(a, strings(Direttive.allianceNations(data, a))));
    json.add("nazioni", nations);
    json.add("fronti", strings(strategia.getFronti()));
    json.add("vc", victoryCities(ctx, alliances));
    json.add("economia", economy(data, allianceOf));
    json.add("tuv", tuv(data, strategia, allianceOf, alliances));
    json.add("capitali", capitals(data, allianceOf));
    json.add("fabbriche", factories(data, allianceOf, alliances));
    json.add("vc_contese", contestedVcs(data, allianceOf));
    json.add("piano", plan(ctx));
    json.add("piani_ammessi", admittedPlans(ctx));
    json.add("lettura_nemico", reading(ctx));
    json.add("operazioni", operations(ctx, allianceOf));
    json.add("minacce", threats(data, alliance, allianceOf));
    json.add("eventi", events(ctx, allianceOf));
    json.add("strumenti", tools(ctx));
    json.add("paletti", guardRails(strategia));
    return json;
  }

  // ------------------------------------------------------------------------------- general

  private static JsonArray turnOrder(final GameState data) {
    final Set<String> order = new LinkedHashSet<>();
    for (final GameStep step : data.getSequence()) {
      final GamePlayer p = step.getPlayerId();
      if (p != null && !p.isNull()) {
        order.add(p.getName());
      }
    }
    return strings(order);
  }

  private static JsonObject victoryCities(
      final Decisore.Contesto ctx, final List<String> alliances) {
    final GameState data = ctx.data();
    final Contromosse.Corsa race =
        Contromosse.Corsa.of(
            data,
            ctx.alleanza(),
            ctx.stato().storico(),
            ctx.round(),
            ctx.strategia().getParametri());
    final JsonObject r = race.json();
    final JsonObject out = new JsonObject();
    for (final String a : alliances) {
      final Collection<GamePlayer> players = data.getAllianceTracker().getPlayersInAlliance(a);
      final List<String> names = new ArrayList<>();
      for (final Territory t : data.getMap().getTerritories()) {
        if (vc(t) > 0 && players.contains(t.getOwner())) {
          names.add(t.getName());
        }
      }
      names.sort(Comparator.naturalOrder());
      final JsonObject o = new JsonObject();
      o.add("tenute", strings(names));
      copy(r, "vc", a, o, "misura_corsa");
      copy(r, "obiettivo_vc", a, o, "obiettivo");
      copy(r, "tendenza", a, o, "tendenza_3_round");
      copy(r, "round_alla_vittoria", a, o, "round_alla_vittoria");
      out.add(a, o);
    }
    if (r.has("esito")) {
      out.addProperty("corsa", r.get("esito").getAsString());
    }
    return out;
  }

  private static void copy(
      final JsonObject from,
      final String key,
      final String a,
      final JsonObject to,
      final String as) {
    if (from.has(key) && from.getAsJsonObject(key).has(a)) {
      to.add(as, from.getAsJsonObject(key).get(a));
    }
  }

  private static JsonObject economy(final GameState data, final Map<String, String> allianceOf) {
    final JsonObject out = new JsonObject();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!allianceOf.containsKey(p.getName())) {
        continue;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("pu", p.getResources().getQuantity(Constants.PUS));
      o.addProperty("reddito", Math.round(ProUtils.getPlayerProduction(p, data)));
      out.add(p.getName(), o);
    }
    return out;
  }

  private static JsonObject tuv(
      final GameState data,
      final Strategia strategia,
      final Map<String, String> allianceOf,
      final List<String> alliances) {
    final Map<String, IntegerMap<UnitType>> costs = new HashMap<>();
    final Map<String, Map<String, int[]>> byFront = new LinkedHashMap<>();
    strategia.getFronti().forEach(f -> byFront.put(f, new LinkedHashMap<>()));
    for (final Territory t : data.getMap().getTerritories()) {
      final String front = strategia.fronteOf(t);
      for (final Unit u : t.getUnits()) {
        final String a = allianceOf.get(u.getOwner().getName());
        if (a == null || Matches.unitIsInfrastructure().test(u)) {
          continue;
        }
        final int cost =
            costs
                .computeIfAbsent(
                    u.getOwner().getName(),
                    k -> new TuvCostsCalculator().getCostsForTuv(u.getOwner()))
                .getInt(u.getType());
        final int kind = Matches.unitIsAir().test(u) ? 2 : Matches.unitIsSea().test(u) ? 1 : 0;
        byFront.computeIfAbsent(front, k -> new LinkedHashMap<>())
                .computeIfAbsent(a, k -> new int[3])[kind] +=
            cost;
      }
    }
    final JsonObject out = new JsonObject();
    byFront.forEach(
        (front, values) -> {
          final JsonObject f = new JsonObject();
          for (final String a : alliances) {
            final int[] v = values.getOrDefault(a, new int[3]);
            final JsonObject o = new JsonObject();
            o.addProperty("terra", v[0]);
            o.addProperty("mare", v[1]);
            o.addProperty("aria", v[2]);
            f.add(a, o);
          }
          out.add(front, f);
        });
    return out;
  }

  private static JsonArray capitals(final GameState data, final Map<String, String> allianceOf) {
    final JsonArray out = new JsonArray();
    for (final Territory t : data.getMap().getTerritories()) {
      final Optional<String> capitalOf =
          TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getCapital);
      if (capitalOf.isEmpty() || !allianceOf.containsKey(capitalOf.get())) {
        continue;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("nazione", capitalOf.get());
      o.addProperty("territorio", t.getName());
      o.addProperty("proprietario", t.getOwner().getName());
      o.addProperty("forza_nemica", Math.round(enemyStrength(data, t)));
      out.add(o);
    }
    return out;
  }

  private static JsonObject factories(
      final GameState data, final Map<String, String> allianceOf, final List<String> alliances) {
    final Map<String, Integer> count = new LinkedHashMap<>();
    final Map<String, List<String>> lost = new LinkedHashMap<>();
    alliances.forEach(
        a -> {
          count.put(a, 0);
          lost.put(a, new ArrayList<>());
        });
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        continue;
      }
      final String owner = allianceOf.get(t.getOwner().getName());
      if (owner != null) {
        count.merge(owner, 1, Integer::sum);
      }
      OriginalOwnerTracker.getOriginalOwner(t)
          .map(o -> allianceOf.get(o.getName()))
          .filter(original -> !original.equals(owner))
          .ifPresent(original -> lost.get(original).add(t.getName()));
    }
    final JsonObject out = new JsonObject();
    for (final String a : alliances) {
      final JsonObject o = new JsonObject();
      o.addProperty("totale", count.get(a));
      o.add("perse", strings(lost.get(a)));
      out.add(a, o);
    }
    return out;
  }

  /** VCs with enemy land units next to them, or held by the side that did not own them. */
  private static JsonArray contestedVcs(
      final GameState data, final Map<String, String> allianceOf) {
    final JsonArray out = new JsonArray();
    for (final Territory t : data.getMap().getTerritories()) {
      if (vc(t) <= 0) {
        continue;
      }
      final String owner = allianceOf.get(t.getOwner().getName());
      final String original =
          OriginalOwnerTracker.getOriginalOwner(t)
              .map(o -> allianceOf.get(o.getName()))
              .orElse(owner);
      final boolean taken = original != null && !original.equals(owner);
      final boolean pressed =
          data.getMap().getNeighbors(t).stream()
              .anyMatch(
                  n ->
                      n.anyUnitsMatch(
                          Matches.unitIsLand()
                              .and(Matches.unitIsNotInfrastructure())
                              .and(
                                  u ->
                                      data.getRelationshipTracker()
                                          .isAtWar(t.getOwner(), u.getOwner()))));
      if (taken || pressed) {
        final JsonObject o = new JsonObject();
        o.addProperty("territorio", t.getName());
        o.addProperty("di", owner == null ? t.getOwner().getName() : owner);
        if (taken) {
          o.addProperty("in_origine", original);
        }
        o.addProperty("nemici_vicini", pressed);
        out.add(o);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------------- plans

  private static JsonObject plan(final Decisore.Contesto ctx) {
    final Strategia.Piano plan = ctx.piano();
    final JsonObject progress = ctx.storico().getAsJsonObject("avanzamento");
    final JsonObject out = new JsonObject();
    out.addProperty("id", plan.id());
    out.addProperty("nome", plan.nome());
    out.addProperty("fase", plan.fase(ctx.round()).map(Strategia.Fase::label).orElse("nessuna"));
    out.addProperty("stato", progress.get("stato").getAsString());
    out.addProperty("in_vigore_dal_round", ctx.stato().roundPiano());
    out.addProperty("cambi_fatti", ctx.stato().cambi().size());
    final JsonArray milestones = new JsonArray();
    Direttive.array(progress, "tappe")
        .forEach(
            e -> {
              final JsonObject t = e.getAsJsonObject();
              final JsonObject o = new JsonObject();
              o.addProperty("tappa", t.get("tappa").getAsString());
              o.addProperty("stato", t.get("stato").getAsString());
              milestones.add(o);
            });
    out.add("tappe", milestones);
    for (final String key :
        List.of(
            "reddito", "reddito_r1", "reddito_nemico", "bilancio_tuv", "operazioni_in_stallo")) {
      if (progress.has(key)) {
        out.add(key, progress.get(key));
      }
    }
    out.add("ruoli", stringMap(plan.ruoli()));
    return out;
  }

  /**
   * The plan in force and its take-overs with the rules' score; {@code ammesso} = a change to it is
   * allowed now by the guard rails (the rules also want 2 rounds of crisis and a margin).
   */
  private static JsonArray admittedPlans(final Decisore.Contesto ctx) {
    final JsonArray out = new JsonArray();
    final Optional<Strategia.Alleanza> plans = ctx.strategia().alleanza(ctx.alleanza());
    if (plans.isEmpty()) {
      return out;
    }
    final Contromosse.Lettura reading = Contromosse.Lettura.of(ctx.storico());
    final Contromosse.Forze forces = new Contromosse.Forze(ctx.data(), ctx.alleanza());
    final String state = ctx.storico().getAsJsonObject("avanzamento").get("stato").getAsString();
    final List<Strategia.Piano> list = new ArrayList<>();
    list.add(ctx.piano());
    ctx.piano().subentri().forEach(id -> plans.get().piano(id).ifPresent(list::add));
    for (final Strategia.Piano p : list) {
      final boolean current = p.id().equals(ctx.piano().id());
      final Contromosse.Punteggio score =
          Contromosse.score(
              ctx.data(),
              ctx.strategia(),
              p,
              forces,
              reading,
              current ? state : null,
              !current,
              ctx.round());
      final JsonObject o = new JsonObject();
      o.addProperty("id", p.id());
      o.addProperty("nome", p.nome());
      o.addProperty("in_vigore", current);
      o.addProperty("punteggio", Contromosse.round2(score.totale()));
      o.addProperty("tappe", Contromosse.round2(score.tappe()));
      o.addProperty("forze", Contromosse.round2(score.forze()));
      o.addProperty("contrasto", Contromosse.round2(score.contrasto()));
      if (!current) {
        final Optional<String> why = Direttive.cambioNonAmmesso(ctx, p.id());
        o.addProperty("ammesso", why.isEmpty());
        why.ifPresent(w -> o.addProperty("perche_no", w));
      }
      o.add("contrasta", strings(p.contrasta()));
      out.add(o);
    }
    return out;
  }

  private static JsonObject reading(final Decisore.Contesto ctx) {
    final JsonObject out = new JsonObject();
    if (!ctx.storico().has("lettura")) {
      return out;
    }
    final JsonObject r = ctx.storico().getAsJsonObject("lettura");
    out.add("piu_probabile", r.get("piu_probabile"));
    out.add("probabilita_piani", r.get("probabilita"));
    if (r.has("indizi_principali")) {
      out.add("indizi_principali", r.get("indizi_principali"));
    }
    return out;
  }

  // ----------------------------------------------------------------------------- operations

  private static JsonArray operations(
      final Decisore.Contesto ctx, final Map<String, String> allianceOf) {
    final GameState data = ctx.data();
    final JsonObject reports = reports(data.getProperties(), ctx.alleanza());
    final JsonArray out = new JsonArray();
    for (final Strategia.Operazione op : ctx.piano().operazioni()) {
      if (!op.covers(ctx.round())) {
        continue;
      }
      final Set<String> nations = new LinkedHashSet<>(op.nazioni());
      final Set<String> carriers = new LinkedHashSet<>(op.nazioni());
      carriers.addAll(op.vettori());
      final List<Territory> zones = new ArrayList<>();
      op.raccolta()
          .forEach(
              z -> Optional.ofNullable(data.getMap().getTerritoryOrNull(z)).ifPresent(zones::add));
      int ready = 0;
      final Set<Territory> fleetZones = new LinkedHashSet<>(zones);
      for (final Strategia.Porto port : op.porti()) {
        final Territory land = data.getMap().getTerritoryOrNull(port.territorio());
        if (land != null) {
          ready +=
              land.getUnitCollection()
                  .countMatches(
                      Matches.unitIsLand()
                          .and(Matches.unitIsNotInfrastructure())
                          .and(u -> nations.contains(u.getOwner().getName())));
        }
        Optional.ofNullable(data.getMap().getTerritoryOrNull(port.zona()))
            .ifPresent(fleetZones::add);
      }
      int transports = 0;
      int aboard = 0;
      int escort = 0;
      double fleetTuv = 0;
      double strength = 0;
      for (final Territory z : fleetZones) {
        final List<Unit> ts =
            z.getMatches(
                Matches.unitIsSeaTransport().and(u -> carriers.contains(u.getOwner().getName())));
        transports += ts.size();
        for (final Unit t : ts) {
          aboard +=
              (int)
                  t.getTransporting().stream()
                      .filter(u -> nations.contains(u.getOwner().getName()))
                      .count();
        }
        final List<Unit> warships =
            z.getMatches(
                Matches.unitIsSea()
                    .and(Matches.unitIsSeaTransport().negate())
                    .and(u -> ctx.alleanza().equals(allianceOf.get(u.getOwner().getName()))));
        escort += warships.size();
        if (!ts.isEmpty()) {
          fleetTuv += tuvOf(ts) + tuvOf(warships);
          strength = Math.max(strength, enemyStrength(data, z));
        }
      }
      final JsonObject o = new JsonObject();
      o.addProperty("id", op.nome());
      o.add("nazioni", strings(op.nazioni()));
      o.add("bersagli", strings(op.bersagli()));
      o.add("raccolta", strings(op.raccolta()));
      o.addProperty("truppe_pronte_nei_porti", ready);
      o.addProperty("trasporti_in_zona", transports);
      o.addProperty("truppe_a_bordo", aboard);
      o.addProperty("scorta", escort);
      o.addProperty("trasporti_richiesti", op.trasporti());
      o.addProperty("scorta_minima", op.scortaMinima());
      o.addProperty("perdita_attesa_trasporti_stima", Math.round(fleetTuv * strength / 100));
      o.addProperty("in_stallo", StalloOperazioni.inStallo(data.getProperties(), op.nome()));
      if (reports.has(op.nome())) {
        o.add("ultimo_rapporto", reports.get(op.nome()));
      }
      out.add(o);
    }
    return out;
  }

  /** The last report of each operation of {@code alliance} (written by {@link #salvaRapporto}). */
  static JsonObject reports(final GameProperties props, final String alliance) {
    if (props.get(REPORTS_PREFIX + alliance) instanceof String text) {
      try {
        return JsonParser.parseString(text).getAsJsonObject();
      } catch (final RuntimeException e) {
        return new JsonObject();
      }
    }
    return new JsonObject();
  }

  /**
   * Saves the last report of operation {@code op} of {@code alliance} (real non-combat move): what
   * the next input shows as {@code ultimo_rapporto} (win% on the target, hold of the bridgehead).
   */
  public static void salvaRapporto(
      final GameProperties props, final String alliance, final String op, final JsonObject report) {
    final JsonObject all = reports(props, alliance);
    all.add(op, report);
    props.set(REPORTS_PREFIX + alliance, (Serializable) null);
    props.set(REPORTS_PREFIX + alliance, all.toString());
  }

  // -------------------------------------------------------------------------------- threats

  /** The own capitals, factories and VCs with the highest enemy strength (top {@link #MINACCE}). */
  private static JsonArray threats(
      final GameState data, final String alliance, final Map<String, String> allianceOf) {
    final List<JsonObject> list = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !alliance.equals(allianceOf.get(t.getOwner().getName()))) {
        continue;
      }
      final boolean capital =
          TerritoryAttachment.get(t).flatMap(TerritoryAttachment::getCapital).isPresent();
      final boolean factory = t.anyUnitsMatch(Matches.unitCanProduceUnits());
      final boolean victory = vc(t) > 0;
      if (!capital && !factory && !victory) {
        continue;
      }
      final double strength = enemyStrength(data, t);
      if (strength <= 0) {
        continue;
      }
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", t.getName());
      o.addProperty("di", t.getOwner().getName());
      o.addProperty("forza_nemica", Math.round(strength));
      o.addProperty("tipo", capital ? "capitale" : victory ? "vc" : "fabbrica");
      list.add(o);
    }
    list.sort(
        Comparator.comparingDouble((JsonObject o) -> -o.get("forza_nemica").getAsDouble())
            .thenComparing(o -> o.get("territorio").getAsString()));
    final JsonArray out = new JsonArray();
    list.stream().limit(MINACCE).forEach(out::add);
    return out;
  }

  /**
   * Quick estimate of the enemy attack on {@code t} (0-100): enemy land units next to it, mobile
   * land units (2+ moves) two steps away, enemy warships next to a sea zone, enemy planes within 3;
   * against the units of {@code t}'s owner and allies there.
   */
  static double enemyStrength(final GameState data, final Territory t) {
    final GamePlayer owner = t.getOwner();
    final List<Unit> defenders =
        t.getMatches(u -> !data.getRelationshipTracker().isAtWar(owner, u.getOwner()));
    final Predicate<Unit> enemy =
        u -> data.getRelationshipTracker().isAtWar(owner, u.getOwner()) && !u.getOwner().isNull();
    final List<Unit> attackers = new ArrayList<>();
    for (final Territory n : data.getMap().getNeighbors(t, 3)) {
      final int d = data.getMap().getDistance(t, n);
      for (final Unit u : n.getUnits()) {
        if (!enemy.test(u) || Matches.unitIsInfrastructure().test(u)) {
          continue;
        }
        final boolean air = Matches.unitIsAir().test(u);
        final boolean land = Matches.unitIsLand().test(u);
        final boolean sea = Matches.unitIsSea().test(u);
        final int moves = u.getUnitAttachment().getMovement(u.getOwner());
        if ((air && d <= 3)
            || (land && !t.isWater() && !n.isWater() && d <= Math.min(2, moves))
            || (sea && t.isWater() && n.isWater() && d <= Math.min(2, moves))) {
          attackers.add(u);
        }
      }
    }
    if (attackers.isEmpty()) {
      return 0;
    }
    final double v = ProBattleUtils.estimateStrengthDifference(t, attackers, defenders);
    return Math.max(0, Math.min(100, v));
  }

  // --------------------------------------------------------------------------------- events

  /** Battles, conquests and declarations of war since the alliance's previous decision. */
  private static JsonObject events(
      final Decisore.Contesto ctx, final Map<String, String> allianceOf) {
    final GameState data = ctx.data();
    final int round = ctx.round();
    final List<JsonObject> battles = new ArrayList<>();
    final JsonArray conquests = new JsonArray();
    final Map<Integer, BattleRecords> records = data.getBattleRecordsList().getBattleRecordsMap();
    for (final int r : List.of(round - 1, round)) {
      final BattleRecords brs = records.get(r);
      if (brs == null) {
        continue;
      }
      for (final BattleRecord b : BattleRecords.getAllRecords(brs)) {
        final Territory site = field(b, "battleSite");
        final GamePlayer attacker = field(b, "attacker");
        final GamePlayer defender = field(b, "defender");
        final Object result = field(b, "battleResultDescription");
        final Integer lostA = field(b, "attackerLostTuv");
        final Integer lostD = field(b, "defenderLostTuv");
        if (site == null || attacker == null) {
          continue;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("round", r);
        o.addProperty("territorio", site.getName());
        o.addProperty("attaccante", attacker.getName());
        o.addProperty("difensore", defender == null ? "" : defender.getName());
        o.addProperty("tuv_perso_attaccante", lostA == null ? 0 : lostA);
        o.addProperty("tuv_perso_difensore", lostD == null ? 0 : lostD);
        o.addProperty("esito", result == null ? "" : result.toString().toLowerCase(Locale.ROOT));
        battles.add(o);
        if (result == BattleRecord.BattleResultDescription.CONQUERED
            || result == BattleRecord.BattleResultDescription.BLITZED) {
          final JsonObject c = new JsonObject();
          c.addProperty("round", r);
          c.addProperty("territorio", site.getName());
          c.addProperty("da", defender == null ? "" : defender.getName());
          c.addProperty("a", attacker.getName());
          conquests.add(c);
        }
      }
    }
    battles.sort(
        Comparator.comparingInt(
                (JsonObject o) ->
                    -(o.get("tuv_perso_attaccante").getAsInt()
                        + o.get("tuv_perso_difensore").getAsInt()))
            .thenComparing(o -> o.get("territorio").getAsString()));
    final JsonArray top = new JsonArray();
    battles.stream().limit(BATTAGLIE).forEach(top::add);
    final JsonObject out = new JsonObject();
    out.add("battaglie_maggiori", top);
    out.add("conquiste", conquests);
    out.add("guerre_nuove", newWars(ctx, allianceOf));
    return out;
  }

  /** Pairs of nations of the two alliances at war now and not at the previous decision. */
  private static JsonArray newWars(
      final Decisore.Contesto ctx, final Map<String, String> allianceOf) {
    final JsonArray now = wars(ctx.data(), allianceOf);
    final JsonArray storico = ctx.stato().storico();
    final JsonArray before =
        storico.isEmpty() || !storico.get(storico.size() - 1).getAsJsonObject().has("guerre")
            ? null
            : storico.get(storico.size() - 1).getAsJsonObject().getAsJsonArray("guerre");
    final JsonArray out = new JsonArray();
    if (before == null) {
      return out;
    }
    for (final JsonElement w : now) {
      if (!before.contains(w)) {
        out.add(w);
      }
    }
    return out;
  }

  /**
   * The pairs "A-B" of nations of the configurable alliances at war now (remembered in the history
   * entry, key {@code guerre}, for the next round's new wars).
   */
  public static JsonArray guerre(final GameState data) {
    final Map<String, String> allianceOf = new HashMap<>();
    ProPlusSettings.configurableAlliances(data)
        .forEach(
            a ->
                data.getAllianceTracker()
                    .getPlayersInAlliance(a)
                    .forEach(p -> allianceOf.put(p.getName(), a)));
    return wars(data, allianceOf);
  }

  private static JsonArray wars(final GameState data, final Map<String, String> allianceOf) {
    final List<GamePlayer> players =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> allianceOf.containsKey(p.getName()))
            .sorted(Comparator.comparing(GamePlayer::getName))
            .toList();
    final JsonArray out = new JsonArray();
    for (int i = 0; i < players.size(); i++) {
      for (int j = i + 1; j < players.size(); j++) {
        final GamePlayer a = players.get(i);
        final GamePlayer b = players.get(j);
        if (!allianceOf.get(a.getName()).equals(allianceOf.get(b.getName()))
            && data.getRelationshipTracker().isAtWar(a, b)) {
          out.add(a.getName() + "-" + b.getName());
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static <T> T field(final BattleRecord record, final String name) {
    try {
      final Field f = BattleRecord.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(record);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------- tools, guard rails

  private static JsonArray tools(final Decisore.Contesto ctx) {
    final List<String> nations =
        new ArrayList<>(Direttive.allianceNations(ctx.data(), ctx.alleanza()));
    final List<String> zones = new ArrayList<>();
    ProPlusSettings.configurableAlliances(ctx.data()).stream()
        .filter(a -> !a.equals(ctx.alleanza()))
        .flatMap(a -> ctx.strategia().alleanza(a).stream())
        .flatMap(a -> a.piani().stream())
        .flatMap(p -> p.zoneDiPassaggio().stream())
        .filter(z -> !zones.contains(z))
        .forEach(zones::add);
    final JsonArray out = new JsonArray();
    for (final String tool : Tattiche.STRUMENTI) {
      final JsonObject o = new JsonObject();
      o.addProperty("strumento", tool);
      o.add("nazioni", strings(nations));
      o.add("fronti", strings(ctx.strategia().getFronti()));
      if (Tattiche.BLOCCO_NAVALE.equals(tool)) {
        o.add("zone_di_passaggio_nemiche", strings(zones));
      }
      o.addProperty("attiva_dalle_regole", false);
      out.add(o);
    }
    return out;
  }

  private static JsonObject guardRails(final Strategia strategia) {
    final JsonObject o = new JsonObject();
    o.addProperty("guarnigione_capitale", "sempre (minaccia oltre 25% di win nemica)");
    o.addProperty("soglia_minima_attacco", Direttive.SOGLIA_MINIMA_ASSOLUTA);
    o.addProperty("perdita_trasporti_max_TUV_per_turno", Direttive.PERDITA_TRASPORTI_MAX);
    o.addProperty(
        "max_cambi_piano", Math.min(Direttive.MAX_CAMBI, strategia.getParametri().maxCambi()));
    o.addProperty(
        "round_tra_cambi",
        Math.max(Direttive.ROUND_TRA_CAMBI, strategia.getParametri().inerziaRound()));
    o.addProperty("rischio_per_fronte", "-1..+1 rispetto al livello base");
    o.addProperty("peso_bersaglio", "0..20");
    return o;
  }

  // ------------------------------------------------------------------------------- helpers

  private static int vc(final Territory t) {
    return TerritoryAttachment.get(t).map(TerritoryAttachment::getVictoryCity).orElse(0);
  }

  private static double tuvOf(final Collection<Unit> units) {
    double sum = 0;
    for (final Unit u : units) {
      sum += new TuvCostsCalculator().getCostsForTuv(u.getOwner()).getInt(u.getType());
    }
    return sum;
  }

  private static JsonArray strings(final Collection<String> values) {
    final JsonArray a = new JsonArray();
    values.forEach(a::add);
    return a;
  }

  private static JsonObject stringMap(final Map<String, String> map) {
    final JsonObject o = new JsonObject();
    map.forEach(o::addProperty);
    return o;
  }
}
