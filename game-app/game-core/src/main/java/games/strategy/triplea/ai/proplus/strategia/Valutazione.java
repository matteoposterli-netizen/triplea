package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.BattleRecordsList;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.history.Event;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Step;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.ProPlusSettings;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.data.BattleRecords;
import games.strategy.triplea.delegate.data.PlacementDescription;
import games.strategy.triplea.util.TuvCostsCalculator;
import games.strategy.triplea.util.TuvUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Step 3c, observation only (no AI decision reads it): at the start of each round, for each
 * alliance, its general staff checks the progress of its own plan and reads the enemy's plan. The
 * result is appended to the alliance's history ({@code storico} of {@link StatoMaggiore}) and
 * written to the decision diary.
 *
 * <p>Progress: each milestone of the plan is reached (the alliance has taken its territories: holds
 * them and they were not its own at the start of the game, or were seen in other hands at an
 * earlier start of round; once reached it stays reached), on time, or late (its round is over and
 * it is not reached); the alliance's production against round 1 and against the enemy; the TUV
 * balance of every battle so far (enemy losses minus own losses, from the battle records). State:
 *
 * <ul>
 *   <li>{@value #IN_CRISI}: at least 2 milestones late and production down in each of the last 2
 *       rounds;
 *   <li>{@value #IN_RITARDO}: at least 1 milestone late, or production down in each of the last 2
 *       rounds;
 *   <li>{@value #IN_LINEA}: otherwise.
 * </ul>
 *
 * <p>Reading the enemy (step 3c-bis: cumulated evidence): each clue of an enemy plan's signature
 * ({@code firma}, {@link Strategia.Firma}) is measured, turned into 0..1 and remembered across
 * rounds in the history ({@code lettura.memoria}): a state clue as {@link #MEMORIA} × remembered +
 * (1 − {@link #MEMORIA}) × this round's value; an event clue (a battle) as the larger of this
 * round's value and {@link #MEMORIA_EVENTI} × remembered, so a battle seen once fades slowly. A
 * clue not measurable this round, or out of its rounds, keeps its remembered value. Each plan's
 * score is the weighted mean of its clues' remembered values; the probabilities are the scores
 * squared (to sharpen) plus {@link #EPSILON}, normalized. Enemy purchases and placements come from
 * the game history (purchase and place steps), battles from the battle records.
 */
@UtilityClass
public final class Valutazione {
  public static final String IN_LINEA = "IN LINEA";
  public static final String IN_RITARDO = "IN RITARDO";
  public static final String IN_CRISI = "IN CRISI";

  /** Weight of a state clue's remembered value against this round's value (step 3c-bis). */
  public static final double MEMORIA = 0.6;

  /** Per-round decay of a remembered event clue (a battle) not seen again (step 3c-bis). */
  public static final double MEMORIA_EVENTI = 0.75;

  /** Added to every squared score, so no plan drops to 0 at once. */
  public static final double EPSILON = 0.05;

  /**
   * Enemy purchases and placements read for {@link Strategia.Firma#QUOTA_ACQUISTI} and {@link
   * Strategia.Firma#PIAZZAMENTI}: the last ones per nation.
   */
  static final int PURCHASES = 2;

  /**
   * What the general staff of {@code alleanza} found at the start of {@code round}.
   *
   * @param storico the entry appended to the alliance's history
   * @param testo the diary block
   */
  public record Risultato(String alleanza, int round, JsonObject storico, String testo) {}

  /**
   * The general staff's check at the start of the round, made by the first Pro+ nation of the
   * alliance to play in it (the others find the entry already there: empty). Appends to the
   * alliance's history. Empty without a fixed plan or strategy file.
   */
  public static Optional<Risultato> inizioRound(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    final Optional<Strategia> strategia = Strategie.forGame(data);
    if (alliance.isEmpty() || strategia.isEmpty()) {
      return Optional.empty();
    }
    final Optional<StatoMaggiore.Stato> state =
        StatoMaggiore.getState(data.getProperties(), alliance.get());
    final int round = data.getSequence().getRound();
    if (state.isEmpty() || lastRound(state.get().storico()) >= round) {
      return Optional.empty();
    }
    final Optional<Strategia.Piano> plan =
        strategia.get().alleanza(alliance.get()).flatMap(a -> a.piano(state.get().piano()));
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    final Risultato result =
        valuta(data, alliance.get(), strategia.get(), plan.get(), state.get().storico(), round);
    StatoMaggiore.aggiungiStorico(data, alliance.get(), result.storico());
    return Optional.of(result);
  }

  static Risultato valuta(
      final GameState data,
      final String alliance,
      final Strategia strategia,
      final Strategia.Piano plan,
      final JsonArray storico,
      final int round) {
    final List<GamePlayer> own = players(data, alliance);
    final Optional<String> enemyAlliance =
        ProPlusSettings.configurableAlliances(data).stream()
            .filter(a -> !a.equals(alliance))
            .findFirst();
    final List<GamePlayer> enemies = enemyAlliance.map(a -> players(data, a)).orElse(List.of());

    final JsonObject entry = new JsonObject();
    entry.addProperty("round", round);
    final JsonObject progress = progress(data, plan, own, enemies, storico, round);
    entry.add("avanzamento", progress);
    final StringBuilder text = new StringBuilder();
    text.append(
        String.format(
            Locale.ROOT,
            "%n================================================================%n"
                + "Stato maggiore %s - inizio round %d (piano %s)%n",
            alliance,
            round,
            plan.label()));
    text.append("  ").append(progressText(progress)).append('\n');

    final Optional<Strategia.Alleanza> enemyPlans =
        enemyAlliance.flatMap(a -> strategia.alleanza(a));
    if (enemyPlans.isPresent()) {
      final JsonObject reading =
          reading(data, strategia, enemyPlans.get(), lastEntry(storico), round);
      enemyAlliance
          .flatMap(a -> StatoMaggiore.getState(data.getProperties(), a))
          .ifPresent(
              s -> {
                reading.addProperty("piano_reale", s.piano());
                reading.addProperty(
                    "lettura_corretta",
                    s.piano().equals(reading.get("piu_probabile").getAsString()));
              });
      entry.add("lettura", reading);
      text.append("  ").append(readingText(reading, enemyPlans.get())).append('\n');
    }
    return new Risultato(alliance, round, entry, text.toString());
  }

  // ------------------------------------------------------------------------------- progress

  private static JsonObject progress(
      final GameState data,
      final Strategia.Piano plan,
      final List<GamePlayer> own,
      final List<GamePlayer> enemies,
      final JsonArray storico,
      final int round) {
    final JsonObject json = new JsonObject();
    final JsonArray milestones = new JsonArray();
    final Map<String, Integer> reachedBefore = reachedBefore(storico);
    int late = 0;
    for (final Strategia.Tappa tappa : plan.tappe()) {
      final JsonObject m = new JsonObject();
      m.addProperty("tappa", tappa.label());
      m.addProperty("entro_round", tappa.entroRound());
      final Integer before = reachedBefore.get(tappa.label());
      if (before != null) {
        m.addProperty("stato", "raggiunta");
        m.addProperty("round_raggiunta", before);
      } else if (taken(data, tappa, own, seenLost(storico, tappa.label()))) {
        m.addProperty("stato", "raggiunta");
        m.addProperty("round_raggiunta", round);
      } else if (round > tappa.entroRound()) {
        m.addProperty("stato", "in ritardo");
        late++;
      } else {
        m.addProperty("stato", "in tempo");
      }
      final JsonArray lost = new JsonArray();
      tappa.territori().stream().filter(name -> !isHeld(data, name, own)).forEach(lost::add);
      m.add("non_dell_alleanza", lost);
      milestones.add(m);
    }
    json.add("tappe", milestones);
    final int income = production(data, own);
    final int enemyIncome = production(data, enemies);
    final List<Integer> incomes = new ArrayList<>(incomes(storico));
    final int firstIncome = incomes.isEmpty() ? income : incomes.get(0);
    incomes.add(income);
    final int n = incomes.size();
    final boolean falling =
        n >= 3
            && incomes.get(n - 1) < incomes.get(n - 2)
            && incomes.get(n - 2) < incomes.get(n - 3);
    json.addProperty("reddito", income);
    json.addProperty("reddito_r1", firstIncome);
    json.addProperty("reddito_nemico", enemyIncome);
    json.addProperty("reddito_in_calo_2_round", falling);
    final int[] tuv = tuvBalance(data, own, enemies);
    json.addProperty("tuv_perso", tuv[0]);
    json.addProperty("tuv_nemico_distrutto", tuv[1]);
    json.addProperty("bilancio_tuv", tuv[1] - tuv[0]);
    json.addProperty("tappe_in_ritardo", late);
    String state;
    if (late >= 2 && falling) {
      state = IN_CRISI;
    } else if (late >= 1 || falling) {
      state = IN_RITARDO;
    } else {
      state = IN_LINEA;
    }
    // step OPERAZIONI ANFIBIE-BIS: an operation in a stall makes the progress one level worse
    final JsonArray stalled = new JsonArray();
    plan.operazioni().stream()
        .filter(o -> StalloOperazioni.inStallo(data.getProperties(), o.nome()))
        .forEach(o -> stalled.add(o.nome()));
    json.add("operazioni_in_stallo", stalled);
    if (!stalled.isEmpty()) {
      state = IN_LINEA.equals(state) ? IN_RITARDO : IN_CRISI;
    }
    json.addProperty("stato", state);
    return json;
  }

  /**
   * Whether the alliance has taken the milestone's territories (all, or one): it holds them now,
   * and each was not its own at the start of the game or was seen in other hands at an earlier
   * start of round (Normandy, French at the start, counts for the Allies only once retaken).
   */
  private static boolean taken(
      final GameState data,
      final Strategia.Tappa tappa,
      final List<GamePlayer> own,
      final Set<String> seenLost) {
    final Predicate<String> isTaken =
        name -> {
          final Territory t = data.getMap().getTerritoryOrNull(name);
          if (t == null || !own.contains(t.getOwner())) {
            return false;
          }
          return seenLost.contains(name)
              || OriginalOwnerTracker.getOriginalOwner(t).map(o -> !own.contains(o)).orElse(false);
        };
    return tappa.tutti()
        ? tappa.territori().stream().allMatch(isTaken)
        : tappa.territori().stream().anyMatch(isTaken);
  }

  private static boolean isHeld(
      final GameState data, final String name, final List<GamePlayer> own) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    return t != null && own.contains(t.getOwner());
  }

  /** Territories of a milestone seen not held by the alliance at an earlier start of round. */
  private static Set<String> seenLost(final JsonArray storico, final String label) {
    final Set<String> lost = new HashSet<>();
    for (final JsonElement e : storico) {
      final JsonObject entry = e.getAsJsonObject();
      if (!entry.has("avanzamento")) {
        continue;
      }
      for (final JsonElement m : entry.getAsJsonObject("avanzamento").getAsJsonArray("tappe")) {
        final JsonObject o = m.getAsJsonObject();
        if (label.equals(o.get("tappa").getAsString()) && o.has("non_dell_alleanza")) {
          o.getAsJsonArray("non_dell_alleanza").forEach(n -> lost.add(n.getAsString()));
        }
      }
    }
    return lost;
  }

  /** Milestones reached at an earlier start of round: label → round. */
  private static Map<String, Integer> reachedBefore(final JsonArray storico) {
    final Map<String, Integer> reached = new HashMap<>();
    for (final JsonElement e : storico) {
      final JsonObject entry = e.getAsJsonObject();
      if (!entry.has("avanzamento")) {
        continue;
      }
      for (final JsonElement m : entry.getAsJsonObject("avanzamento").getAsJsonArray("tappe")) {
        final JsonObject o = m.getAsJsonObject();
        if ("raggiunta".equals(o.get("stato").getAsString())) {
          reached.putIfAbsent(o.get("tappa").getAsString(), o.get("round_raggiunta").getAsInt());
        }
      }
    }
    return reached;
  }

  private static List<Integer> incomes(final JsonArray storico) {
    final List<Integer> incomes = new ArrayList<>();
    for (final JsonElement e : storico) {
      final JsonObject entry = e.getAsJsonObject();
      if (entry.has("avanzamento")) {
        incomes.add(entry.getAsJsonObject("avanzamento").get("reddito").getAsInt());
      }
    }
    return incomes;
  }

  private static int production(final GameState data, final List<GamePlayer> players) {
    return (int)
        Math.round(players.stream().mapToDouble(p -> ProUtils.getPlayerProduction(p, data)).sum());
  }

  /**
   * TUV lost by {@code own} and destroyed of {@code enemies} in every battle so far: {own lost,
   * enemy lost}. A battle is counted by its attacker; the defender is assumed to be of the other
   * side (attacks on neutrals count as enemy losses).
   */
  static int[] tuvBalance(
      final GameState data, final List<GamePlayer> own, final List<GamePlayer> enemies) {
    int ownLost = 0;
    int enemyLost = 0;
    final BattleRecordsList list = data.getBattleRecordsList();
    if (list == null) {
      return new int[] {0, 0};
    }
    for (final BattleRecords records : list.getBattleRecordsMapCopy().values()) {
      for (final GamePlayer p : own) {
        final var mine = BattleRecords.getRecordsForPlayerId(p, records);
        ownLost += BattleRecords.getLostTuvForBattleRecords(mine, true, false);
        enemyLost += BattleRecords.getLostTuvForBattleRecords(mine, false, false);
      }
      for (final GamePlayer p : enemies) {
        final var theirs = BattleRecords.getRecordsForPlayerId(p, records);
        enemyLost += BattleRecords.getLostTuvForBattleRecords(theirs, true, false);
        ownLost += BattleRecords.getLostTuvForBattleRecords(theirs, false, false);
      }
    }
    return new int[] {ownLost, enemyLost};
  }

  /**
   * Eg: "Avanzamento: IN LINEA - tappe: France R1 raggiunta (R1), Egypt R4 in tempo; reddito 74 PU
   * (R1 70, nemico 82), bilancio TUV +12 (nemico -40, noi -28)".
   */
  static String progressText(final JsonObject p) {
    final List<String> milestones = new ArrayList<>();
    for (final JsonElement e : p.getAsJsonArray("tappe")) {
      final JsonObject m = e.getAsJsonObject();
      final String state = m.get("stato").getAsString();
      milestones.add(
          m.get("tappa").getAsString()
              + " "
              + state
              + (m.has("round_raggiunta")
                  ? " (R" + m.get("round_raggiunta").getAsInt() + ")"
                  : ""));
    }
    final int balance = p.get("bilancio_tuv").getAsInt();
    return String.format(
            Locale.ROOT,
            "Avanzamento: %s - tappe: %s; reddito %d PU (R1 %d, nemico %d%s), bilancio TUV %s%d"
                + " (nemico -%d, noi -%d)",
            p.get("stato").getAsString(),
            milestones.isEmpty() ? "nessuna" : String.join(", ", milestones),
            p.get("reddito").getAsInt(),
            p.get("reddito_r1").getAsInt(),
            p.get("reddito_nemico").getAsInt(),
            p.get("reddito_in_calo_2_round").getAsBoolean() ? "; in calo da 2 round" : "",
            balance >= 0 ? "+" : "",
            balance,
            p.get("tuv_nemico_distrutto").getAsInt(),
            p.get("tuv_perso").getAsInt())
        + (p.has("operazioni_in_stallo") && !p.getAsJsonArray("operazioni_in_stallo").isEmpty()
            ? "; operazioni in stallo: "
                + String.join(", ", strings(p.getAsJsonArray("operazioni_in_stallo")))
            : "");
  }

  private static List<String> strings(final JsonArray array) {
    final List<String> list = new ArrayList<>();
    array.forEach(e -> list.add(e.getAsString()));
    return list;
  }

  // ------------------------------------------------------------------------------- reading

  private static JsonObject reading(
      final GameState data,
      final Strategia strategia,
      final Strategia.Alleanza enemy,
      final @Nullable JsonObject last,
      final int round) {
    final List<Strategia.Piano> plans =
        enemy.piani().stream().filter(p -> !p.firma().isEmpty()).toList();
    final JsonObject previousMemory =
        last != null && last.has("lettura") && last.getAsJsonObject("lettura").has("memoria")
            ? last.getAsJsonObject("lettura").getAsJsonObject("memoria")
            : new JsonObject();
    final Measures measures = new Measures(data, strategia, round);
    final Map<String, Double> scores = new LinkedHashMap<>();
    final List<Clue> clues = new ArrayList<>();
    final JsonObject cluesJson = new JsonObject();
    final JsonObject memory = new JsonObject();
    for (final Strategia.Piano plan : plans) {
      final JsonObject planBefore =
          previousMemory.has(plan.id())
              ? previousMemory.getAsJsonObject(plan.id())
              : new JsonObject();
      final JsonObject planMemory = new JsonObject();
      double weighted = 0;
      double weights = 0;
      final JsonArray planClues = new JsonArray();
      for (final Strategia.Firma f : plan.firma()) {
        final Double before =
            planBefore.has(f.indizio()) ? planBefore.get(f.indizio()).getAsDouble() : null;
        final Double x = f.covers(round) ? measures.measure(f).value() : null;
        final Double now = x == null ? null : f.valore(x);
        final Double remembered = remember(f, before, now);
        final JsonObject c = new JsonObject();
        c.addProperty("indizio", f.indizio());
        if (x == null) {
          c.addProperty("misura", f.covers(round) ? "non disponibile" : "fuori dai suoi round");
        } else {
          c.addProperty("misura", round2(x));
          c.addProperty("valore_round", round2(now));
        }
        if (remembered == null) {
          planClues.add(c);
          continue;
        }
        planMemory.addProperty(f.indizio(), round3(remembered));
        weighted += f.peso() * remembered;
        weights += f.peso();
        c.addProperty("valore", round2(remembered));
        c.addProperty("peso", f.peso());
        planClues.add(c);
        clues.add(new Clue(plan, f, remembered));
      }
      final double score = weights > 0 ? weighted / weights : 0;
      scores.put(plan.id(), score);
      cluesJson.add(plan.id(), planClues);
      memory.add(plan.id(), planMemory);
    }
    double total = 0;
    final Map<String, Double> raw = new LinkedHashMap<>();
    for (final Map.Entry<String, Double> e : scores.entrySet()) {
      final double r = e.getValue() * e.getValue() + EPSILON;
      raw.put(e.getKey(), r);
      total += r;
    }
    final JsonObject json = new JsonObject();
    final JsonObject probs = new JsonObject();
    final JsonObject scoresJson = new JsonObject();
    String best = "";
    double bestP = -1;
    for (final Map.Entry<String, Double> e : raw.entrySet()) {
      final double p = e.getValue() / total;
      probs.addProperty(e.getKey(), round3(p));
      scoresJson.addProperty(e.getKey(), round2(scores.get(e.getKey())));
      if (p > bestP) {
        bestP = p;
        best = e.getKey();
      }
    }
    json.add("probabilita", probs);
    json.add("punteggi", scoresJson);
    json.addProperty("piu_probabile", best);
    json.add("indizi", cluesJson);
    json.add("memoria", memory);
    final JsonArray main = new JsonArray();
    clues.stream()
        .filter(c -> c.value() > 0)
        .sorted(
            Comparator.comparingDouble((Clue c) -> -c.firma().peso() * c.value())
                .thenComparing(c -> c.plan().id()))
        .limit(3)
        .forEach(
            c ->
                main.add(
                    c.firma().indizio()
                        + " ("
                        + c.plan().label()
                        + ", "
                        + Math.round(c.value() * 100)
                        + "%)"));
    json.add("indizi_principali", main);
    return json;
  }

  /**
   * Eg: "Lettura del nemico: 1 Germania prima 55%, 2 Italia prima 30%, 3 Pressione economica 15% -
   * indizi principali: ... (piano reale avversario: 1 Germania prima - lettura corretta si')".
   */
  static String readingText(final JsonObject r, final Strategia.Alleanza enemy) {
    final List<String> probabilities = new ArrayList<>();
    r.getAsJsonObject("probabilita").entrySet().stream()
        .sorted(Comparator.comparingDouble(e -> -e.getValue().getAsDouble()))
        .forEach(
            e ->
                probabilities.add(
                    enemy.piano(e.getKey()).map(Strategia.Piano::label).orElse(e.getKey())
                        + " "
                        + Math.round(e.getValue().getAsDouble() * 100)
                        + "%"));
    final List<String> main = new ArrayList<>();
    r.getAsJsonArray("indizi_principali").forEach(e -> main.add(e.getAsString()));
    final StringBuilder sb =
        new StringBuilder("Lettura del nemico: ")
            .append(String.join(", ", probabilities))
            .append(" - indizi principali: ")
            .append(main.isEmpty() ? "nessuno" : String.join("; ", main));
    if (r.has("piano_reale")) {
      final String real = r.get("piano_reale").getAsString();
      sb.append(" (piano reale avversario: ")
          .append(enemy.piano(real).map(Strategia.Piano::label).orElse(real))
          .append(" - lettura corretta ")
          .append(r.get("lettura_corretta").getAsBoolean() ? "si'" : "no")
          .append(')');
    }
    return sb.toString();
  }

  /**
   * The clue's value to remember: this round's {@code now} merged with the remembered {@code
   * before} (either may be missing), see the class comment.
   */
  static @Nullable Double remember(
      final Strategia.Firma f, final @Nullable Double before, final @Nullable Double now) {
    if (now == null || before == null) {
      return now == null ? before : now;
    }
    return f.isEvento()
        ? Math.max(now, MEMORIA_EVENTI * before)
        : MEMORIA * before + (1 - MEMORIA) * now;
  }

  private record Clue(Strategia.Piano plan, Strategia.Firma firma, double value) {}

  private record OptionalMeasure(@Nullable Double value) {}

  /** The measures of the clues on the current game state (TUV costs and history cached). */
  private static final class Measures {
    private final GameState data;
    private final Strategia strategia;
    private final int round;
    private final Map<String, IntegerMap<UnitType>> costs = new HashMap<>();
    private final Map<String, List<Unit>> purchases = new HashMap<>();
    private final Map<String, List<Placement>> placements = new HashMap<>();

    Measures(final GameState data, final Strategia strategia, final int round) {
      this.data = data;
      this.strategia = strategia;
      this.round = round;
    }

    OptionalMeasure measure(final Strategia.Firma f) {
      final List<GamePlayer> nations = players(f.nazioni());
      if (nations.isEmpty()) {
        return new OptionalMeasure(null);
      }
      return switch (f.tipo()) {
        case Strategia.Firma.QUOTA_TUV_FRONTE -> frontShare(f, nations);
        case Strategia.Firma.QUOTA_ACQUISTI -> purchaseShare(f, nations);
        case Strategia.Firma.PIAZZAMENTI -> placementShare(f, nations);
        case Strategia.Firma.ATTACCHI -> attacks(f, nations);
        case Strategia.Firma.IN_GUERRA -> atWar(f, nations);
        default -> unitsIn(f, nations);
      };
    }

    private List<GamePlayer> players(final List<String> names) {
      return names.stream()
          .map(n -> data.getPlayerList().getPlayerId(n))
          .filter(p -> p != null && !p.isNull())
          .toList();
    }

    private OptionalMeasure frontShare(final Strategia.Firma f, final List<GamePlayer> nations) {
      double onFront = 0;
      double all = 0;
      final Predicate<Unit> land = Matches.unitIsLand().and(Matches.unitIsNotInfrastructure());
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.isWater()) {
          continue;
        }
        final boolean front = f.fronte().equals(strategia.fronteOf(t));
        for (final GamePlayer p : nations) {
          final int tuv =
              TuvUtils.getTuv(t.getMatches(land.and(Matches.unitIsOwnedBy(p))), cost(p));
          all += tuv;
          if (front) {
            onFront += tuv;
          }
        }
      }
      return new OptionalMeasure(all > 0 ? onFront / all : 0.0);
    }

    private OptionalMeasure purchaseShare(final Strategia.Firma f, final List<GamePlayer> nations) {
      double matching = 0;
      double all = 0;
      final Predicate<Unit> filter = filter(f);
      for (final GamePlayer p : nations) {
        for (final Unit u : purchases(p)) {
          final double c = cost(p).getInt(u.getType());
          all += c;
          if (filter.test(u)) {
            matching += c;
          }
        }
      }
      return new OptionalMeasure(all > 0 ? matching / all : null);
    }

    /** TUV placed in {@code territori} / in {@code territori} + {@code altrove} (or anywhere). */
    private OptionalMeasure placementShare(
        final Strategia.Firma f, final List<GamePlayer> nations) {
      final Set<String> here = Set.copyOf(f.territori());
      final Set<String> elsewhere = Set.copyOf(f.altrove());
      final Predicate<Unit> filter = filter(f);
      double inHere = 0;
      double all = 0;
      for (final GamePlayer p : nations) {
        for (final Placement placement : placements(p)) {
          final boolean isHere = here.contains(placement.territory());
          if (!isHere && !elsewhere.isEmpty() && !elsewhere.contains(placement.territory())) {
            continue;
          }
          for (final Unit u : placement.units()) {
            if (filter.test(u)) {
              final double c = cost(p).getInt(u.getType());
              all += c;
              if (isHere) {
                inHere += c;
              }
            }
          }
        }
      }
      return new OptionalMeasure(all > 0 ? inHere / all : null);
    }

    /** Territories of the clue with a battle of the nations as attacker in this or last round. */
    private OptionalMeasure attacks(final Strategia.Firma f, final List<GamePlayer> nations) {
      final int here = battlesIn(f.territori(), nations, players(f.contro()));
      if (f.altrove().isEmpty()) {
        return new OptionalMeasure((double) here);
      }
      final int elsewhere = battlesIn(f.altrove(), nations, players(f.contro()));
      return new OptionalMeasure(here + elsewhere > 0 ? (double) here / (here + elsewhere) : null);
    }

    private int battlesIn(
        final List<String> names,
        final List<GamePlayer> attackers,
        final List<GamePlayer> defenders) {
      final BattleRecordsList list = data.getBattleRecordsList();
      if (list == null) {
        return 0;
      }
      final Map<Integer, BattleRecords> byRound = list.getBattleRecordsMapCopy();
      final List<BattleRecords> recent = new ArrayList<>();
      for (int r = Math.max(1, round - 1); r <= round; r++) {
        Optional.ofNullable(byRound.get(r)).ifPresent(recent::add);
      }
      int count = 0;
      for (final String name : names) {
        final Territory t = data.getMap().getTerritoryOrNull(name);
        if (t != null && foughtIn(recent, t, attackers, defenders)) {
          count++;
        }
      }
      return count;
    }

    private static boolean foughtIn(
        final List<BattleRecords> recent,
        final Territory t,
        final List<GamePlayer> attackers,
        final List<GamePlayer> defenders) {
      for (final BattleRecords records : recent) {
        for (final GamePlayer attacker : attackers) {
          final var theirs = BattleRecords.getRecordsForPlayerId(attacker, records);
          if (defenders.isEmpty()
              ? BattleRecords.getWereThereBattlesInTerritoriesMatching(
                  theirs, attacker, null, "any", Set.of(t))
              : defenders.stream()
                  .anyMatch(
                      d ->
                          BattleRecords.getWereThereBattlesInTerritoriesMatching(
                              theirs, attacker, d, "any", Set.of(t)))) {
            return true;
          }
        }
      }
      return false;
    }

    private OptionalMeasure atWar(final Strategia.Firma f, final List<GamePlayer> nations) {
      final List<GamePlayer> enemies = players(f.contro());
      final boolean war =
          nations.stream()
              .anyMatch(
                  p -> enemies.stream().anyMatch(e -> data.getRelationshipTracker().isAtWar(p, e)));
      return new OptionalMeasure(war ? 1.0 : 0.0);
    }

    /** Units in {@code territori}, or their share in {@code territori} + {@code altrove}. */
    private OptionalMeasure unitsIn(final Strategia.Firma f, final List<GamePlayer> nations) {
      final int here = count(f, nations, f.territori());
      if (f.altrove().isEmpty()) {
        return new OptionalMeasure((double) here);
      }
      final int elsewhere = count(f, nations, f.altrove());
      return new OptionalMeasure(here + elsewhere > 0 ? (double) here / (here + elsewhere) : null);
    }

    private int count(
        final Strategia.Firma f, final List<GamePlayer> nations, final List<String> names) {
      final Set<String> where = Set.copyOf(names);
      final Predicate<Unit> owned = Matches.unitIsOwnedByAnyOf(nations);
      final Predicate<Unit> filter = filter(f).and(owned);
      final boolean loaded = f.classi().contains(Strategia.Firma.TRASPORTI_CARICHI);
      int count = 0;
      for (final Territory t : data.getMap().getTerritories()) {
        if (where.isEmpty() || where.contains(t.getName())) {
          count += t.getUnitCollection().countMatches(filter);
          if (loaded) {
            // transports carrying units (the cargo is only known in its territory)
            count +=
                (int)
                    t
                        .getUnitCollection()
                        .getMatches(owned.and(Matches.unitIsSeaTransport()))
                        .stream()
                        .filter(u -> !filter.test(u) && !u.getTransporting(t).isEmpty())
                        .count();
          }
        }
      }
      return count;
    }

    private IntegerMap<UnitType> cost(final GamePlayer p) {
      return costs.computeIfAbsent(p.getName(), k -> new TuvCostsCalculator().getCostsForTuv(p));
    }

    /** Units of the last {@link #PURCHASES} purchase steps of {@code p} in the game history. */
    private List<Unit> purchases(final GamePlayer p) {
      return purchases.computeIfAbsent(p.getName(), k -> lastPurchases(data, p, PURCHASES));
    }

    /** Placements of the last {@link #PURCHASES} place steps of {@code p} in the game history. */
    private List<Placement> placements(final GamePlayer p) {
      return placements.computeIfAbsent(p.getName(), k -> lastPlacements(data, p, PURCHASES));
    }
  }

  /** Units placed in one territory or sea zone. */
  record Placement(String territory, List<Unit> units) {}

  /** Placements of {@code p} in its last {@code count} place steps (from the game history). */
  static List<Placement> lastPlacements(final GameState data, final GamePlayer p, final int count) {
    if (!(data instanceof GameData gameData) || gameData.getHistory() == null) {
      return List.of();
    }
    final List<List<Placement>> steps = new ArrayList<>();
    collectPlacements((HistoryNode) gameData.getHistory().getRoot(), p, steps);
    final List<Placement> placements = new ArrayList<>();
    for (int i = Math.max(0, steps.size() - count); i < steps.size(); i++) {
      placements.addAll(steps.get(i));
    }
    return placements;
  }

  private static void collectPlacements(
      final HistoryNode node, final GamePlayer p, final List<List<Placement>> steps) {
    for (int i = 0; i < node.getChildCount(); i++) {
      final HistoryNode child = (HistoryNode) node.getChildAt(i);
      if (child instanceof Step step) {
        final String name = step.getStepName().toLowerCase(Locale.ROOT);
        if (name.endsWith("place")
            && !name.endsWith("bidplace")
            && step.getPlayerId().map(p::equals).orElse(false)) {
          final List<Placement> placed = new ArrayList<>();
          for (int j = 0; j < step.getChildCount(); j++) {
            if (step.getChildAt(j) instanceof Event event
                && event.getRenderingData() instanceof PlacementDescription d
                && d.getTerritory() != null) {
              placed.add(new Placement(d.getTerritory().getName(), List.copyOf(d.getUnits())));
            }
          }
          steps.add(placed);
        }
      } else {
        collectPlacements(child, p, steps);
      }
    }
  }

  /** Units bought by {@code p} in its last {@code count} purchase steps (from the game history). */
  static List<Unit> lastPurchases(final GameState data, final GamePlayer p, final int count) {
    if (!(data instanceof GameData gameData) || gameData.getHistory() == null) {
      return List.of();
    }
    final List<List<Unit>> steps = new ArrayList<>();
    collect((HistoryNode) gameData.getHistory().getRoot(), p, steps);
    final List<Unit> units = new ArrayList<>();
    for (int i = Math.max(0, steps.size() - count); i < steps.size(); i++) {
      units.addAll(steps.get(i));
    }
    return units;
  }

  private static void collect(
      final HistoryNode node, final GamePlayer p, final List<List<Unit>> steps) {
    for (int i = 0; i < node.getChildCount(); i++) {
      final HistoryNode child = (HistoryNode) node.getChildAt(i);
      if (child instanceof Step step) {
        final String name = step.getStepName().toLowerCase(Locale.ROOT);
        if (name.endsWith("purchase") && step.getPlayerId().map(p::equals).orElse(false)) {
          final List<Unit> bought = new ArrayList<>();
          for (int j = 0; j < step.getChildCount(); j++) {
            if (step.getChildAt(j) instanceof Event event
                && event.getRenderingData() instanceof Collection<?> units) {
              units.stream()
                  .filter(Unit.class::isInstance)
                  .map(Unit.class::cast)
                  .forEach(bought::add);
            }
          }
          steps.add(bought);
        }
      } else {
        collect(child, p, steps);
      }
    }
  }

  /** Units of the clue's types or classes (none listed: every unit but infrastructure). */
  static Predicate<Unit> filter(final Strategia.Firma f) {
    if (f.unita().isEmpty() && f.classi().isEmpty()) {
      return Matches.unitIsNotInfrastructure();
    }
    Predicate<Unit> any = u -> f.unita().contains(u.getType().getName());
    for (final String c : f.classi()) {
      any = any.or(classe(c));
    }
    return any;
  }

  private static Predicate<Unit> classe(final String c) {
    return switch (c) {
      case "terra" -> Matches.unitIsLand().and(Matches.unitIsNotInfrastructure());
      case "aerei" -> Matches.unitIsAir();
      case "navi" -> Matches.unitIsSea();
      case "navi_da_guerra" -> Matches.unitIsSea().and(Matches.unitIsSeaTransport().negate());
      case "trasporti" -> Matches.unitIsSeaTransport();
      // counted per territory in Measures (the cargo is known only there)
      case Strategia.Firma.TRASPORTI_CARICHI -> u -> false;
      default -> u -> false;
    };
  }

  // ------------------------------------------------------------------------------- helpers

  private static List<GamePlayer> players(final GameState data, final String alliance) {
    return data.getAllianceTracker().getPlayersInAlliance(alliance).stream()
        .sorted(Comparator.comparing(GamePlayer::getName))
        .collect(Collectors.toList());
  }

  private static int lastRound(final JsonArray storico) {
    final JsonObject last = lastEntry(storico);
    return last != null && last.has("round") ? last.get("round").getAsInt() : 0;
  }

  private static @Nullable JsonObject lastEntry(final JsonArray storico) {
    return storico.isEmpty() ? null : storico.get(storico.size() - 1).getAsJsonObject();
  }

  private static double round2(final double v) {
    return Math.round(v * 100) / 100.0;
  }

  private static double round3(final double v) {
    return Math.round(v * 1000) / 1000.0;
  }
}
