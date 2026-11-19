package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProDecisionRecorder;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProPlaceTerritory;
import games.strategy.triplea.ai.pro.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Step P4: three indications to Hard for the strategist's Allies, each behind its own switch (off
 * by default): {@link StrategaInterruttori#USA_PRONTI} ({@link GuerraVicina}), {@link
 * StrategaInterruttori#NORVEGIA_PRESTO} (a target weight, here) and {@link
 * StrategaInterruttori#RUSSIA_FRONTE} ({@link FronteCasa}). Values, thresholds and blind spots
 * removed; Hard still decides. No nation or territory in the code: the data file {@value #RESOURCE}
 * has them.
 *
 * <p>Also the per-AI state of the turn ({@link Stato}) and the register rows (types {@code
 * usa_pronti}, {@code norvegia_presto}, {@code russia_fronte}).
 */
public final class IndicazioniP4 {
  private IndicazioniP4() {}

  public static final String RESOURCE = "proplus/strategia/p4.json";

  // ============================================================================ data

  /** One nation of {@code usa_pronti}. */
  record Guerra(int guerraDopoIlRound, Map<String, String> relazioni, List<String> nemici) {}

  /** One weighted target of {@code norvegia_presto}. */
  record Bersaglio(String territorio, double peso, int dalRound, int alRound) {}

  /** The parameters of {@code russia_fronte}. */
  record Fronte(
      double pesoDifesaFabbrica,
      double pesoRipresaFabbrica,
      double sogliaDesiderata,
      double sogliaMinima,
      double sogliaTenutaCapitale,
      double scambioMinimo) {}

  /** The whole data file. */
  record Config(
      boolean usaDiDefault,
      int raggioZoneMare,
      double fattoreCosta,
      int turniDiGuerraConValoreMare,
      Map<String, Guerra> guerra,
      boolean norvegiaDiDefault,
      Map<String, List<Bersaglio>> bersagli,
      boolean fronteDiDefault,
      Fronte fronte,
      List<String> nazioniFronte) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = IndicazioniP4.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P4 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      final JsonObject a = root.getAsJsonObject("usa_pronti");
      final JsonObject ap = a.getAsJsonObject("parametri");
      final Map<String, Guerra> guerra = new LinkedHashMap<>();
      for (final var e : a.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject n = e.getValue().getAsJsonObject();
        final Map<String, String> rel = new LinkedHashMap<>();
        n.getAsJsonObject("relazioni").entrySet().forEach(r -> rel.put(r.getKey(), str(r)));
        final List<String> enemies = new ArrayList<>();
        n.getAsJsonArray("nemici").forEach(x -> enemies.add(x.getAsString()));
        guerra.put(
            e.getKey(),
            new Guerra(
                n.get("guerra_dopo_il_round").getAsInt(), Map.copyOf(rel), List.copyOf(enemies)));
      }
      final JsonObject b = root.getAsJsonObject("norvegia_presto");
      final Map<String, List<Bersaglio>> targets = new LinkedHashMap<>();
      for (final var e : b.getAsJsonObject("nazioni").entrySet()) {
        final List<Bersaglio> list = new ArrayList<>();
        for (final JsonElement x : e.getValue().getAsJsonArray()) {
          final JsonObject o = x.getAsJsonObject();
          list.add(
              new Bersaglio(
                  o.get("territorio").getAsString(),
                  o.get("peso").getAsDouble(),
                  o.get("dal_round").getAsInt(),
                  o.get("al_round").getAsInt()));
        }
        targets.put(e.getKey(), List.copyOf(list));
      }
      final JsonObject c = root.getAsJsonObject("russia_fronte");
      final JsonObject cp = c.getAsJsonObject("parametri");
      final List<String> front = new ArrayList<>();
      c.getAsJsonArray("nazioni").forEach(x -> front.add(x.getAsString()));
      return new Config(
          v(a, "acceso_di_default").getAsBoolean(),
          v(ap, "raggio_zone_mare").getAsInt(),
          v(ap, "fattore_costa").getAsDouble(),
          ap.has("turni_di_guerra_con_valore_mare")
              ? v(ap, "turni_di_guerra_con_valore_mare").getAsInt()
              : 0,
          Map.copyOf(guerra),
          v(b, "acceso_di_default").getAsBoolean(),
          Map.copyOf(targets),
          v(c, "acceso_di_default").getAsBoolean(),
          new Fronte(
              v(cp, "peso_difesa_fabbrica").getAsDouble(),
              v(cp, "peso_ripresa_fabbrica").getAsDouble(),
              v(cp, "soglia_desiderata_contrattacco").getAsDouble(),
              v(cp, "soglia_minima_contrattacco").getAsDouble(),
              v(cp, "soglia_tenuta_capitale").getAsDouble(),
              v(cp, "scambio_minimo").getAsDouble()),
          List.copyOf(front));
    }

    private static String str(final Map.Entry<String, JsonElement> e) {
      return e.getValue().getAsString();
    }

    private static JsonElement v(final JsonObject o, final String key) {
      final JsonElement e = o.get(key);
      return e.isJsonObject() && e.getAsJsonObject().has("valore")
          ? e.getAsJsonObject().get("valore")
          : e;
    }
  }

  // ======================================================================== switches

  /** {@code s} is on for the player's alliance, the player is the strategist's and in the data. */
  static boolean attivo(
      final GameState data, @Nullable final GamePlayer player, final StrategaInterruttori s) {
    if (player == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty() || !s.acceso(data.getProperties(), alliance.get())) {
      return false;
    }
    final Config c = Config.get();
    final String name = player.getName();
    return switch (s) {
      case USA_PRONTI -> c.guerra().containsKey(name);
      case NORVEGIA_PRESTO -> c.bersagli().containsKey(name);
      case RUSSIA_FRONTE -> c.nazioniFronte().contains(name);
      default -> false;
    };
  }

  // ========================================================================= B: weight

  /**
   * B ({@code norvegia_presto}): the weight of {@code target} in the attack priority of the
   * player's Hard this round (0 out of the data's rounds, for other targets, switch off).
   */
  static double pesoBersaglio(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (player == null
        || data == null
        || !attivo(data, player, StrategaInterruttori.NORVEGIA_PRESTO)) {
      return 0;
    }
    return peso(
        Config.get().bersagli().getOrDefault(player.getName(), List.of()),
        target.getName(),
        data.getSequence().getRound());
  }

  /** The weight of {@code target} in {@code round} (pure rule). */
  static double peso(final List<Bersaglio> list, final String target, final int round) {
    double best = 0;
    for (final Bersaglio b : list) {
      if (b.territorio().equals(target) && round >= b.dalRound() && round <= b.alRound()) {
        best = Math.max(best, b.peso());
      }
    }
    return best;
  }

  /** The targets of the round for the register (B). */
  static List<Bersaglio> bersagliDelRound(final String nation, final int round) {
    return Config.get().bersagli().getOrDefault(nation, List.of()).stream()
        .filter(b -> round >= b.dalRound() && round <= b.alRound())
        .toList();
  }

  // =================================================================== turn state

  /**
   * The state of one AI's turn (one AI = one nation): what the register needs and what the hooks of
   * the tuning read (A's war view, C's targets and repairs).
   */
  public static final class Stato {
    int round = -1;

    /** A: the war view of the purchase in progress (null: none). */
    @Nullable GuerraVicina.Vista vista;

    /** A: the sea zones' value also without the war view (first war turns). */
    boolean soloValoreMare;

    /** A: the units bought at each step of the purchase in the war view. */
    final Map<String, Map<String, Integer>> passi = new LinkedHashMap<>();

    private Map<String, Integer> finora = new TreeMap<>();

    /** A: the value given to the place sea zones. */
    final Map<String, Double> valoriMare = new TreeMap<>();

    /** B and C: Hard's attack priority of the watched targets (name → value). */
    final Map<String, Double> priorita = new TreeMap<>();

    /** B and C: the targets dropped by Hard (name → reason). */
    final Map<String, String> scartati = new TreeMap<>();

    /** B and C: Hard's attacks (name → {unita, vittoria}), the last plan of the turn. */
    final Map<String, JsonObject> attacchi = new TreeMap<>();

    /** C: the counterattack targets of the turn (name → estimate). */
    final Map<String, FronteCasa.Candidato> candidati = new TreeMap<>();

    /** C: the targets kept in the defensive stance by the switch. */
    final List<String> stanceTenuti = new ArrayList<>();

    /** C: repairs (factory → {danno, riparato, risparmiato}). */
    final JsonArray riparazioni = new JsonArray();

    /** C: the own non-capital home factories (territory names) found at the start of the turn. */
    final List<String> fabbriche = new ArrayList<>();

    /** Clears the turn's state on a new round. */
    void inizio(final int newRound) {
      if (newRound == round) {
        return;
      }
      round = newRound;
      vista = null;
      soloValoreMare = false;
      passi.clear();
      finora = new TreeMap<>();
      valoriMare.clear();
      priorita.clear();
      scartati.clear();
      attacchi.clear();
      candidati.clear();
      stanceTenuti.clear();
      while (!riparazioni.isEmpty()) {
        riparazioni.remove(0);
      }
      fabbriche.clear();
      osservatiB.clear();
    }

    boolean osservato(final String territory) {
      return candidati.containsKey(territory) || osservatiB.contains(territory);
    }

    final List<String> osservatiB = new ArrayList<>();

    void passo(
        final String step,
        final Map<Territory, ProPurchaseTerritory> purchase,
        final Map<Territory, ProPurchaseTerritory> factories) {
      if (vista == null && !soloValoreMare) {
        return;
      }
      final Map<String, Integer> now = new TreeMap<>();
      for (final Map<Territory, ProPurchaseTerritory> m : List.of(purchase, factories)) {
        for (final ProPurchaseTerritory ppt : m.values()) {
          for (final ProPlaceTerritory place : ppt.getCanPlaceTerritories()) {
            for (final Unit u : place.getPlaceUnits()) {
              now.merge(u.getType().getName(), 1, Integer::sum);
            }
          }
        }
      }
      final Map<String, Integer> delta = new TreeMap<>();
      for (final var e : now.entrySet()) {
        final int d = e.getValue() - finora.getOrDefault(e.getKey(), 0);
        if (d != 0) {
          delta.put(e.getKey(), d);
        }
      }
      if (!delta.isEmpty()) {
        passi.merge(
            step,
            delta,
            (x, y) -> {
              final Map<String, Integer> z = new TreeMap<>(x);
              y.forEach((k, n) -> z.merge(k, n, Integer::sum));
              return z;
            });
      }
      finora = now;
    }
  }

  /**
   * The recorder of {@code proData} with a tap that also gives {@code stato} the purchase steps (A)
   * and the attack priorities and drops of the watched targets (B, C). Never changes what the inner
   * recorder receives. Idempotent.
   */
  static void tap(final ProData proData, final Stato stato) {
    final ProDecisionRecorder inner = proData.getRecorder();
    if (Proxy.isProxyClass(inner.getClass()) && Proxy.getInvocationHandler(inner) instanceof Tap) {
      return;
    }
    proData.setRecorder(
        (ProDecisionRecorder)
            Proxy.newProxyInstance(
                ProDecisionRecorder.class.getClassLoader(),
                new Class<?>[] {ProDecisionRecorder.class},
                new Tap(inner, stato)));
  }

  /** The recorder under the tap of {@link #tap} ({@code r} itself without one). */
  public static ProDecisionRecorder interno(final ProDecisionRecorder r) {
    if (Proxy.isProxyClass(r.getClass()) && Proxy.getInvocationHandler(r) instanceof Tap t) {
      return t.inner();
    }
    return r;
  }

  private record Tap(ProDecisionRecorder inner, Stato stato) implements InvocationHandler {
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(final Object proxy, final Method m, final Object[] args) throws Throwable {
      try {
        switch (m.getName()) {
          case "purchaseStep" ->
              stato.passo(
                  (String) args[1],
                  (Map<Territory, ProPurchaseTerritory>) args[2],
                  (Map<Territory, ProPurchaseTerritory>) args[3]);
          case "attackPriority" -> {
            final ProTerritory patd = (ProTerritory) args[1];
            final String name = patd.getTerritory().getName();
            if (stato.osservato(name)) {
              stato.priorita.put(name, patd.getValue());
            }
          }
          case "attackDropped" -> {
            final ProTerritory patd = (ProTerritory) args[1];
            final String name = patd.getTerritory().getName();
            if (stato.osservato(name)) {
              stato.scartati.put(name, String.valueOf(args[2]));
            }
          }
          default -> {
            // nothing to note
          }
        }
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] P4, osservazione: " + e);
      }
      if (m.getDeclaringClass() == Object.class) {
        return m.invoke(inner, args);
      }
      try {
        return m.invoke(inner, args);
      } catch (final InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }

  /** B and C: Hard's attacks of the watched targets (from the tuning's review hook). */
  static void attacchiDiHard(final Stato stato, final Map<Territory, ProTerritory> attackMap) {
    for (final var e : attackMap.entrySet()) {
      final String name = e.getKey().getName();
      if (!stato.osservato(name)) {
        continue;
      }
      final ProTerritory patd = e.getValue();
      final List<Unit> units = new ArrayList<>(patd.getUnits());
      patd.getAmphibAttackMap().values().forEach(units::addAll);
      final JsonObject o = new JsonObject();
      o.addProperty("unita", units.size());
      final Map<String, Integer> types = new TreeMap<>();
      units.forEach(u -> types.merge(u.getType().getName(), 1, Integer::sum));
      final JsonObject t = new JsonObject();
      types.forEach(t::addProperty);
      o.add("tipi", t);
      final ProBattleResult r = patd.getBattleResult();
      o.addProperty("vittoria_stimata", r == null ? -1 : Math.round(r.getWinPercentage()));
      o.addProperty("scambio_stimato", r == null ? 0 : Math.round(r.getTuvSwing()));
      if (units.isEmpty()) {
        stato.attacchi.remove(name);
      } else {
        stato.attacchi.put(name, o);
      }
    }
  }

  // ======================================================================== register

  /** A register row of step P4 (type {@code tipo}, reason {@code codice}). Never throws. */
  static void registra(
      final GameState data,
      final int round,
      final GamePlayer player,
      final String tipo,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    try {
      final String id = "R" + round + "-" + player.getName() + "-" + tipo;
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              tipo,
              new JsonArray(),
              codice,
              codice,
              frase,
              parametri));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P4, registro: " + e);
    }
  }

  /** B: the row of the nation's turn (after the non-combat move: the outcome is known). */
  static void registraBersagli(final GameState data, final GamePlayer player, final Stato stato) {
    final int round = data.getSequence().getRound();
    final List<Bersaglio> today = bersagliDelRound(player.getName(), round);
    if (today.isEmpty()) {
      return;
    }
    final JsonObject p = new JsonObject();
    final JsonArray list = new JsonArray();
    for (final Bersaglio b : today) {
      final JsonObject o = new JsonObject();
      o.addProperty("territorio", b.territorio());
      o.addProperty("peso", b.peso());
      o.addProperty("priorita_hard", stato.priorita.getOrDefault(b.territorio(), -1.0));
      o.addProperty("candidato", stato.priorita.containsKey(b.territorio()));
      if (stato.scartati.containsKey(b.territorio())) {
        o.addProperty("scartato", stato.scartati.get(b.territorio()));
      }
      final JsonObject att = stato.attacchi.get(b.territorio());
      o.addProperty("attaccato", att != null);
      if (att != null) {
        o.add("attacco", att);
      }
      final Territory t = data.getMap().getTerritoryOrNull(b.territorio());
      o.addProperty("proprietario_fine_turno", t == null ? "?" : t.getOwner().getName());
      o.addProperty(
          "alleato_fine_turno",
          t != null && (t.getOwner().equals(player) || player.isAllied(t.getOwner())));
      list.add(o);
    }
    p.add("bersagli", list);
    registra(
        data,
        round,
        player,
        "norvegia_presto",
        "PESO_ATTIVO",
        "bersagli con peso alzato nella priorita' d'attacco di Hard",
        p);
  }
}
