package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.UnitUtils;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Step R4 block A "ASSE" (switch {@link StrategaInterruttori#ASSEDIO}, off by default, on top of
 * {@link Impegno}):
 *
 * <ol>
 *   <li>A1 Germany: the Russian factories (and the Russian territories on the way: the stages)
 *       before Moscow; Moscow only with the factories taken, or with the calculator at {@code
 *       soglia_mosca} on Moscow in the turn ({@link #bersagli}, {@link #scegli}).
 *   <li>A2 the operation's purchases in the captured factories nearest to Moscow ({@link
 *       #fabbricheCatturate}).
 *   <li>A3 strategic bombing of Moscow's factory while its production is not blocked ({@link
 *       #bombarda}), bombers bought from the operation's share ({@link #bombardieriDaComprare}), an
 *       idle bomber flies to a safe base within range ({@link #nonCombattimento}).
 *   <li>A4 Italy: Egypt and North Africa, then the rest of Africa and east of Egypt; never Russia
 *       while those are open; the operation's PUs to the Africa shuttle (balanced load) or, with
 *       Egypt and North Africa of the Axis, to warships. Gibraltar is NOT in this step.
 * </ol>
 *
 * <p>Ties between targets of the same level: the gain per round of rule C ({@link Reddito}).
 */
public final class Assedio {
  private Assedio() {}

  public static final String KEY_STATO = "Pro+ stratega r4: ";

  /** The bombing raid planned in the combat move, by "nation|round" (simulation or real). */
  private static final Map<String, JsonObject> RAID = new ConcurrentHashMap<>();

  // ============================================================================ pure rules

  /** One target of the choice with its level (0 first) and the gain of rule C. */
  record Scelta(
      String territorio, int livello, Reddito.Guadagno guadagno, boolean fattibile, int tappe) {}

  /**
   * The choice: the lowest level; within it the highest gain per round (C2), then feasible, then
   * fewest stages, then the name.
   */
  static Optional<Scelta> scegli(final List<Scelta> all) {
    return all.stream()
        .min(
            Comparator.comparingInt(Scelta::livello)
                .thenComparingInt(s -> -s.guadagno().totale())
                .thenComparing(s -> !s.fattibile())
                .thenComparingInt(Scelta::tappe)
                .thenComparing(Scelta::territorio));
  }

  /**
   * A1: the German levels: Moscow at level 0 when the calculator wins >= the threshold this turn,
   * the Russian factories still the enemy's at level 1, Moscow at level 1 only once no factory is
   * left. Returns territory name → level.
   */
  static Map<String, Integer> livelliGermania(
      final List<String> fabbricheNemiche,
      final boolean moscaNemica,
      final double vittoriaMosca,
      final double soglia,
      final String mosca) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    if (moscaNemica && vittoriaMosca >= soglia) {
      out.put(mosca, 0);
    }
    fabbricheNemiche.forEach(f -> out.put(f, 1));
    if (moscaNemica && fabbricheNemiche.isEmpty()) {
      out.putIfAbsent(mosca, 1);
    }
    return out;
  }

  /** A4: the Italian levels: 1 Egypt and North Africa, 2 the rest of Africa and east of Egypt. */
  static Map<String, Integer> livelliItalia(
      final List<String> livello1Nemici, final List<String> livello2Nemici) {
    final Map<String, Integer> out = new LinkedHashMap<>();
    livello1Nemici.forEach(t -> out.put(t, 1));
    livello2Nemici.forEach(t -> out.putIfAbsent(t, 2));
    return out;
  }

  /** A3: bombers needed so that the expected damage per turn covers the production left. */
  static int bombardieriNecessari(final int produzioneMosca, final double dannoAtteso) {
    if (produzioneMosca <= 0 || dannoAtteso <= 0) {
      return 0;
    }
    return (int) Math.ceil(produzioneMosca / dannoAtteso);
  }

  /** A3: a raid is ordered when Moscow still produces and at least one bomber can go and land. */
  static boolean ordinaBombardamento(final int produzioneMosca, final int raggiungibili) {
    return produzioneMosca > 0 && raggiungibili > 0;
  }

  // ========================================================================== entry points

  /** The switch is on and the nation plays block A (Germans or Italians of the strategist). */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    if (player == null || !Impegno.attivo(data, player)) {
      return false;
    }
    final ConfigR4 cfg = ConfigR4.get();
    if (!player.getName().equals(cfg.nazioneAssedio())
        && !player.getName().equals(cfg.nazioneItalia())) {
      return false;
    }
    return ProPlusSettings.allianceOf(data, player)
        .map(a -> StrategaInterruttori.ASSEDIO.acceso(data.getProperties(), a))
        .orElse(false);
  }

  static boolean germania(final GameState data, final GamePlayer player) {
    return attivo(data, player) && player.getName().equals(ConfigR4.get().nazioneAssedio());
  }

  public static boolean italia(final GameState data, final GamePlayer player) {
    return attivo(data, player) && player.getName().equals(ConfigR4.get().nazioneItalia());
  }

  /**
   * A1/A4: the targets with their levels (empty = not this block's: the rule of R2b applies).
   * Records nothing; {@link #registraScelta} records the choice.
   */
  static Optional<Map<Territory, Integer>> bersagli(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c) {
    final GameState data = proData.getData();
    final ConfigR4 cfg = ConfigR4.get();
    final Map<String, Integer> levels;
    if (germania(data, player)) {
      final List<String> factories = nemici(data, player, c, cfg.fabbricheRusse());
      final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
      final boolean moscowEnemy = moscow != null && nemico(data, player, c, moscow);
      final double win =
          moscowEnemy && !factories.isEmpty() ? vittoriaMosca(proData, calc, player) : 0;
      levels = livelliGermania(factories, moscowEnemy, win, cfg.sogliaMosca(), cfg.capitaleRussa());
    } else if (italia(data, player)) {
      levels =
          livelliItalia(
              nemici(data, player, c, cfg.italiaLivello1()),
              nemici(data, player, c, cfg.italiaLivello2()));
    } else {
      return Optional.empty();
    }
    if (levels.isEmpty()) {
      return Optional.empty();
    }
    final Map<Territory, Integer> out = new LinkedHashMap<>();
    levels.forEach(
        (n, l) -> {
          final Territory t = data.getMap().getTerritoryOrNull(n);
          if (t != null) {
            out.put(t, l);
          }
        });
    return Optional.of(out);
  }

  /** A1: Moscow at the threshold this turn while factories are left: the target changes now. */
  static boolean moscaOra(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      @Nullable final String obiettivo) {
    final GameState data = proData.getData();
    if (!germania(data, player)) {
      return false;
    }
    final ConfigR4 cfg = ConfigR4.get();
    if (cfg.capitaleRussa().equals(obiettivo)) {
      return false;
    }
    final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
    if (moscow == null || !nemico(data, player, c, moscow)) {
      return false;
    }
    return !nemici(data, player, c, cfg.fabbricheRusse()).isEmpty()
        && vittoriaMosca(proData, calc, player) >= cfg.sogliaMosca();
  }

  /** The decision of A1/A4 in the register (type obiettivo_assedio or italia_africa). */
  static void registraScelta(
      final GameState data,
      final GamePlayer player,
      final List<Scelta> all,
      final Scelta chosen,
      final Map<Territory, Integer> levels) {
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      final boolean germany = germania(data, player);
      final String type = germany ? "obiettivo_assedio" : "italia_africa";
      final JsonArray alt = new JsonArray();
      for (final Scelta s : all) {
        final JsonObject a = s.guadagno().json();
        a.addProperty("territorio", s.territorio());
        a.addProperty("livello", s.livello());
        a.addProperty("fattibile", s.fattibile());
        a.addProperty("tappe", s.tappe());
        alt.add(a);
      }
      final ConfigR4 cfg = ConfigR4.get();
      final String code;
      final String why;
      if (germany && chosen.livello() == 0) {
        code = "MOSCA_70";
        why = "vittoria su Mosca >= " + Math.round(cfg.sogliaMosca()) + "% nel turno";
      } else if (germany && chosen.territorio().equals(cfg.capitaleRussa())) {
        code = "MOSCA_FABBRICHE_PRESE";
        why = "fabbriche russe prese: Mosca";
      } else if (all.stream().filter(s -> s.livello() == chosen.livello()).count() > 1) {
        code = "GUADAGNO_MAGGIORE";
        why =
            "livello "
                + chosen.livello()
                + ", guadagno per round piu' alto ("
                + chosen.guadagno().totale()
                + " PU)";
      } else if (germany) {
        code = "FABBRICHE_PRIMA";
        why = "fabbriche russe prima di Mosca";
      } else {
        code = chosen.livello() == 1 ? "AFRICA_PRIMA" : "EST_EGITTO";
        why =
            chosen.livello() == 1
                ? "Egitto e Nord Africa prima di tutto"
                : "resto dell'Africa e a est dell'Egitto";
      }
      final JsonObject par = new JsonObject();
      par.addProperty("soglia_mosca", cfg.sogliaMosca());
      final JsonObject lv = new JsonObject();
      levels.forEach((t, l) -> lv.addProperty(t.getName(), l));
      par.add("livelli", lv);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), type),
              round,
              player.getName(),
              type,
              alt,
              chosen.territorio(),
              code,
              why,
              par));
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, registro della scelta: " + e);
    }
  }

  // ------------------------------------------------------------------------ A2 factories

  /** A2: the own factories in the captured Russian territories, nearest to Moscow first. */
  static List<Territory> fabbricheCatturate(final GameState data, final GamePlayer player) {
    final ConfigR4 cfg = ConfigR4.get();
    final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
    final List<Territory> out = new ArrayList<>();
    final List<String> names = new ArrayList<>(cfg.fabbricheRusse());
    names.add(cfg.capitaleRussa());
    for (final String n : names) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && t.isOwnedBy(player) && t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
        out.add(t);
      }
    }
    out.sort(
        Comparator.comparingInt(
                (Territory t) ->
                    moscow == null ? 0 : Math.max(0, data.getMap().getDistance(t, moscow)))
            .thenComparing(Territory::getName));
    return out;
  }

  /** The units a factory territory can still produce this turn (capacity minus damage). */
  static int capacita(final Territory t) {
    int best = 0;
    for (final Unit u : t.getMatches(Matches.unitCanProduceUnits())) {
      best = Math.max(best, UnitUtils.getHowMuchCanUnitProduce(u, t, true, true));
    }
    return best;
  }

  /**
   * A2: the factory of the operation's orders (the captured one nearest to Moscow, the others after
   * it in {@code order.fabbriche_extra}) and the register row; empty without one.
   */
  static Optional<Territory> fabbricaOperazione(
      final GameState data,
      final GamePlayer player,
      final JsonObject order,
      final List<String> lines) {
    if (!germania(data, player)) {
      return Optional.empty();
    }
    try {
      final List<Territory> captured = fabbricheCatturate(data, player);
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
      final JsonArray alt = new JsonArray();
      for (final Territory t : captured) {
        final JsonObject a = new JsonObject();
        a.addProperty("territorio", t.getName());
        a.addProperty("distanza_mosca", moscow == null ? -1 : data.getMap().getDistance(t, moscow));
        a.addProperty("capacita", capacita(t));
        alt.add(a);
      }
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "rinforzo_fabbrica");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "rinforzo_fabbrica",
              alt,
              captured.isEmpty() ? "" : captured.get(0).getName(),
              captured.isEmpty() ? "NESSUNA_FABBRICA_CATTURATA" : "FABBRICA_CATTURATA_VICINA",
              captured.isEmpty()
                  ? "nessuna fabbrica russa catturata: fabbrica propria piu' vicina alla tappa"
                  : "fabbriche catturate piu' vicine a Mosca, fino alla capacita'",
              new JsonObject()));
      if (captured.isEmpty()) {
        salva(data.getProperties(), alliance, st);
        return Optional.empty();
      }
      final JsonObject open = new JsonObject();
      open.addProperty("id", id);
      open.addProperty("round", round);
      st.add("rinforzo_aperto", open);
      salva(data.getProperties(), alliance, st);
      final JsonArray extra = new JsonArray();
      captured.stream().skip(1).forEach(t -> extra.add(t.getName()));
      order.add("fabbriche_extra", extra);
      lines.add(
          "  R4 rinforzi: fabbriche catturate "
              + String.join(", ", captured.stream().map(Territory::getName).toList())
              + " (la piu' vicina a Mosca prima)");
      return Optional.of(captured.get(0));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, fabbrica dell'operazione: " + e);
      return Optional.empty();
    }
  }

  /** A2: outcome of the reinforcement row: units placed per captured factory. */
  static void esitoRinforzo(
      final GameState data,
      final GamePlayer player,
      final Map<String, Map<String, Integer>> placed) {
    if (!germania(data, player)) {
      return;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      if (!st.has("rinforzo_aperto")) {
        return;
      }
      final JsonObject open = st.getAsJsonObject("rinforzo_aperto");
      final int round = data.getSequence().getRound();
      st.remove("rinforzo_aperto");
      if (open.get("round").getAsInt() == round) {
        final Set<String> captured = new HashSet<>();
        fabbricheCatturate(data, player).forEach(t -> captured.add(t.getName()));
        final JsonObject x = new JsonObject();
        int n = 0;
        final JsonObject per = new JsonObject();
        for (final var e : placed.entrySet()) {
          final int k = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
          per.addProperty(e.getKey(), k);
          if (captured.contains(e.getKey())) {
            n += k;
          }
        }
        x.add("piazzate_per_territorio", per);
        x.addProperty("unita_nelle_fabbriche_catturate", n);
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(
                open.get("id").getAsString(), round, player.getName(), "rinforzo_fabbrica", x));
      }
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, esito dei rinforzi: " + e);
    }
  }

  // --------------------------------------------------------------------------- A3 bombing

  @Nullable
  static Unit fabbricaMosca(final GameState data, final GamePlayer player) {
    final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
    if (moscow == null) {
      return null;
    }
    return moscow
        .getMatches(Matches.unitCanProduceUnitsAndCanBeDamaged().and(Matches.enemyUnit(player)))
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Moscow's production left this turn (0 = blocked or not the enemy's). */
  static int produzioneMosca(final GameState data, final GamePlayer player) {
    final Unit f = fabbricaMosca(data, player);
    if (f == null) {
      return 0;
    }
    final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
    return UnitUtils.getHowMuchCanUnitProduce(f, moscow, true, true);
  }

  /**
   * A3: the own territory from which a bomber reaches Moscow and comes back (air distance at most
   * half its movement), safe (realistic counterattack <= {@code soglia_base_sicura}), nearest to
   * Moscow; empty if none.
   */
  static Optional<Territory> baseBombardieri(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c) {
    final GameState data = proData.getData();
    final ConfigR4 cfg = ConfigR4.get();
    final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
    final var type = data.getUnitTypeList().getUnitType(cfg.bombardamento().bombardiere());
    if (moscow == null || type.isEmpty()) {
      return Optional.empty();
    }
    final int half = type.get().getUnitAttachment().getMovement(player) / 2;
    final List<Territory> bases = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !t.isOwnedBy(player) || t.equals(moscow)) {
        continue;
      }
      final int d = data.getMap().getDistance(t, moscow);
      if (d >= 1 && d <= half) {
        bases.add(t);
      }
    }
    bases.sort(
        Comparator.comparingInt((Territory t) -> data.getMap().getDistance(t, moscow))
            .thenComparing(Territory::getName));
    for (final Territory t : bases) {
      final List<Unit> counter = c.contrattacco(t);
      if (counter.isEmpty()) {
        return Optional.of(t);
      }
      final List<Unit> defenders = t.getMatches(Matches.isUnitAllied(player));
      if (defenders.isEmpty()) {
        continue;
      }
      final double w =
          calc.calculateBattleResults(proData, t, counter, defenders, List.of()).getWinPercentage();
      if (w <= cfg.bombardamento().sogliaBaseSicura()) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /**
   * A3 purchase: bombers (and escorts if Moscow has interceptors) to buy from the operation's
   * share: enough bombers for the expected damage to cover Moscow's production, at most {@code
   * max_comprati_per_turno}, only with a base within range. Writes the register row; returns type →
   * count.
   */
  static Map<String, Integer> bombardieriDaComprare(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final int pu,
      final List<String> lines) {
    final GameState data = proData.getData();
    if (!germania(data, player)) {
      return Map.of();
    }
    final ConfigR4 cfg = ConfigR4.get();
    final ConfigR4.Bombardamento b = cfg.bombardamento();
    final Map<String, Integer> out = new LinkedHashMap<>();
    try {
      final int production = produzioneMosca(data, player);
      final int needed = bombardieriNecessari(production, b.dannoAtteso());
      final List<Unit> own = proprie(data, player, b.bombardiere());
      // the bombers that reach Moscow this turn stay out of Hard's attacks (the raid comes first)
      final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
      final List<Unit> reach =
          production <= 0 || moscow == null
              ? List.of()
              : raggiungibili(proData, player, moscow, b.bombardiere(), Set.of());
      final List<Unit> hold = new ArrayList<>(reach.subList(0, Math.min(needed, reach.size())));
      // with a base in range every bomber is the siege's: Hard would spend them in its attacks
      // before they reach the base (seen in the local test games)
      final Optional<Territory> base = baseBombardieri(proData, calc, player, c);
      if (production > 0 && moscow != null && base.isPresent()) {
        own.stream().filter(u -> !hold.contains(u)).forEach(hold::add);
      }
      if (!hold.isEmpty() && intercettori(data, player) > 0) {
        final List<Unit> fighters = raggiungibili(proData, player, moscow, b.scorta(), Set.of());
        fighters.removeIf(u -> !u.getUnitAttachment().canEscort());
        hold.addAll(fighters.subList(0, Math.min(intercettori(data, player), fighters.size())));
      }
      final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
      int left = pu;
      String code;
      String why;
      if (production <= 0) {
        code = "DANNO_MASSIMO";
        why = "Mosca non produce (danno al massimo o non nemica)";
      } else if (base.isEmpty()) {
        code = "NON_RAGGIUNGIBILE";
        why = "nessuna base sicura entro meta' movimento da Mosca";
      } else {
        final int toBuy = Math.min(b.maxComprati(), Math.max(0, needed - own.size()));
        final int cost = costs.getOrDefault(b.bombardiere(), 0);
        for (int i = 0; i < toBuy && cost > 0 && cost <= left; i++) {
          out.merge(b.bombardiere(), 1, Integer::sum);
          left -= cost;
        }
        final int interceptors = intercettori(data, player);
        final int escorts = proprie(data, player, b.scorta()).size();
        final int escortCost = costs.getOrDefault(b.scorta(), 0);
        final int escortsToBuy =
            Math.min(b.maxScorteComprate(), Math.max(0, interceptors - escorts));
        for (int i = 0; i < escortsToBuy && escortCost > 0 && escortCost <= left; i++) {
          out.merge(b.scorta(), 1, Integer::sum);
          left -= escortCost;
        }
        code = out.isEmpty() ? "BOMBARDA" : "COMPRA_BOMBARDIERI";
        why =
            String.format(
                Locale.ROOT,
                "produzione di Mosca %d, danno atteso %.2f per bombardiere -> %d necessari, %d"
                    + " posseduti, intercettori %d",
                production,
                b.dannoAtteso(),
                needed,
                own.size(),
                interceptors);
      }
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      final JsonObject held = new JsonObject();
      held.addProperty("round", round);
      final JsonArray heldIds = new JsonArray();
      hold.forEach(u -> heldIds.add(u.getId().toString()));
      held.add("ids", heldIds);
      st.add("trattenuti", held);
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("bombardieri_raggiungibili_ora", reach.size());
      a.addProperty("produzione_mosca", production);
      a.addProperty("bombardieri_necessari", needed);
      a.addProperty("bombardieri_posseduti", own.size());
      a.addProperty("base", base.map(Territory::getName).orElse(""));
      a.addProperty("intercettori_mosca", intercettori(data, player));
      a.addProperty("pu_operazione", pu);
      alt.add(a);
      final JsonObject choice = new JsonObject();
      out.forEach(choice::addProperty);
      final JsonObject par = new JsonObject();
      par.addProperty("danno_atteso_per_bombardiere", b.dannoAtteso());
      par.addProperty("fase", "acquisto");
      par.addProperty("pu_spesi", pu - left);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), "bombardamento"),
              round,
              player.getName(),
              "bombardamento",
              alt,
              choice.toString(),
              code,
              why,
              par));
      salva(data.getProperties(), alliance, st);
      lines.add(
          "  R4 bombardamento (acquisto): " + why + " -> " + (out.isEmpty() ? "niente" : out));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, acquisto dei bombardieri: " + e);
    }
    return out;
  }

  /** A3: the bombers (and escorts) kept out of Hard's attacks this round for the raid. */
  public static Set<Unit> trattenute(final GameState data, final GamePlayer player) {
    if (!germania(data, player)) {
      return Set.of();
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = stato(data.getProperties(), alliance);
    if (!st.has("trattenuti")
        || st.getAsJsonObject("trattenuti").get("round").getAsInt()
            != data.getSequence().getRound()) {
      return Set.of();
    }
    final Set<String> ids = new HashSet<>();
    st.getAsJsonObject("trattenuti").getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
    final Set<Unit> out = new LinkedHashSet<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream()
          .filter(u -> ids.contains(u.getId().toString()))
          .forEach(out::add);
    }
    return out;
  }

  private static List<Unit> proprie(
      final GameState data, final GamePlayer player, final String type) {
    final List<Unit> out = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritories()) {
      t.getUnitCollection().stream()
          .filter(u -> u.isOwnedBy(player) && u.getType().getName().equals(type))
          .forEach(out::add);
    }
    return out;
  }

  private static int intercettori(final GameState data, final GamePlayer player) {
    final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
    return moscow == null
        ? 0
        : moscow.getMatches(Matches.enemyUnit(player).and(Matches.unitCanIntercept())).size();
  }

  /**
   * A3 combat move (after Hard chose its attacks): the raid on Moscow's factory with the bombers
   * not used by Hard that reach it and can land afterwards, as many as needed; escorts if Moscow
   * has interceptors. Nothing if Moscow does not produce.
   */
  public static void bombarda(final ProData proData, final Map<Territory, ProTerritory> attackMap) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (player == null || !germania(data, player)) {
      return;
    }
    try {
      final ConfigR4 cfg = ConfigR4.get();
      final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
      final Unit factory = fabbricaMosca(data, player);
      final int round = data.getSequence().getRound();
      final JsonObject raid = new JsonObject();
      raid.addProperty("round", round);
      if (moscow == null || factory == null) {
        return;
      }
      final int production = produzioneMosca(data, player);
      final Set<Unit> committed = new HashSet<>();
      for (final ProTerritory p : attackMap.values()) {
        committed.addAll(p.getUnits());
        committed.addAll(p.getBombers());
        committed.addAll(p.getAmphibAttackMap().keySet());
        p.getAmphibAttackMap().values().forEach(committed::addAll);
        committed.addAll(p.getBombardTerritoryMap().keySet());
      }
      final List<Unit> bombers =
          raggiungibili(proData, player, moscow, cfg.bombardamento().bombardiere(), committed);
      final int needed = bombardieriNecessari(production, cfg.bombardamento().dannoAtteso());
      final List<Unit> chosen = new ArrayList<>();
      final List<Unit> escorts = new ArrayList<>();
      if (ordinaBombardamento(production, bombers.size())) {
        chosen.addAll(bombers.subList(0, Math.min(needed, bombers.size())));
        final int interceptors = intercettori(data, player);
        if (interceptors > 0) {
          final List<Unit> fighters =
              raggiungibili(proData, player, moscow, cfg.bombardamento().scorta(), committed);
          fighters.removeIf(u -> !u.getUnitAttachment().canEscort());
          escorts.addAll(fighters.subList(0, Math.min(interceptors, fighters.size())));
        }
        final ProTerritory target =
            attackMap.computeIfAbsent(moscow, t -> new ProTerritory(t, proData));
        target.getBombers().addAll(chosen);
        target.getBombers().addAll(escorts);
      }
      raid.addProperty("produzione_mosca", production);
      raid.addProperty("danno_prima", factory.getUnitDamage());
      raid.addProperty("bombardieri_raggiungibili", bombers.size());
      raid.addProperty("bombardieri_necessari", needed);
      raid.addProperty("intercettori", intercettori(data, player));
      final JsonArray ids = new JsonArray();
      chosen.forEach(u -> ids.add(u.getId().toString()));
      raid.add("bombardieri", ids);
      final JsonArray eids = new JsonArray();
      escorts.forEach(u -> eids.add(u.getId().toString()));
      raid.add("scorta", eids);
      raid.addProperty("fabbrica", factory.getId().toString());
      RAID.put(player.getName() + "|" + round, raid);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, bombardamento: " + e);
    }
  }

  /** Own units of {@code type} not used by Hard that reach {@code target} and can land after. */
  private static List<Unit> raggiungibili(
      final ProData proData,
      final GamePlayer player,
      final Territory target,
      final String type,
      final Set<Unit> committed) {
    final GameState data = proData.getData();
    final List<Unit> out = new ArrayList<>();
    final Map<Unit, Integer> length = new LinkedHashMap<>();
    for (final Territory from : data.getMap().getTerritories()) {
      for (final Unit u : from.getUnitCollection()) {
        if (!u.isOwnedBy(player)
            || !u.getType().getName().equals(type)
            || committed.contains(u)
            || u.getMovementLeft().signum() <= 0
            || from.equals(target)) {
          continue;
        }
        final Optional<Route> r =
            data.getMap()
                .getRouteForUnit(
                    from,
                    target,
                    ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, true),
                    u,
                    player);
        if (r.isEmpty()) {
          continue;
        }
        final int left = u.getMovementLeft().intValue() - r.get().numberOfSteps();
        if (left < 1 || !puoAtterrare(data, player, target, left)) {
          continue;
        }
        out.add(u);
        length.put(u, r.get().numberOfSteps());
      }
    }
    out.sort(
        Comparator.comparingInt((Unit u) -> length.get(u))
            .thenComparing(u -> u.getId().toString()));
    return out;
  }

  private static boolean puoAtterrare(
      final GameState data, final GamePlayer player, final Territory from, final int range) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || t.equals(from)) {
        continue;
      }
      if (Matches.airCanLandOnThisAlliedNonConqueredLandTerritory(player).test(t)) {
        final int d = data.getMap().getDistance(from, t);
        if (d >= 1 && d <= range) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Non-combat move (real board, before Hard's): the outcome of the raid (damage done, planes
   * lost), the Russian units on the map (for Moscow's production in the next Russian turn), the
   * idle bombers to a safe base within range. Returns the bombers moved (Hard leaves them).
   */
  static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final List<String> lines) {
    final GameState data = proData.getData();
    final Set<Unit> held = new LinkedHashSet<>();
    if (!germania(data, player)) {
      return held;
    }
    try {
      final ConfigR4 cfg = ConfigR4.get();
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      final JsonObject raid = RAID.remove(player.getName() + "|" + round);
      if (raid != null && raid.get("round").getAsInt() == round) {
        final int planes = raid.getAsJsonArray("bombardieri").size();
        final String code =
            raid.get("produzione_mosca").getAsInt() <= 0
                ? "DANNO_MASSIMO"
                : planes > 0
                    ? "BOMBARDA"
                    : raid.get("bombardieri_raggiungibili").getAsInt() == 0
                        ? "NON_RAGGIUNGIBILE"
                        : "NESSUN_BOMBARDIERE";
        final JsonArray alt = new JsonArray();
        final JsonObject a = new JsonObject();
        for (final String k :
            List.of(
                "produzione_mosca",
                "danno_prima",
                "bombardieri_raggiungibili",
                "bombardieri_necessari",
                "intercettori")) {
          a.add(k, raid.get(k));
        }
        alt.add(a);
        final JsonObject par = new JsonObject();
        par.addProperty("fase", "combattimento");
        par.addProperty("scorta", raid.getAsJsonArray("scorta").size());
        final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "bombardamento");
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.decisione(
                id,
                round,
                player.getName(),
                "bombardamento",
                alt,
                String.valueOf(planes),
                code,
                planes + " bombardieri, " + raid.getAsJsonArray("scorta").size() + " di scorta",
                par));
        if (planes > 0) {
          final Unit factory = fabbricaMosca(data, player);
          // the raid's planes are still over Moscow before landing: the survivors (the plan made
          // on a copy at the purchase moves units of the same type, not always the same ids)
          final Territory over = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
          final ConfigR4.Bombardamento b = cfg.bombardamento();
          final long bombersThere =
              over == null
                  ? 0
                  : over.getUnitCollection().stream()
                      .filter(
                          u -> u.isOwnedBy(player) && u.getType().getName().equals(b.bombardiere()))
                      .count();
          final long escortsThere =
              over == null
                  ? 0
                  : over.getUnitCollection().stream()
                      .filter(u -> u.isOwnedBy(player) && u.getType().getName().equals(b.scorta()))
                      .count();
          final int lost = (int) Math.max(0, planes - bombersThere);
          final int lostEscort =
              (int) Math.max(0, raid.getAsJsonArray("scorta").size() - escortsThere);
          final JsonObject x = new JsonObject();
          x.addProperty("bombardieri", planes);
          x.addProperty("scorta", raid.getAsJsonArray("scorta").size());
          x.addProperty(
              "danno_fatto",
              factory == null ? -1 : factory.getUnitDamage() - raid.get("danno_prima").getAsInt());
          x.addProperty("danno_ora", factory == null ? -1 : factory.getUnitDamage());
          x.addProperty("bombardieri_persi", lost);
          x.addProperty("scorta_persa", lostEscort);
          RegistroDecisioni.scrivi(
              data, RegistroDecisioni.esito(id, round, player.getName(), "bombardamento", x));
          lines.add("  R4 bombardamento di Mosca: " + x);
          final JsonObject open = new JsonObject();
          open.addProperty("id", id);
          open.addProperty("round", round);
          st.add("produzione_aperta", open);
        }
      }
      // Russian units on the map now: the new ones in Moscow at the next German turn = produced
      final JsonArray russians = new JsonArray();
      final Territory moscow = data.getMap().getTerritoryOrNull(cfg.capitaleRussa());
      if (moscow != null) {
        final GamePlayer owner = data.getPlayerList().getPlayerId("Russians");
        for (final Territory t : data.getMap().getTerritories()) {
          t.getUnitCollection().stream()
              .filter(u -> owner != null && u.isOwnedBy(owner))
              .forEach(u -> russians.add(u.getId().toString()));
        }
      }
      st.add("russi_fine_turno", russians);
      // idle bombers to a safe base within range of Moscow
      final Optional<Territory> base = baseBombardieri(proData, calc, player, c);
      int moved = 0;
      if (base.isPresent()) {
        for (final Unit u : proprie(data, player, cfg.bombardamento().bombardiere())) {
          final Territory from = luogo(data, u);
          if (from == null
              || from.equals(base.get())
              || Matches.unitHasMoved().test(u)
              || data.getMap().getDistance(from, moscow)
                  <= data.getMap().getDistance(base.get(), moscow)) {
            continue;
          }
          final Optional<Route> r =
              data.getMap()
                  .getRouteForUnit(
                      from,
                      base.get(),
                      ProMatches.territoryCanMoveAirUnits(data, player, false),
                      u,
                      player);
          if (r.isEmpty() || r.get().numberOfSteps() > u.getMovementLeft().intValue()) {
            continue;
          }
          if (moveDel.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
            held.add(u);
            moved++;
          }
        }
      }
      if (moved > 0) {
        lines.add("  R4 bombardieri alla base " + base.get().getName() + ": " + moved);
      }
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, non combattimento dell'Asse: " + e);
    }
    return held;
  }

  /** German purchase: the units Moscow produced since the last German turn (raid's outcome). */
  static void produzioneMoscaEsito(final GameState data, final GamePlayer player) {
    if (!germania(data, player)) {
      return;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      if (!st.has("russi_fine_turno")) {
        return;
      }
      final Set<String> before = new HashSet<>();
      st.getAsJsonArray("russi_fine_turno").forEach(e -> before.add(e.getAsString()));
      st.remove("russi_fine_turno");
      final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
      final GamePlayer owner = data.getPlayerList().getPlayerId("Russians");
      int produced = 0;
      if (moscow != null && owner != null) {
        produced =
            (int)
                moscow.getUnitCollection().stream()
                    .filter(u -> u.isOwnedBy(owner) && !before.contains(u.getId().toString()))
                    .filter(Matches.unitIsNotInfrastructure())
                    .count();
      }
      if (st.has("produzione_aperta")) {
        final JsonObject open = st.getAsJsonObject("produzione_aperta");
        st.remove("produzione_aperta");
        final JsonObject x = new JsonObject();
        x.addProperty("unita_prodotte_da_mosca", produced);
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(
                open.get("id").getAsString(),
                data.getSequence().getRound(),
                player.getName(),
                "bombardamento",
                x));
      }
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, produzione di Mosca: " + e);
    }
  }

  // ---------------------------------------------------------------------------- A4 Italy

  /** A4: Egypt and North Africa (level 1) are all of the Axis now. */
  static boolean livello1Asse(final GameState data, final GamePlayer player) {
    for (final String n : ConfigR4.get().italiaLivello1()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && !Matches.isTerritoryAllied(player).test(t)) {
        return false;
      }
    }
    return true;
  }

  /** A4: the Axis base in Africa where the Italian shuttle unloads (first of the list). */
  @Nullable
  public static Territory baseAfrica(final GameState data, final GamePlayer player) {
    for (final String n : ConfigR4.get().basiAfricaItalia()) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && Matches.isTerritoryAllied(player).test(t) && !t.isWater()) {
        return t;
      }
    }
    return null;
  }

  /**
   * A4 purchase: the operation's PUs to warships at the Italian port (Egypt and North Africa of the
   * Axis) or to the Africa shuttle with the balanced load. Writes the order and the register row;
   * returns the PUs ordered.
   */
  static int acquistiItalia(
      final GameState data,
      final GamePlayer player,
      final int pu,
      final Programmi.Turno programs,
      final int garrison,
      final JsonObject turn,
      final JsonObject order,
      final List<String> lines) {
    if (!italia(data, player)) {
      return 0;
    }
    final ConfigR4 cfg = ConfigR4.get();
    final Map<String, Integer> costs = Impegno.costiAcquisto(data, player);
    final boolean fleet = livello1Asse(data, player);
    final Map<String, Integer> mix;
    final Territory port = data.getMap().getTerritoryOrNull(cfg.portoItalia());
    if (fleet) {
      mix = Impegno.composizione(pu, cfg.tipiFlottaItalia(), costs);
    } else if (ConvoglioItalia.attivo(data, player)) {
      // step R6c (E4): warships until the convoy's route is safe, then complete pairs
      mix = ConvoglioItalia.acquisti(programs, pu, garrison, turn);
    } else {
      mix = programs.acquisti(pu, garrison, turn);
    }
    int spent = 0;
    for (final var e : mix.entrySet()) {
      spent += costs.getOrDefault(e.getKey(), 0) * e.getValue();
    }
    if (!mix.isEmpty() && port != null && port.isOwnedBy(player)) {
      final JsonObject m = new JsonObject();
      mix.forEach(m::addProperty);
      order.add("tipi_programma", m);
      order.addProperty("fabbrica_programma", port.getName());
    } else {
      spent = 0;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("pu_operazione", pu);
      a.addProperty("livello1_asse", fleet);
      final Territory base = baseAfrica(data, player);
      a.addProperty("base_africa", base == null ? "" : base.getName());
      a.addProperty("pu_ordinati", spent);
      final JsonObject m = new JsonObject();
      mix.forEach(m::addProperty);
      a.add("ordine", m);
      a.addProperty("pu_navi", fleet ? spent : 0);
      alt.add(a);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), "italia_africa"),
              round,
              player.getName(),
              "italia_africa",
              alt,
              fleet ? "flotta" : "spola_africa",
              fleet ? "FLOTTA" : "SPOLA_AFRICA",
              fleet
                  ? "Egitto e Nord Africa dell'Asse: navi da guerra per il Mediterraneo"
                  : "unita' di terra e trasporti per l'Africa (carico bilanciato)",
              new JsonObject()));
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R4, acquisti italiani: " + e);
    }
    lines.add(
        "  R4 Italia: "
            + (fleet ? "FLOTTA " : "SPOLA AFRICA ")
            + (mix.isEmpty() ? "niente" : mix.toString())
            + " ("
            + spent
            + " PU di "
            + pu
            + ")");
    return spent;
  }

  // ------------------------------------------------------------------------------ helpers

  private static List<String> nemici(
      final GameState data,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final List<String> names) {
    final List<String> out = new ArrayList<>();
    for (final String n : names) {
      final Territory t = data.getMap().getTerritoryOrNull(n);
      if (t != null && nemico(data, player, c, t)) {
        out.add(n);
      }
    }
    return out;
  }

  private static boolean nemico(
      final GameState data,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final Territory t) {
    return !t.isWater()
        && data.getRelationshipTracker().isAtWar(player, t.getOwner())
        && (c.nemico(t.getOwner()) || ProPlusSettings.allianceOf(data, t.getOwner()).isEmpty());
  }

  /** Win% on Moscow with every unit in range (Hard's attack options) and the real defenders. */
  static double vittoriaMosca(
      final ProData proData, final ProOddsCalculator calc, final GamePlayer player) {
    final GameState data = proData.getData();
    final Territory moscow = data.getMap().getTerritoryOrNull(ConfigR4.get().capitaleRussa());
    if (moscow == null) {
      return 0;
    }
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateAttackOptions();
    final ProTerritory pt = tm.getAttackOptions().getTerritoryMap().get(moscow);
    if (pt == null) {
      return 0;
    }
    final List<Unit> attackers = new ArrayList<>(pt.getMaxUnits());
    pt.getMaxAmphibUnits().stream().filter(u -> !attackers.contains(u)).forEach(attackers::add);
    if (attackers.stream().noneMatch(Matches.unitIsLand())) {
      return 0;
    }
    final List<Unit> defenders = moscow.getMatches(Matches.enemyUnit(player));
    if (defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())) {
      return 100;
    }
    return calc.calculateBattleResults(
            proData, moscow, attackers, defenders, pt.getMaxBombardUnits())
        .getWinPercentage();
  }

  @Nullable
  private static Territory luogo(final GameState data, final Unit u) {
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.getUnitCollection().contains(u)) {
        return t;
      }
    }
    return null;
  }

  static JsonObject stato(final GameProperties props, final String alliance) {
    if (props.get(KEY_STATO + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        return new JsonObject();
      }
    }
    return new JsonObject();
  }

  static void salva(final GameProperties props, final String alliance, final JsonObject st) {
    props.set(KEY_STATO + alliance, (Serializable) st.toString());
  }
}
