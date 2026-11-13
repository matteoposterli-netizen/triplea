package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleRecord;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R6b "TRASPORTI PROTETTI": the USA's program of {@link SquadraGibilterra} (same switch, off
 * by default) never leaves a transport it moved, at the end of the turn, in a sea zone where the
 * realistic enemy attack wins more than {@code soglia_trasporti} (40) against the defenders: the
 * allied warships there at the end of the turn (those arriving with the wave too), the allied
 * planes that can scramble to it, the planes on the carriers there. The attack is every enemy unit
 * in range ({@link Programmi.Turno#attaccoPieno}, no commitment share: with the share a local game
 * estimated 8% and lost a battleship, a cruiser and 3 transports), with the safe stops' danger
 * memory (US units lost defending the zone in the last 3 rounds ⇒ threshold halved).
 *
 * <ul>
 *   <li>A3/B1 waves: the minimum escort of the squadron's warships (one ship at a time, the one
 *       that lowers the enemy's win most), else another free unload zone next to the target, else
 *       the target is given up and the next by value is tried ({@link #proteggi});
 *   <li>B2-B3: escort and empty transports go back together to zone 91 (stops only in safe zones);
 *       from there the empty transports load the program's troops waiting at Gibraltar, or go back
 *       to the port ({@link #raduna});
 *   <li>C2: the shuttle's loaded transports leave together toward zone 91 when it is safe ({@link
 *       #naviga}); C3: a second factory for the shuttle's troops.
 * </ul>
 *
 * <p>Nothing of the Axis, the United Kingdom, Russia or France changes. Every decision goes to
 * {@link RegistroDecisioni} (types trasporti_sicurezza, scorta_ondata, rientro_trasporti).
 */
public final class TrasportiProtetti {
  private TrasportiProtetti() {}

  static final String SICURA = "SICURA";
  static final String SCORTATA = "SCORTATA";
  static final String ZONA_CAMBIATA = "ZONA_CAMBIATA";
  static final String RINUNCIA = "RINUNCIA";

  /** Values of {@code impegno.r6b} in {@code stratega-llm.json}. */
  public record Config(
      double soglia,
      int roundPericolo,
      String zonaRientro,
      String secondaFabbrica,
      int maxScortaSpola) {
    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            TrasportiProtetti.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          final JsonObject o =
              JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                  .getAsJsonObject()
                  .getAsJsonObject("impegno")
                  .getAsJsonObject("r6b");
          cache =
              new Config(
                  v(o, "soglia_trasporti").getAsDouble(),
                  v(o, "round_pericolo").getAsInt(),
                  v(o, "zona_rientro").getAsString(),
                  v(o, "seconda_fabbrica").getAsString(),
                  v(o, "max_scorta_spola_per_turno").getAsInt());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    private static JsonElement v(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
    }
  }

  /** R6b is on with the squadron's switch (the USA of the commitment). */
  static boolean attivo(final GameState data, final GamePlayer player) {
    return SquadraGibilterra.attivo(data, player);
  }

  // ============================================================================ A1 the rule

  /** A1: the numbers of one zone at the end of the turn. */
  record Sicurezza(
      Territory zona,
      double vittoria,
      double soglia,
      boolean pericolo,
      List<Unit> difensori,
      List<Unit> attaccanti) {
    boolean sicura() {
      return vittoria <= soglia;
    }

    JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("zona", zona.getName());
      o.add("difensori", perTipo(difensori));
      o.add("attacco_realistico", perTipo(attaccanti));
      o.addProperty("vittoria_nemica_pct", AlleatiR5.round1(vittoria));
      o.addProperty("soglia_pct", soglia);
      o.addProperty("zona_pericolosa", pericolo);
      return o;
    }
  }

  /** A1: the zone is safe with the enemy's win at most the threshold. */
  static boolean sicura(final double vittoria, final double soglia) {
    return vittoria <= soglia;
  }

  /** The safe stops' danger memory: the threshold halved in a zone with recent US losses. */
  static double soglia(final boolean pericolo, final Config cfg) {
    return pericolo ? cfg.soglia() / 2 : cfg.soglia();
  }

  /**
   * A1 for {@code z} at the end of the turn: the allied sea units there minus {@code leaving}, the
   * ships of {@code arriving} (cargo excluded), the allied planes on carriers there and those that
   * can scramble to it, against the realistic attack of every enemy in range.
   */
  static Sicurezza valuta(
      final Programmi.Turno p,
      final Territory z,
      final Collection<Unit> arriving,
      final Collection<Unit> leaving) {
    final Config cfg = Config.get();
    if (!p.tuttiINemici) {
      p.tuttiINemici = true;
      p.enemy = null;
    }
    final List<Unit> defenders =
        new ArrayList<>(z.getMatches(Matches.isUnitAllied(p.player).and(Matches.unitIsSea())));
    defenders.removeAll(leaving);
    arriving.stream()
        .filter(Matches.unitIsSea())
        .filter(u -> !defenders.contains(u))
        .forEach(defenders::add);
    AlleatiR5.aereiInDifesa(p.data, p.player, z).stream()
        .filter(u -> !defenders.contains(u) && !leaving.contains(u))
        .forEach(defenders::add);
    final boolean danger = pericolo(p.data, p.player, z, cfg.roundPericolo());
    final Programmi.Turno.Attacco a = p.attaccoPieno(z, defenders);
    return new Sicurezza(z, a.vittoria(), soglia(danger, cfg), danger, defenders, a.attaccanti());
  }

  /**
   * The safe stops' danger memory: units of {@code player} lost defending {@code z} in the last
   * {@code rounds} rounds (battle records).
   */
  static boolean pericolo(
      final GameState data, final GamePlayer player, final Territory z, final int rounds) {
    if (!z.isWater() || data.getBattleRecordsList() == null) {
      return false;
    }
    final int round = data.getSequence().getRound();
    for (final Map.Entry<Integer, BattleRecords> e :
        data.getBattleRecordsList().getBattleRecordsMapCopy().entrySet()) {
      if (e.getKey() < round - rounds + 1 || e.getKey() > round) {
        continue;
      }
      for (final BattleRecord r : BattleRecords.getAllRecords(e.getValue())) {
        final List<BattleRecord> one = List.of(r);
        if (BattleRecords.getWereThereBattlesInTerritoriesMatching(
                one, null, player, "any", List.of(z))
            && BattleRecords.getLostTuvForBattleRecords(one, false, false) > 0) {
          return true;
        }
      }
    }
    return false;
  }

  // ==================================================================== A3/B1 the waves

  /** A3: how a wave's transports are protected. */
  record Protezione(
      String esito,
      Territory zona,
      List<Unit> scorta,
      SbarcoCoordinato.Turno.Piano piano,
      SbarcoCoordinato.Turno.Stima stima,
      Sicurezza sicurezza,
      String idRegistro) {}

  /**
   * A3 at the purchase for the wave of {@code units} on {@code target} ({@code plan}, {@code stima}
   * at >= 70): the unload zone of the plan, then the other free zones next to the target; in each
   * the transports (and the ships that bombard from there) alone, then the minimum escort of the
   * squadron's warships that reach it (B1, one ship at a time). Empty = given up. One row {@code
   * trasporti_sicurezza} with every zone tried.
   */
  static Optional<Protezione> proteggi(
      final SbarcoCoordinato.Turno t,
      final Territory target,
      final SbarcoCoordinato.Turno.Piano plan,
      final SbarcoCoordinato.Turno.Stima stima,
      final Predicate<Unit> units,
      final String gruppo) {
    final double landingThreshold = AlleatiR5.Config.get().sogliaSbarco();
    final List<Territory> zones = zoneScarico(t, target, plan);
    final JsonArray alt = new JsonArray();
    Protezione found = null;
    final Set<String> originalCargo = ids(plan.carico());
    for (int i = 0; i < zones.size() && found == null; i++) {
      final Territory z = zones.get(i);
      final JsonObject a = new JsonObject();
      a.addProperty("zona", z.getName());
      alt.add(a);
      t.zonaScarico = z;
      t.filtro = units;
      final SbarcoCoordinato.Turno.Piano pz;
      try {
        pz = t.piano(target, t.player, t.presidioBase());
      } finally {
        t.zonaScarico = null;
        t.filtro = null;
      }
      if (pz.carico().isEmpty()) {
        a.addProperty("esito", "NESSUN_TRASPORTO");
        continue;
      }
      final SbarcoCoordinato.Turno.Stima sz =
          ids(pz.carico()).equals(originalCargo)
                  && plan.viaggi().stream().allMatch(v -> v.rotta().getEnd().equals(z))
              ? stima
              : t.stima(pz, false);
      a.addProperty("unita", pz.carico().size());
      a.addProperty("vittoria_sbarco_pct", AlleatiR5.round1(sz.vittoria()));
      if (!AlleatiR5.sbarca(sz.vittoria(), landingThreshold)) {
        a.addProperty("esito", "MANCA_VITTORIA");
        continue;
      }
      final List<Unit> base = new ArrayList<>();
      pz.viaggi().forEach(v -> base.add(v.trasporto()));
      if (t.supportoR6) {
        SquadraGibilterra.naviSupporto(t, pz).stream()
            .filter(u -> !base.contains(u))
            .forEach(base::add);
      }
      final Sicurezza alone = valuta(t.p, z, base, List.of());
      a.add("senza_scorta", alone.json());
      final List<Unit> escort = new ArrayList<>();
      Sicurezza s = alone;
      if (!s.sicura()) {
        final List<Unit> candidates = candidatiScorta(t, z, base);
        while (!s.sicura() && !candidates.isEmpty()) {
          // B1: one ship at a time, the type that lowers the enemy's win the most
          Unit best = null;
          Sicurezza bestS = null;
          final Set<String> tried = new HashSet<>();
          for (final Unit c : candidates) {
            if (!tried.add(c.getType().getName())) {
              continue;
            }
            final List<Unit> trial = new ArrayList<>(base);
            trial.addAll(escort);
            trial.add(c);
            final Sicurezza x = valuta(t.p, z, trial, List.of());
            if (bestS == null || x.vittoria() < bestS.vittoria()) {
              best = c;
              bestS = x;
            }
          }
          escort.add(best);
          candidates.remove(best);
          s = bestS;
        }
        a.add("con_scorta", s.json());
        a.add("scorta", perTipo(escort));
      }
      final String outcome =
          !s.sicura() ? RINUNCIA : i > 0 ? ZONA_CAMBIATA : escort.isEmpty() ? SICURA : SCORTATA;
      a.addProperty("esito", s.sicura() ? outcome : "NON_SICURA");
      if (s.sicura()) {
        found = new Protezione(outcome, z, List.copyOf(escort), pz, sz, s, "");
      }
    }
    final String code = found == null ? RINUNCIA : found.esito();
    final String id =
        registra(
            t.data,
            t.st,
            t.player,
            "trasporti_sicurezza",
            alt,
            found == null ? "rinuncia a " + target.getName() : found.zona().getName(),
            code,
            String.format(
                Locale.ROOT,
                "ondata %s su %s (%d unita'): %s",
                gruppo,
                target.getName(),
                plan.carico().size(),
                found == null
                    ? "nessuna zona di scarico sicura nemmeno con tutta la scorta"
                    : found.zona().getName()
                        + ", vittoria nemica "
                        + AlleatiR5.round1(found.sicurezza().vittoria())
                        + "% con "
                        + found.scorta().size()
                        + " navi di scorta"),
            parametri("ondata", target.getName()));
    t.lines.add(
        String.format(
            Locale.ROOT,
            "  R6b trasporti dell'ondata %s su %s: %s",
            gruppo,
            target.getName(),
            found == null
                ? "RINUNCIA (trasporti esposti in ogni zona)"
                : found.esito()
                    + " in "
                    + found.zona().getName()
                    + " (scorta "
                    + found.scorta().size()
                    + ", vittoria nemica "
                    + AlleatiR5.round1(found.sicurezza().vittoria())
                    + "%)"));
    if (found == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Protezione(
            found.esito(),
            found.zona(),
            found.scorta(),
            found.piano(),
            found.stima(),
            found.sicurezza(),
            id));
  }

  /** The unload zones next to {@code target} without enemies: the plan's first, then by name. */
  private static List<Territory> zoneScarico(
      final SbarcoCoordinato.Turno t,
      final Territory target,
      final SbarcoCoordinato.Turno.Piano plan) {
    final List<Territory> out = new ArrayList<>();
    final Territory first = SquadraGibilterra.zonaSbarco(plan);
    if (first != null) {
      out.add(first);
    }
    t.data.getMap().getNeighbors(target, Matches.territoryIsWater()).stream()
        .filter(z -> !z.anyUnitsMatch(Matches.enemyUnit(t.player)))
        .sorted(Comparator.comparing(Territory::getName))
        .filter(z -> !out.contains(z))
        .forEach(out::add);
    return out;
  }

  /**
   * B1: the squadron's warships (no carrier: its planes land on it) that reach {@code z} this turn,
   * not already in {@code base}, not escorting another wave of the turn nor empty transports still
   * on their way back (B2).
   */
  static List<Unit> candidatiScorta(
      final SbarcoCoordinato.Turno t, final Territory z, final Collection<Unit> base) {
    final Set<String> busy = new HashSet<>(t.scorteUsate);
    busy.addAll(scorteInRientro(t));
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : SquadraGibilterra.squadra(t.data, SquadraGibilterra.r6(t.st))) {
      if (!AlleatiR5.naveDaGuerra().test(u)
          || Matches.unitIsCarrier().test(u)
          || base.contains(u)
          || busy.contains(u.getId().toString())
          || !Matches.unitHasMovementLeft().test(u)) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at != null
          && (at.equals(z) || SquadraGibilterra.rotta(t, at, z, List.of(u)).isPresent())) {
        out.add(u);
      }
    }
    out.sort(Comparator.comparing(u -> u.getId().toString()));
    return out;
  }

  /** B2: the escorts of earlier waves whose transports are not back yet. */
  private static Set<String> scorteInRientro(final SbarcoCoordinato.Turno t) {
    final Set<String> out = new HashSet<>();
    for (final JsonElement e : scorte(t.st)) {
      final JsonObject o = e.getAsJsonObject();
      if (o.get("round").getAsInt() < t.round) {
        o.getAsJsonArray("scorta").forEach(x -> out.add(x.getAsString()));
      }
    }
    return out;
  }

  /** The wave's protection kept in its JSON (decided at the purchase, used in the combat). */
  static void salvaInOnda(final JsonObject w, final Protezione pr) {
    w.addProperty("zona_scarico", pr.zona().getName());
    final JsonArray e = new JsonArray();
    pr.scorta().forEach(u -> e.add(u.getId().toString()));
    w.add("scorta_ids", e);
    w.addProperty("esito_trasporti", pr.esito());
    w.addProperty("vittoria_nemica_trasporti_pct", AlleatiR5.round1(pr.sicurezza().vittoria()));
    w.addProperty("id_trasporti_sicurezza", pr.idRegistro());
  }

  /**
   * A1 in the combat move, before the wave of {@code w} sails: the plan's zone with its transports,
   * the bombarding ships and the escort still there. Unsafe (the board changed) ⇒ given up (row
   * {@code trasporti_sicurezza} RINUNCIA).
   */
  static boolean primaDelloSbarco(
      final SbarcoCoordinato.Turno t,
      final JsonObject w,
      final SbarcoCoordinato.Turno.Piano plan,
      final List<Unit> bombard) {
    if (!w.has("zona_scarico") || plan.viaggi().isEmpty()) {
      return true;
    }
    final Territory z = t.data.getMap().getTerritoryOrNull(w.get("zona_scarico").getAsString());
    if (z == null) {
      return true;
    }
    final List<Unit> group = new ArrayList<>();
    plan.viaggi().forEach(v -> group.add(v.trasporto()));
    group.addAll(bombard);
    group.addAll(scortaDi(t, w, z));
    final Sicurezza s = valuta(t.p, z, group, List.of());
    if (s.sicura()) {
      return true;
    }
    final JsonArray alt = new JsonArray();
    alt.add(s.json());
    registra(
        t.data,
        t.st,
        t.player,
        "trasporti_sicurezza",
        alt,
        "rinuncia a " + w.get("bersaglio").getAsString(),
        RINUNCIA,
        "ricontrollo nel combattimento: la zona di scarico non e' piu' sicura",
        parametri("ondata_ricontrollo", w.get("bersaglio").getAsString()));
    t.lines.add(
        "  R6b ondata su "
            + w.get("bersaglio").getAsString()
            + " annullata: "
            + z.getName()
            + " non piu' sicura (vittoria nemica "
            + AlleatiR5.round1(s.vittoria())
            + "%)");
    return false;
  }

  /** The escort of the wave still alive that reaches {@code z} (or is there). */
  private static List<Unit> scortaDi(
      final SbarcoCoordinato.Turno t, final JsonObject w, final Territory z) {
    final List<Unit> out = new ArrayList<>();
    if (!w.has("scorta_ids")) {
      return out;
    }
    final Set<String> ids = new HashSet<>();
    w.getAsJsonArray("scorta_ids").forEach(e -> ids.add(e.getAsString()));
    for (final Unit u : SquadraGibilterra.squadra(t.data, SquadraGibilterra.r6(t.st))) {
      if (!ids.contains(u.getId().toString())) {
        continue;
      }
      final Territory at = t.luogoDi(u);
      if (at != null
          && (at.equals(z) || SquadraGibilterra.rotta(t, at, z, List.of(u)).isPresent())) {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * B1-B2 after the wave landed: its escort sails to the unload zone and stays with the transports;
   * the wave's transports and escort are kept for the way back; row {@code scorta_ondata} with the
   * ships gone and those left at Gibraltar. Returns the units moved.
   */
  static Set<Unit> dopoLoSbarco(
      final SbarcoCoordinato.Turno t,
      final JsonObject w,
      final SbarcoCoordinato.Turno.Piano plan,
      final IMoveDelegate moveDel) {
    final Set<Unit> moved = new LinkedHashSet<>();
    if (!w.has("zona_scarico")) {
      return moved;
    }
    final Territory z = t.data.getMap().getTerritoryOrNull(w.get("zona_scarico").getAsString());
    if (z == null) {
      return moved;
    }
    final List<Unit> escort = scortaDi(t, w, z);
    final List<Unit> gone = new ArrayList<>();
    for (final Unit u : escort) {
      final Territory at = t.luogoDi(u);
      if (at == null) {
        continue;
      }
      if (at.equals(z)) {
        gone.add(u);
        continue;
      }
      final Optional<Route> r = SquadraGibilterra.rotta(t, at, z, List.of(u));
      if (r.isPresent()
          && moveDel.performMove(new MoveDescription(List.of(u), r.get())).isEmpty()) {
        moved.add(u);
        gone.add(u);
      }
    }
    final JsonObject entry = new JsonObject();
    entry.addProperty("round", t.round);
    entry.addProperty("zona", z.getName());
    entry.addProperty("bersaglio", w.get("bersaglio").getAsString());
    final JsonArray tr = new JsonArray();
    plan.viaggi().forEach(v -> tr.add(v.trasporto().getId().toString()));
    entry.add("trasporti", tr);
    final JsonArray es = new JsonArray();
    gone.forEach(u -> es.add(u.getId().toString()));
    entry.add("scorta", es);
    scorte(t.st).add(entry);
    final Territory base = t.data.getMap().getTerritoryOrNull(Config.get().zonaRientro());
    final List<Unit> left =
        base == null
            ? List.of()
            : base.getMatches(
                u ->
                    SquadraGibilterra.ids(SquadraGibilterra.r6(t.st)).has(u.getId().toString())
                        && AlleatiR5.naveDaGuerra().test(u));
    final JsonArray alt = new JsonArray();
    final JsonObject a = new JsonObject();
    a.addProperty("zona", z.getName());
    a.addProperty("bersaglio", w.get("bersaglio").getAsString());
    a.addProperty("trasporti", plan.viaggi().size());
    a.add("scorta_andata", perTipo(gone));
    a.addProperty("navi_andate", gone.size());
    a.add("rimaste_a_gibilterra", perTipo(left));
    a.addProperty("navi_rimaste", left.size());
    a.addProperty(
        "esito_trasporti", w.has("esito_trasporti") ? w.get("esito_trasporti").getAsString() : "");
    alt.add(a);
    registra(
        t.data,
        t.st,
        t.player,
        "scorta_ondata",
        alt,
        gone.size() + " navi",
        gone.isEmpty() ? SICURA : SCORTATA,
        String.format(
            Locale.ROOT,
            "scorta dell'ondata su %s in %s: %d navi andate, %d rimaste in %s",
            w.get("bersaglio").getAsString(),
            z.getName(),
            gone.size(),
            left.size(),
            Config.get().zonaRientro()),
        parametri("scorta", z.getName()));
    if (!gone.isEmpty()) {
      t.lines.add(
          "  R6b scorta dell'ondata in "
              + z.getName()
              + ": "
              + gone.size()
              + " navi, "
              + left.size()
              + " rimaste");
    }
    return moved;
  }

  static JsonArray scorte(final JsonObject st) {
    return SbarcoCoordinato.arr(SbarcoCoordinato.obj(st, "r6b"), "scorte");
  }

  // ========================================================= B2-B3 the way back (non combat)

  /**
   * B2-B3 in the USA's non-combat move once the squadron is at Gibraltar (instead of R6's
   * gathering): the escorts of this turn's waves stay with their transports; elsewhere, per zone,
   * the squadron's warships, the program's empty transports and the shuttle's escorts sail together
   * toward zone 91 stopping only in a safe zone (A1); the empty transports in Gibraltar's zones
   * with no program troop waiting at Gibraltar go back to the port (safe stops). Returns the units
   * moved.
   */
  static Set<Unit> raduna(
      final SbarcoCoordinato.Turno t, final Territory base, final IMoveDelegate moveDel) {
    final Set<Unit> moved = new LinkedHashSet<>();
    final JsonObject r6 = SquadraGibilterra.r6(t.st);
    final Set<String> staying = new HashSet<>();
    final JsonArray entries = scorte(t.st);
    for (final JsonElement e : entries) {
      final JsonObject o = e.getAsJsonObject();
      if (o.get("round").getAsInt() == t.round) {
        o.getAsJsonArray("scorta").forEach(x -> staying.add(x.getAsString()));
      }
    }
    final Predicate<Unit> programTransport = AlleatiR5.trasportoDelProgramma(t.data, t.player);
    final Set<String> shuttleEscorts = new HashSet<>();
    SbarcoCoordinato.arr(r6, "scorte_spola").forEach(x -> shuttleEscorts.add(x.getAsString()));
    final List<Territory> portZones = t.p.zone(t.p.porto());
    final Territory gibraltar =
        t.data.getMap().getTerritoryOrNull(AlleatiR5.Config.get().gibilterra());
    final List<Territory> gibZones = t.p.zone(gibraltar);
    final boolean troopsWaiting = attesaA(t, gibraltar) > 0;
    // per zone: the group that sails together
    final Map<Territory, List<Unit>> byZone =
        new TreeMap<>(Comparator.comparing(Territory::getName));
    for (final Territory z : t.data.getMap().getTerritories()) {
      if (!z.isWater() || z.equals(base)) {
        continue;
      }
      final boolean port = portZones.contains(z);
      // C2/C4: the warships with loaded program transports sail with them (the shuttle), never
      // alone
      if (z.anyUnitsMatch(
          Matches.unitIsOwnedBy(t.player).and(Unit::isTransporting).and(programTransport))) {
        continue;
      }
      for (final Unit u : z.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitIsSea()))) {
        if (!Matches.unitHasMovementLeft().test(u) || staying.contains(u.getId().toString())) {
          continue;
        }
        final boolean transport = Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u);
        if (port && !SquadraGibilterra.inSquadra(t.data, t.player, u)) {
          continue; // at the port: the shuttle's (the squadron's ships left there join it, C4)
        }
        if (transport && port) {
          continue;
        }
        if (transport) {
          if (!programTransport.test(u) || u.isTransporting() || !u.getUnloaded().isEmpty()) {
            continue;
          }
          // B3: in Gibraltar's other zone they stay to load, or go to the port (below)
          if (gibZones.contains(z)) {
            continue;
          }
        } else if (!SquadraGibilterra.inSquadra(t.data, t.player, u)
            && !shuttleEscorts.contains(u.getId().toString())) {
          continue;
        }
        byZone.computeIfAbsent(z, k -> new ArrayList<>()).add(u);
      }
    }
    for (final var e : byZone.entrySet()) {
      final List<Unit> group = e.getValue();
      final boolean hasTransports =
          group.stream().anyMatch(Matches.unitIsSeaTransportButNotCombatSeaTransport());
      if (!hasTransports) {
        // only warships: R6's gathering, one by one
        for (final Unit u : group) {
          if (!SquadraGibilterra.inSquadra(t.data, t.player, u)) {
            continue;
          }
          SquadraGibilterra.rotta(t, e.getKey(), base, List.of(u))
              .ifPresent(
                  r -> {
                    if (moveDel.performMove(new MoveDescription(List.of(u), r)).isEmpty()) {
                      moved.add(u);
                    }
                  });
        }
        continue;
      }
      naviga(t.p, group, e.getKey(), List.of(base), moved, moveDel, "rientro_91", t.st);
    }
    // B3: the empty transports at Gibraltar's zones with no troop to load go back to the port
    if (!troopsWaiting && !portZones.isEmpty()) {
      for (final Territory z : gibZones.isEmpty() ? List.of(base) : gibZones) {
        final List<Unit> group = new ArrayList<>();
        for (final Unit u :
            z.getMatches(Matches.unitIsOwnedBy(t.player).and(Matches.unitHasMovementLeft()))) {
          if (moved.contains(u)) {
            continue;
          }
          if (Matches.unitIsSeaTransportButNotCombatSeaTransport().test(u)
              && programTransport.test(u)
              && !u.isTransporting()
              && u.getUnloaded().isEmpty()) {
            group.add(u);
          } else if (shuttleEscorts.contains(u.getId().toString())) {
            group.add(u);
          }
        }
        if (group.stream().anyMatch(Matches.unitIsSeaTransportButNotCombatSeaTransport())) {
          naviga(t.p, group, z, portZones, moved, moveDel, "rientro_porto", t.st);
        }
      }
    }
    // the entries whose transports are back (or gone) are closed
    final JsonArray keep = new JsonArray();
    for (final JsonElement e : entries) {
      final JsonObject o = e.getAsJsonObject();
      boolean out = false;
      for (final JsonElement x : o.getAsJsonArray("trasporti")) {
        final Unit u = trova(t.data, x.getAsString());
        final Territory at = u == null ? null : t.luogoDi(u);
        if (at != null && !at.equals(base) && !gibZones.contains(at) && !portZones.contains(at)) {
          out = true;
        }
      }
      if (out || o.get("round").getAsInt() == t.round) {
        keep.add(o);
      }
    }
    SbarcoCoordinato.obj(t.st, "r6b").add("scorte", keep);
    return moved;
  }

  /** The program's land units at {@code land} that a transport can carry. */
  static int attesaA(final SbarcoCoordinato.Turno t, @Nullable final Territory land) {
    if (land == null) {
      return 0;
    }
    final Set<String> program = SnodoAlleati.programma(t.data, t.player);
    return (int)
        land.getUnitCollection().stream()
            .filter(u -> u.isOwnedBy(t.player) && program.contains(u.getId().toString()))
            .filter(u -> u.getUnitAttachment().getTransportCost() > 0)
            .count();
  }

  @Nullable
  private static Unit trova(final GameState data, final String id) {
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit u : t.getUnitCollection()) {
        if (u.getId().toString().equals(id)) {
          return u;
        }
      }
    }
    return null;
  }

  // ================================================================= C2 sailing together

  /**
   * A1-C2: {@code group} (ships, with their cargo) sails from {@code z} toward the nearest of
   * {@code targets} through zones without enemies, ending in the farthest zone within its movement
   * where A1 holds (the group arriving there); none ⇒ it stays. One row {@code trasporti_sicurezza}
   * (departure, kind {@code tipo}) or {@code rientro_trasporti} for the empty ones. Returns the end
   * zone when it moved.
   */
  static Optional<Territory> naviga(
      final Programmi.Turno p,
      final List<Unit> group,
      final Territory z,
      final List<Territory> targets,
      final Set<Unit> held,
      final IMoveDelegate moveDel,
      final String tipo,
      final JsonObject st) {
    final List<Unit> ships = new ArrayList<>(group);
    ships.removeIf(Matches.unitIsSea().negate());
    final List<Unit> all = new ArrayList<>(ships);
    for (final Unit s : ships) {
      s.getTransporting().stream().filter(u -> !all.contains(u)).forEach(all::add);
    }
    final Predicate<Territory> free =
        x ->
            x.isWater()
                && ProMatches.territoryCanMoveSeaUnitsThrough(p.player, false).test(x)
                && !x.anyUnitsMatch(Matches.enemyUnit(p.player));
    Route best = null;
    for (final Territory to : targets) {
      if (to.equals(z)) {
        return Optional.empty();
      }
      final Optional<Route> r = p.data.getMap().getRouteForUnits(z, to, free, all, p.player);
      if (r.isPresent() && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
        best = r.get();
      }
    }
    final boolean loaded = ships.stream().anyMatch(Unit::isTransporting);
    final String type = loaded ? "trasporti_sicurezza" : "rientro_trasporti";
    final JsonArray alt = new JsonArray();
    final int range = ships.stream().mapToInt(u -> u.getMovementLeft().intValue()).min().orElse(0);
    Territory end = null;
    Sicurezza ok = null;
    if (best != null) {
      final List<Territory> steps = best.getSteps();
      for (int i = Math.min(range, steps.size()); i >= 1; i--) {
        final Territory x = steps.get(i - 1);
        final Sicurezza s = valuta(p, x, ships, List.of());
        alt.add(s.json());
        if (!s.sicura()) {
          continue;
        }
        final Route r = new Route(z, steps.subList(0, i).toArray(new Territory[0]));
        final Optional<String> error = moveDel.performMove(new MoveDescription(all, r));
        if (error.isEmpty()) {
          end = x;
          ok = s;
          break;
        }
        ProLogger.warn("[Pro+] R6b: rotta non riuscita " + error.get());
      }
    }
    held.addAll(all);
    final boolean arrived = end != null && targets.contains(end);
    final String code =
        end == null ? "FERMO" : arrived ? (loaded ? SICURA : "RIENTRO") : "RIENTRO_PARZIALE";
    final long transports =
        ships.stream().filter(Matches.unitIsSeaTransportButNotCombatSeaTransport()).count();
    final JsonObject par = parametri(tipo, z.getName());
    par.addProperty("trasporti", transports);
    par.addProperty("carichi", loaded);
    par.addProperty("navi_da_guerra", ships.size() - transports);
    par.addProperty("verso", targets.isEmpty() ? "" : targets.get(0).getName());
    registra(
        p.data,
        st,
        p.player,
        type,
        alt,
        end == null ? "fermi in " + z.getName() : end.getName(),
        code,
        String.format(
            Locale.ROOT,
            "%s: %d trasporti %s e %d navi da guerra da %s: %s",
            tipo,
            transports,
            loaded ? "carichi" : "vuoti",
            ships.size() - transports,
            z.getName(),
            end == null
                ? (best == null ? "nessuna rotta libera" : "nessuna sosta sicura, restano")
                : "fino a "
                    + end.getName()
                    + " (vittoria nemica "
                    + AlleatiR5.round1(ok.vittoria())
                    + "%)"),
        par);
    p.lines.add(
        String.format(
            Locale.ROOT,
            "  R6b %s: %d trasporti %s da %s -> %s",
            tipo,
            transports,
            loaded ? "carichi" : "vuoti",
            z.getName(),
            end == null ? "fermi (nessuna sosta sicura)" : end.getName()));
    return Optional.ofNullable(end);
  }

  // ============================================================== C2-C3 the shuttle's purchase

  /** C2: an escort is bought only when the arrival zone of the shuttle is not safe. */
  static boolean serveScortaSpola(final Programmi.Turno p, @Nullable final Territory arrival) {
    if (arrival == null) {
      return false;
    }
    final List<Unit> one =
        p.data
            .getUnitTypeList()
            .getUnitType("transport")
            .map(ut -> ut.createTemp(1, p.player))
            .orElse(List.of());
    return !valuta(p, arrival, one, List.of()).sicura();
  }

  /** C3: the second factory of the shuttle's troops (own, producing, next to a port zone). */
  @Nullable
  static Territory secondaFabbrica(final Programmi.Turno p) {
    final Territory t = p.data.getMap().getTerritoryOrNull(Config.get().secondaFabbrica());
    final Territory port = p.porto();
    if (t == null
        || port == null
        || t.equals(port)
        || !t.isOwnedBy(p.player)
        || !t.anyUnitsMatch(Matches.unitCanProduceUnits())) {
      return null;
    }
    final List<Territory> portZones = p.zone(port);
    return p.data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
            .anyMatch(portZones::contains)
        ? t
        : null;
  }

  /**
   * C3: the shuttle's purchase split by factory: the ships and as many troops as fit at the port
   * ({@code placesPort}), the rest of the troops at the second factory.
   */
  static Map<String, Integer> eccedenza(
      final Map<String, Integer> mix, final int placesPort, final Set<String> landTypes) {
    final Map<String, Integer> extra = new LinkedHashMap<>();
    int used = 0;
    for (final var e : mix.entrySet()) {
      if (!landTypes.contains(e.getKey())) {
        used += e.getValue();
      }
    }
    for (final var e : mix.entrySet()) {
      if (!landTypes.contains(e.getKey())) {
        continue;
      }
      final int here = Math.max(0, Math.min(e.getValue(), placesPort - used));
      used += here;
      if (e.getValue() - here > 0) {
        extra.put(e.getKey(), e.getValue() - here);
      }
    }
    return extra;
  }

  // ======================================================================== the register

  static JsonObject perTipo(final Collection<Unit> units) {
    final Map<String, Integer> m = new TreeMap<>();
    units.forEach(u -> m.merge(u.getType().getName(), 1, Integer::sum));
    final JsonObject o = new JsonObject();
    m.forEach(o::addProperty);
    return o;
  }

  private static Set<String> ids(final Collection<Unit> units) {
    final Set<String> out = new HashSet<>();
    units.forEach(u -> out.add(u.getId().toString()));
    return out;
  }

  static JsonObject parametri(final String tipo, final String dove) {
    final Config cfg = Config.get();
    final JsonObject o = new JsonObject();
    o.addProperty("tipo", tipo);
    o.addProperty("luogo", dove);
    o.addProperty("soglia_trasporti", cfg.soglia());
    o.addProperty("round_pericolo", cfg.roundPericolo());
    o.addProperty("zona_rientro", cfg.zonaRientro());
    return o;
  }

  static String registra(
      final GameState data,
      final JsonObject st,
      final GamePlayer player,
      final String type,
      final JsonArray alt,
      final String choice,
      final String code,
      final String why,
      final JsonObject par) {
    try {
      final int round = data.getSequence().getRound();
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), type);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id, round, player.getName(), type, alt, choice, code, why, par));
      return id;
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R6b, registro " + type + ": " + e);
      return "";
    }
  }
}
