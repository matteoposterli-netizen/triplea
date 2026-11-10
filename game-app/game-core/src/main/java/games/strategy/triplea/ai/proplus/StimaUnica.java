package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.OperazioniCongiunte.Minaccia;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Step COERENZA STIME: the ONE estimate of an operation of the strategist, used by the candidates
 * (start of the round), by the decision points (turn orders) and by the execution (the units the
 * engine moves are the ones counted here). Same state ⇒ same numbers.
 *
 * <ul>
 *   <li>forces: the units of the nations of the operation that can really be on the target within
 *       the rounds left (movement, transports), without the units another operation of the alliance
 *       holds and the capital's guard;
 *   <li>conquest: step 1 (who opens) and step 2 (who closes, enemy reinforcements between the
 *       steps) with the battle calculator; taken = P(step 1 takes) + P(not) × P(step 2 takes);
 *   <li>hold: the counterattack on the target held by the garrison (survivors, or defenders now
 *       plus the reinforcements the engine can move): capitals and key factories against the
 *       MAXIMUM, the other territories against the REALISTIC attack (the share of the enemy forces
 *       in reach the enemy really committed in this game, {@link StoricoNemico}, never under a
 *       prudent minimum while the history is thin).
 * </ul>
 */
public final class StimaUnica {
  private StimaUnica() {}

  /**
   * The estimate.
   *
   * @param presa probability (0-1) that the steps take the target (1 for a hold/reinforcement)
   * @param tenuta hold% (0-100) of the target at the enemy's turns
   * @param successo presa × tenuta (0-1): the target is the alliance's after the enemy's turns
   * @param impegno share (0-1) of the enemy forces in reach counted in the counterattack
   * @param base why that share (maximum, history, prudent minimum)
   */
  public record Stima(
      String tipo,
      double presa,
      double tenuta,
      double successo,
      double impegno,
      String base,
      List<Unit> tappa1,
      List<Unit> tappa2,
      List<Unit> presidio,
      double win1,
      double win2,
      double perdita1,
      double perdita2,
      double tuvDifensori,
      double tuvRinforziNemici,
      double tuvResidui,
      double tuvDistrutto,
      double tuvPresidio,
      double tuvContrattacco,
      double tuvContrattaccoMassimo,
      int round,
      int roundObiettivo,
      Minaccia minaccia,
      double valoreBersaglio,
      boolean bersaglioVuoto,
      boolean soloAerei,
      List<Unit> esclusePercorso) {

    /** Step C1-ter: why the take of an empty target is 100% or 0% (diary and LLM). */
    public String notaPresa() {
      return soloAerei
          ? "bersaglio senza difensori ma solo aerei nelle tappe: presa 0%, serve un'unita' di terra"
          : "bersaglio senza difensori: presa 100% con un'unita' di terra nelle tappe";
    }

    /**
     * Step C1-bis: expected TUV lost (= PU): step 1, step 2 when step 1 does not take it, the
     * garrison lost with the hold (same terms as the variant v2's value).
     */
    public double tuvPersoAtteso() {
      final double take1 = tappa1.stream().anyMatch(Matches.unitIsLand()) ? win1 / 100 : 0;
      return perdita1 + (1 - take1) * perdita2 + (1 - tenuta / 100) * tuvPresidio;
    }

    /**
     * Step C1-bis: the value of the alliance in PU equivalents = value of the target (PU) x taken x
     * held + enemy TUV destroyed - TUV lost ({@code stratega-llm.json}, {@code valore_pu}).
     */
    public double valoreAlleanza() {
      return valoreBersaglio * presa * tenuta / 100 + tuvDistrutto - tuvPersoAtteso();
    }

    /** The value of the alliance with its components (diary and LLM state). */
    public JsonObject jsonValore() {
      final JsonObject o = new JsonObject();
      o.addProperty(
          "formula",
          "valore_bersaglio_PU x presa x tenuta + TUV_nemico_distrutto - TUV_perso (1 TUV = 1 PU)");
      o.addProperty("valore_bersaglio_PU", Math.round(valoreBersaglio));
      o.addProperty("presa_pct", Math.round(presa * 100));
      o.addProperty("tenuta_pct", Math.round(tenuta));
      o.addProperty("bersaglio_atteso_PU", Math.round(valoreBersaglio * presa * tenuta / 100));
      o.addProperty("tuv_nemico_distrutto", Math.round(tuvDistrutto));
      o.addProperty("tuv_perso", Math.round(tuvPersoAtteso()));
      o.addProperty("totale_PU", Math.round(valoreAlleanza()));
      return o;
    }

    /** The numbers for the LLM and the diary (same keys in candidates and decision points). */
    public JsonObject json() {
      final JsonObject o = new JsonObject();
      o.addProperty("funzione", "stima unica del motore (candidato = punto di decisione)");
      o.addProperty("calcolata_al_round", round);
      o.addProperty("per_il_round", roundObiettivo);
      if (!"tenuta".equals(tipo)) {
        o.addProperty("attaccanti_tappa1", OperazioniAnfibie.unitsText(tappa1));
        if (!bersaglioVuoto) {
          o.addProperty("win_tappa1_pct", Math.round(win1));
        }
        o.addProperty("perdita_attesa_tappa1_TUV", Math.round(perdita1));
        o.addProperty("attaccanti_tappa2", OperazioniAnfibie.unitsText(tappa2));
        if (!bersaglioVuoto) {
          o.addProperty("win_tappa2_pct", Math.round(win2));
        } else {
          o.addProperty("bersaglio_vuoto", true);
          o.addProperty("nota_presa", notaPresa());
        }
        if (!esclusePercorso.isEmpty()) {
          o.addProperty(
              "escluse_percorso_non_sicuro", OperazioniAnfibie.unitsText(esclusePercorso));
          o.addProperty("escluse_percorso_non_sicuro_n", esclusePercorso.size());
        }
        o.addProperty("perdita_attesa_tappa2_TUV", Math.round(perdita2));
        o.addProperty("tuv_difensori", Math.round(tuvDifensori));
        o.addProperty("tuv_rinforzi_nemici_tra_le_tappe", Math.round(tuvRinforziNemici));
        o.addProperty("presa_pct", Math.round(presa * 100));
      }
      o.addProperty(
          "presidio", presidio.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(presidio));
      o.addProperty("contrattacco_contato_TUV", Math.round(tuvContrattacco));
      o.addProperty("contrattacco_massimo_TUV", Math.round(tuvContrattaccoMassimo));
      o.addProperty("impegno_nemico", Math.round(impegno * 100) / 100.0);
      o.addProperty("base_impegno", base);
      o.addProperty("tenuta_pct", Math.round(tenuta));
      o.addProperty("successo_pct", Math.round(successo * 100));
      o.add("valore_alleanza", jsonValore());
      return o;
    }

    /**
     * Diary only (never sent to the LLM): the exchange the estimate implies, for the analysis of
     * the launches. TUV destroyed = enemy defenders and reinforcements between the steps minus what
     * is left after step 2; TUV lost in the steps = step 1 + step 2 when step 1 does not take it;
     * garrison lost = (1 - hold) x garrison TUV.
     */
    public JsonObject jsonAnalisi() {
      final double take1 = tappa1.stream().anyMatch(Matches.unitIsLand()) ? win1 / 100 : 0;
      final double lostSteps = perdita1 + (1 - take1) * perdita2;
      final double lostGarrison = (1 - tenuta / 100) * tuvPresidio;
      final JsonObject o = new JsonObject();
      o.addProperty("presa_pct", Math.round(presa * 100));
      o.addProperty("tenuta_pct", Math.round(tenuta));
      o.addProperty("successo_pct", Math.round(successo * 100));
      o.addProperty("impegno_nemico", Math.round(impegno * 100) / 100.0);
      o.addProperty("impegno_minimo_prudente", base.startsWith("minimo prudente"));
      o.addProperty("base_impegno", base);
      o.addProperty("tuv_nemico_distrutto_atteso", Math.round(tuvDistrutto));
      o.addProperty("tuv_perso_atteso_tappe", Math.round(lostSteps));
      o.addProperty("tuv_presidio", Math.round(tuvPresidio));
      o.addProperty("tuv_presidio_perso_atteso", Math.round(lostGarrison));
      o.addProperty("scambio_tuv_tappe", Math.round(tuvDistrutto - lostSteps));
      o.addProperty(
          "scambio_tuv_con_presidio", Math.round(tuvDistrutto - lostSteps - lostGarrison));
      o.addProperty("contrattacco_contato_TUV", Math.round(tuvContrattacco));
      o.addProperty("contrattacco_massimo_TUV", Math.round(tuvContrattaccoMassimo));
      o.add("valore_alleanza", jsonValore());
      return o;
    }
  }

  /** The share of the enemy forces in reach the counterattack counts, and why. */
  public record Impegno(double frazione, String base) {}

  // ================================================================================ conquest

  /**
   * Conquest ({@code def}: offensive or landing) of {@code target}: forces within {@code rounds}
   * rounds (1 = this turn), steps, hold.
   */
  public static Stima conquista(
      final ProData proData,
      final ProOddsCalculator calc,
      final Definizione def,
      final Territory target,
      final int rounds) {
    final OperazioniCongiunte op = OperazioniCongiunte.perStima(proData, calc, def);
    final GameState data = proData.getData();
    final GamePlayer opener = data.getPlayerList().getPlayerId(def.apre());
    final GamePlayer closer = data.getPlayerList().getPlayerId(def.chiude());
    final int k = Math.max(1, rounds);
    final Set<Unit> reserved = riservate(data, def.alleanza(), def.id());
    final List<Unit> excluded = new ArrayList<>();
    final List<Unit> t1 = forze(op, def, target, opener, k, reserved, List.of(), excluded);
    final List<Unit> t2 = forze(op, def, target, closer, k, reserved, t1, excluded);
    excluded.removeAll(t1);
    excluded.removeAll(t2);
    final List<Unit> defenders =
        opener == null
            ? List.of()
            : target.getMatches(Matches.enemyUnit(opener).and(Matches.unitIsNotInfrastructure()));
    final boolean land1 = t1.stream().anyMatch(Matches.unitIsLand());
    final ProBattleResult r1 =
        t1.isEmpty() || defenders.isEmpty()
            ? null
            : land1
                ? calc.calculateBattleResults(proData, target, t1, defenders, Set.of())
                : calc.callBattleCalcWithRetreatAir(proData, target, t1, defenders, Set.of());
    final double win1 =
        defenders.isEmpty() ? (land1 ? 100 : 0) : r1 == null ? 0 : r1.getWinPercentage();
    final double loss1 =
        r1 == null ? 0 : op.tuvOf(t1) - op.tuvOf(r1.getAverageAttackersRemaining());
    final List<Unit> left =
        r1 == null
            ? new ArrayList<>(defenders)
            : new ArrayList<>(r1.getAverageDefendersRemaining());
    final List<Unit> enemyIn = op.enemyReinforcements(target, closer, opener);
    final List<Unit> defenders2 = new ArrayList<>(left);
    defenders2.addAll(enemyIn);
    final ProBattleResult r2 =
        t2.isEmpty() || defenders2.isEmpty()
            ? null
            : calc.calculateBattleResults(proData, target, t2, defenders2, Set.of());
    final boolean land2 = t2.stream().anyMatch(Matches.unitIsLand());
    final double win2 =
        defenders2.isEmpty() ? (land2 ? 100 : 0) : r2 == null ? 0 : r2.getWinPercentage();
    final double loss2 =
        r2 == null ? 0 : op.tuvOf(t2) - op.tuvOf(r2.getAverageAttackersRemaining());
    // step C1-ter: an empty target is taken by any land unit that can conquer in the steps (100%),
    // never by planes alone (0%: a land unit is needed); no win% of step 1 in that case
    final boolean empty = defenders.isEmpty();
    final boolean anyLand =
        Stream.concat(t1.stream(), t2.stream())
            .anyMatch(Matches.unitIsLand().and(Matches.unitIsNotInfrastructure()));
    final double take1 = land1 ? win1 / 100 : 0;
    final double take = empty ? (anyLand ? 1 : 0) : Math.min(1, take1 + (1 - take1) * win2 / 100);
    // the garrison: land survivors of the last step, else the strongest land unit; plus the
    // closer's land units in reach not used in the steps
    final List<Unit> garrison = new ArrayList<>();
    final Collection<Unit> survivors =
        r2 != null
            ? r2.getAverageAttackersRemaining()
            : r1 != null ? r1.getAverageAttackersRemaining() : land2 ? t2 : t1;
    survivors.stream().filter(Matches.unitIsLand()).forEach(garrison::add);
    if (garrison.isEmpty() && take > 0) {
      (t2.isEmpty() ? t1 : t2)
          .stream()
              .filter(Matches.unitIsLand())
              .max(
                  Comparator.comparingInt(
                          (Unit u) -> u.getUnitAttachment().getDefense(u.getOwner()))
                      .thenComparing(u -> -op.tuvOf(List.of(u))))
              .ifPresent(garrison::add);
    }
    // step R1 (B7): plus the closer's units left out of the steps that its non-combat move of the
    // same turn can bring in (the same reinforcements as a hold), as the comment above says
    if (take > 0 && closer != null) {
      final Set<Unit> used = new HashSet<>(t1);
      used.addAll(t2);
      op.rinforziPossibili(target, closer, reserved).stream()
          .filter(u -> !used.contains(u) && !garrison.contains(u))
          .forEach(garrison::add);
    }
    final Impegno imp = impegno(data, def.alleanza(), target, TipoBersaglio.of(def.id()));
    final Minaccia m = op.minaccia(target);
    final List<Unit> counter = parte(op, target, m, imp.frazione());
    final double hold = tenuta(proData, calc, target, m, counter, garrison);
    return new Stima(
        "conquista",
        take,
        hold,
        take * hold / 100,
        imp.frazione(),
        imp.base(),
        List.copyOf(t1),
        List.copyOf(t2),
        List.copyOf(garrison),
        win1,
        win2,
        Math.max(0, loss1),
        Math.max(0, loss2),
        op.tuvOf(defenders),
        op.tuvOf(enemyIn),
        op.tuvOf(left),
        // step C1-ter: TUV destroyed = only the defenders there at the estimate (the enemy
        // reinforcements expected between the steps stay in the counterattack and the hold)
        destroyed(op, defenders, r2 != null ? r2.getAverageDefendersRemaining() : left),
        op.tuvOf(garrison),
        op.tuvOf(counter),
        op.tuvOf(m.tutti()),
        data.getSequence().getRound(),
        data.getSequence().getRound() + k - 1,
        m,
        def.valore(),
        empty,
        empty && !anyLand,
        List.copyOf(excluded));
  }

  /** TUV of the {@code defenders} not among {@code remaining} (expected destroyed), at least 0. */
  static double destroyed(
      final OperazioniCongiunte op,
      final Collection<Unit> defenders,
      final Collection<Unit> remaining) {
    final Set<Unit> original = new HashSet<>(defenders);
    final List<Unit> left = remaining.stream().filter(original::contains).toList();
    return Math.max(0, op.tuvOf(defenders) - op.tuvOf(left));
  }

  /**
   * Step C2 (tracking): the units {@link #conquista} counts in the steps of {@code def} on {@code
   * target} within {@code rounds} rounds (no battle estimate).
   */
  public static List<Unit> contate(
      final ProData proData,
      final ProOddsCalculator calc,
      final Definizione def,
      final Territory target,
      final int rounds) {
    final OperazioniCongiunte op = OperazioniCongiunte.perStima(proData, calc, def);
    final GameState data = proData.getData();
    final GamePlayer opener = data.getPlayerList().getPlayerId(def.apre());
    final GamePlayer closer = data.getPlayerList().getPlayerId(def.chiude());
    final int k = Math.max(1, rounds);
    final Set<Unit> reserved = riservate(data, def.alleanza(), def.id());
    final List<Unit> excluded = new ArrayList<>();
    final List<Unit> t1 = forze(op, def, target, opener, k, reserved, List.of(), excluded);
    final List<Unit> all = new ArrayList<>(t1);
    all.addAll(forze(op, def, target, closer, k, reserved, t1, excluded));
    return all;
  }

  private static final java.util.Map<String, Set<String>> REAL_ENEMIES =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final java.util.Map.Entry<String, Set<String>> e) {
              return size() > 32;
            }
          });

  /**
   * Step C2: the names of {@code player}'s real enemies now (at war, or able to declare war by the
   * map's rules: {@link MinacciaReale}), cached per game, round, step and player.
   */
  static Set<String> nemiciReali(final GameState data, final GamePlayer player) {
    final String key =
        System.identityHashCode(data)
            + "|"
            + data.getSequence().getRound()
            + "|"
            + (data.getSequence().getStep() == null ? "" : data.getSequence().getStep().getName())
            + "|"
            + player.getName();
    return REAL_ENEMIES.computeIfAbsent(
        key,
        k -> {
          final Set<String> names = new LinkedHashSet<>();
          MinacciaReale.nemici(data, player, java.util.Optional.empty())
              .keySet()
              .forEach(p -> names.add(p.getName()));
          return Set.copyOf(names);
        });
  }

  /** {@code m} with only the units of {@code player}'s real enemies (the purchases already are). */
  static Minaccia soloNemiciReali(final GameState data, final GamePlayer player, final Minaccia m) {
    final Set<String> real = nemiciReali(data, player);
    return new Minaccia(
        m.attaccanti().stream().filter(u -> real.contains(u.getOwner().getName())).toList(),
        m.acquisti(),
        m.bombardamento().stream().filter(u -> real.contains(u.getOwner().getName())).toList());
  }

  // ==================================================================================== hold

  /**
   * Hold (hold or reinforcement of {@code def}) of {@code target}: the alliance's defenders now
   * plus the reinforcements the nations of the operation can really move there this turn.
   */
  public static Stima tenuta(
      final ProData proData,
      final ProOddsCalculator calc,
      final Definizione def,
      final Territory target) {
    final OperazioniCongiunte op = OperazioniCongiunte.perStima(proData, calc, def);
    final GameState data = proData.getData();
    final Set<Unit> reserved = riservate(data, def.alleanza(), def.id());
    final List<Unit> garrison = new ArrayList<>(op.alliedDefendersOf(target));
    final List<Unit> help = new ArrayList<>();
    for (final String n : nazioni(def)) {
      final GamePlayer p = data.getPlayerList().getPlayerId(n);
      if (p != null) {
        op.rinforziPossibili(target, p, reserved).stream()
            .filter(u -> !help.contains(u))
            .forEach(help::add);
      }
    }
    garrison.addAll(help);
    final Impegno imp = impegno(data, def.alleanza(), target, TipoBersaglio.of(def.id()));
    final Minaccia m = op.minaccia(target);
    final List<Unit> counter = parte(op, target, m, imp.frazione());
    final double hold = tenuta(proData, calc, target, m, counter, garrison);
    final int round = data.getSequence().getRound();
    return new Stima(
        "tenuta",
        1,
        hold,
        hold / 100,
        imp.frazione(),
        imp.base(),
        List.of(),
        List.copyOf(help),
        List.copyOf(garrison),
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        op.tuvOf(garrison),
        op.tuvOf(counter),
        op.tuvOf(m.tutti()),
        round,
        round,
        m,
        def.valore(),
        false,
        false,
        List.of());
  }

  /**
   * Step R1 (A8): the hold% of {@code target} Hard gets alone (the alliance's defenders there now,
   * no reinforcement of the operation), against the same realistic counterattack as {@link
   * #tenuta(ProData, ProOddsCalculator, Definizione, Territory)}.
   */
  public static double tenutaDaSolo(
      final ProData proData,
      final ProOddsCalculator calc,
      final Definizione def,
      final Territory target) {
    final OperazioniCongiunte op = OperazioniCongiunte.perStima(proData, calc, def);
    final Impegno imp =
        impegno(proData.getData(), def.alleanza(), target, TipoBersaglio.of(def.id()));
    final Minaccia m = op.minaccia(target);
    return tenuta(
        proData, calc, target, m, parte(op, target, m, imp.frazione()), op.alliedDefendersOf(target));
  }

  /** The estimate of any operation of the strategist now (conquest before the take, else hold). */
  public static Stima ora(
      final ProData proData, final ProOddsCalculator calc, final Definizione def) {
    final GameState data = proData.getData();
    final Registro reg = Registro.leggi(data.getProperties(), def.id());
    final String held =
        reg.json().has("tenuta_bersaglio")
            ? reg.json().get("tenuta_bersaglio").getAsString()
            : def.bersaglio();
    final Territory t = data.getMap().getTerritoryOrNull(held);
    if (t == null) {
      throw new IllegalArgumentException("bersaglio sconosciuto: " + held);
    }
    final boolean conquest =
        reg.stato() != Stato.TENUTA
            && OperazioneCongiunta.tipoStratega(def.id()).matches("offensiva|sbarco");
    if (conquest) {
      final int round = data.getSequence().getRound();
      final int target = reg.isNuovo() ? def.roundMin() : reg.roundObiettivo();
      return conquista(proData, calc, def, t, Math.max(1, target - round + 1));
    }
    return tenuta(proData, calc, def, t);
  }

  // ========================================================================= counterattack

  /**
   * The share of the enemy forces in reach of {@code target} counted in the counterattack: 1
   * (maximum) on a capital or a factory of production at least {@code produzione_fabbrica_chiave};
   * elsewhere the share the enemy really committed against the alliance's operations in this game
   * (observations weighted), at least {@code impegno_nemico_minimo}, and at least {@code
   * impegno_nemico_prudente} with fewer than {@code osservazioni_minime_storico} observations.
   */
  public static Impegno impegno(
      final GameState data, final String alliance, final Territory target) {
    return impegno(data, alliance, target, TipoBersaglio.TENUTA);
  }

  /**
   * Step R1 (A3): the kind of target of an operation, with its own history of the enemy's
   * commitment: a conquest (offensive), a coast (landing), the hold of an own territory (hold,
   * reinforcement). A conquest keeps its kind after the take (the hold of a conquered territory).
   */
  public enum TipoBersaglio {
    CONQUISTA("conquista"),
    COSTA("costa"),
    TENUTA("tenuta di territorio proprio");

    private final String testo;

    TipoBersaglio(final String testo) {
      this.testo = testo;
    }

    public String testo() {
      return testo;
    }

    /** The kind of the operation {@code id} (a strategist's id, else a hold). */
    public static TipoBersaglio of(final String id) {
      return switch (OperazioneCongiunta.tipoStratega(id)) {
        case "offensiva" -> CONQUISTA;
        case "sbarco" -> COSTA;
        default -> TENUTA;
      };
    }

    boolean conquista() {
      return this != TENUTA;
    }
  }

  /**
   * As {@link #impegno(GameState, String, Territory)} with the history of the operations of the
   * same kind only (step R1, A3); for a conquest or a coast never under {@code
   * impegno_nemico_prudente}.
   */
  public static Impegno impegno(
      final GameState data,
      final String alliance,
      final Territory target,
      final TipoBersaglio kind) {
    final StrategaConfig cfg = StrategaConfig.get();
    final var ta = TerritoryAttachment.get(target);
    if (ta.isPresent()
        && (ta.get().isCapital()
            || (target.anyUnitsMatch(Matches.unitCanProduceUnits())
                && ta.get().getProduction() >= cfg.produzioneFabbricaChiave()))) {
      return new Impegno(
          1, ta.get().isCapital() ? "massimo (capitale)" : "massimo (fabbrica chiave)");
    }
    final GameProperties props = data.getProperties();
    final List<String> ids = new ArrayList<>();
    OperazioneCongiunta.dinamicheJson(props, alliance)
        .forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
    for (final JsonObject past : Stratega.storicoOperazioni(props, alliance)) {
      final String id = past.get("id").getAsString();
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    double weighted = 0;
    int n = 0;
    for (final String id : ids) {
      if (TipoBersaglio.of(id) != kind) {
        continue; // step R1 (A3): the history of this kind of target only
      }
      final JsonObject s = StoricoNemico.riassunto(props, id);
      final int obs = s.has("osservazioni") ? s.get("osservazioni").getAsInt() : 0;
      if (obs > 0) {
        weighted += s.get("frazione_impegnata_media").getAsDouble() * obs;
        n += obs;
      }
    }
    final double seen = n == 0 ? 0 : weighted / n;
    if (n < cfg.osservazioniMinimeStorico()) {
      final double f = Math.max(cfg.impegnoNemicoPrudente(), seen);
      return new Impegno(
          f,
          String.format(
              Locale.ROOT,
              "minimo prudente %.2f (storico scarso per " + kind.testo() + ": %d osservazioni%s)",
              cfg.impegnoNemicoPrudente(),
              n,
              n == 0 ? "" : String.format(Locale.ROOT, ", frazione %.2f", seen)));
    }
    final double floor =
        kind.conquista()
            ? Math.max(cfg.impegnoNemicoMinimo(), cfg.impegnoNemicoPrudente())
            : cfg.impegnoNemicoMinimo();
    final double f = Math.min(1, Math.max(floor, seen));
    return new Impegno(
        f,
        String.format(
            Locale.ROOT,
            "storico del nemico (%s): frazione impegnata %.2f su %d osservazioni%s",
            kind.testo(),
            seen,
            n,
            kind.conquista() && seen < floor
                ? String.format(Locale.ROOT, ", minimo per una conquista %.2f", floor)
                : ""));
  }

  /** The {@code share} of the counterattack (TUV), the closest units first; all with 1. */
  static List<Unit> parte(
      final OperazioniCongiunte op, final Territory target, final Minaccia m, final double share) {
    final List<Unit> all = new ArrayList<>(m.tutti());
    if (share >= 1) {
      return all;
    }
    final GameMap map = target.getData().getMap();
    final var where = StoricoNemico.locations(map);
    all.sort(
        Comparator.comparingInt(
                (Unit u) -> {
                  final Territory from = where.get(u);
                  final int d = from == null ? 1 : map.getDistance(from, target);
                  return d < 0 ? 9 : d;
                })
            .thenComparing(u -> -op.tuvOf(List.of(u)))
            .thenComparing(u -> u.getType().getName()));
    final double total = op.tuvOf(all);
    final List<Unit> part = new ArrayList<>();
    double sum = 0;
    for (final Unit u : all) {
      if (sum >= total * share) {
        break;
      }
      part.add(u);
      sum += op.tuvOf(List.of(u));
    }
    return part;
  }

  /**
   * Hold% of {@code target} with {@code defenders} against {@code counter}: the one estimate of the
   * strategist's holds, reinforcements and (step C2) computed defense.
   */
  static double tenuta(
      final ProData proData,
      final ProOddsCalculator calc,
      final Territory target,
      final Minaccia m,
      final List<Unit> counter,
      final Collection<Unit> defenders) {
    if (counter.isEmpty()) {
      return 100;
    }
    if (defenders.isEmpty()) {
      return 0;
    }
    return 100
        - calc.calculateBattleResults(
                proData, target, counter, new ArrayList<>(defenders), m.bombardamento())
            .getWinPercentage();
  }

  // ================================================================================== forces

  /**
   * The forces of {@code owner} on the target at the target round: the units that can be there this
   * turn and, for a later round, the units the operation holds for it (the only ones Hard does not
   * use in between): the opener's planes that reach its air gathering place in time (up to {@code
   * aerei_apre}), the closer's land units at its gathering places; never the units of another
   * operation nor the capital's guard.
   */
  private static List<Unit> forze(
      final OperazioniCongiunte op,
      final Definizione def,
      final Territory target,
      final GamePlayer owner,
      final int k,
      final Set<Unit> reserved,
      final List<Unit> already,
      final List<Unit> excluded) {
    if (owner == null) {
      return List.of();
    }
    final List<Unit> list = new ArrayList<>();
    if (k <= 1) {
      // step R1 (A2): this turn the executable set, the same units the execution moves
      list.addAll(op.eseguibili(target, owner));
    } else {
      // a later round: the land and air units that can be there by then (the engine brings the
      // counted land units closer and holds them, OperazioniCongiunte.avvicina/trattenute), plus
      // what the operation already holds (gathering places, transports in the gathering zone, the
      // opener's planes that reach their gathering place in time)
      final List<Unit> now = op.raggiungibiliOra(target, owner);
      final List<Territory> gathering = op.luoghiRaccolta(owner, target);
      final GameMap gameMap = target.getData().getMap();
      for (final Unit u : op.raggiungibiliEntro(target, owner, k)) {
        // step C1-ter: a land unit that must walk to the step counts only if the engine could
        // move it there with its own safety check (OperazioniCongiunte.passiSicuri)
        final Territory at = op.luogo(u);
        final boolean inPlace =
            now.contains(u)
                || Matches.unitIsAir().test(u)
                || (at != null
                    && (gathering.contains(at) || gameMap.getNeighbors(target).contains(at)));
        if (inPlace || op.haPercorsoSicuro(u, gathering)) {
          list.add(u);
        } else if (!excluded.contains(u)) {
          excluded.add(u);
        }
      }
      now.stream().filter(u -> !list.contains(u)).forEach(list::add);
      final GameMap map = target.getData().getMap();
      final Set<Unit> gatherPlanes = new HashSet<>();
      final boolean opens = def.isApre(owner.getName());
      final List<String> places = opens ? def.raccoltaApre() : def.raccoltaChiude();
      for (final String name : places) {
        final Territory x = map.getTerritoryOrNull(name);
        if (x == null || x.isWater()) {
          continue;
        }
        x
            .getMatches(
                Matches.unitIsOwnedBy(owner)
                    .and(Matches.unitIsNotInfrastructure())
                    .and(u -> u.getUnitAttachment().getAttack(owner) > 0)
                    .and(
                        u ->
                            Matches.unitIsAir().test(u)
                                ? opens && def.aereiApre() > 0
                                : Matches.unitIsLand().test(u)
                                    && (!def.zonaRaccolta().isEmpty() || !opens)))
            .stream()
            .filter(u -> !list.contains(u))
            .forEach(list::add);
      }
      final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
      if (zone != null) {
        for (final Unit t :
            zone.getMatches(Matches.unitIsOwnedBy(owner).and(OperazioniAnfibie::isTransport))) {
          t.getTransporting().stream()
              .filter(Matches.unitIsOwnedBy(owner))
              .filter(u -> !list.contains(u))
              .forEach(list::add);
        }
      }
      if (opens && def.aereiApre() > 0) {
        final List<Territory> airPlaces =
            places.stream().map(map::getTerritoryOrNull).filter(Objects::nonNull).toList();
        final List<Unit> planes = new ArrayList<>();
        for (final Territory x : map.getTerritories()) {
          for (final Unit u :
              x.getMatches(
                  Matches.unitIsOwnedBy(owner)
                      .and(Matches.unitIsAir())
                      .and(u -> u.getUnitAttachment().getAttack(owner) > 0))) {
            if (!list.contains(u)
                && airPlaces.stream().anyMatch(p -> op.aereoInTempo(u, p, k - 1))) {
              planes.add(u);
            }
          }
        }
        // step C1-ter: only the planes the engine can fly there with its safety check
        planes.removeIf(
            u -> {
              if (op.haPercorsoSicuro(u, airPlaces)) {
                return false;
              }
              if (!excluded.contains(u)) {
                excluded.add(u);
              }
              return true;
            });
        planes.sort(
            Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(owner))
                .thenComparing(u -> u.getType().getName()));
        list.addAll(planes.stream().limit(Math.max(0, def.aereiApre())).toList());
        gatherPlanes.addAll(list.stream().filter(Matches.unitIsAir()).toList());
      }
      // step C1-bis: a plane counts for a later round only if the engine can hold it until the
      // step: on land and able to strike the target from there, or one of the opener's planes for
      // the air gathering place (flown there and held); a plane on a carrier or that must land
      // elsewhere first is not counted
      list.removeIf(
          u -> {
            if (!Matches.unitIsAir().test(u) || gatherPlanes.contains(u)) {
              return false;
            }
            final Territory at = op.luogo(u);
            return at == null || at.isWater() || !op.colpisceDaQui(u, at, target);
          });
    }
    list.removeAll(reserved);
    list.removeAll(already);
    return op.guardiaCapitali(list, owner);
  }

  /**
   * Units another active operation of {@code alliance} holds (garrison of a held target; planes,
   * land units and transports at its gathering places), except operation {@code except}.
   */
  static Set<Unit> riservate(final GameState data, final String alliance, final String except) {
    final Set<Unit> out = new LinkedHashSet<>();
    final GameMap map = data.getMap();
    for (final Definizione d : OperazioneCongiunta.dinamiche(data.getProperties(), alliance)) {
      if (d.id().equals(except)) {
        continue;
      }
      final Registro reg = Registro.leggi(data.getProperties(), d.id());
      if (reg.isNuovo() || reg.stato().isFinale()) {
        continue;
      }
      final Set<String> nations = new LinkedHashSet<>(nazioni(d));
      if (reg.stato() == Stato.TENUTA) {
        final Territory t =
            reg.json().has("tenuta_bersaglio")
                ? map.getTerritoryOrNull(reg.json().get("tenuta_bersaglio").getAsString())
                : null;
        if (t != null) {
          out.addAll(
              t.getMatches(
                  u ->
                      nations.contains(u.getOwner().getName())
                          && Matches.unitIsLand().test(u)
                          && Matches.unitIsNotInfrastructure().test(u)));
        }
        continue;
      }
      for (final String name : d.raccoltaApre()) {
        final Territory t = map.getTerritoryOrNull(name);
        if (t != null && !t.isWater()) {
          out.addAll(
              t.getMatches(
                  u ->
                      u.getOwner().getName().equals(d.apre())
                          && ((d.aereiApre() > 0 && Matches.unitIsAir().test(u))
                              || (!d.zonaRaccolta().isEmpty() && Matches.unitIsLand().test(u)))));
        }
      }
      for (final String name : d.raccoltaChiude()) {
        final Territory t = map.getTerritoryOrNull(name);
        if (t != null && !t.isWater()) {
          out.addAll(
              t.getMatches(
                  u ->
                      u.getOwner().getName().equals(d.chiude())
                          && Matches.unitIsLand().test(u)
                          && Matches.unitIsNotInfrastructure().test(u)));
        }
      }
      final Territory zone = map.getTerritoryOrNull(d.zonaRaccolta());
      if (zone != null) {
        for (final Unit t :
            zone.getMatches(
                u ->
                    nations.contains(u.getOwner().getName()) && OperazioniAnfibie.isTransport(u))) {
          out.add(t);
          out.addAll(t.getTransporting());
        }
      }
    }
    return out;
  }

  static List<String> nazioni(final Definizione d) {
    return d.apre().equals(d.chiude()) ? List.of(d.apre()) : List.of(d.apre(), d.chiude());
  }

  /** The {@code successo_pct} of a saved estimate (JSON), -1 if none. */
  static double successo(final JsonElement e) {
    return e != null && e.isJsonObject() && e.getAsJsonObject().has("successo_pct")
        ? e.getAsJsonObject().get("successo_pct").getAsDouble()
        : -1;
  }
}
