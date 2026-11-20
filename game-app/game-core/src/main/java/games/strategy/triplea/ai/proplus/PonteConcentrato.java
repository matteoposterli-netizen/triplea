package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProCombatMoveAi;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step P9+P10, {@link StrategaInterruttori#PONTE_CONCENTRATO} (data {@value #RESOURCE}): the own
 * reinforcement of the ally's bridgeheads of the round goes to ONE target, with all the cargo that
 * can reach it. In the non-combat move, after Hard's combat move and before Hard's non-combat move
 * (as {@link PonteRegnoUnito}, which it replaces while on).
 *
 * <ol>
 *   <li>Candidates: the territories where the ally landed this round at least {@code
 *       unita_minime_sbarco} land units (register of {@link SbarcoConcentrato}), allied now,
 *       reached this turn by own transports with loadable troops (the same routes, the same guard
 *       of the capital and the factories and the same check of exposed transports as {@link
 *       PonteRegnoUnito}: the allied warships in the unload zone count as defense).
 *   <li>For each: the win% of the enemy's best counterattack (Hard's estimate: the max units of one
 *       enemy of the data's nations, amphibious and bombarding included) against the allied
 *       defenders now, BEFORE and AFTER adding all the own cargo that can reach it.
 *   <li>The target: AFTER below the threshold, the largest drop; a candidate already below the
 *       threshold BEFORE is not a target. No target: nothing happens, Hard plays as always.
 * </ol>
 *
 * One register row per own turn (type {@value #TIPO}): the candidates with their numbers, the
 * target and the units sent, or why nothing was done.
 */
public final class PonteConcentrato {
  private PonteConcentrato() {}

  public static final String RESOURCE = "proplus/strategia/p9.json";
  static final String TIPO = "ponte_concentrato";

  static final String RINFORZO = "PONTE_CONC_RINFORZO";
  static final String NESSUN_CANDIDATO = "PONTE_CONC_NESSUN_CANDIDATO";
  static final String NESSUNO_SOTTO_SOGLIA = "PONTE_CONC_NESSUNO_SOTTO_SOGLIA";
  static final String GIA_SICURO = "PONTE_CONC_GIA_SICURO";
  static final String ESPOSTI = "PONTE_CONC_ESPOSTI";
  static final String RIFIUTATO = "PONTE_CONC_RIFIUTATO";

  /** The data file. */
  record Config(
      boolean diDefault,
      List<String> nazioni,
      String alleatoSbarco,
      List<String> nazioniContrattacco,
      int unitaMinime,
      double soglia) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            PonteConcentrato.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P9 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      final JsonObject a = root.getAsJsonObject("ponte_concentrato");
      final JsonObject p = a.getAsJsonObject("parametri");
      return new Config(
          v(a, "acceso_di_default").getAsBoolean(),
          lista(v(a, "nazioni")),
          v(a, "alleato_sbarco").getAsString(),
          lista(v(a, "nazioni_contrattacco")),
          v(p, "unita_minime_sbarco").getAsInt(),
          v(p, "soglia_attacco_pct").getAsDouble());
    }

    private static List<String> lista(final JsonElement e) {
      final List<String> out = new ArrayList<>();
      e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
      return List.copyOf(out);
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  /** On for the player's alliance, the player is the strategist's and one of the data's nations. */
  static boolean attivo(final GameState data, @Nullable final GamePlayer player) {
    if (player == null || data == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    return alliance.isPresent()
        && StrategaInterruttori.PONTE_CONCENTRATO.acceso(data.getProperties(), alliance.get())
        && Config.get().nazioni().contains(player.getName());
  }

  /** One candidate's numbers (pure data). */
  record Candidato(String territorio, double prima, double dopo, int unita, String stato) {}

  /**
   * The pure rule of the choice: among the candidates with {@code dopo < soglia} and {@code prima
   * >= soglia} and units to send, the one with the largest drop (ties: by name). Empty: none.
   */
  static Optional<Candidato> scegli(final List<Candidato> candidates, final double soglia) {
    return candidates.stream()
        .filter(c -> c.unita() > 0 && c.prima() >= soglia && c.dopo() < soglia)
        .min(
            Comparator.comparingDouble((Candidato c) -> -(c.prima() - c.dopo()))
                .thenComparing(Candidato::territorio));
  }

  /**
   * The pure rule of the reason when no candidate is a target: none reached ({@value
   * #NESSUN_CANDIDATO}), all exposed ({@value #ESPOSTI}), all already safe ({@value #GIA_SICURO}),
   * else {@value #NESSUNO_SOTTO_SOGLIA}.
   */
  static String motivo(final List<Candidato> candidates) {
    if (candidates.isEmpty()) {
      return NESSUN_CANDIDATO;
    }
    if (candidates.stream().allMatch(c -> "esposti".equals(c.stato()))) {
      return ESPOSTI;
    }
    if (candidates.stream().allMatch(c -> "gia_sicuro".equals(c.stato()))) {
      return GIA_SICURO;
    }
    return NESSUNO_SOTTO_SOGLIA;
  }

  /** This turn's reinforcement; never throws. */
  static void nonCombattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate del) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final Config cfg = Config.get();
    final JsonObject row = new JsonObject();
    row.addProperty("soglia", cfg.soglia());
    final JsonArray cj = new JsonArray();
    row.add("candidati", cj);
    try {
      final Map<String, Integer> landed =
          IndicazioniP7.sbarchi(
              RegistroDecisioni.righe(data),
              cfg.alleatoSbarco(),
              data.getSequence().getRound(),
              cfg.unitaMinime());
      row.addProperty("sbarchi_alleato", landed.size());
      final List<Candidato> candidates = new ArrayList<>();
      final Map<String, List<PonteRegnoUnito.Viaggio>> plans = new LinkedHashMap<>();
      final Set<GamePlayer> axis = new LinkedHashSet<>();
      for (final GamePlayer p : data.getPlayerList().getPlayers()) {
        if (cfg.nazioniContrattacco().contains(p.getName()) && player.isAtWar(p)) {
          axis.add(p);
        }
      }
      for (final var e : landed.entrySet()) {
        final Territory t = data.getMap().getTerritoryOrNull(e.getKey());
        if (t == null
            || t.getOwner().isNull()
            || !(t.isOwnedBy(player) || player.isAllied(t.getOwner()))) {
          continue;
        }
        final JsonObject c = new JsonObject();
        c.addProperty("territorio", t.getName());
        c.addProperty("unita_sbarcate_alleato", e.getValue());
        final List<PonteRegnoUnito.Viaggio> go = piano(proData, calc, t, c);
        if (go == null) {
          // not reached this turn: not a candidate
          continue;
        }
        final List<Unit> cargo = new ArrayList<>();
        go.forEach(v -> cargo.addAll(v.carico));
        final double[] win = contrattacco(proData, calc, t, cargo, axis, c);
        final String state =
            go.isEmpty()
                ? "esposti"
                : win[0] < cfg.soglia()
                    ? "gia_sicuro"
                    : win[1] < cfg.soglia() ? "sotto_soglia" : "sopra_soglia";
        c.addProperty("stato", state);
        cj.add(c);
        candidates.add(new Candidato(t.getName(), win[0], win[1], cargo.size(), state));
        plans.put(t.getName(), go);
      }
      final Optional<Candidato> target = scegli(candidates, cfg.soglia());
      if (target.isEmpty()) {
        final String why = motivo(candidates);
        registra(data, player, why, frase(why), row);
        return;
      }
      final Territory t = data.getMap().getTerritoryOrNull(target.get().territorio());
      final List<PonteRegnoUnito.Viaggio> go = plans.get(target.get().territorio());
      row.addProperty("bersaglio", target.get().territorio());
      row.addProperty("vittoria_asse_prima", AlleatiR5.round1(target.get().prima()));
      row.addProperty("vittoria_asse_dopo", AlleatiR5.round1(target.get().dopo()));
      row.addProperty("unita_che_possono_arrivare", target.get().unita());
      int ashore = 0;
      int trs = 0;
      for (final PonteRegnoUnito.Viaggio v : go) {
        final int n = PonteRegnoUnito.esegui(del, v, t);
        if (n > 0) {
          ashore += n;
          trs++;
        }
      }
      row.addProperty("trasporti", trs);
      row.addProperty("unita_mandate", ashore);
      registra(
          data,
          player,
          ashore > 0 ? RINFORZO : RIFIUTATO,
          ashore > 0
              ? "tutto il carico sull'unico bersaglio: la vittoria dell'Asse scende sotto la soglia"
              : "mosse rifiutate dal motore: nessun rinforzo",
          row);
    } catch (final RuntimeException ex) {
      ProLogger.warn("[Pro+] P9 ponte concentrato: " + ex);
    }
  }

  private static String frase(final String why) {
    return switch (why) {
      case NESSUN_CANDIDATO ->
          "nessuno sbarco alleato del round, alleato ora, raggiungibile con truppe caricabili";
      case ESPOSTI -> "trasporti esposti in ogni zona di scarico (controllo di Hard)";
      case GIA_SICURO -> "ogni candidato e' gia' sotto la soglia dell'Asse senza rinforzo";
      default -> "nessun candidato scende sotto la soglia dell'Asse nemmeno con tutto il carico";
    };
  }

  /**
   * The trips that bring the own cargo to {@code t} now (as {@link PonteRegnoUnito}: routes, guard
   * of the capital and the factories, exposed transports with three passes); null if no own unit
   * reaches {@code t} this turn, empty if every trip is exposed. Moves nothing.
   */
  static @Nullable List<PonteRegnoUnito.Viaggio> piano(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final JsonObject row) {
    final GameData data = proData.getData();
    final GamePlayer player = proData.getPlayer();
    final IndicazioniP7.Config cfg = IndicazioniP7.Config.get();
    final Predicate<Territory> free = SbarcoConcentrato.libera(player);
    final Set<Territory> zones = new LinkedHashSet<>();
    for (final Territory z : data.getMap().getNeighbors(t, Matches.territoryIsWater())) {
      if (free.test(z)) {
        zones.add(z);
      }
    }
    if (zones.isEmpty()) {
      return null;
    }
    final Map<Unit, Territory> where = new HashMap<>();
    for (final Territory x : data.getMap().getTerritories()) {
      x.getUnits().forEach(u -> where.put(u, x));
    }
    final Set<GamePlayer> enemies = new LinkedHashSet<>();
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      if (!p.equals(player) && player.isAtWar(p)) {
        enemies.add(p);
      }
    }
    final ProTerritoryManager tmz = new ProTerritoryManager(calc, proData);
    tmz.populateEnemyAttackOptionsOfEveryEnemy(zones, zones);
    final Map<Territory, Integer> risk = new HashMap<>();
    for (final Territory z : zones) {
      risk.put(
          z,
          PonteRegnoUnito.tuv(proData, ForzaSbarco.minaccia(tmz, z, enemies))
              - PonteRegnoUnito.tuv(proData, PonteRegnoUnito.copertura(player, z)));
    }
    final List<Territory> ordered = new ArrayList<>(zones);
    ordered.sort(
        Comparator.comparingInt((Territory z) -> risk.get(z)).thenComparing(Territory::getName));
    final Set<Unit> used = new HashSet<>();
    final List<PonteRegnoUnito.Viaggio> trips = new ArrayList<>();
    final Set<Unit> reached = new LinkedHashSet<>();
    for (final Unit tr : ForzaSbarco.trasporti(data, player)) {
      if (!tr.getUnloaded().isEmpty()) {
        continue;
      }
      final PonteRegnoUnito.Viaggio v =
          PonteRegnoUnito.pianifica(data, player, tr, where, ordered, Set.of(), used, t, reached);
      if (v != null) {
        used.add(tr);
        used.addAll(v.carico);
        trips.add(v);
      }
    }
    row.addProperty("trasporti_che_arrivano", reached.size());
    int loadable = 0;
    for (final PonteRegnoUnito.Viaggio v : trips) {
      loadable += v.carico.size();
    }
    row.addProperty("unita_caricabili", loadable);
    if (trips.isEmpty()) {
      return null;
    }
    // the guard of the capital and the factories (Hard's defense, as ponte_uk)
    final Set<Territory> guarded = new LinkedHashSet<>();
    for (final PonteRegnoUnito.Viaggio v : trips) {
      for (final Territory src : v.caricoDa.keySet()) {
        if (src.equals(proData.getMyCapital())
            || ProMatches.territoryHasInfraFactoryAndIsLand().test(src)) {
          guarded.add(src);
        }
      }
    }
    int keptHome = 0;
    if (!guarded.isEmpty()) {
      final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
      tm.populateEnemyAttackOptionsOfEveryEnemy(guarded, guarded);
      final double limit = 100 - proData.getWinPercentage();
      for (final Territory src : guarded) {
        final List<Unit> att = ForzaSbarco.minaccia(tm, src, enemies);
        while (true) {
          final Set<Unit> leaving = new LinkedHashSet<>();
          trips.forEach(v -> leaving.addAll(v.caricoDa.getOrDefault(src, List.of())));
          if (leaving.isEmpty()) {
            break;
          }
          final List<Unit> def =
              new ArrayList<>(
                  src.getMatches(
                      Matches.isUnitAllied(player)
                          .and(Matches.unitIsLand())
                          .or(Matches.isUnitAllied(player).and(Matches.unitIsAir()))));
          def.removeAll(leaving);
          if (ForzaSbarco.vittoria(proData, calc, src, att, def) <= limit) {
            break;
          }
          for (int i = trips.size() - 1; i >= 0; i--) {
            final List<Unit> l = trips.get(i).caricoDa.get(src);
            if (l != null && !l.isEmpty()) {
              final Unit u = l.remove(l.size() - 1);
              trips.get(i).carico.remove(u);
              used.remove(u);
              keptHome++;
              break;
            }
          }
        }
      }
    }
    trips.removeIf(v -> v.carico.isEmpty());
    row.addProperty("rimaste_per_la_difesa", keptHome);
    if (trips.isEmpty()) {
      return null;
    }
    // Hard's check of exposed transports per unload zone; the trips of an exposed zone try the
    // other zones next to T (up to three passes), the check itself is never skipped
    final ProCombatMoveAi.ProductionAndIsCapital pc = ProCombatMoveAi.getProductionAndIsCapital(t);
    final Set<Territory> bad = new LinkedHashSet<>();
    JsonArray zj = new JsonArray();
    for (int pass = 0; pass < 3; pass++) {
      zj = new JsonArray();
      final Map<Territory, List<PonteRegnoUnito.Viaggio>> byZone = new LinkedHashMap<>();
      trips.forEach(v -> byZone.computeIfAbsent(v.zona, k -> new ArrayList<>()).add(v));
      final Set<Territory> exposedNow = new LinkedHashSet<>();
      for (final var e : byZone.entrySet()) {
        final JsonObject zo =
            PonteRegnoUnito.esposizione(
                proData, calc, tmz, enemies, e.getKey(), e.getValue(), pc, cfg);
        zj.add(zo);
        if (zo.get("esposti").getAsBoolean()) {
          exposedNow.add(e.getKey());
        }
      }
      if (exposedNow.isEmpty()) {
        break;
      }
      bad.addAll(exposedNow);
      final List<PonteRegnoUnito.Viaggio> moved = new ArrayList<>();
      for (final Iterator<PonteRegnoUnito.Viaggio> it = trips.iterator(); it.hasNext(); ) {
        final PonteRegnoUnito.Viaggio v = it.next();
        if (bad.contains(v.zona)) {
          it.remove();
          used.removeAll(v.carico);
          final PonteRegnoUnito.Viaggio again =
              PonteRegnoUnito.pianifica(
                  data, player, v.trasporto, where, ordered, bad, used, t, new HashSet<>());
          if (again != null) {
            used.addAll(again.carico);
            moved.add(again);
          } else {
            used.remove(v.trasporto);
          }
        }
      }
      if (moved.isEmpty()) {
        break;
      }
      trips.addAll(moved);
    }
    row.add("zone", zj);
    final List<PonteRegnoUnito.Viaggio> go = new ArrayList<>();
    for (final PonteRegnoUnito.Viaggio v : trips) {
      if (!bad.contains(v.zona)) {
        go.add(v);
      }
    }
    int n = 0;
    for (final PonteRegnoUnito.Viaggio v : go) {
      n += v.carico.size();
    }
    row.addProperty("unita_che_possono_arrivare", n);
    return go;
  }

  /**
   * The win% of the enemy's best counterattack on {@code t} (Hard's estimate: the max units of one
   * enemy among {@code axis}, amphibious included, with its bombardment) against the allied land
   * and air units there now: {before, after adding {@code cargo}}.
   */
  static double[] contrattacco(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final Collection<Unit> cargo,
      final Set<GamePlayer> axis,
      final JsonObject row) {
    final GamePlayer player = proData.getPlayer();
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptionsOfEveryEnemy(List.of(t), List.of(t));
    final ProTerritory max = tm.getEnemyAttackOptions().getMax(t);
    final List<Unit> att = new ArrayList<>();
    final List<Unit> bombard = new ArrayList<>();
    if (max != null) {
      final Set<Unit> all = new LinkedHashSet<>(max.getMaxUnits());
      all.addAll(max.getMaxAmphibUnits());
      all.stream().filter(u -> axis.contains(u.getOwner())).forEach(att::add);
      max.getMaxBombardUnits().stream()
          .filter(u -> axis.contains(u.getOwner()))
          .forEach(bombard::add);
    }
    att.sort(Comparator.comparing(u -> u.getId().toString()));
    final List<Unit> def =
        new ArrayList<>(
            t.getMatches(
                Matches.isUnitAllied(player)
                    .and(Matches.unitIsLand().or(Matches.unitIsAir()))
                    .and(Matches.unitIsNotInfrastructure())));
    final List<Unit> defAfter = new ArrayList<>(def);
    defAfter.addAll(cargo);
    final double before = win(proData, calc, t, att, def, bombard);
    final double after = win(proData, calc, t, att, defAfter, bombard);
    row.addProperty("contrattacco_nazione", att.isEmpty() ? "-" : att.get(0).getOwner().getName());
    row.addProperty("unita_asse_in_portata", att.size());
    row.addProperty("difensori_alleati", def.size());
    row.addProperty("unita_proprie_che_possono_arrivare", cargo.size());
    row.addProperty("vittoria_asse_prima", AlleatiR5.round1(before));
    row.addProperty("vittoria_asse_dopo", AlleatiR5.round1(after));
    return new double[] {before, after};
  }

  private static double win(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory t,
      final List<Unit> att,
      final List<Unit> def,
      final List<Unit> bombard) {
    if (att.isEmpty()) {
      return 0;
    }
    if (def.isEmpty()) {
      return 100;
    }
    return calc.calculateBattleResults(proData, t, att, def, bombard).getWinPercentage();
  }

  /** A register row of step P9 (only on the real game). Never throws. */
  private static void registra(
      final GameState data,
      final GamePlayer player,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    IndicazioniP7.registra(data, player, TIPO, codice, frase, parametri);
  }
}
