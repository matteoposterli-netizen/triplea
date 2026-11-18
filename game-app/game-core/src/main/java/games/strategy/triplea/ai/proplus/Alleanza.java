package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Step P2: the ALLIANCE level above the tasks ({@link Compiti}), driven by the data ({@code
 * compiti.json}, {@code direzioni}): no nation, territory or rule of thumb in the code.
 *
 * <ol>
 *   <li>Direction: at the start of the round (the first task nation of the alliance in turn order)
 *       the alliance computes for every direction its OBSTACLES (P1's analysis with the COMBINED
 *       forces of its task nations) and its VALUE ({@link ValoreDirezione}: PUs per round taken
 *       from the enemy and given to the alliance, the capital, the national objectives of the map,
 *       the urgency of the enemy's victory cities) and chooses the best ratio value / obstacles;
 *       kept {@code tenuta_direzione_round} rounds. Row {@code alleanza_direzione}.
 *   <li>Shared result "testa di ponte tenuta": a territory of the direction's theatre taken and
 *       still allied at the end of the next round. Obtainable = a contributor in turn order takes it
 *       (win >= {@code soglia_attacco}, landing zone safe) and the survivors plus what the later
 *       contributors land the same round hold against the enemy's realistic counterattack (win <=
 *       {@code soglia_tenuta}). Not obtainable: the nations gather troops (TERRA), at most {@code
 *       round_accumulo_max} rounds, then they land anyway where the exchange of losses is best
 *       (attrition). Row {@code alleanza_risultato_condiviso}; every later contributor checks again
 *       on the real board at its turn.
 * </ol>
 */
final class Alleanza {
  private Alleanza() {}

  // ============================================================================ the data

  static Optional<Compito.Livello> livello(final Compiti.Turno t) {
    return Compito.dati().livello(t.alliance).filter(l -> !l.direzioni().isEmpty());
  }

  static boolean ha(final Compiti.Turno t) {
    return livello(t).isPresent();
  }

  static JsonObject al(final Compiti.Turno t) {
    return Compiti.obj(t.st, "alleanza");
  }

  /** The direction the alliance chose (empty before the choice). */
  static Optional<Compito.Direzione> direzione(final Compiti.Turno t) {
    final Optional<Compito.Livello> lv = livello(t);
    final JsonObject al = al(t);
    if (lv.isEmpty() || !al.has("direzione")) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        lv.get().direzioni().get(al.getAsJsonObject("direzione").get("scelta").getAsString()));
  }

  /** The first capital of the direction not owned by the alliance (empty: all taken). */
  static Optional<Territory> capitale(final Compiti.Turno t) {
    return direzione(t).flatMap(d -> prossimaCapitale(t, d));
  }

  static Optional<Territory> prossimaCapitale(final Compiti.Turno t, final Compito.Direzione d) {
    for (final String c : d.capitali()) {
      final Territory x = t.data.getMap().getTerritoryOrNull(c);
      if (x != null && !dellAlleanza(t, x)) {
        return Optional.of(x);
      }
    }
    return Optional.empty();
  }

  static List<Territory> teatro(final Compiti.Turno t, final Compito.Direzione d) {
    final List<Territory> out = new ArrayList<>();
    for (final String n : d.teatro()) {
      final Territory x = t.data.getMap().getTerritoryOrNull(n);
      if (x != null && !x.isWater()) {
        out.add(x);
      }
    }
    return out;
  }

  /**
   * The territory is the alliance's: owned by a nation of the alliance (the data's alliances, not
   * the state of war: a nation not yet at war sees its future allies as neutral) or allied.
   */
  static boolean dellAlleanza(final Compiti.Turno t, final Territory x) {
    final GamePlayer owner = x.getOwner();
    if (owner == null || owner.isNull()) {
      return false;
    }
    return t.alleato(x)
        || ProPlusSettings.allianceOf(t.data, owner).filter(t.alliance::equals).isPresent();
  }

  /** The territory belongs to another alliance of the data (an enemy of the alliance). */
  static boolean nemicoDellAlleanza(final Compiti.Turno t, final Territory x) {
    final GamePlayer owner = x.getOwner();
    if (owner == null || owner.isNull()) {
      return false;
    }
    final Optional<String> a = ProPlusSettings.allianceOf(t.data, owner);
    return a.isPresent() && !a.get().equals(t.alliance);
  }

  /** The nations in the order of their turns (the game's sequence). */
  static List<GamePlayer> ordineTurni(final GameState data) {
    final List<GamePlayer> out = new ArrayList<>();
    for (final GameStep s : data.getSequence()) {
      final GamePlayer p = s.getPlayerId();
      if (p != null && !p.isNull() && !out.contains(p)) {
        out.add(p);
      }
    }
    return out;
  }

  /** The alliance's task nations still in the game, in turn order. */
  static List<GamePlayer> squadra(final Compiti.Turno t) {
    final List<String> names = Compito.dati().nazioniAttive().getOrDefault(t.alliance, List.of());
    final List<GamePlayer> out = new ArrayList<>();
    for (final GamePlayer p : ordineTurni(t.data)) {
      if (names.contains(p.getName())
          && Compiti.compito(t.data, p).isPresent()
          && (p.equals(t.player) || vivo(t.data, p))) {
        out.add(p);
      }
    }
    if (!out.contains(t.player)) {
      out.add(0, t.player);
    }
    return out;
  }

  private static boolean vivo(final GameState data, final GamePlayer p) {
    for (final Territory x : data.getMap().getTerritories()) {
      if (!x.isWater() && x.isOwnedBy(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The combined analysis of the round (cached in the nation's turn), from the point of view of the
   * nation in turn (always at war: {@link #inizioRound}).
   */
  static Compiti.Turno combinata(final Compiti.Turno t) {
    if (t.combinata == null) {
      t.combinata = new Compiti.Turno(t.proData, t.calc, t.player, squadra(t));
    }
    return t.combinata;
  }

  /** {@code p} is at war with a nation of another alliance of the data. */
  static boolean inGuerraConNemici(final Compiti.Turno t, final GamePlayer p) {
    for (final GamePlayer q : t.data.getPlayerList().getPlayers()) {
      final Optional<String> a = ProPlusSettings.allianceOf(t.data, q);
      if (a.isPresent() && !a.get().equals(t.alliance) && p.isAtWar(q)) {
        return true;
      }
    }
    return false;
  }

  /** The current result of {@code m} in the alliance's state (continuous results skipped). */
  @Nullable
  static Compito.Risultato correnteDi(final Compiti.Turno t, final GamePlayer m) {
    final Optional<Compito> task = Compiti.compito(t.data, m);
    if (task.isEmpty()) {
      return null;
    }
    final JsonObject ns = Compiti.obj(Compiti.obj(t.st, "nazioni"), m.getName());
    int i = ns.has("indice") ? ns.get("indice").getAsInt() : 0;
    final List<Compito.Risultato> list = task.get().risultati();
    while (i < list.size() && list.get(i).continuo()) {
      i++;
    }
    return i < list.size() ? list.get(i) : null;
  }

  // ===================================================================== round start

  /**
   * At the first task nation's turn of the round: the bridgeheads taken (ownership seen at the
   * start of every round), then the direction (every {@code tenuta_direzione_round} rounds).
   */
  static void inizioRound(final Compiti.Turno t) {
    final JsonObject al = al(t);
    if (al.has("round") && al.get("round").getAsInt() == t.round) {
      return;
    }
    if (!inGuerraConNemici(t, t.player)) {
      // a nation not yet at war sees no enemy (and its AI data count every non-ally as one): the
      // next task nation of the round, at war, counts for the alliance
      t.lines.add("  Alleanza: " + t.player.getName() + " non e' in guerra, conta la prossima nazione");
      return;
    }
    al.addProperty("round", t.round);
    try {
      aggiornaPrese(t, combinata(t), al);
      scegliDirezione(t, al);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Alleanza, inizio round: " + e);
      t.lines.add("  Alleanza, inizio round: errore " + e + " " + java.util.Arrays.toString(e.getStackTrace()).substring(0, 600));
    }
  }

  /**
   * The territories of the theatres taken by the alliance: an enemy territory seen allied at the
   * start of a round is taken ({@code dal}); {@code visto} = rounds it has been seen allied since.
   */
  static void aggiornaPrese(final Compiti.Turno t, final Compiti.Turno c, final JsonObject al) {
    final JsonObject prese = Compiti.obj(al, "prese");
    final Set<String> enemy = new LinkedHashSet<>();
    if (al.has("nemici")) {
      al.getAsJsonArray("nemici").forEach(e -> enemy.add(e.getAsString()));
    }
    // a bridgehead is enemy ground: a territory of the alliance lost and taken back is not one
    final boolean first = !al.has("nemici_iniziali");
    final Set<String> initial = new LinkedHashSet<>();
    if (!first) {
      al.getAsJsonArray("nemici_iniziali").forEach(e -> initial.add(e.getAsString()));
    }
    final JsonArray now = new JsonArray();
    final Set<Territory> all = new LinkedHashSet<>();
    livello(t).ifPresent(lv -> lv.direzioni().values().forEach(d -> all.addAll(teatro(t, d))));
    for (final Territory x : all) {
      if (dellAlleanza(c, x)) {
        if (prese.has(x.getName())) {
          final JsonObject p = prese.getAsJsonObject(x.getName());
          p.addProperty("visto", p.get("visto").getAsInt() + 1);
        } else if (enemy.contains(x.getName()) && initial.contains(x.getName())) {
          final JsonObject p = new JsonObject();
          p.addProperty("dal", t.round);
          p.addProperty("visto", 1);
          prese.add(x.getName(), p);
        }
      } else {
        prese.remove(x.getName());
        now.add(x.getName());
      }
    }
    al.add("nemici", now);
    if (first) {
      al.add("nemici_iniziali", now.deepCopy());
    }
  }

  /**
   * A bridgehead of the direction held: taken by the alliance and still allied at the end of the
   * next round (seen allied at the start of two rounds).
   */
  static Optional<String> testaDiPonteTenuta(final Compiti.Turno t) {
    final Optional<Compito.Direzione> d = direzione(t);
    if (d.isEmpty()) {
      return Optional.empty();
    }
    final JsonObject prese = Compiti.obj(al(t), "prese");
    for (final Territory x : teatro(t, d.get())) {
      if (prese.has(x.getName())
          && prese.getAsJsonObject(x.getName()).get("visto").getAsInt() >= 2
          && dellAlleanza(t, x)) {
        return Optional.of(x.getName());
      }
    }
    return Optional.empty();
  }

  // ======================================================================= direction

  /**
   * The direction: for every option VALUE and OBSTACLES (combined forces), the best ratio; kept
   * {@code tenuta_direzione_round} rounds.
   */
  static void scegliDirezione(final Compiti.Turno t, final JsonObject al) {
    final Compito.Livello lv = livello(t).orElseThrow();
    final JsonObject old = al.has("direzione") ? al.getAsJsonObject("direzione") : null;
    if (old != null
        && !Risposta.ricontrollaDirezione(
            t.round, old.get("dal_round").getAsInt(), lv.tenutaRound())) {
      return;
    }
    final Compiti.Turno c = combinata(t);
    final List<Risposta.ContoDirezione> conti = new ArrayList<>();
    final JsonArray alt = new JsonArray();
    final JsonObject contiJ = new JsonObject();
    for (final Compito.Direzione d : lv.direzioni().values()) {
      final JsonObject a = new JsonObject();
      a.addProperty("direzione", d.nome());
      final Optional<Territory> capital = prossimaCapitale(c, d);
      if (capital.isEmpty()) {
        a.addProperty("nota", "capitali gia' dell'alleanza");
        conti.add(new Risposta.ContoDirezione(d.nome(), 0, 1e9));
        a.addProperty("valore", 0);
        a.addProperty("ostacoli", 1e9);
        alt.add(a);
        continue;
      }
      final List<Territory> theatre = teatro(c, d);
      final List<Risposta.Bersaglio> cand = new ArrayList<>();
      for (final Territory x : candidati(c, d)) {
        cand.add(c.sbarco(x));
      }
      cand.addAll(c.presa(capital.get()));
      final Risposta.Bersaglio best =
          cand.stream()
              .min(Comparator.comparingDouble(Risposta.Bersaglio::somma).thenComparing(Risposta.Bersaglio::nome))
              .orElse(null);
      final double obstacles = best == null ? 1e9 : best.somma();
      final List<Territory> taken = new ArrayList<>();
      if (best != null && c.data.getMap().getTerritoryOrNull(best.nome()) != null) {
        taken.add(c.data.getMap().getTerritoryOrNull(best.nome()));
      }
      if (!taken.contains(capital.get())) {
        taken.add(capital.get());
      }
      final ValoreDirezione.Conto v =
          ValoreDirezione.valore(
              c.data,
              c.player,
              taken,
              theatre,
              c.par.pesoUrgenzaPu(),
              x -> minacciata(c, x));
      final Risposta.ContoDirezione conto =
          new Risposta.ContoDirezione(d.nome(), v.valore(), obstacles);
      conti.add(conto);
      a.addProperty("capitale", capital.get().getName());
      a.addProperty("valore", Compiti.r1(v.valore()));
      a.addProperty("ostacoli", Compiti.r1(obstacles));
      a.addProperty("rapporto", Math.round(conto.rapporto(c.par.ostacoliMinimiPu()) * 1000) / 1000.0);
      a.add("voci_valore", v.voci());
      if (best != null) {
        a.add("bersaglio", best.json());
      }
      alt.add(a);
      final JsonObject cj = new JsonObject();
      cj.addProperty("valore", Compiti.r1(v.valore()));
      cj.addProperty("ostacoli", Compiti.r1(Math.min(obstacles, 1e9)));
      cj.addProperty("rapporto", Math.round(conto.rapporto(c.par.ostacoliMinimiPu()) * 1000) / 1000.0);
      cj.addProperty("bersaglio", best == null ? "" : best.nome());
      contiJ.add(d.nome(), cj);
    }
    if (conti.isEmpty()) {
      return;
    }
    final String choice = Risposta.scegliDirezioneAlleanza(conti, c.par.ostacoliMinimiPu());
    final String code;
    if (old == null) {
      code = "DIREZIONE_SCELTA";
    } else if (old.get("scelta").getAsString().equals(choice)) {
      code = "DIREZIONE_TENUTA";
    } else {
      code = "DIREZIONE_CAMBIATA";
      al.remove("condiviso");
      for (final var e : Compiti.obj(t.st, "nazioni").entrySet()) {
        e.getValue().getAsJsonObject().remove("bersaglio");
      }
    }
    final JsonObject d = new JsonObject();
    d.addProperty("scelta", choice);
    d.addProperty("dal_round", t.round);
    d.addProperty("fino_al_round", t.round + lv.tenutaRound() - 1);
    d.add("conti", contiJ);
    al.add("direzione", d);
    // the P1 fields (the capital of the direction) for every nation of the alliance
    final Risposta.ContoDirezione ch =
        conti.stream().filter(x -> x.nome().equals(choice)).findFirst().orElseThrow();
    final String why =
        String.format(
            Locale.ROOT,
            "%s: valore %.0f PU a round, ostacoli %.0f PU, rapporto %.2f; %s; vale fino al round %d",
            choice,
            ch.valore(),
            Math.min(ch.ostacoli(), 1e9),
            ch.rapporto(c.par.ostacoliMinimiPu()),
            conti.stream()
                .filter(x -> !x.nome().equals(choice))
                .map(
                    x ->
                        String.format(
                            Locale.ROOT,
                            "%s valore %.0f, ostacoli %.0f, rapporto %.2f",
                            x.nome(),
                            x.valore(),
                            Math.min(x.ostacoli(), 1e9),
                            x.rapporto(c.par.ostacoliMinimiPu())))
                .toList(),
            t.round + lv.tenutaRound() - 1);
    final JsonObject par0 = new JsonObject();
    par0.add("conti", contiJ);
    par0.addProperty("scelta", choice);
    par0.addProperty("fino_al_round", t.round + lv.tenutaRound() - 1);
    par0.addProperty("tenuta_round", lv.tenutaRound());
    par0.addProperty("ostacoli_minimi_pu", c.par.ostacoliMinimiPu());
    final JsonArray squad = new JsonArray();
    c.squadra.forEach(p -> squad.add(p.getName()));
    par0.add("forze_di", squad);
    t.scrivi("alleanza_direzione", alt, choice, code, why, par0);
    t.lines.add("  Alleanza, direzione " + code + ": " + why);
  }

  /**
   * Enemy coastal land territories of the theatre (where a bridgehead can be taken); the
   * direction's capitals after the next one are later results, never a bridgehead.
   */
  static List<Territory> candidati(final Compiti.Turno c, final Compito.Direzione d) {
    final List<Territory> theatre = teatro(c, d);
    final Optional<Territory> next = prossimaCapitale(c, d);
    final List<Territory> out = new ArrayList<>();
    for (final Territory x : theatre) {
      if (d.capitali().contains(x.getName()) && !next.filter(x::equals).isPresent()) {
        continue;
      }
      if (!dellAlleanza(c, x)
          && nemicoDellAlleanza(c, x)
          && !c.data.getMap().getNeighbors(x, Matches.territoryIsWater()).isEmpty()) {
        out.add(x);
      }
    }
    return out;
  }

  /** An allied territory the enemy's realistic attack takes this round (win > soglia_tenuta). */
  static boolean minacciata(final Compiti.Turno c, final Territory x) {
    if (!c.alleato(x)) {
      return false;
    }
    final List<Unit> def =
        x.getMatches(Matches.isUnitAllied(c.player).and(Matches.unitIsNotInfrastructure()));
    return contrattacco(c, x, def, false).vittoria() > c.par.sogliaTenuta();
  }

  // ================================================================== counterattack

  /** The enemy's realistic attack on {@code x}: win%, attackers, its TUV swing. */
  record Contrattacco(double vittoria, List<Unit> attaccanti, double swing) {}

  /**
   * The enemy's realistic attack on {@code x} at the end of the round against exactly {@code
   * defenders}: every enemy unit that can reach it ({@code conquistato}: as if the alliance had
   * taken it, the units in it are gone), the share of it the enemy commits ({@link
   * StimaUnica#impegno}: all of it on a capital), most expensive first.
   */
  static Contrattacco contrattacco(
      final Compiti.Turno t,
      final Territory x,
      final List<Unit> defenders,
      final boolean conquistato) {
    try {
      final String key = x.getName() + "|" + conquistato;
      ProTerritoryManager m = t.nemici.get(key);
      if (m == null) {
        m = new ProTerritoryManager(t.calc, t.proData);
        m.populateEnemyAttackOptions(conquistato ? List.of(x) : List.of(), List.of(x));
        t.nemici.put(key, m);
      }
      final ProTerritory max = m.getEnemyAttackOptions().getMax(x);
      final List<Unit> all = new ArrayList<>();
      if (max != null) {
        all.addAll(max.getMaxUnits());
        max.getMaxAmphibUnits().stream().filter(u -> !all.contains(u)).forEach(all::add);
      }
      if (conquistato) {
        all.removeAll(x.getUnits());
      }
      all.removeIf(u -> !t.player.isAtWar(u.getOwner()));
      if (all.isEmpty()) {
        return new Contrattacco(0, List.of(), 0);
      }
      all.sort(
          Comparator.comparingInt((Unit u) -> -t.tuv(List.of(u)))
              .thenComparing(u -> u.getId().toString()));
      final double share =
          StimaUnica.impegno(
                  t.data,
                  t.alliance,
                  x,
                  conquistato ? StimaUnica.TipoBersaglio.CONQUISTA : StimaUnica.TipoBersaglio.TENUTA)
              .frazione();
      final double total = t.tuv(all);
      final List<Unit> counter = new ArrayList<>();
      double sum = 0;
      for (final Unit u : all) {
        if (sum >= total * share) {
          break;
        }
        counter.add(u);
        sum += t.tuv(List.of(u));
      }
      if (defenders.isEmpty()) {
        return new Contrattacco(100, counter, t.tuv(counter));
      }
      final ProBattleResult r =
          t.calc.calculateBattleResults(t.proData, x, counter, defenders, List.of());
      return new Contrattacco(r.getWinPercentage(), counter, r.getTuvSwing());
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Alleanza, contrattacco: " + e);
      return new Contrattacco(100, List.of(), 0);
    }
  }

  // ================================================================ shared result

  /** The valuation of one bridgehead. */
  record Valutazione(
      Territory territorio,
      @Nullable Territory zona,
      @Nullable GamePlayer presaDa,
      double vittoriaPresa,
      double contrattacco,
      int truppeMancanti,
      double scambio,
      boolean ottenibile,
      JsonObject json) {}

  /**
   * The decision of the round on the shared result, at the first contributing nation's turn (the
   * contributors = the direction's nations whose current result is the shared one).
   */
  static void condivisoDelRound(final Compiti.Turno t) {
    try {
      decidiCondiviso(t);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Alleanza, risultato condiviso: " + e);
      t.lines.add("  Alleanza, risultato condiviso: errore " + e);
    }
  }

  private static void decidiCondiviso(final Compiti.Turno t) {
    if (!inGuerraConNemici(t, t.player)) {
      return;
    }
    final JsonObject al = al(t);
    final Optional<Compito.Direzione> dir = direzione(t);
    if (dir.isEmpty()) {
      return;
    }
    final JsonObject old = al.has("condiviso") ? al.getAsJsonObject("condiviso") : null;
    if (old != null && old.has("round") && old.get("round").getAsInt() == t.round) {
      return;
    }
    final List<GamePlayer> contributors = contributori(t, dir.get());
    if (contributors.isEmpty()) {
      return;
    }
    final Compiti.Turno c = combinata(t);
    final int accDal =
        old != null
                && old.has("accumulo_dal")
                && dir.get().nome().equals(old.has("direzione") ? old.get("direzione").getAsString() : "")
            ? old.get("accumulo_dal").getAsInt()
            : 0;
    final int roundsAcc = Risposta.roundDiAccumulo(t.round, accDal);
    final List<Valutazione> vals = new ArrayList<>();
    for (final Territory x : candidati(c, dir.get())) {
      vals.add(valuta(t, c, x, contributors));
    }
    final List<Valutazione> obtainable = vals.stream().filter(Valutazione::ottenibile).toList();
    final List<Valutazione> gatherable = vals.stream().filter(v -> v.zona() != null).toList();
    final List<Valutazione> attrition = vals.stream().filter(v -> v.presaDa() != null).toList();
    final Risposta.Condivisa dec =
        Risposta.decidiCondiviso(
            !obtainable.isEmpty(),
            !gatherable.isEmpty(),
            roundsAcc,
            c.par.roundAccumuloMax(),
            !attrition.isEmpty());
    final Valutazione chosen =
        switch (dec) {
          case CONGIUNTO ->
              obtainable.stream()
                  .min(Comparator.comparingDouble(Valutazione::contrattacco)
                      .thenComparing(v -> v.territorio().getName()))
                  .orElseThrow();
          case LOGORAMENTO ->
              attrition.stream()
                  .max(Comparator.comparingDouble(Valutazione::scambio)
                      .thenComparing(v -> v.territorio().getName(), Comparator.reverseOrder()))
                  .orElseThrow();
          case ACCUMULO -> {
            final String prev =
                old != null && old.has("territorio") ? old.get("territorio").getAsString() : "";
            yield gatherable.stream()
                .filter(v -> accDal > 0 && v.territorio().getName().equals(prev))
                .findFirst()
                .orElseGet(
                    () ->
                        gatherable.stream()
                            .min(Comparator.comparingInt(Valutazione::truppeMancanti)
                                .thenComparingDouble(Valutazione::contrattacco)
                                .thenComparing(v -> v.territorio().getName()))
                            .orElseThrow());
          }
          case NESSUNO -> null;
        };
    final JsonObject cv = new JsonObject();
    cv.addProperty("round", t.round);
    cv.addProperty("direzione", dir.get().nome());
    cv.addProperty("decisione", dec.name());
    cv.addProperty("accumulo_dal", dec == Risposta.Condivisa.ACCUMULO ? (accDal > 0 ? accDal : t.round) : 0);
    cv.addProperty("round_di_accumulo", roundsAcc);
    final JsonArray cj = new JsonArray();
    contributors.forEach(p -> cj.add(p.getName()));
    cv.add("contributori", cj);
    if (chosen != null) {
      cv.addProperty("territorio", chosen.territorio().getName());
      if (chosen.zona() != null) {
        cv.addProperty("zona", chosen.zona().getName());
      }
      cv.addProperty("presa_da", chosen.presaDa() == null ? "" : chosen.presaDa().getName());
      cv.addProperty("truppe_mancanti", chosen.truppeMancanti());
      cv.addProperty("contrattacco_pct", Compiti.r1(chosen.contrattacco()));
    }
    al.add("condiviso", cv);
    // the register
    final JsonArray alt = new JsonArray();
    vals.stream().filter(v -> v != chosen).forEach(v -> alt.add(v.json()));
    final JsonObject par0 = chosen == null ? new JsonObject() : chosen.json().deepCopy();
    par0.addProperty("decisione", dec.name());
    par0.addProperty("round_di_accumulo", roundsAcc);
    par0.addProperty("round_accumulo_max", c.par.roundAccumuloMax());
    par0.addProperty("direzione", dir.get().nome());
    par0.add("contributori", cj);
    par0.addProperty("candidati", vals.size());
    final String code =
        switch (dec) {
          case CONGIUNTO -> "CONGIUNTO";
          case ACCUMULO -> "ACCUMULO";
          case LOGORAMENTO -> "LOGORAMENTO";
          case NESSUNO -> "NIENTE";
        };
    final String why =
        chosen == null
            ? "nessuna testa di ponte che le forze possano prendere: unita' a Hard"
            : String.format(
                Locale.ROOT,
                "%s su %s: presa da %s (vittoria %.0f%%), contrattacco %.0f%% (soglia %.0f%%),"
                    + " mancano %d truppe; accumulo da %d round",
                dec,
                chosen.territorio().getName(),
                chosen.presaDa() == null ? "nessuno" : chosen.presaDa().getName(),
                chosen.vittoriaPresa(),
                chosen.contrattacco(),
                c.par.sogliaTenuta(),
                chosen.truppeMancanti(),
                roundsAcc);
    t.scrivi(
        "alleanza_risultato_condiviso",
        alt,
        chosen == null ? "nessuno" : chosen.territorio().getName(),
        code,
        why,
        par0);
    t.lines.add("  Alleanza, testa di ponte: " + why);
  }

  /** The direction's contributors whose current result is the shared one, in turn order. */
  static List<GamePlayer> contributori(final Compiti.Turno t, final Compito.Direzione d) {
    final List<GamePlayer> out = new ArrayList<>();
    for (final GamePlayer m : squadra(t)) {
      if (!d.contributori().contains(m.getName())) {
        continue;
      }
      final Compito.Risultato r = m.equals(t.player) ? t.corrente() : correnteDi(t, m);
      if (r != null && Compito.TESTA_DI_PONTE.equals(r.tipo()) && r.valePer(d.nome())) {
        out.add(m);
      }
    }
    return out;
  }

  /** The single-nation analysis of {@code m} (the nation in turn itself, or a fresh one). */
  static Compiti.Turno turnoDi(final Compiti.Turno t, final GamePlayer m) {
    if (m.equals(t.player)) {
      return t;
    }
    return t.altri.computeIfAbsent(m.getName(), k -> new Compiti.Turno(t.proData, t.calc, m));
  }

  /**
   * One bridgehead {@code x}: the landing zone of the combined analysis, what every contributor
   * can land this round, who takes it (the first in turn order whose landing wins >= the threshold
   * with a safe zone), the survivors plus the later contributors' landings against the realistic
   * counterattack, the troops missing to hold, the exchange of losses (attrition).
   */
  static Valutazione valuta(
      final Compiti.Turno t,
      final Compiti.Turno c,
      final Territory x,
      final List<GamePlayer> contributors) {
    final JsonObject j = new JsonObject();
    j.addProperty("territorio", x.getName());
    final Risposta.Bersaglio analysis = c.sbarco(x);
    final Territory z =
        analysis.zona() == null ? null : c.data.getMap().getTerritoryOrNull(analysis.zona());
    j.addProperty("somma_ostacoli_combinati_pu", Compiti.r1(analysis.somma()));
    if (z == null) {
      j.addProperty("nota", "nessuna zona di sbarco raggiungibile");
      return new Valutazione(x, null, null, 0, 100, c.par.maxTruppeStima(), -1e9, false, j);
    }
    j.addProperty("zona", z.getName());
    final JsonObject forces = new JsonObject();
    GamePlayer taker = null;
    Compiti.Turno.ForzaOra takerForce = null;
    final Map<GamePlayer, Compiti.Turno.ForzaOra> all = new LinkedHashMap<>();
    for (final GamePlayer m : contributors) {
      final Compiti.Turno tm = turnoDi(t, m);
      final Compiti.Turno.ForzaOra f = tm.forzaOra(x, z);
      all.put(m, f);
      forces.add(m.getName(), f.json());
      if (taker == null && tm.inGuerraCon(x) && f.prende(c.par.sogliaAttacco())) {
        taker = m;
        takerForce = f;
      }
    }
    j.add("forze_sbarcabili", forces);
    final List<Unit> hold = new ArrayList<>();
    double takeWin = 0;
    double takeSwing = 0;
    if (taker != null) {
      final Compiti.Turno tm = turnoDi(t, taker);
      final List<Unit> def =
          x.getMatches(Matches.enemyUnit(taker).and(Matches.unitIsNotInfrastructure()));
      final ProBattleResult r =
          tm.calc.calculateBattleResults(
              tm.proData, x, tm.propri(takerForce.attaccanti()), def, tm.propri(takerForce.bombardieri()));
      takeWin = r.getWinPercentage();
      takeSwing = r.getTuvSwing();
      hold.addAll(superstiti(takerForce.attaccanti(), r));
      boolean after = false;
      for (final var e : all.entrySet()) {
        if (e.getKey().equals(taker)) {
          after = true;
          continue;
        }
        if (after && e.getValue().sicura()) {
          hold.addAll(e.getValue().carico());
          hold.addAll(
              e.getValue().terraVicina().stream().filter(u -> !hold.contains(u)).toList());
        }
      }
      j.addProperty("presa_da", taker.getName());
      j.addProperty("vittoria_presa_pct", Compiti.r1(takeWin));
    } else {
      // nobody takes it this round: what the contributors could land together (the gathering)
      for (final Compiti.Turno.ForzaOra f : all.values()) {
        f.carico().stream().filter(u -> !hold.contains(u)).forEach(hold::add);
        f.terraVicina().stream().filter(u -> !hold.contains(u)).forEach(hold::add);
      }
      j.addProperty("presa_da", "");
    }
    final List<Unit> holdLand = hold.stream().filter(Matches.unitIsLand()).toList();
    final Contrattacco counter = contrattacco(c, x, holdLand, true);
    j.add("tenuta_con", TrasportiProtetti.perTipo(holdLand));
    j.add("contrattacco", TrasportiProtetti.perTipo(counter.attaccanti()));
    j.addProperty("contrattacco_pct", Compiti.r1(counter.vittoria()));
    j.addProperty("soglia_tenuta_pct", c.par.sogliaTenuta());
    // the troops missing to hold (and to take it, if nobody can)
    int missing = 0;
    if (counter.vittoria() > c.par.sogliaTenuta()) {
      final List<Unit> more = new ArrayList<>(holdLand);
      double w = counter.vittoria();
      while (missing < c.par.maxTruppeStima() && w > c.par.sogliaTenuta()) {
        more.addAll(c.temp(c.par.tipiTruppe().get(missing % c.par.tipiTruppe().size()), 1));
        missing++;
        if (missing % 2 == 0 || missing == c.par.maxTruppeStima()) {
          w = contrattacco(c, x, more, true).vittoria();
        }
      }
      j.addProperty("contrattacco_dopo_pct", Compiti.r1(w));
    }
    if (taker == null) {
      for (final Ostacolo o : analysis.ostacoli()) {
        if (o.tipo() == Ostacolo.Tipo.TERRA && o.numeri().has("truppe_mancanti")) {
          missing = Math.max(missing, o.numeri().get("truppe_mancanti").getAsInt());
        }
      }
    }
    j.addProperty("truppe_mancanti", missing);
    final boolean obtainable = taker != null && counter.vittoria() <= c.par.sogliaTenuta();
    j.addProperty("ottenibile", obtainable);
    final double exchange = taker == null ? -1e9 : takeSwing - counter.swing();
    if (taker != null) {
      j.addProperty("scambio_tuv", Compiti.r1(exchange));
      j.addProperty("swing_presa", Compiti.r1(takeSwing));
      j.addProperty("swing_contrattacco_nemico", Compiti.r1(counter.swing()));
    }
    return new Valutazione(
        x, z, taker, takeWin, counter.vittoria(), missing, exchange, obtainable, j);
  }

  /** The attackers that survive {@code r} on average (land units first, matched by type). */
  static List<Unit> superstiti(final List<Unit> attackers, final ProBattleResult r) {
    final Map<String, Integer> left = new TreeMap<>();
    for (final Unit u : r.getAverageAttackersRemaining()) {
      left.merge(u.getType().getName(), 1, Integer::sum);
    }
    final List<Unit> out = new ArrayList<>();
    for (final Unit u : attackers) {
      final int n = left.getOrDefault(u.getType().getName(), 0);
      if (n > 0 && Matches.unitIsLand().test(u)) {
        out.add(u);
        left.put(u.getType().getName(), n - 1);
      }
    }
    return out;
  }

  // ============================================================= the nation's turn

  /**
   * The nation's target for the shared result this turn: the alliance's decision checked again on
   * the real board ({@link Compiti.Turno#condivisoNazione}). A bridgehead already allied
   * (taken this round by an earlier contributor) is reinforced in the non-combat move; an enemy
   * one is taken if this nation's landing wins (CONGIUNTO: and holds; LOGORAMENTO: anyway).
   */
  static Optional<Risposta.Bersaglio> bersaglio(final Compiti.Turno t) {
    final JsonObject plan = new JsonObject();
    t.condivisoNazione = plan;
    final JsonObject al = al(t);
    final JsonObject cv = al.has("condiviso") ? al.getAsJsonObject("condiviso") : null;
    if (cv == null
        || !cv.has("round")
        || cv.get("round").getAsInt() != t.round
        || !cv.has("territorio")
        || !cv.has("zona")) {
      plan.addProperty("decisione", Risposta.Condivisa.NESSUNO.name());
      return Optional.empty();
    }
    final Risposta.Condivisa dec = Risposta.Condivisa.valueOf(cv.get("decisione").getAsString());
    final Territory x = t.data.getMap().getTerritoryOrNull(cv.get("territorio").getAsString());
    final Territory z = t.data.getMap().getTerritoryOrNull(cv.get("zona").getAsString());
    plan.addProperty("decisione", dec.name());
    if (x == null || z == null || dec == Risposta.Condivisa.NESSUNO) {
      plan.addProperty("decisione", Risposta.Condivisa.NESSUNO.name());
      return Optional.empty();
    }
    plan.addProperty("condiviso_territorio", x.getName());
    plan.addProperty("condiviso_zona", z.getName());
    final Compiti.Turno.ForzaOra f = t.forzaOra(x, z);
    plan.add("forza_ora", f.json());
    boolean obtainable = false;
    boolean reinforce = false;
    List<Unit> hold;
    if (t.alleato(x)) {
      // taken this round (or before) by an ally: this nation reinforces it in the non-combat move
      reinforce = f.sicura() && !f.carico().isEmpty();
      hold = new ArrayList<>(x.getMatches(Matches.isUnitAllied(t.player).and(Matches.unitIsLand()).and(Matches.unitIsNotInfrastructure())));
      if (reinforce) {
        hold.addAll(f.carico());
      }
      hold.addAll(f.terraVicina().stream().filter(u -> !hold.contains(u)).toList());
      plan.addProperty("ruolo", "RINFORZO");
    } else {
      final boolean canTake = t.inGuerraCon(x) && f.prende(t.par.sogliaAttacco());
      hold = new ArrayList<>();
      if (canTake) {
        final List<Unit> def =
            x.getMatches(Matches.enemyUnit(t.player).and(Matches.unitIsNotInfrastructure()));
        final ProBattleResult r =
            t.calc.calculateBattleResults(t.proData, x, f.attaccanti(), def, f.bombardieri());
        hold.addAll(superstiti(f.attaccanti(), r));
        // the later contributors of the round reinforce it
        final List<GamePlayer> contributors = new ArrayList<>();
        cv.getAsJsonArray("contributori")
            .forEach(e -> {
              final GamePlayer p = t.data.getPlayerList().getPlayerId(e.getAsString());
              if (p != null) {
                contributors.add(p);
              }
            });
        boolean after = false;
        for (final GamePlayer m : contributors) {
          if (m.equals(t.player)) {
            after = true;
            continue;
          }
          if (after) {
            final Compiti.Turno.ForzaOra g = turnoDi(t, m).forzaOra(x, z);
            if (g.sicura()) {
              hold.addAll(g.carico());
            }
          }
        }
      }
      plan.addProperty("ruolo", canTake ? "PRESA" : "ACCUMULO");
      if (canTake && dec == Risposta.Condivisa.LOGORAMENTO) {
        obtainable = true;
      } else if (canTake && dec == Risposta.Condivisa.CONGIUNTO) {
        final double w = contrattacco(t, x, hold, true).vittoria();
        plan.addProperty("contrattacco_ricontrollato_pct", Compiti.r1(w));
        obtainable = w <= t.par.sogliaTenuta();
      }
    }
    plan.addProperty("rinforzo", reinforce);
    plan.addProperty("sbarco_ora", obtainable);
    // the obstacles: P1's analysis of the landing with this nation's forces, TERRA = the troops
    // missing to hold (the alliance's count)
    final Risposta.Bersaglio own = t.sbarco(x);
    final List<Ostacolo> obs = new ArrayList<>();
    for (final Ostacolo o : own.ostacoli()) {
      if (o.tipo() != Ostacolo.Tipo.TERRA) {
        obs.add(o);
      }
    }
    final int missing = cv.has("truppe_mancanti") ? cv.get("truppe_mancanti").getAsInt() : 0;
    if (missing > 0) {
      final JsonObject tj = new JsonObject();
      tj.addProperty("truppe_mancanti", missing);
      tj.addProperty("contrattacco_pct", cv.has("contrattacco_pct") ? cv.get("contrattacco_pct").getAsDouble() : -1);
      tj.addProperty("nota", "truppe che mancano per tenere la testa di ponte (decisione dell'alleanza)");
      int pu = 0;
      for (int k = 0; k < missing; k++) {
        pu += t.costo(t.par.tipiTruppe().get(k % t.par.tipiTruppe().size()));
      }
      obs.add(new Ostacolo(Ostacolo.Tipo.TERRA, x.getName(), pu, f.vittoria(), tj));
    }
    t.lines.add(
        "  Testa di ponte "
            + x.getName()
            + " ("
            + dec
            + "): "
            + plan.get("ruolo").getAsString()
            + (obtainable ? ", sbarco ora" : "")
            + (reinforce ? ", rinforzo nel non combattimento" : ""));
    return Optional.of(
        new Risposta.Bersaglio(x.getName(), z.getName(), obtainable, List.copyOf(obs), null, -1));
  }

  /** The nation's part of the shared result (fields of the turn's plan). */
  static Optional<JsonObject> pianoCondiviso(final Compiti.Turno t) {
    return Optional.ofNullable(t.condivisoNazione);
  }

  /** The decision of the alliance this round for the register of the landings. */
  static String decisioneDelRound(final Compiti.Turno t) {
    final JsonObject al = al(t);
    if (al.has("condiviso")) {
      final JsonObject cv = al.getAsJsonObject("condiviso");
      if (cv.has("round") && cv.get("round").getAsInt() == t.round) {
        return cv.get("decisione").getAsString();
      }
    }
    return "";
  }

  static List<String> strings(final JsonArray a) {
    final List<String> out = new ArrayList<>();
    for (final JsonElement e : a) {
      out.add(e.getAsString());
    }
    return out;
  }

  static Map<String, Integer> vuota() {
    return new HashMap<>();
  }
}
