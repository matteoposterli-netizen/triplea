package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.RelationshipType;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.delegate.Matches;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Step P7: the data of the three levers ({@value #RESOURCE}), their activation, the pure rules and
 * the register rows (types {@code niente_neutrali}, {@code ponte_uk}, {@code ponte_pronti}).
 *
 * <ul>
 *   <li>A {@link StrategaInterruttori#NIENTE_NEUTRALI}: no political action toward war with the
 *       neutral of the data, none of its land as an attack target ({@link
 *       ProPlusTuning#allowsPoliticalAction}, {@link ProPlusTuning#excludesAttackTarget});
 *   <li>B {@link StrategaInterruttori#PONTE_UK}: the round's landing of the ally (register of
 *       {@link SbarcoConcentrato}); weight on Hard's attack priority while the enemy's ({@link
 *       #pesoAttacco}), reinforcement in the non-combat move while allied ({@link
 *       PonteRegnoUnito});
 *   <li>C {@link StrategaInterruttori#PONTE_PRONTI}: an indication to Hard's purchase, land units
 *       at home ({@link #ordini}).
 * </ul>
 */
public final class IndicazioniP7 {
  private IndicazioniP7() {}

  public static final String RESOURCE = "proplus/strategia/p7.json";
  static final String TIPO_A = "niente_neutrali";
  static final String TIPO_B = "ponte_uk";
  static final String TIPO_C = "ponte_pronti";
  static final String ETICHETTA_C = "P7 ponte pronti";

  /** The landing decisions of {@link SbarcoConcentrato} that put troops ashore. */
  static final Set<String> SBARCHI = Set.of("SBARCO_IN_FORZA", "SBARCO_DOPO_ATTESA");

  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * Rows already written (once per key: the purchase plans and the politics run twice). Never on a
   * simulation's copy of the game: a row there would open a new diary folder for the copy.
   */
  private static final Set<String> SCRITTE =
      Collections.synchronizedSet(
          Collections.newSetFromMap(
              new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> e) {
                  return size() > 5000;
                }
              }));

  /** The data file. */
  record Config(
      boolean neutraliDiDefault,
      List<String> nazioniNeutrali,
      String neutrale,
      boolean ponteDiDefault,
      List<String> nazioniPonte,
      String alleatoSbarco,
      int unitaMinime,
      double pesoAttacco,
      double fattoreEsposizione,
      boolean prontiDiDefault,
      List<String> nazioniPronti,
      String alleatoInGuerra,
      String patria,
      double quotaPct) {

    private static @Nullable Config cache;

    static synchronized Config get() {
      if (cache == null) {
        try (InputStream in = IndicazioniP7.class.getClassLoader().getResourceAsStream(RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject());
        } catch (final IOException e) {
          throw new IllegalStateException("File dello step P7 non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject root) {
      final JsonObject a = root.getAsJsonObject("niente_neutrali");
      final JsonObject b = root.getAsJsonObject("ponte_uk");
      final JsonObject bp = b.getAsJsonObject("parametri");
      final JsonObject c = root.getAsJsonObject("ponte_pronti");
      final JsonObject cp = c.getAsJsonObject("parametri");
      return new Config(
          v(a, "acceso_di_default").getAsBoolean(),
          lista(v(a, "nazioni")),
          v(a, "neutrale").getAsString(),
          v(b, "acceso_di_default").getAsBoolean(),
          lista(v(b, "nazioni")),
          v(b, "alleato_sbarco").getAsString(),
          v(bp, "unita_minime_sbarco").getAsInt(),
          v(bp, "peso_attacco").getAsDouble(),
          v(bp, "fattore_esposizione").getAsDouble(),
          v(c, "acceso_di_default").getAsBoolean(),
          lista(v(c, "nazioni")),
          v(c, "alleato_in_guerra").getAsString(),
          v(c, "patria").getAsString(),
          v(cp, "quota_pct").getAsDouble());
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

  // ======================================================================== switches

  /** {@code s} is on for the player's alliance, the player is the strategist's and in the data. */
  static boolean attivo(
      final GameState data, @Nullable final GamePlayer player, final StrategaInterruttori s) {
    if (player == null || data == null || !Stratega.gioca(data, player)) {
      return false;
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty() || !s.acceso(data.getProperties(), alliance.get())) {
      return false;
    }
    final Config c = Config.get();
    return switch (s) {
      case NIENTE_NEUTRALI -> c.nazioniNeutrali().contains(player.getName());
      case PONTE_UK -> c.nazioniPonte().contains(player.getName());
      case PONTE_PRONTI -> c.nazioniPronti().contains(player.getName());
      default -> false;
    };
  }

  // ======================================================================== A: neutrals

  /** A (pure rule): {@code action} changes a relationship with {@code neutral} into war. */
  static boolean guerraAlNeutrale(final PoliticalActionAttachment action, final String neutral) {
    for (final PoliticalActionAttachment.RelationshipChange rc : action.getRelationshipChanges()) {
      final RelationshipType to = rc.relationshipType;
      if (to != null
          && Matches.relationshipTypeIsAtWar().test(to)
          && (neutral.equals(rc.player1.getName()) || neutral.equals(rc.player2.getName()))) {
        return true;
      }
    }
    return false;
  }

  /** A: whether the politics of {@code proData}'s player may choose {@code action}. */
  static boolean consente(final ProData proData, final PoliticalActionAttachment action) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (!attivo(data, player, StrategaInterruttori.NIENTE_NEUTRALI)) {
      return true;
    }
    final String neutral = Config.get().neutrale();
    if (!guerraAlNeutrale(action, neutral)) {
      return true;
    }
    final int round = data.getSequence().getRound();
    if (!proData.isSimulation()
        && SCRITTE.add(round + "|" + player.getName() + "|A|" + action.getName())) {
      final JsonObject row = new JsonObject();
      row.addProperty("azione", action.getName());
      row.addProperty("neutrale", neutral);
      registra(
          data,
          player,
          TIPO_A,
          "GUERRA_NEUTRALI_ESCLUSA",
          "azione politica di guerra ai neutrali veri non scelta",
          row);
    }
    return false;
  }

  /** A (pure rule): a land territory owned by {@code neutral}. */
  static boolean delNeutrale(final Territory t, final String neutral) {
    return !t.isWater() && neutral.equals(t.getOwner().getName());
  }

  /** A: whether the combat move of {@code proData}'s player leaves {@code t} out. */
  static boolean escluso(final ProData proData, final Territory t) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (!attivo(data, player, StrategaInterruttori.NIENTE_NEUTRALI)
        || !delNeutrale(t, Config.get().neutrale())) {
      return false;
    }
    final int round = data.getSequence().getRound();
    if (!proData.isSimulation()
        && SCRITTE.add(round + "|" + player.getName() + "|A|" + t.getName())) {
      final JsonObject row = new JsonObject();
      row.addProperty("territorio", t.getName());
      registra(
          data,
          player,
          TIPO_A,
          "BERSAGLIO_NEUTRALE_ESCLUSO",
          "territorio dei neutrali veri tolto dai bersagli d'attacco di Hard",
          row);
    }
    return true;
  }

  // ======================================================================== B: bridgehead

  /**
   * B (pure rule): the territories where the ally landed this round with at least {@code min} land
   * units, from the register rows (landing decisions of {@link SbarcoConcentrato}).
   */
  static Map<String, Integer> sbarchi(
      final List<JsonObject> rows, final String ally, final int round, final int min) {
    final Map<String, Integer> sum = new TreeMap<>();
    for (final JsonObject r : rows) {
      try {
        if (!"decisione".equals(str(r, "riga"))
            || !SbarcoConcentrato.TIPO.equals(str(r, "tipo"))
            || !ally.equals(str(r, "nazione"))
            || r.get("round").getAsInt() != round
            || !SBARCHI.contains(r.getAsJsonObject("motivo").get("codice").getAsString())) {
          continue;
        }
        final JsonObject p = r.getAsJsonObject("parametri");
        final int n = p.get("unita_sbarcate").getAsInt();
        if (n > 0) {
          sum.merge(p.get("bersaglio").getAsString(), n, Integer::sum);
        }
      } catch (final RuntimeException e) {
        // a malformed row is skipped
      }
    }
    sum.values().removeIf(n -> n < min);
    return sum;
  }

  /** B: this round's landings of the ally (empty if B is off for the player). */
  static Map<String, Integer> sbarchiDelRound(final GameState data, final GamePlayer player) {
    if (!attivo(data, player, StrategaInterruttori.PONTE_UK)) {
      return Map.of();
    }
    final Config c = Config.get();
    return sbarchi(
        RegistroDecisioni.righe(data),
        c.alleatoSbarco(),
        data.getSequence().getRound(),
        c.unitaMinime());
  }

  /** B: the attack weight of {@code target} (the ally's landing failed: still the enemy's). */
  static double pesoAttacco(final ProData proData, final Territory target) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (player == null
        || target.isWater()
        || target.getOwner().isNull()
        || !player.isAtWar(target.getOwner())) {
      return 0;
    }
    final Map<String, Integer> landed = sbarchiDelRound(data, player);
    if (!landed.containsKey(target.getName())) {
      return 0;
    }
    final int round = data.getSequence().getRound();
    if (!proData.isSimulation()
        && SCRITTE.add(round + "|" + player.getName() + "|B|" + target.getName())) {
      final JsonObject row = new JsonObject();
      row.addProperty("territorio", target.getName());
      row.addProperty("proprietario", target.getOwner().getName());
      row.addProperty("unita_sbarcate_alleato", landed.get(target.getName()));
      row.addProperty("peso", Config.get().pesoAttacco());
      registra(
          data,
          player,
          TIPO_B,
          "PONTE_PESO_ATTACCO",
          "sbarco alleato fallito: peso sul territorio tra i bersagli d'attacco di Hard",
          row);
    }
    return Config.get().pesoAttacco();
  }

  // ======================================================================== C: troops ready

  /** C: whether the ally of the data is at war with a nation of another configurable alliance. */
  static boolean alleatoInGuerra(final GameState data) {
    final GamePlayer ally = data.getPlayerList().getPlayerId(Config.get().alleatoInGuerra());
    if (ally == null) {
      return false;
    }
    final Optional<String> own = ProPlusSettings.allianceOf(data, ally);
    for (final GamePlayer p : data.getPlayerList().getPlayers()) {
      final Optional<String> theirs = ProPlusSettings.allianceOf(data, p);
      if (!p.equals(ally)
          && theirs.isPresent()
          && own.isPresent()
          && !theirs.equals(own)
          && ally.isAtWar(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * C (pure rule): an indication while the troops at home do not exceed the capacity next to it.
   */
  static boolean sottoCapacita(final int postiInPatria, final int capacita) {
    return postiInPatria <= capacita;
  }

  /** C: transport places of the own land units at home. */
  static int postiInPatria(final Territory home, final GamePlayer player) {
    int n = 0;
    for (final Unit u :
        home.getMatches(
            Matches.unitIsOwnedBy(player)
                .and(Matches.unitIsLand())
                .and(Matches.unitIsNotInfrastructure()))) {
      n += Math.max(0, u.getUnitAttachment().getTransportCost());
    }
    return n;
  }

  /** C: capacity of the own transports in the sea zones next to home. */
  static int capacitaAccanto(final GameState data, final Territory home, final GamePlayer player) {
    int n = 0;
    for (final Territory z : data.getMap().getNeighbors(home, Matches.territoryIsWater())) {
      for (final Unit u :
          z.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsSeaTransportButNotCombatSeaTransport()))) {
        n += u.getUnitAttachment().getTransportCapacity();
      }
    }
    return n;
  }

  /** C: the indication for Hard's purchase this turn (empty: Hard buys as always). */
  static Optional<ProPurchaseOrders> ordini(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    final GameState data = proData.getData();
    if (!attivo(data, player, StrategaInterruttori.PONTE_PRONTI) || !alleatoInGuerra(data)) {
      return Optional.empty();
    }
    final Config c = Config.get();
    final Territory home = data.getMap().getTerritoryOrNull(c.patria());
    if (home == null || !home.isOwnedBy(player)) {
      return Optional.empty();
    }
    final int places = postiInPatria(home, player);
    final int cap = capacitaAccanto(data, home, player);
    final int round = data.getSequence().getRound();
    final boolean write =
        !proData.isSimulation() && SCRITTE.add(round + "|" + player.getName() + "|C");
    final JsonObject row = new JsonObject();
    row.addProperty("patria", home.getName());
    row.addProperty("posti_terra_in_patria", places);
    row.addProperty("capacita_trasporti_accanto", cap);
    row.addProperty("quota_pct", c.quotaPct());
    if (!sottoCapacita(places, cap)) {
      if (write) {
        registra(
            data,
            player,
            TIPO_C,
            "PRONTI_OLTRE_CAPACITA",
            "truppe in patria oltre la capacita' dei trasporti accanto: nessuna indicazione",
            row);
      }
      return Optional.empty();
    }
    final List<String> types = tipiTerra(proData);
    if (types.isEmpty()) {
      return Optional.empty();
    }
    final JsonArray t = new JsonArray();
    types.forEach(t::add);
    row.add("tipi", t);
    if (write) {
      registra(
          data,
          player,
          TIPO_C,
          "PRONTI_INDICAZIONE",
          "dopo la difesa di Hard, quota dei PU in unita' di terra in patria",
          row);
    }
    return Optional.of(
        new ProPurchaseOrders(
            ETICHETTA_C + " " + player.getName(),
            List.of(new ProPurchaseOrders.Category(ETICHETTA_C, c.quotaPct(), types)),
            List.of(),
            List.of(home.getName()),
            List.of(),
            0));
  }

  /** C: the land types in rotation: one transport's load by Hard's amphibious efficiency. */
  static List<String> tipiTerra(final ProData proData) {
    final List<ForzaSbarco.Opzione> options = ForzaSbarco.opzioniTruppe(proData);
    final int cap =
        ForzaSbarco.opzioneTrasporto(proData)
            .map(o -> o.getUnitType().getUnitAttachment().getTransportCapacity())
            .orElse(0);
    final List<String> load = cap > 0 ? ForzaSbarco.riempi(cap, options) : List.of();
    if (!load.isEmpty()) {
      return load;
    }
    final Set<String> all = new LinkedHashSet<>();
    options.forEach(o -> all.add(o.tipo()));
    return List.copyOf(all);
  }

  // ======================================================================== register

  private static @Nullable String str(final JsonObject o, final String k) {
    return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
  }

  /** A register row of step P7 (type {@code tipo}, reason {@code codice}). Never throws. */
  static void registra(
      final GameState data,
      final GamePlayer player,
      final String tipo,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    try {
      final int round = data.getSequence().getRound();
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              "R"
                  + round
                  + "-"
                  + player.getName()
                  + "-"
                  + tipo
                  + "-"
                  + codice
                  + "-"
                  + SEQ.incrementAndGet(),
              round,
              player.getName(),
              tipo,
              new JsonArray(),
              codice,
              codice,
              frase,
              parametri));
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] P7, registro: " + e);
    }
  }
}
