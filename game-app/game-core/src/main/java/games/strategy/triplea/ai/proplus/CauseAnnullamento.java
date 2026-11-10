package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.delegate.Matches;
import java.util.Collection;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Step COERENZA STIME: the cause of every cancelled operation of the strategist, recorded
 * automatically in the operation's register ({@code causa_annullamento}, {@code causa_testo}) with
 * the classes of the analysis of run 42775993327 ({@code proplus/analisi/}):
 *
 * <ul>
 *   <li>C1 target already the alliance's without the operation (redundant launch);
 *   <li>C2 hold/reinforcement whose engine estimate fell far under the launch's, enemy unchanged;
 *   <li>C3 conquest whose engine estimate at the step fell far under the launch's, enemy unchanged;
 *   <li>C4 gathering never done (nothing gathered after 2 rounds, no step);
 *   <li>C5 the candidate left the list (conquest not yet started, target not among the candidates);
 *   <li>C6 second thoughts on the same numbers;
 *   <li>giustificato: the enemy grew by at least {@value #CRESCITA_NEMICO_PCT}% in reach of the
 *       target since the launch.
 * </ul>
 *
 * <p>Rough rules on the numbers the engine has, in this order (step C1-bis: C1, then the engine's
 * estimate against the launch's - giustificato, C3/C2 - then C5, C4, C6); they are a measure, not a
 * decision.
 */
final class CauseAnnullamento {
  static final String C1 = "C1";
  static final String C2 = "C2";
  static final String C3 = "C3";
  static final String C4 = "C4";
  static final String C5 = "C5";
  static final String C6 = "C6";
  static final String GIUSTIFICATO = "giustificato";

  /** Growth of the maximum counterattack (TUV) since the launch that justifies a cancellation. */
  static final double CRESCITA_NEMICO_PCT = 30;

  /** Fall of the engine's success estimate (points) since the launch: forces short (C2/C3). */
  static final double CALO_STIMA_PUNTI = 15;

  private CauseAnnullamento() {}

  static void registra(final Registro reg, final String causa, final String testo) {
    reg.set("causa_annullamento", causa);
    reg.set("causa_testo", testo);
  }

  /**
   * Classifies and records the cause of the cancellation of {@code def} now.
   *
   * @param bersagliCandidati targets of this round's candidates (null: not known at this point)
   * @param ora the engine's estimate now (null: the last one of a decision point, if any)
   * @return "causa: testo", for the diary
   */
  static String classifica(
      final GameState data,
      final Definizione def,
      final Registro reg,
      final @Nullable Collection<String> bersagliCandidati,
      final @Nullable JsonObject ora) {
    final String[] c = calcola(data, def, reg, bersagliCandidati, ora);
    registra(reg, c[0], c[1]);
    return c[0] + ": " + c[1];
  }

  private static String[] calcola(
      final GameState data,
      final Definizione def,
      final Registro reg,
      final @Nullable Collection<String> bersagliCandidati,
      final @Nullable JsonObject ora) {
    final int round = data.getSequence().getRound();
    final boolean conquest = OperazioneCongiunta.tipoStratega(def.id()).matches("offensiva|sbarco");
    final boolean held = reg.stato() == Stato.TENUTA || reg.json().has("tenuta_bersaglio");
    final Territory target = data.getMap().getTerritoryOrNull(def.bersaglio());
    final GamePlayer me = data.getPlayerList().getPlayerId(def.apre());
    if (conquest
        && target != null
        && me != null
        && Matches.isTerritoryAllied(me).test(target)
        && (!reg.flag("presa") || reg.flag("presa_senza_operazione"))) {
      return new String[] {
        C1, "bersaglio gia' dell'alleanza (" + target.getOwner().getName() + ") senza le tappe"
      };
    }
    final boolean started = reg.json().has("tappa1_round");
    final int launched = reg.intOf("avvio_round", round);
    final JsonObject start =
        reg.json().has("stima_avvio") && reg.json().get("stima_avvio").isJsonObject()
            ? reg.json().getAsJsonObject("stima_avvio")
            : null;
    final JsonObject now =
        ora != null
            ? ora
            : reg.json().has("stima_decisione_ultima")
                ? reg.json().getAsJsonObject("stima_decisione_ultima")
                : null;
    // step C1-bis: the fall of the engine's estimate (or the enemy's growth) comes before "out of
    // the list": a candidate leaves the list because its numbers fell (run 42822465005: the 7 C5
    // were offensives whose counted planes Hard had used), so C5 is only what remains
    if (start != null && now != null) {
      final double maxStart = num(start, "contrattacco_massimo_TUV");
      final double maxNow = num(now, "contrattacco_massimo_TUV");
      final double growth = maxStart <= 0 ? (maxNow > 0 ? 100 : 0) : (maxNow / maxStart - 1) * 100;
      final double drop = num(start, "successo_pct") - num(now, "successo_pct");
      final String numbers =
          String.format(
              Locale.ROOT,
              "successo stimato dal motore %.0f%% al lancio, %.0f%% ora; contrattacco massimo TUV"
                  + " %.0f -> %.0f",
              num(start, "successo_pct"),
              num(now, "successo_pct"),
              maxStart,
              maxNow);
      if (growth >= CRESCITA_NEMICO_PCT) {
        return new String[] {GIUSTIFICATO, "il nemico in portata e' cresciuto: " + numbers};
      }
      if (drop >= CALO_STIMA_PUNTI) {
        return new String[] {
          conquest ? C3 : C2,
          (conquest
                  ? "forze alla tappa inferiori al lancio: "
                  : "tenuta al punto di decisione inferiore al lancio: ")
              + numbers
        };
      }
    }
    if (conquest
        && !held
        && !started
        && bersagliCandidati != null
        && !bersagliCandidati.contains(def.bersaglio())) {
      return new String[] {C5, "il candidato non e' piu' nella lista del round"};
    }
    if (conquest
        && !held
        && !started
        && round - launched >= 2
        && reg.json().has("in_raccolta_tuv")
        && reg.json().get("in_raccolta_tuv").getAsDouble() <= 0) {
      return new String[] {C4, "nessuna unita' raccolta dopo " + (round - launched) + " round"};
    }
    if (start == null || now == null) {
      return new String[] {C6, "nessuna stima del motore da confrontare: stessi numeri"};
    }
    return new String[] {
      C6,
      String.format(
          Locale.ROOT,
          "stessi numeri del lancio: successo stimato dal motore %.0f%% al lancio, %.0f%% ora",
          num(start, "successo_pct"),
          num(now, "successo_pct"))
    };
  }

  private static double num(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
  }
}
