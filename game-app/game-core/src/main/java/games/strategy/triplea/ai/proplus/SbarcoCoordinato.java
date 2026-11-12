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
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Step R3a "SBARCO COORDINATO E TEATRI", behind the switch {@code impegno} (off by default = R2c
 * and before), only for the nations of {@code nazioni_r3a} (the Allies; the Axis stays R2c).
 *
 * <ol>
 *   <li><b>Theater</b> ({@link Turno#teatro}, alliance choice at the start of the round, in the US
 *       turn): NORTH (base United Kingdom) or SOUTH (Morocco or Gibraltar). Open = the shuttle's
 *       sea zones toward its base are safe; one open = that one; both = the score "alliance hold x
 *       value of the best target"; at least {@code round_minimi_tra_cambi} between changes, never
 *       with a wave at sea. The shuttle and its base follow the theater.
 *   <li><b>Coordinated landing</b>: PREPARATION = the landing force at the base is held out of
 *       Hard's attacks (it still defends the base); GREEN LIGHT in the US turn with win >= {@code
 *       soglia_via_libera} with the load of the transports in position AND the realistic Axis
 *       counterattack within {@code soglia_tenuta_alleanza} against the US survivors plus the
 *       British reinforcements of the same round: the USA lands everything at once ({@link
 *       #combattimento}). The United Kingdom reinforces the bridgehead, completes it only with win
 *       >= {@code soglia_completa_uk}, lands alone only if it holds alone. A bridgehead held with
 *       safe zones becomes the shuttle's base.
 *   <li><b>Transport cap</b> ({@link #tetto}): Hard buys no transport for the USA and the United
 *       Kingdom while an idle empty usable transport exists.
 * </ol>
 *
 * <p>Every decision goes to the register {@link RegistroDecisioni}. State per alliance in {@value
 * #KEY_STATO}; the cap of a nation in {@value #KEY_TETTO}.
 */
public final class SbarcoCoordinato {
  private SbarcoCoordinato() {}

  public static final String KEY_STATO = "Pro+ stratega r3a: ";
  public static final String KEY_TETTO = "Pro+ stratega r3a tetto: ";
  static final String NORD = "NORD";
  static final String SUD = "SUD";
  private static final String AFRICA_TUTTE = "AFRICA";
  private static final String FRANCIA_TUTTE = "FRANCIA";

  // ================================================================================ config

  /** A theater: its bases (the first usable one) and its targets (from the map). */
  public record Teatro(List<String> basi, List<String> bersagli) {}

  /** Values of {@code impegno.r3a} in {@code stratega-llm.json} ("scelto" or from the map). */
  public record Config(
      List<String> nazioni,
      List<String> nazioniCampagnaTerra,
      Map<String, Teatro> teatri,
      String teatroIniziale,
      double sogliaMareAperto,
      int roundMinimi,
      double sogliaViaLibera,
      double sogliaTenuta,
      double sogliaCompletaUk,
      Map<String, Integer> obiettiviTolti,
      List<String> campagneTestaDiPonte,
      List<String> nordAfrica,
      int distanzaFabbricaAfrica,
      Map<String, List<String>> patria,
      List<String> europaOccidentale,
      List<String> mediterraneo) {

    private static @Nullable Config cache;

    public static synchronized Config get() {
      if (cache == null) {
        try (InputStream in =
            SbarcoCoordinato.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
          if (in == null) {
            throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
          }
          cache =
              parse(
                  JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                      .getAsJsonObject()
                      .getAsJsonObject("impegno")
                      .getAsJsonObject("r3a"));
        } catch (final IOException e) {
          throw new IllegalStateException("File dello stratega non valido: " + e, e);
        }
      }
      return cache;
    }

    static Config parse(final JsonObject o) {
      final Map<String, Teatro> theaters = new LinkedHashMap<>();
      for (final var e : v(o, "teatri").getAsJsonObject().entrySet()) {
        final JsonObject t = e.getValue().getAsJsonObject();
        theaters.put(
            e.getKey(),
            new Teatro(strings(t.getAsJsonArray("basi")), strings(t.getAsJsonArray("bersagli"))));
      }
      final Map<String, Integer> objectives = new LinkedHashMap<>();
      v(o, "obiettivi_nazionali_tolti")
          .getAsJsonObject()
          .entrySet()
          .forEach(e -> objectives.put(e.getKey(), e.getValue().getAsInt()));
      final JsonObject regions = v(o, "regioni").getAsJsonObject();
      final Map<String, List<String>> home = new LinkedHashMap<>();
      regions
          .getAsJsonObject("patria")
          .entrySet()
          .forEach(e -> home.put(e.getKey(), strings(e.getValue().getAsJsonArray())));
      return new Config(
          strings(v(o, "nazioni_r3a").getAsJsonArray()),
          strings(v(o, "nazioni_campagna_terra").getAsJsonArray()),
          Map.copyOf(theaters),
          v(o, "teatro_iniziale").getAsString(),
          v(o, "soglia_mare_aperto").getAsDouble(),
          v(o, "round_minimi_tra_cambi").getAsInt(),
          v(o, "soglia_via_libera").getAsDouble(),
          v(o, "soglia_tenuta_alleanza").getAsDouble(),
          v(o, "soglia_completa_uk").getAsDouble(),
          Map.copyOf(objectives),
          strings(v(o, "campagne_testa_di_ponte").getAsJsonArray()),
          strings(v(o, "nord_africa").getAsJsonArray()),
          v(o, "distanza_fabbrica_africa").getAsInt(),
          Map.copyOf(home),
          strings(regions.getAsJsonArray("europa_occidentale")),
          strings(regions.getAsJsonArray("mediterraneo_nord_africa")));
    }

    private static JsonElement v(final JsonObject o, final String key) {
      return o.getAsJsonObject(key).get("valore");
    }

    private static List<String> strings(final JsonArray a) {
      final List<String> out = new ArrayList<>();
      a.forEach(e -> out.add(e.getAsString()));
      return List.copyOf(out);
    }
  }

  // ============================================================================ pure rules

  /** The theater chosen, with the reason code of the register and a sentence. */
  record SceltaTeatro(String teatro, String codice, String frase) {}

  /**
   * The theater rule: one open = that one; both open = the higher score (a tie keeps the current
   * one); none open = the current one. A change needs {@code minimi} rounds since the last one and
   * no wave at sea. The first choice has no inertia.
   */
  static SceltaTeatro sceltaTeatro(
      @Nullable final String attuale,
      final int dalRound,
      final int round,
      final boolean ondataInMare,
      final boolean apertoNord,
      final boolean apertoSud,
      final double punteggioNord,
      final double punteggioSud,
      final int minimi,
      final String iniziale) {
    final String keep = attuale == null ? iniziale : attuale;
    String wanted;
    String code;
    String why;
    if (apertoNord != apertoSud) {
      wanted = apertoNord ? NORD : SUD;
      code = "UNICO_APERTO";
      why = "solo " + wanted + " aperto";
    } else if (apertoNord) {
      if (punteggioNord > punteggioSud) {
        wanted = NORD;
      } else if (punteggioSud > punteggioNord) {
        wanted = SUD;
      } else {
        wanted = keep;
      }
      code = "PUNTEGGIO_MAGGIORE";
      why =
          String.format(
              Locale.ROOT,
              "entrambi aperti, punteggio NORD %.1f SUD %.1f",
              punteggioNord,
              punteggioSud);
    } else {
      wanted = keep;
      code = "NESSUNO_APERTO";
      why = "nessun teatro aperto: resta " + keep;
    }
    if (attuale == null) {
      return new SceltaTeatro(wanted, "PRIMA_SCELTA", "prima scelta (" + why + ")");
    }
    if (wanted.equals(attuale)) {
      return "NESSUNO_APERTO".equals(code)
          ? new SceltaTeatro(attuale, code, why)
          : new SceltaTeatro(attuale, "INVARIATO", why + ": resta " + attuale);
    }
    if (ondataInMare) {
      return new SceltaTeatro(
          attuale, "ONDATA_IN_MARE", why + ", ma un'ondata e' in mare: resta " + attuale);
    }
    if (round - dalRound < minimi) {
      return new SceltaTeatro(
          attuale,
          "INERZIA_ROUND",
          why
              + ", ma "
              + (round - dalRound)
              + " round dall'ultimo cambio < "
              + minimi
              + ": resta "
              + attuale);
    }
    return new SceltaTeatro(wanted, code, why + ": cambio " + attuale + " -> " + wanted);
  }

  /** The green light (code VIA_LIBERA) or what is missing. */
  record ViaLibera(boolean via, String codice) {}

  /** Win >= {@code sogliaVittoria} and the counterattack's win <= {@code sogliaTenuta}. */
  static ViaLibera viaLibera(
      final double vittoria,
      final double contrattacco,
      final double sogliaVittoria,
      final double sogliaTenuta) {
    final boolean win = vittoria >= sogliaVittoria;
    final boolean hold = contrattacco <= sogliaTenuta;
    if (win && hold) {
      return new ViaLibera(true, "VIA_LIBERA");
    }
    if (!win && !hold) {
      return new ViaLibera(false, "MANCANO_VITTORIA_E_TENUTA");
    }
    return new ViaLibera(false, win ? "MANCA_TENUTA" : "MANCA_VITTORIA");
  }

  /** The cap: no transport for Hard while an idle empty usable transport exists. */
  static boolean tettoTrasporti(final int fermiVuoti) {
    return fermiVuoti > 0;
  }

  // ========================================================================== entry points

  /** The switch is on and the nation plays step R3a (the Allies of the strategist). */
  public static boolean attivo(final GameState data, final GamePlayer player) {
    return player != null
        && Impegno.attivo(data, player)
        && Config.get().nazioni().contains(player.getName());
  }

  /** The base of the shuttle of {@code alliance} now (theater base or bridgehead), if any. */
  public static Optional<String> baseCorrente(final GameState data, final String alliance) {
    final JsonObject st = stato(data.getProperties(), alliance);
    return st.has("base") ? Optional.of(st.get("base").getAsString()) : Optional.empty();
  }

  /** The current theater of {@code alliance}, if any. */
  public static Optional<String> teatroCorrente(final GameState data, final String alliance) {
    final JsonObject st = stato(data.getProperties(), alliance);
    return st.has("teatro") ? Optional.of(st.get("teatro").getAsString()) : Optional.empty();
  }

  /** Units of {@code player} held out of Hard's combat this round (preparation and the wave). */
  public static Set<Unit> trattenute(final GameState data, final GamePlayer player) {
    if (!attivo(data, player)) {
      return Set.of();
    }
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return Set.of();
    }
    final JsonObject st = stato(data.getProperties(), alliance.get());
    if (!st.has("trattenute")) {
      return Set.of();
    }
    final JsonObject t = st.getAsJsonObject("trattenute");
    if (!t.has(player.getName())) {
      return Set.of();
    }
    final JsonObject n = t.getAsJsonObject(player.getName());
    if (n.get("round").getAsInt() != data.getSequence().getRound()) {
      return Set.of();
    }
    final Set<String> ids = new HashSet<>();
    n.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
    final Set<Unit> out = new LinkedHashSet<>();
    for (final Territory x : data.getMap().getTerritories()) {
      x.getUnitCollection().stream()
          .filter(u -> ids.contains(u.getId().toString()))
          .forEach(out::add);
    }
    return out;
  }

  /** The cap on Hard's transports holds for the nation of {@code proData} this round. */
  public static boolean tetto(final ProData proData) {
    final GamePlayer player = proData.getPlayer();
    if (player == null || !attivo(proData.getData(), player)) {
      return false;
    }
    return proData.getData().getProperties().get(KEY_TETTO + player.getName()) instanceof String s
        && s.equals(String.valueOf(proData.getData().getSequence().getRound()));
  }

  /**
   * Purchase of a nation of R3a (called by {@link Impegno} with its calculation, lines and the
   * cases of rule 2.6): checks of the earlier decisions, the shuttle's base, the theater (USA), the
   * units held in preparation, the green light (USA) or the United Kingdom's action, the cap.
   */
  static void inizio(
      final ProData proData,
      final ProOddsCalculator calc,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final List<String> lines) {
    if (!attivo(proData.getData(), player)) {
      return;
    }
    final GameProperties props = proData.getData().getProperties();
    if (!AlleatiR5.attivo(proData.getData(), player)) {
      // step R5 decides the cap (B2) at the program's purchase
      props.set(KEY_TETTO + player.getName(), (Serializable) null);
    }
    if (Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName())) {
      try {
        new Turno(proData, calc, player, c, lines).inizio();
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] R3a, inizio turno: " + e);
        lines.add("  R3a: errore " + e);
      }
    }
  }

  /**
   * Combat move of the USA or the United Kingdom (real board, before Hard's): the wave decided at
   * the purchase lands with all its load. Returns the units moved (Hard must leave them).
   */
  public static Set<Unit> combattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player) {
    if (!attivo(proData.getData(), player)
        || !(Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName()))) {
      return Set.of();
    }
    try {
      final Optional<String> alliance = ProPlusSettings.allianceOf(proData.getData(), player);
      final CampagnaOmbra.Calcolo c =
          new CampagnaOmbra.Calcolo(
              proData, calc, player, alliance.orElseThrow(), CampagnaOmbra.Config.get());
      final List<String> lines = new ArrayList<>();
      final Set<Unit> moved = new Turno(proData, calc, player, c, lines).combattimento(moveDel);
      Impegno.diarioRighe(proData.getData(), lines);
      return moved;
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, combattimento: " + e);
      return Set.of();
    }
  }

  /**
   * Non-combat move of the USA or the United Kingdom (called by {@link Impegno}): outcome of the
   * wave, the bridgehead, the British reinforcement, the units kept at the base. Returns the units
   * Hard must leave where they are.
   */
  static Set<Unit> nonCombattimento(
      final ProData proData,
      final ProOddsCalculator calc,
      final IMoveDelegate moveDel,
      final GamePlayer player,
      final CampagnaOmbra.Calcolo c,
      final List<String> lines) {
    if (!attivo(proData.getData(), player)
        || !(Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName()))) {
      return Set.of();
    }
    try {
      return new Turno(proData, calc, player, c, lines).nonCombattimento(moveDel);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, non combattimento: " + e);
      lines.add("  R3a: errore " + e);
      return Set.of();
    }
  }

  /**
   * The land targets of a campaign of R3a for the USA or the United Kingdom (bridgehead campaigns
   * toward Paris, Berlin, Rome; North Africa), each with its kind; empty = not R3a's (the R2c rule
   * applies: the United Kingdom's Egypt).
   */
  static Optional<Map<Territory, String>> bersagliCampagna(
      final GameState data, final GamePlayer player, final CampagnaOmbra.Calcolo c) {
    if (!attivo(data, player)
        || !(Programmi.USA.equals(player.getName()) || Programmi.UK.equals(player.getName()))) {
      return Optional.empty();
    }
    final Config cfg = Config.get();
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final Map<Territory, String> out = new LinkedHashMap<>();
    if (Programmi.UK.equals(player.getName())) {
      final Territory egypt = data.getMap().getTerritoryOrNull("Egypt");
      if (egypt != null && c.nemico(egypt.getOwner())) {
        return Optional.empty(); // R2c: Egypt first
      }
    }
    final boolean africa =
        Programmi.UK.equals(player.getName())
            || (SUD.equals(teatroCorrente(data, alliance).orElse(""))
                && baseCorrente(data, alliance)
                    .map(b -> cfg.nordAfrica().contains(b) || "Gibraltar".equals(b))
                    .orElse(false));
    if (africa) {
      for (final String n : cfg.nordAfrica()) {
        final Territory t = data.getMap().getTerritoryOrNull(n);
        if (t != null
            && c.nemico(t.getOwner())
            && data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          out.put(t, "campagna_africa");
        }
      }
    }
    if (testaDiPonteTenuta(data, alliance)) {
      for (final String n : cfg.campagneTestaDiPonte()) {
        final Territory t = data.getMap().getTerritoryOrNull(n);
        if (t != null
            && c.nemico(t.getOwner())
            && data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          out.put(t, "campagna_testa_di_ponte");
        }
      }
    }
    return Optional.of(out);
  }

  /** A bridgehead of the alliance is held now. */
  static boolean testaDiPonteTenuta(final GameState data, final String alliance) {
    final JsonObject st = stato(data.getProperties(), alliance);
    for (final JsonElement e : arr(st, "teste_di_ponte")) {
      final Territory t =
          data.getMap().getTerritoryOrNull(e.getAsJsonObject().get("territorio").getAsString());
      if (t != null && dellAlleanza(data, alliance, t)) {
        return true;
      }
    }
    return false;
  }

  /** Register row of a new campaign target of R3a (chosen by {@link Impegno}). */
  static void registraCampagna(
      final GameState data,
      final GamePlayer player,
      final String tipo,
      final JsonArray alternative,
      final String scelta,
      final String frase) {
    try {
      registraCampagnaImpl(data, player, tipo, alternative, scelta, frase);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, registro: " + e);
    }
  }

  private static void registraCampagnaImpl(
      final GameState data,
      final GamePlayer player,
      final String tipo,
      final JsonArray alternative,
      final String scelta,
      final String frase) {
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = stato(data.getProperties(), alliance);
    final int round = data.getSequence().getRound();
    final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), tipo);
    final JsonObject par = new JsonObject();
    par.addProperty("soglia_esecuzione", Programmi.Config.get().sogliaEsecuzione());
    par.addProperty("margine_scelta", Impegno.Config.get().margineScelta());
    par.addProperty("distanza_fabbrica_africa", Config.get().distanzaFabbricaAfrica());
    RegistroDecisioni.scrivi(
        data,
        RegistroDecisioni.decisione(
            id,
            round,
            player.getName(),
            tipo,
            alternative,
            scelta,
            "OBIETTIVO_SCELTO",
            frase,
            par));
    final JsonObject p = new JsonObject();
    p.addProperty("id", id);
    p.addProperty("tipo", tipo);
    p.addProperty("bersaglio", scelta);
    p.addProperty("nazione", player.getName());
    p.addProperty("round", round);
    p.addProperty("controllo", "preso");
    arr(st, "pendenti").add(p);
    salva(data.getProperties(), alliance, st);
  }

  /** Register row of the program's purchase (R2c balanced load) of the turn. */
  static void registraProgramma(
      final GameState data, final GamePlayer player, @Nullable final JsonObject programma) {
    try {
      registraProgrammaImpl(data, player, programma);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, registro: " + e);
    }
  }

  private static void registraProgrammaImpl(
      final GameState data, final GamePlayer player, @Nullable final JsonObject programma) {
    if (programma == null || !attivo(data, player)) {
      return;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = stato(data.getProperties(), alliance);
    final int round = data.getSequence().getRound();
    final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "acquisto_programma");
    final JsonArray alt = new JsonArray();
    final JsonObject x = programma.deepCopy();
    alt.add(x);
    final JsonObject par = new JsonObject();
    par.addProperty("base", baseCorrente(data, alliance).orElse(""));
    RegistroDecisioni.scrivi(
        data,
        RegistroDecisioni.decisione(
            id,
            round,
            player.getName(),
            "acquisto_programma",
            alt,
            programma.has("ordine") ? programma.get("ordine").toString() : "{}",
            "CARICO_BILANCIATO",
            "programma permanente con la regola del carico bilanciato (R2c), il resto dei PU a Hard",
            par));
    salva(data.getProperties(), alliance, st);
  }

  /** Register row of the USA's shuttle of the turn (with a check of the losses at sea). */
  static void registraSpola(
      final GameState data, final GamePlayer player, @Nullable final JsonObject spola) {
    try {
      registraSpolaImpl(data, player, spola);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, registro: " + e);
    }
  }

  private static void registraSpolaImpl(
      final GameState data, final GamePlayer player, @Nullable final JsonObject spola) {
    if (spola == null || spola.isEmpty() || !attivo(data, player)) {
      return;
    }
    final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
    final JsonObject st = stato(data.getProperties(), alliance);
    final int round = data.getSequence().getRound();
    final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "spola");
    final JsonArray alt = new JsonArray();
    alt.add(spola.deepCopy());
    final JsonObject par = new JsonObject();
    par.addProperty("base", baseCorrente(data, alliance).orElse(""));
    par.addProperty("soglia_mare_sicuro", Programmi.Config.get().sogliaMareSicuro());
    RegistroDecisioni.scrivi(
        data,
        RegistroDecisioni.decisione(
            id,
            round,
            player.getName(),
            "spola",
            alt,
            "base " + par.get("base").getAsString(),
            "SPOLA",
            "spola USA verso la base del teatro (zone sicure)",
            par));
    // the loaded transports at sea at the end of the turn: lost at sea before the next US turn?
    final JsonArray ids = new JsonArray();
    final JsonArray transports = new JsonArray();
    for (final Territory z : data.getMap().getTerritories()) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit tr :
          z.getMatches(
              Matches.unitIsSeaTransportButNotCombatSeaTransport()
                  .and(Matches.unitIsOwnedBy(player)))) {
        ids.add(tr.getId().toString());
        transports.add(tr.getId().toString());
        tr.getTransporting().forEach(u -> ids.add(u.getId().toString()));
      }
    }
    final JsonObject p = new JsonObject();
    p.addProperty("id", id);
    p.addProperty("tipo", "spola");
    p.addProperty("nazione", player.getName());
    p.addProperty("round", round);
    p.addProperty("controllo", "perse_in_mare");
    p.add("unita", ids);
    p.add("trasporti", transports);
    arr(st, "pendenti").add(p);
    salva(data.getProperties(), alliance, st);
  }

  /** After the placement: what Hard bought under the transport cap (outcome of the cap row). */
  static void dopoIlPiazzamento(
      final GameState data, final GamePlayer player, final Set<String> before) {
    if (!attivo(data, player)) {
      return;
    }
    try {
      final String alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      final JsonObject st = stato(data.getProperties(), alliance);
      final int round = data.getSequence().getRound();
      if (!st.has("tetto_aperto")) {
        return;
      }
      final JsonObject open = st.getAsJsonObject("tetto_aperto");
      if (open.get("round").getAsInt() != round
          || !open.get("nazione").getAsString().equals(player.getName())) {
        return;
      }
      final Map<String, Integer> bought = new LinkedHashMap<>();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getUnitCollection().stream()
            .filter(Matches.unitIsOwnedBy(player))
            .filter(u -> !before.contains(u.getId().toString()))
            .forEach(u -> bought.merge(u.getType().getName(), 1, Integer::sum));
      }
      final JsonObject x = new JsonObject();
      final JsonObject b = new JsonObject();
      bought.forEach(b::addProperty);
      x.add("comprate", b);
      x.addProperty("trasporti_comprati", bought.getOrDefault("transport", 0));
      x.addProperty("tetto_attivo", open.get("attivo").getAsBoolean());
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.esito(
              open.get("id").getAsString(), round, player.getName(), "tetto_trasporti", x));
      st.remove("tetto_aperto");
      salva(data.getProperties(), alliance, st);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] R3a, piazzamento: " + e);
    }
  }

  // ============================================================================ one turn

  /** One phase of the USA's or the United Kingdom's turn. */
  static final class Turno {
    final ProData proData;
    final ProOddsCalculator calc;
    final GamePlayer player;
    final GameState data;
    final GameProperties props;
    final String alliance;
    final int round;
    final Config cfg = Config.get();
    final CampagnaOmbra.Calcolo c;
    final List<String> lines;
    final JsonObject st;
    final Programmi.Turno p;
    @Nullable IMoveDelegate moveDel;

    /** Step R4 (B2, B3): only these units are loaded by {@link #piano} (null = any). */
    @Nullable Predicate<Unit> filtro;

    /** Step R5: only these transports are used by {@link #piano} (null = any). */
    @Nullable Predicate<Unit> filtroTrasporti;

    /** Step R5: the United Kingdom lands from its home port (null = the alliance's base). */
    @Nullable Territory baseFissa;

    /** Step R6 (C3): the estimates count the squadron's ships and planes that can support. */
    boolean supportoR6;

    /** Step R5: the land units unloaded by {@link #esegui} (they leave the program). */
    final List<Unit> partite = new ArrayList<>();

    Turno(
        final ProData proData,
        final ProOddsCalculator calc,
        final GamePlayer player,
        final CampagnaOmbra.Calcolo c,
        final List<String> lines) {
      this.proData = proData;
      this.calc = calc;
      this.player = player;
      this.data = proData.getData();
      this.props = data.getProperties();
      this.alliance = ProPlusSettings.allianceOf(data, player).orElseThrow();
      this.round = data.getSequence().getRound();
      this.c = c;
      this.lines = lines;
      this.st = stato(props, alliance);
      this.p = new Programmi.Turno(proData, calc, player, alliance, c, lines);
      if (r5()) {
        filtroTrasporti = AlleatiR5.trasportoDelProgramma(data, player);
        if (!usa()) {
          baseFissa = p.porto();
        }
      }
    }

    /** Step R5: one row "perdite_in_mare" from a check of the units at sea. */
    private void perditeInMare(final JsonObject x, final Set<String> onMap, final int lost) {
      int lostTransports = 0;
      if (x.has("trasporti")) {
        for (final JsonElement id : x.getAsJsonArray("trasporti")) {
          lostTransports += onMap.contains(id.getAsString()) ? 0 : 1;
        }
      }
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("decisione", x.get("id").getAsString());
      a.addProperty("unita_in_mare", x.getAsJsonArray("unita").size());
      a.addProperty("unita_perse", lost);
      a.addProperty("trasporti_persi", lostTransports);
      a.addProperty("carico_perso", Math.max(0, lost - lostTransports));
      alt.add(a);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), "perdite_in_mare"),
              round,
              player.getName(),
              "perdite_in_mare",
              alt,
              String.valueOf(lost),
              "CONTROLLO",
              lost + " unita' perse in mare (" + lostTransports + " trasporti)",
              AlleatiR5.parametri()));
    }

    boolean usa() {
      return Programmi.USA.equals(player.getName());
    }

    /** Step R4 block B (switch "alleati_r4"). */
    boolean r4() {
      return SnodoAlleati.attivo(data, player);
    }

    /** Step R5 (switch "alleati_r5"): replaces R3a and R4 for the USA and the United Kingdom. */
    boolean r5() {
      return AlleatiR5.attivo(data, player);
    }

    /** Step R4 (B3): the landing program's units (followed by id). */
    Predicate<Unit> delProgramma() {
      final Set<String> ids = SnodoAlleati.programma(data, player);
      return u -> ids.contains(u.getId().toString());
    }

    JsonObject parametri() {
      final JsonObject o = new JsonObject();
      o.addProperty("soglia_mare_aperto", cfg.sogliaMareAperto());
      o.addProperty("round_minimi_tra_cambi", cfg.roundMinimi());
      o.addProperty("soglia_via_libera", cfg.sogliaViaLibera());
      o.addProperty("soglia_tenuta_alleanza", cfg.sogliaTenuta());
      o.addProperty("soglia_completa_uk", cfg.sogliaCompletaUk());
      return o;
    }

    // -------------------------------------------------------------------------- purchase

    void inizio() {
      if (r5()) {
        AlleatiR5.inizio(this);
        return;
      }
      lines.add("  --- R3a sbarco coordinato e teatri (" + player.getName() + ") ---");
      controlli();
      if (usa()) {
        teatro();
      } else if (!st.has("teatro")) {
        // the United Kingdom before the first US turn of the game: the initial theater
        st.addProperty("teatro", cfg.teatroIniziale());
        st.addProperty("teatro_dal", round);
      }
      aggiornaBase();
      final Set<Unit> held = new LinkedHashSet<>(preparazione());
      if (usa()) {
        held.addAll(viaLiberaUsa());
      } else {
        held.addAll(azioneUk());
      }
      final JsonObject t = obj(st, "trattenute");
      final JsonObject n = new JsonObject();
      n.addProperty("round", round);
      final JsonArray ids = new JsonArray();
      held.forEach(u -> ids.add(u.getId().toString()));
      n.add("ids", ids);
      t.add(player.getName(), n);
      tetto();
      salva(props, alliance, st);
    }

    /** The outcomes known now of earlier decisions (pending checks). */
    void controlli() {
      final JsonArray pending = arr(st, "pendenti");
      final JsonArray keep = new JsonArray();
      final Set<String> onMap = new HashSet<>();
      for (final Territory t : data.getMap().getTerritories()) {
        t.getUnitCollection().forEach(u -> onMap.add(u.getId().toString()));
      }
      for (final JsonElement e : pending) {
        final JsonObject x = e.getAsJsonObject();
        final String check = x.get("controllo").getAsString();
        final int since = x.get("round").getAsInt();
        if (since >= round) {
          keep.add(x);
          continue;
        }
        final String nation = x.get("nazione").getAsString();
        // a check of the other nation's decision waits for that nation's turn (one per round)
        if (!nation.equals(player.getName()) && !"tenuta".equals(check)) {
          keep.add(x);
          continue;
        }
        final JsonObject out = new JsonObject();
        boolean done = true;
        switch (check) {
          case "tenuta" -> {
            if (!usa()) {
              keep.add(x);
              continue;
            }
            final Territory t = data.getMap().getTerritoryOrNull(x.get("bersaglio").getAsString());
            final boolean held = t != null && dellAlleanza(data, alliance, t);
            final int after = round - since;
            out.addProperty("controllo", "tenuto dopo " + after + " round");
            out.addProperty("bersaglio", x.get("bersaglio").getAsString());
            out.addProperty("tenuto", held);
            out.addProperty("round_dopo", after);
            if (x.has("tenuta_stimata")) {
              out.add("tenuta_stimata", x.get("tenuta_stimata"));
            }
            lines.add(
                "  Esito "
                    + x.get("id").getAsString()
                    + ": "
                    + x.get("bersaglio").getAsString()
                    + (held ? " TENUTO" : " PERSO")
                    + " dopo "
                    + after
                    + " round");
            done = !held || after >= 2;
          }
          case "perse_in_mare" -> {
            int lost = 0;
            for (final JsonElement id : x.getAsJsonArray("unita")) {
              lost += onMap.contains(id.getAsString()) ? 0 : 1;
            }
            out.addProperty("controllo", "unita' perse in mare nei turni nemici");
            out.addProperty("unita_perse_in_mare", lost);
            if (r5()) {
              perditeInMare(x, onMap, lost);
            }
          }
          case "preso" -> {
            final Territory t = data.getMap().getTerritoryOrNull(x.get("bersaglio").getAsString());
            final boolean taken = t != null && dellAlleanza(data, alliance, t);
            if (!taken && round - since < 6) {
              keep.add(x);
              continue;
            }
            out.addProperty("controllo", "obiettivo della campagna");
            out.addProperty("preso", taken);
            out.addProperty("round_presa", taken ? round : -1);
          }
          default -> {
            continue;
          }
        }
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(
                x.get("id").getAsString(), round, nation, x.get("tipo").getAsString(), out));
        if (!done) {
          keep.add(x);
        }
      }
      st.add("pendenti", keep);
    }

    // ------------------------------------------------------------------------- theater

    /** 1: the alliance's theater at the start of the round (US turn). */
    private void teatro() {
      if (r4()) {
        teatroR4();
        return;
      }
      final String current = st.has("teatro") ? st.get("teatro").getAsString() : null;
      final int since = st.has("teatro_dal") ? st.get("teatro_dal").getAsInt() : round;
      final Map<String, Boolean> open = new LinkedHashMap<>();
      final Map<String, Double> worst = new LinkedHashMap<>();
      final Map<String, Territory> bases = new LinkedHashMap<>();
      for (final String name : List.of(NORD, SUD)) {
        final Territory b = baseTeatro(name);
        bases.put(name, b);
        final double w = b == null ? 100 : peggioreZona(p.zoneSpolaVerso(b));
        worst.put(name, w);
        open.put(name, b != null && w <= cfg.sogliaMareAperto());
      }
      final Map<String, Double> score = new LinkedHashMap<>();
      final Map<String, JsonObject> best = new LinkedHashMap<>();
      if (open.get(NORD) && open.get(SUD)) {
        for (final String name : List.of(NORD, SUD)) {
          final JsonObject b = migliorBersaglio(name, bases.get(name));
          best.put(name, b);
          score.put(name, b.has("punteggio") ? b.get("punteggio").getAsDouble() : 0);
        }
      }
      final boolean wave = ondataInMare();
      final SceltaTeatro choice =
          sceltaTeatro(
              current,
              since,
              round,
              wave,
              open.get(NORD),
              open.get(SUD),
              score.getOrDefault(NORD, 0.0),
              score.getOrDefault(SUD, 0.0),
              cfg.roundMinimi(),
              cfg.teatroIniziale());
      final JsonArray alt = new JsonArray();
      for (final String name : List.of(NORD, SUD)) {
        final JsonObject a = new JsonObject();
        a.addProperty("teatro", name);
        a.addProperty("base", bases.get(name) == null ? "" : bases.get(name).getName());
        a.addProperty("aperto", open.get(name));
        a.addProperty("contrattacco_peggiore_zona_pct", round1(worst.get(name)));
        if (score.containsKey(name)) {
          a.addProperty("punteggio", round1(score.get(name)));
          a.add("bersaglio_migliore", best.get(name));
        }
        alt.add(a);
      }
      final JsonObject par = parametri();
      par.addProperty("ondata_in_mare", wave);
      par.addProperty("teatro_dal", since);
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "teatro");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "teatro",
              alt,
              choice.teatro(),
              choice.codice(),
              choice.frase(),
              par));
      lines.add(
          String.format(
              Locale.ROOT,
              "  Teatro: NORD %s (zona peggiore %.0f%%), SUD %s (zona peggiore %.0f%%) -> %s (%s)",
              open.get(NORD) ? "aperto" : "chiuso",
              worst.get(NORD),
              open.get(SUD) ? "aperto" : "chiuso",
              worst.get(SUD),
              choice.teatro(),
              choice.frase()));
      final JsonObject rec = new JsonObject();
      rec.addProperty("round", round);
      rec.addProperty("teatro", choice.teatro());
      rec.addProperty("aperto_nord", open.get(NORD));
      rec.addProperty("aperto_sud", open.get(SUD));
      arr(st, "round_teatro").add(rec);
      if (current == null || !current.equals(choice.teatro())) {
        if (current != null) {
          final JsonObject ch = new JsonObject();
          ch.addProperty("round", round);
          ch.addProperty("da", current);
          ch.addProperty("a", choice.teatro());
          ch.addProperty("motivo", choice.codice());
          arr(st, "cambi").add(ch);
        }
        st.addProperty("teatro", choice.teatro());
        st.addProperty("teatro_dal", round);
        final Territory b = bases.get(choice.teatro());
        if (current != null && b != null) {
          cambiaBase(b, "CAMBIO_TEATRO", "la base segue il teatro " + choice.teatro());
        }
      }
    }

    /** The first usable base of {@code theater}: land of the alliance. */
    @Nullable
    Territory baseTeatro(final String theater) {
      if (r4() && SUD.equals(theater)) {
        // step R4 (B1): the southern hub is Gibraltar
        final Territory g = data.getMap().getTerritoryOrNull(ConfigR4.get().gibilterra());
        return g != null && dellAlleanza(data, alliance, g) ? g : null;
      }
      final Teatro t = cfg.teatri().get(theater);
      if (t == null) {
        return null;
      }
      for (final String name : t.basi()) {
        final Territory b = data.getMap().getTerritoryOrNull(name);
        if (b != null && !b.isWater() && dellAlleanza(data, alliance, b)) {
          return b;
        }
      }
      return null;
    }

    /** The worst realistic counterattack (enemy win%) over {@code zones}, with the US escort. */
    private double peggioreZona(final List<Territory> zones) {
      if (zones.isEmpty()) {
        return 100;
      }
      final List<Unit> escort = new ArrayList<>();
      for (final Territory z : p.zone(p.porto())) {
        escort.addAll(
            z.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsSea())
                    .and(Matches.unitIsNotSeaTransport())
                    .and(Matches.unitCanEvade().negate())));
      }
      double worst = 0;
      for (final Territory z : zones) {
        worst = Math.max(worst, p.vittoriaNemicaMare(z, escort));
      }
      return worst;
    }

    /** A US transport with own cargo outside the port's zones. */
    private boolean ondataInMare() {
      final List<Territory> portZones = p.zone(p.porto());
      for (final Territory z : data.getMap().getTerritories()) {
        if (!z.isWater() || portZones.contains(z)) {
          continue;
        }
        for (final Unit tr :
            z.getMatches(
                Matches.unitIsSeaTransportButNotCombatSeaTransport()
                    .and(Matches.unitIsOwnedBy(player)))) {
          if (tr.isTransporting()) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * The best target of {@code theater}: alliance hold (100 - the realistic counterattack against
     * the average survivors of the alliance's land units at the base or arriving next turn) x
     * value.
     */
    private JsonObject migliorBersaglio(final String theater, @Nullable final Territory base) {
      final JsonObject out = new JsonObject();
      if (base == null) {
        return out;
      }
      final List<Unit> forces = new ArrayList<>(forzaAllaBase(base, false));
      forces.addAll(caricoInArrivo(base));
      out.addProperty("forze_unita", forces.size());
      out.addProperty("forze_tuv", Math.round(c.tuv(forces)));
      double bestScore = -1;
      for (final Territory t : bersagliAttivi(theater, false)) {
        final double value = valore(t);
        double hold = 0;
        double win = 0;
        if (!forces.isEmpty()) {
          final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
          final ProBattleResult r =
              calc.calculateBattleResults(proData, t, forces, defenders, List.of());
          win = r.getWinPercentage();
          hold = tenuta(t, new ArrayList<>(r.getAverageAttackersRemaining()));
        }
        final double s = hold / 100.0 * value;
        if (s > bestScore) {
          bestScore = s;
          out.addProperty("territorio", t.getName());
          out.addProperty("valore", round1(value));
          out.addProperty("vittoria_pct", round1(win));
          out.addProperty("tenuta_alleanza_pct", round1(hold));
          out.addProperty("punteggio", round1(s));
        }
      }
      return out;
    }

    /** 100 - the realistic counterattack's win against {@code held} on {@code t}. */
    private double tenuta(final Territory t, final List<Unit> held) {
      if (held.stream().noneMatch(Matches.unitIsLand())) {
        return 0;
      }
      final List<Unit> counter = c.contrattacco(t);
      if (counter.isEmpty()) {
        return 100;
      }
      return 100
          - calc.calculateBattleResults(proData, t, counter, held, List.of()).getWinPercentage();
    }

    /** Production + 10 VC + 10 capital (index of R2b) + the national objectives taken away. */
    double valore(final Territory t) {
      return CampagnaOmbra.Calcolo.valoreIndice(t)
          + cfg.obiettiviTolti().getOrDefault(t.getName(), 0);
    }

    /** Targets of {@code theater} owned now by an enemy (at war with the nation if asked). */
    List<Territory> bersagliAttivi(final String theater, final boolean atWar) {
      final List<Territory> out = new ArrayList<>();
      final Teatro th = cfg.teatri().get(theater);
      if (th == null) {
        return out;
      }
      for (final String n : th.bersagli()) {
        final Territory t = data.getMap().getTerritoryOrNull(n);
        if (t == null || t.isWater() || !c.nemico(t.getOwner())) {
          continue;
        }
        if (atWar && !data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          continue;
        }
        out.add(t);
      }
      return out;
    }

    // ------------------------------------------------------------------------ the base

    /**
     * 2 "Dopo": a bridgehead held with safe zones becomes the shuttle's base; a bridgehead base
     * lost goes back to the theater's base.
     */
    private void aggiornaBase() {
      final String theater = st.get("teatro").getAsString();
      final Territory theaterBase = baseTeatro(theater);
      Territory base =
          st.has("base") ? data.getMap().getTerritoryOrNull(st.get("base").getAsString()) : null;
      if (base == null) {
        if (theaterBase != null) {
          st.addProperty("base", theaterBase.getName());
          st.addProperty("base_origine", "teatro");
        }
        return;
      }
      final boolean bridgeheadBase =
          st.has("base_origine") && "testa di ponte".equals(st.get("base_origine").getAsString());
      if (!dellAlleanza(data, alliance, base)) {
        if (theaterBase != null) {
          cambiaBase(
              theaterBase,
              bridgeheadBase ? "TESTA_DI_PONTE_PERSA" : "CAMBIO_TEATRO",
              base.getName() + " non piu' dell'alleanza: base " + theaterBase.getName());
        }
        return;
      }
      if (!usa() || r4()) {
        // step R4: the hub stays Gibraltar (or the United Kingdom)
        return;
      }
      // the most recent bridgehead of the theater, held, with safe zones
      final List<String> theaterTargets = cfg.teatri().get(theater).bersagli();
      final JsonArray heads = arr(st, "teste_di_ponte");
      for (int i = heads.size() - 1; i >= 0; i--) {
        final String name = heads.get(i).getAsJsonObject().get("territorio").getAsString();
        final Territory t = data.getMap().getTerritoryOrNull(name);
        if (t == null || t.equals(base) || !theaterTargets.contains(name)) {
          continue;
        }
        if (!dellAlleanza(data, alliance, t)) {
          continue;
        }
        final double w = peggioreZona(p.zoneSpolaVerso(t));
        if (w <= cfg.sogliaMareAperto()) {
          cambiaBase(
              t,
              "TESTA_DI_PONTE_TENUTA",
              String.format(
                  Locale.ROOT,
                  "testa di ponte %s tenuta, zone della spola sicure (peggiore %.0f%%)",
                  name,
                  w));
          st.addProperty("base_origine", "testa di ponte");
        } else {
          lines.add(
              String.format(
                  Locale.ROOT,
                  "  Testa di ponte %s tenuta ma zone non sicure (peggiore %.0f%%): base invariata",
                  name,
                  w));
        }
        break;
      }
    }

    private void cambiaBase(final Territory to, final String code, final String why) {
      final String from = st.has("base") ? st.get("base").getAsString() : "";
      if (from.equals(to.getName())) {
        return;
      }
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("da", from);
      a.addProperty("a", to.getName());
      alt.add(a);
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "cambio_base");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "cambio_base",
              alt,
              to.getName(),
              code,
              why,
              parametri()));
      st.addProperty("base", to.getName());
      st.addProperty("base_origine", "testa di ponte".equals(code) ? "testa di ponte" : "teatro");
      lines.add("  Base della spola: " + from + " -> " + to.getName() + " (" + why + ")");
    }

    // ---------------------------------------------------------------- preparation

    /**
     * 2 PREPARATION: the nation's land units at the base are held out of Hard's attacks (they stay
     * and defend it). Not when the base touches enemy land (a bridgehead, Morocco next to an Axis
     * Algeria): there the land campaign uses them.
     */
    private List<Unit> preparazione() {
      final Territory base = base();
      final List<Unit> held = new ArrayList<>();
      String why;
      if (base == null) {
        why = "nessuna base";
      } else if (toccaNemico(base)) {
        why = "base " + base.getName() + " confinante con terra nemica: niente trattenute";
      } else {
        held.addAll(forzaAllaBase(base, true));
        if (r4()) {
          // step R4 (B3): only the landing program's units are held, the others stay Hard's
          held.removeIf(delProgramma().negate());
        }
        why = "PREPARAZIONE a " + base.getName() + (r4() ? " (solo unita' del programma)" : "");
      }
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("base", base == null ? "" : base.getName());
      a.addProperty("unita", held.size());
      a.addProperty("tuv", Math.round(c.tuv(held)));
      alt.add(a);
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "unita_trattenute");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "unita_trattenute",
              alt,
              String.valueOf(held.size()),
              "PREPARAZIONE",
              why,
              parametri()));
      lines.add(
          "  Preparazione: "
              + held.size()
              + " unita' di terra trattenute alla base ("
              + why
              + "): Hard non le usa negli attacchi, difendono la base");
      return held;
    }

    @Nullable
    Territory base() {
      if (baseFissa != null) {
        return baseFissa;
      }
      return st.has("base") ? data.getMap().getTerritoryOrNull(st.get("base").getAsString()) : null;
    }

    private boolean toccaNemico(final Territory t) {
      return data.getMap().getNeighbors(t, Matches.territoryIsLand()).stream()
          .anyMatch(
              n ->
                  c.nemico(n.getOwner())
                      && data.getRelationshipTracker().isAtWar(player, n.getOwner()));
    }

    /** Land units at {@code base} of the nation ({@code own}) or of the USA and the UK. */
    private List<Unit> forzaAllaBase(final Territory base, final boolean own) {
      final Predicate<Unit> who =
          own
              ? Matches.unitIsOwnedBy(player)
              : u ->
                  Programmi.USA.equals(u.getOwner().getName())
                      || Programmi.UK.equals(u.getOwner().getName());
      final List<Unit> out =
          new ArrayList<>(
              base.getMatches(
                  who.and(Matches.unitIsLand()).and(Matches.unitIsNotInfrastructure())));
      out.sort(Comparator.comparing(u -> u.getId().toString()));
      return out;
    }

    /** The US cargo on transports within 2 zones of {@code base}'s zones (arrives next turn). */
    private List<Unit> caricoInArrivo(final Territory base) {
      final List<Unit> out = new ArrayList<>();
      final List<Territory> zones = p.zone(base);
      final GamePlayer usa = data.getPlayerList().getPlayerId(Programmi.USA);
      if (usa == null) {
        return out;
      }
      for (final Territory z : data.getMap().getTerritories()) {
        if (!z.isWater() || p.distanzaMare(z, zones) > 2) {
          continue;
        }
        for (final Unit tr :
            z.getMatches(
                Matches.unitIsSeaTransportButNotCombatSeaTransport()
                    .and(Matches.unitIsOwnedBy(usa)))) {
          out.addAll(tr.getTransporting());
        }
      }
      return out;
    }

    // ------------------------------------------------------------- the green light

    /** The wave's plan on {@code t}: per transport its zone, unload zone, route and cargo. */
    record Viaggio(
        Unit trasporto, Territory da, Route rotta, List<Unit> daCaricare, List<Unit> carico) {}

    record Piano(Territory bersaglio, List<Viaggio> viaggi, List<Unit> carico) {
      List<Unit> tutte() {
        final List<Unit> out = new ArrayList<>();
        for (final Viaggio v : viaggi) {
          out.add(v.trasporto());
        }
        out.addAll(carico);
        return out;
      }
    }

    /**
     * The load of {@code nation}'s transports in position for {@code t}: the loaded transports that
     * reach a free unload zone of {@code t} this turn, and the transports in the base's zones
     * loaded with the nation's land units at the base (strongest first, minus {@code garrison}).
     */
    Piano piano(final Territory t, final GamePlayer nation, final int garrison) {
      final List<Territory> unload =
          data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream()
              .filter(z -> !z.anyUnitsMatch(Matches.enemyUnit(nation)))
              .sorted(Comparator.comparing(Territory::getName))
              .toList();
      final List<Viaggio> trips = new ArrayList<>();
      final List<Unit> cargo = new ArrayList<>();
      if (unload.isEmpty()) {
        return new Piano(t, trips, cargo);
      }
      final Territory base = base();
      final List<Territory> baseZones = base == null ? List.of() : p.zone(base);
      final List<Unit> loadable = new ArrayList<>();
      if (base != null) {
        loadable.addAll(
            base.getMatches(
                Matches.unitIsOwnedBy(nation)
                    .and(Matches.unitIsLand())
                    .and(Matches.unitIsNotInfrastructure())
                    .and(Matches.unitHasMovementLeft())
                    .and(u -> u.getUnitAttachment().getTransportCost() > 0)
                    .and(u -> u.getUnitAttachment().getAttack(u.getOwner()) > 0)));
        if (filtro != null) {
          loadable.removeIf(filtro.negate());
        }
        // the garrison stays: the cheapest first
        loadable.sort(
            Comparator.comparingInt((Unit u) -> u.getUnitAttachment().getTransportCost())
                .thenComparing(u -> u.getId().toString()));
        loadable.subList(0, Math.min(garrison, loadable.size())).clear();
        loadable.sort(
            Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(u.getOwner()))
                .thenComparing(u -> u.getId().toString()));
      }
      final List<Unit> transports = new ArrayList<>();
      for (final Territory z : data.getMap().getTerritories()) {
        if (z.isWater()) {
          transports.addAll(
              z.getMatches(
                  Matches.unitIsSeaTransportButNotCombatSeaTransport()
                      .and(Matches.unitIsOwnedBy(nation))
                      .and(Matches.unitHasMovementLeft())));
        }
      }
      transports.sort(Comparator.comparing(u -> u.getId().toString()));
      for (final Unit tr : transports) {
        if (!tr.getUnloaded().isEmpty()
            || trasportiUsati.contains(tr)
            || (filtroTrasporti != null && !filtroTrasporti.test(tr))) {
          continue;
        }
        final Territory z = luogo(tr);
        if (z == null) {
          continue;
        }
        final boolean loaded = tr.isTransporting();
        if (loaded && !tr.getTransporting().stream().allMatch(u -> u.isOwnedBy(nation))) {
          continue;
        }
        if (loaded && filtro != null && !tr.getTransporting().stream().allMatch(filtro)) {
          continue;
        }
        if (!loaded && !baseZones.contains(z)) {
          continue;
        }
        final List<Unit> load = new ArrayList<>();
        if (!loaded) {
          int room = TransportTracker.getAvailableCapacity(tr);
          for (final Unit u : new ArrayList<>(loadable)) {
            final int cost = u.getUnitAttachment().getTransportCost();
            if (cost <= room) {
              load.add(u);
              room -= cost;
            }
          }
          if (load.isEmpty()) {
            continue;
          }
        }
        final List<Unit> moving = new ArrayList<>(List.of(tr));
        moving.addAll(loaded ? tr.getTransporting() : load);
        final Optional<Route> r = rotta(z, unload, moving, tr.getMovementLeft().intValue());
        if (r.isEmpty()) {
          continue;
        }
        loadable.removeAll(load);
        trips.add(
            new Viaggio(
                tr,
                z,
                r.get(),
                List.copyOf(load),
                List.copyOf(loaded ? tr.getTransporting() : load)));
        cargo.addAll(loaded ? tr.getTransporting() : load);
      }
      return new Piano(t, trips, cargo);
    }

    /** The shortest route from {@code z} to one of {@code to} within {@code range}, no enemy. */
    private Optional<Route> rotta(
        final Territory z, final List<Territory> to, final List<Unit> units, final int range) {
      if (to.contains(z)) {
        return Optional.of(new Route(z));
      }
      final Predicate<Territory> free =
          x ->
              x.isWater()
                  && ProMatches.territoryCanMoveSeaUnitsThrough(player, false).test(x)
                  && !x.anyUnitsMatch(Matches.enemyUnit(player));
      Route best = null;
      for (final Territory target : to) {
        final Optional<Route> r = data.getMap().getRouteForUnits(z, target, free, units, player);
        if (r.isPresent()
            && r.get().numberOfSteps() <= range
            && (best == null || r.get().numberOfSteps() < best.numberOfSteps())) {
          best = r.get();
        }
      }
      return Optional.ofNullable(best);
    }

    /** The numbers of a landing of {@code plan}: win, P80, survivors, holds. */
    record Stima(
        double vittoria,
        double p80,
        List<Unit> superstiti,
        double contrattaccoSolo,
        double contrattaccoAlleanza,
        List<Unit> rinforzi) {}

    Stima stima(final Piano plan, final boolean withUk) {
      final Territory t = plan.bersaglio();
      final List<Unit> defenders = t.getMatches(Matches.enemyUnit(player));
      double win;
      double p80 = 0;
      List<Unit> survivors;
      if (defenders.stream().noneMatch(Matches.unitIsNotInfrastructure())) {
        win = plan.carico().isEmpty() ? 0 : 100;
        survivors = new ArrayList<>(plan.carico());
      } else {
        // step R6 (C3): the squadron's planes attack and its ships bombard with the landing
        final List<Unit> attackers = new ArrayList<>(plan.carico());
        List<Unit> bombard = List.of();
        if (supportoR6 && usa() && SquadraGibilterra.attivo(data, player)) {
          SquadraGibilterra.aereiSupporto(this, t).stream()
              .filter(u -> !attackers.contains(u))
              .forEach(attackers::add);
          bombard = SquadraGibilterra.naviSupporto(this, plan);
        }
        final ProBattleResult r =
            calc.calculateBattleResults(proData, t, attackers, defenders, bombard);
        win = r.getWinPercentage();
        survivors = new ArrayList<>(r.getAverageAttackersRemaining());
        if (c.calcolatore.isPresent()) {
          final CampagnaOmbraMisure.Distribuzione d = c.attacco(t, plan.carico(), defenders);
          p80 = d.percentile(80);
        }
      }
      final List<Unit> uk = withUk ? rinforziUk(t) : List.of();
      final double solo = 100 - tenuta(t, survivors);
      final List<Unit> all = new ArrayList<>(survivors);
      all.addAll(uk);
      final double alliance = withUk ? 100 - tenuta(t, all) : solo;
      return new Stima(win, p80, survivors, solo, alliance, uk);
    }

    /**
     * The British land units that can land on {@code t} in the United Kingdom's turn of this round:
     * the British transports in the base's zones that reach an unload zone of {@code t}, loaded
     * with the British land units at the base minus London's garrison of the last UK turn.
     */
    List<Unit> rinforziUk(final Territory t) {
      final GamePlayer uk = data.getPlayerList().getPlayerId(Programmi.UK);
      final Territory base = base();
      if (uk == null || base == null) {
        return List.of();
      }
      final JsonObject imp = Impegno.stato(props, alliance);
      final JsonObject ukState = Impegno.nazione(imp, Programmi.UK);
      final int garrison =
          ukState.has("presidio_porto") ? ukState.get("presidio_porto").getAsInt() : 0;
      final List<Unit> units =
          new ArrayList<>(
              base.getMatches(
                  Matches.unitIsOwnedBy(uk)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(u -> u.getUnitAttachment().getTransportCost() > 0)));
      units.sort(
          Comparator.comparingInt((Unit u) -> u.getUnitAttachment().getTransportCost())
              .thenComparing(u -> u.getId().toString()));
      units.subList(0, Math.min(garrison, units.size())).clear();
      units.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(u.getOwner()))
              .thenComparing(u -> u.getId().toString()));
      final List<Territory> unload =
          data.getMap().getNeighbors(t, Matches.territoryIsWater()).stream().toList();
      final List<Unit> out = new ArrayList<>();
      for (final Territory z : p.zone(base)) {
        for (final Unit tr :
            z.getMatches(
                Matches.unitIsSeaTransportButNotCombatSeaTransport()
                    .and(Matches.unitIsOwnedBy(uk)))) {
          if (p.distanzaMare(z, unload) > tr.getUnitAttachment().getMovement(uk)) {
            continue;
          }
          int room = TransportTracker.getAvailableCapacity(tr);
          for (final Unit u : new ArrayList<>(units)) {
            final int cost = u.getUnitAttachment().getTransportCost();
            if (cost <= room) {
              out.add(u);
              units.remove(u);
              room -= cost;
            }
          }
        }
      }
      return out;
    }

    /** The US: green light or wait, on the targets of the theater. Returns the wave's units. */
    private List<Unit> viaLiberaUsa() {
      if (r4()) {
        return viaLiberaR4();
      }
      final String theater = st.get("teatro").getAsString();
      final List<Territory> targets = bersagliAttivi(theater, true);
      final JsonArray alt = new JsonArray();
      final JsonObject par = parametri();
      par.addProperty("teatro", theater);
      par.addProperty("base", base() == null ? "" : base().getName());
      final int garrison = presidioBase();
      par.addProperty("presidio_base", garrison);
      Piano bestPlan = null;
      Stima bestStima = null;
      double bestValue = -1;
      JsonObject closest = null;
      String closestCode = targets.isEmpty() ? "NESSUN_BERSAGLIO" : "NESSUN_CARICO";
      double closestWin = -1;
      for (final Territory t : targets) {
        final Piano plan = piano(t, player, garrison);
        final JsonObject a = new JsonObject();
        a.addProperty("territorio", t.getName());
        a.addProperty("valore", round1(valore(t)));
        a.addProperty("trasporti", plan.viaggi().size());
        a.addProperty("unita", plan.carico().size());
        a.addProperty("tuv", Math.round(c.tuv(plan.carico())));
        a.addProperty("difensori_tuv", Math.round(c.tuv(t.getMatches(Matches.enemyUnit(player)))));
        if (plan.carico().isEmpty()) {
          a.addProperty("esito", "NESSUN_CARICO");
          alt.add(a);
          continue;
        }
        final Stima s = stima(plan, true);
        final ViaLibera v =
            viaLibera(
                s.vittoria(), s.contrattaccoAlleanza(), cfg.sogliaViaLibera(), cfg.sogliaTenuta());
        a.addProperty("vittoria_pct", round1(s.vittoria()));
        a.addProperty("perdite_p80_tuv", round1(s.p80()));
        a.addProperty("superstiti_attesi", s.superstiti().size());
        a.addProperty("rinforzi_uk", s.rinforzi().size());
        a.addProperty("tenuta_usa_pct", round1(100 - s.contrattaccoSolo()));
        a.addProperty("tenuta_alleanza_pct", round1(100 - s.contrattaccoAlleanza()));
        a.addProperty("esito", v.codice());
        alt.add(a);
        if (v.via()) {
          final double value = valore(t);
          if (value > bestValue || (value == bestValue && s.vittoria() > bestStima.vittoria())) {
            bestValue = value;
            bestPlan = plan;
            bestStima = s;
          }
        } else if (s.vittoria() > closestWin) {
          closestWin = s.vittoria();
          closest = a;
          closestCode = v.codice();
        }
      }
      final String target = bestPlan == null ? "" : bestPlan.bersaglio().getName();
      final String idTarget =
          RegistroDecisioni.nuovoId(st, round, player.getName(), "bersaglio_sbarco");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              idTarget,
              round,
              player.getName(),
              "bersaglio_sbarco",
              alt,
              bestPlan == null ? "nessuno" : target,
              bestPlan == null ? closestCode : "VALORE_MAGGIORE",
              bestPlan == null
                  ? "nessun bersaglio con via libera"
                  : "via libera sul bersaglio di valore piu' alto (" + round1(bestValue) + ")",
              par));
      final String idGo =
          RegistroDecisioni.nuovoId(st, round, player.getName(), "via_libera_o_attesa");
      final String missing;
      if (bestPlan != null) {
        missing = "";
      } else if (closest == null) {
        missing =
            targets.isEmpty()
                ? "nessun bersaglio nemico in guerra nel teatro " + theater
                : "nessun trasporto carico in posizione ne' trasporto alla base con truppe";
      } else {
        missing =
            String.format(
                Locale.ROOT,
                "%s: vittoria %.1f%% (serve >= %.0f), tenuta d'alleanza %.1f%% (contrattacco <= %.0f)",
                closest.get("territorio").getAsString(),
                closest.get("vittoria_pct").getAsDouble(),
                cfg.sogliaViaLibera(),
                closest.get("tenuta_alleanza_pct").getAsDouble(),
                cfg.sogliaTenuta());
      }
      final JsonObject parGo = par.deepCopy();
      parGo.addProperty("manca", missing);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              idGo,
              round,
              player.getName(),
              "via_libera_o_attesa",
              alt,
              bestPlan == null ? "attesa" : "via libera su " + target,
              bestPlan == null ? closestCode : "VIA_LIBERA",
              bestPlan == null ? "ATTESA, manca: " + missing : "via libera su " + target,
              parGo));
      if (bestPlan == null) {
        st.remove("ondata");
        lines.add("  Via libera USA: ATTESA (" + closestCode + ": " + missing + ")");
        return List.of();
      }
      final JsonObject w = new JsonObject();
      w.addProperty("round", round);
      w.addProperty("nazione", player.getName());
      w.addProperty("bersaglio", target);
      w.addProperty("id", idGo);
      w.addProperty("vittoria_pct", round1(bestStima.vittoria()));
      w.addProperty("perdite_p80_tuv", round1(bestStima.p80()));
      w.addProperty("tenuta_alleanza_pct", round1(100 - bestStima.contrattaccoAlleanza()));
      w.addProperty("tenuta_usa_pct", round1(100 - bestStima.contrattaccoSolo()));
      w.addProperty("unita", bestPlan.carico().size());
      w.addProperty("rinforzi_uk_stimati", bestStima.rinforzi().size());
      w.addProperty("presidio_base", garrison);
      w.addProperty("eseguito", false);
      st.add("ondata", w);
      lines.add(
          String.format(
              Locale.ROOT,
              "  Via libera USA su %s: vittoria %.1f%%, tenuta d'alleanza %.1f%% (USA da soli"
                  + " %.1f%%, rinforzi britannici %d): %d unita' su %d trasporti, sbarco nel"
                  + " combattimento",
              target,
              bestStima.vittoria(),
              100 - bestStima.contrattaccoAlleanza(),
              100 - bestStima.contrattaccoSolo(),
              bestStima.rinforzi().size(),
              bestPlan.carico().size(),
              bestPlan.viaggi().size()));
      return bestPlan.tutte();
    }

    // ------------------------------------------------------------- step R4 (B1, B2)

    /** B1: the hub is Gibraltar when usable (the alliance's, no enemy warship in its zones). */
    private void teatroR4() {
      final String current = st.has("teatro") ? st.get("teatro").getAsString() : null;
      final ConfigR4 r = ConfigR4.get();
      final Territory g = data.getMap().getTerritoryOrNull(r.gibilterra());
      final boolean allied = g != null && dellAlleanza(data, alliance, g);
      int ships = 0;
      if (g != null) {
        for (final Territory z : p.zone(g)) {
          ships +=
              z.getMatches(
                      Matches.enemyUnit(player)
                          .and(Matches.unitIsSea())
                          .and(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate()))
                  .size();
        }
      }
      final boolean usable = SnodoAlleati.gibilterraUtilizzabile(allied, ships);
      final String choice = usable ? SUD : NORD;
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("teatro", SUD);
      a.addProperty("base", r.gibilterra());
      a.addProperty("dell_alleanza", allied);
      a.addProperty("navi_nemiche_nelle_zone", ships);
      a.addProperty("utilizzabile", usable);
      alt.add(a);
      final JsonObject b = new JsonObject();
      b.addProperty("teatro", NORD);
      b.addProperty("base", "United Kingdom");
      alt.add(b);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              RegistroDecisioni.nuovoId(st, round, player.getName(), "teatro"),
              round,
              player.getName(),
              "teatro",
              alt,
              choice,
              usable ? "GIBILTERRA" : "REGNO_UNITO",
              usable
                  ? "Gibilterra dell'alleanza e senza navi da guerra nemiche nelle sue zone"
                  : "Gibilterra non utilizzabile: base United Kingdom dall'Atlantico",
              parametri()));
      lines.add(
          "  R4 snodo: "
              + (usable
                  ? "Gibilterra utilizzabile"
                  : "Gibilterra NON utilizzabile ("
                      + (allied ? ships + " navi nemiche" : "non dell'alleanza")
                      + ")")
              + " -> "
              + choice);
      final JsonObject rec = new JsonObject();
      rec.addProperty("round", round);
      rec.addProperty("teatro", choice);
      rec.addProperty("aperto_nord", true);
      rec.addProperty("aperto_sud", usable);
      arr(st, "round_teatro").add(rec);
      if (current == null || !current.equals(choice)) {
        if (current != null) {
          final JsonObject ch = new JsonObject();
          ch.addProperty("round", round);
          ch.addProperty("da", current);
          ch.addProperty("a", choice);
          ch.addProperty("motivo", usable ? "GIBILTERRA" : "REGNO_UNITO");
          arr(st, "cambi").add(ch);
        }
        st.addProperty("teatro", choice);
        st.addProperty("teatro_dal", round);
        final Territory base = baseTeatro(choice);
        if (current != null && base != null) {
          cambiaBase(base, "CAMBIO_TEATRO", "lo snodo segue Gibilterra: base " + base.getName());
        }
      }
    }

    /** One option of the hub: the best target of a set for some units, with its numbers. */
    record Opzione(Territory t, Piano piano, Stima stima, double valore, double punteggio) {}

    /**
     * Transports already taken by the African wave (step R4): the main wave does not count them.
     */
    final Set<Unit> trasportiUsati = new HashSet<>();

    /**
     * B2: among {@code targets} the one with the green light and the highest score (alliance hold x
     * value with the gain of rule C); every alternative goes to {@code alt} with its numbers.
     */
    @Nullable
    private Opzione migliore(
        final List<Territory> targets,
        final Predicate<Unit> units,
        final int garrison,
        final JsonArray alt,
        final String label) {
      Opzione best = null;
      for (final Territory t : targets) {
        filtro = units;
        final Piano plan = piano(t, player, garrison);
        filtro = null;
        final Reddito.Guadagno g = Reddito.guadagno(data, player, t);
        final double value =
            CampagnaOmbra.Calcolo.valoreIndice(t) + g.bonusOttenuti() + g.bonusTolti();
        final JsonObject a = g.json();
        a.addProperty("gruppo", label);
        a.addProperty("territorio", t.getName());
        a.addProperty("valore", round1(value));
        a.addProperty("unita", plan.carico().size());
        a.addProperty("trasporti", plan.viaggi().size());
        if (plan.carico().isEmpty()) {
          a.addProperty("esito", "NESSUN_CARICO");
          alt.add(a);
          continue;
        }
        final Stima s = stima(plan, true);
        final ViaLibera v =
            viaLibera(
                s.vittoria(), s.contrattaccoAlleanza(), cfg.sogliaViaLibera(), cfg.sogliaTenuta());
        final double score = (100 - s.contrattaccoAlleanza()) / 100.0 * value;
        a.addProperty("vittoria_pct", round1(s.vittoria()));
        a.addProperty("tenuta_alleanza_pct", round1(100 - s.contrattaccoAlleanza()));
        a.addProperty("punteggio", round1(score));
        a.addProperty("esito", v.codice());
        alt.add(a);
        if (v.via()
            && (best == null
                || score > best.punteggio()
                || (score == best.punteggio() && s.vittoria() > best.stima().vittoria()))) {
          best = new Opzione(t, plan, s, value, score);
        }
      }
      return best;
    }

    private List<Territory> nemiciInGuerra(final List<String> names) {
      final List<Territory> out = new ArrayList<>();
      for (final String n : names) {
        final Territory t = data.getMap().getTerritoryOrNull(n);
        if (t != null
            && !t.isWater()
            && c.nemico(t.getOwner())
            && data.getRelationshipTracker().isAtWar(player, t.getOwner())) {
          out.add(t);
        }
      }
      return out;
    }

    /**
     * B2: the US choice at the hub: while the Axis has North Africa the fast program units go to
     * Africa (Morocco, or an Axis territory of North Africa), the rest to the best-scoring target
     * of all; otherwise everything to the best-scoring target. Writes snodo_usa and the wave(s).
     */
    private List<Unit> viaLiberaR4() {
      final ConfigR4 r = ConfigR4.get();
      final int garrison = presidioBase();
      final Predicate<Unit> prog = delProgramma();
      final Predicate<Unit> fast = u -> SnodoAlleati.veloce(u, r);
      final boolean axisNa = SnodoAlleati.asseInNordAfrica(data, player);
      final List<Territory> africa = nemiciInGuerra(r.bersagliAfrica());
      final List<Territory> france = nemiciInGuerra(r.bersagliFrancia());
      final List<Territory> all = new ArrayList<>(africa);
      all.addAll(france);
      final JsonArray alt = new JsonArray();
      trasportiUsati.clear();
      final Opzione onlyAfrica = migliore(africa, prog, garrison, alt, AFRICA_TUTTE);
      final Opzione onlyFrance = migliore(france, prog, garrison, alt, FRANCIA_TUTTE);
      Opzione fastWave = null;
      Opzione restWave;
      if (axisNa) {
        final List<Territory> fastTargets = new ArrayList<>();
        final Territory morocco = data.getMap().getTerritoryOrNull("Morocco");
        if (morocco != null && dellAlleanza(data, alliance, morocco)) {
          fastTargets.add(morocco);
        } else {
          africa.stream()
              .filter(t -> r.nordAfrica().contains(t.getName()))
              .forEach(fastTargets::add);
        }
        fastWave = migliore(fastTargets, prog.and(fast), garrison, alt, "VELOCI_AFRICA");
        if (fastWave != null) {
          fastWave.piano().viaggi().forEach(v -> trasportiUsati.add(v.trasporto()));
        }
        final Set<Unit> fastCargo =
            fastWave == null ? Set.of() : new HashSet<>(fastWave.piano().carico());
        restWave = migliore(all, prog.and(u -> !fastCargo.contains(u)), garrison, alt, "RESTO");
        trasportiUsati.clear();
      } else {
        restWave =
            onlyAfrica == null
                ? onlyFrance
                : onlyFrance == null || onlyAfrica.punteggio() >= onlyFrance.punteggio()
                    ? onlyAfrica
                    : onlyFrance;
      }
      final Optional<String> choice =
          SnodoAlleati.sceltaSnodo(
              fastWave != null, restWave == null ? null : restWave.t().getName(), r);
      final JsonObject par = parametri();
      par.addProperty("asse_in_nord_africa", axisNa);
      par.addProperty("base", base() == null ? "" : base().getName());
      par.addProperty("unita_del_programma", SnodoAlleati.programma(data, player).size());
      // waiting: the reason of the loaded alternative closest to the green light (as in R3a)
      String waitCode = alt.isEmpty() ? "NESSUN_BERSAGLIO" : "NESSUN_CARICO";
      double closest = -1;
      for (final var e : alt) {
        final JsonObject a = e.getAsJsonObject();
        if (a.has("vittoria_pct") && a.get("vittoria_pct").getAsDouble() > closest) {
          closest = a.get("vittoria_pct").getAsDouble();
          waitCode = a.get("esito").getAsString();
        }
      }
      final String code = choice.orElse(waitCode);
      final String why =
          choice.isEmpty()
              ? "nessun bersaglio con via libera"
              : (fastWave != null ? "veloci su " + fastWave.t().getName() : "")
                  + (fastWave != null && restWave != null ? ", " : "")
                  + (restWave != null
                      ? "resto su "
                          + restWave.t().getName()
                          + " (punteggio "
                          + round1(restWave.punteggio())
                          + ")"
                      : "");
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "snodo_usa");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "snodo_usa",
              alt,
              choice.orElse("attesa"),
              code,
              why,
              par));
      lines.add("  R4 snodo USA: " + choice.orElse("ATTESA") + " (" + why + ")");
      st.remove("ondata");
      st.remove("ondata_africa");
      final List<Unit> out = new ArrayList<>();
      if (restWave != null) {
        st.add(
            "ondata",
            onda(restWave, fastWave == null ? "programma" : "resto", id, choice.orElse("")));
        out.addAll(restWave.piano().tutte());
      }
      if (fastWave != null) {
        st.add(
            restWave == null ? "ondata" : "ondata_africa",
            onda(fastWave, "veloci", id, choice.orElse("")));
        out.addAll(fastWave.piano().tutte());
      }
      return out;
    }

    private JsonObject onda(
        final Opzione o, final String filter, final String id, final String choice) {
      final JsonObject w = new JsonObject();
      w.addProperty("round", round);
      w.addProperty("nazione", player.getName());
      w.addProperty("bersaglio", o.t().getName());
      w.addProperty("id", id);
      w.addProperty("filtro", filter);
      w.addProperty("snodo", choice);
      w.addProperty("vittoria_pct", round1(o.stima().vittoria()));
      w.addProperty("perdite_p80_tuv", round1(o.stima().p80()));
      w.addProperty("tenuta_alleanza_pct", round1(100 - o.stima().contrattaccoAlleanza()));
      w.addProperty("tenuta_usa_pct", round1(100 - o.stima().contrattaccoSolo()));
      w.addProperty("unita", o.piano().carico().size());
      w.addProperty("rinforzi_uk_stimati", o.stima().rinforzi().size());
      w.addProperty("presidio_base", presidioBase());
      w.addProperty("eseguito", false);
      return w;
    }

    /** The units a wave loads (step R4), from its filter; null = any (R3a). */
    @Nullable
    private Predicate<Unit> filtroOndata(final JsonObject w) {
      if (!w.has("filtro")) {
        return null;
      }
      final ConfigR4 r = ConfigR4.get();
      final Predicate<Unit> prog = delProgramma();
      final Predicate<Unit> fast = u -> SnodoAlleati.veloce(u, r);
      return switch (w.get("filtro").getAsString()) {
        case "veloci" -> prog.and(fast);
        case "lenti" -> prog.and(fast.negate());
        default -> prog;
      };
    }

    /** B2: the African wave of the fast units, before the main wave. */
    private Set<Unit> ondataAfrica(final int garrison) {
      final Set<Unit> moved = new LinkedHashSet<>();
      final JsonObject w = st.getAsJsonObject("ondata_africa");
      if (w.get("round").getAsInt() != round || w.get("eseguito").getAsBoolean()) {
        return moved;
      }
      final Territory t = data.getMap().getTerritoryOrNull(w.get("bersaglio").getAsString());
      if (t == null) {
        return moved;
      }
      filtro = filtroOndata(w);
      final Piano plan = piano(t, player, garrison);
      filtro = null;
      final int landed = esegui(plan, t, moved);
      w.addProperty("eseguito", landed > 0);
      w.addProperty("unita_sbarcate", landed);
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "sbarco");
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("territorio", t.getName());
      a.addProperty("unita_previste", plan.carico().size());
      a.addProperty("unita_sbarcate", landed);
      a.addProperty("trasporti", plan.viaggi().size());
      a.addProperty("vittoria_pct", w.get("vittoria_pct").getAsDouble());
      a.addProperty("filtro", "veloci");
      alt.add(a);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "sbarco",
              alt,
              t.getName(),
              landed > 0 ? "ESEGUITO" : "NON_ESEGUITO",
              "unita' veloci in Africa: " + landed,
              parametri()));
      w.addProperty("id_sbarco", id);
      final JsonArray ships = new JsonArray();
      plan.viaggi().forEach(v -> ships.add(v.trasporto().getId().toString()));
      w.add("trasporti_ids", ships);
      st.add("ondata_africa", w);
      lines.add("  SBARCO USA (veloci, Africa) su " + t.getName() + ": " + landed + " unita'");
      return moved;
    }

    /** The case-c garrison of the base (Impegno's port garrison when the base is the port). */
    int presidioBase() {
      final Territory base = base();
      if (base == null || !base.isOwnedBy(player)) {
        return 0;
      }
      final JsonObject ns = Impegno.nazione(Impegno.stato(props, alliance), player.getName());
      return ns.has("presidio_porto") && base.equals(p.porto())
          ? ns.get("presidio_porto").getAsInt()
          : 0;
    }

    /**
     * The United Kingdom's action of the round: reinforce the US bridgehead (non-combat), complete
     * a US wave that did not take its target (win >= {@code soglia_completa_uk}), or land alone
     * only if it holds alone. Returns the units of a combat landing.
     */
    private List<Unit> azioneUk() {
      final JsonObject wave =
          st.has("ondata") && st.getAsJsonObject("ondata").get("round").getAsInt() == round
              ? st.getAsJsonObject("ondata")
              : null;
      final JsonObject par = parametri();
      final int garrison = presidioBase();
      par.addProperty("presidio_base", garrison);
      final JsonArray alt = new JsonArray();
      final JsonObject action = new JsonObject();
      action.addProperty("round", round);
      String code;
      String why;
      String choice;
      List<Unit> units = List.of();
      if (wave != null && wave.get("eseguito").getAsBoolean()) {
        final Territory t = data.getMap().getTerritoryOrNull(wave.get("bersaglio").getAsString());
        if (t != null && dellAlleanza(data, alliance, t)) {
          code = "RINFORZO";
          choice = "rinforzo di " + t.getName();
          why = "lo sbarco USA ha preso " + t.getName() + ": il Regno Unito la rinforza";
          action.addProperty("tipo", "rinforzo");
          action.addProperty("bersaglio", t.getName());
          final JsonObject a = new JsonObject();
          a.addProperty("territorio", t.getName());
          a.addProperty("rinforzi_disponibili", piano(t, player, garrison).carico().size());
          alt.add(a);
        } else if (t != null) {
          final Piano plan = piano(t, player, garrison);
          final Stima s = plan.carico().isEmpty() ? null : stima(plan, false);
          final JsonObject a = new JsonObject();
          a.addProperty("territorio", t.getName());
          a.addProperty("unita", plan.carico().size());
          a.addProperty("vittoria_pct", s == null ? 0 : round1(s.vittoria()));
          alt.add(a);
          if (s != null && s.vittoria() >= cfg.sogliaCompletaUk()) {
            code = "COMPLETAMENTO";
            choice = "completa " + t.getName();
            why =
                String.format(
                    Locale.ROOT,
                    "lo sbarco USA non ha preso %s: il Regno Unito completa (vittoria %.1f%% >= %.0f)",
                    t.getName(),
                    s.vittoria(),
                    cfg.sogliaCompletaUk());
            action.addProperty("tipo", "completamento");
            action.addProperty("bersaglio", t.getName());
            action.addProperty("vittoria_pct", round1(s.vittoria()));
            action.addProperty("perdite_p80_tuv", round1(s.p80()));
            action.addProperty("tenuta_pct", round1(100 - s.contrattaccoSolo()));
            units = plan.tutte();
          } else {
            code = "MANCA_VITTORIA";
            choice = "niente";
            why =
                "lo sbarco USA non ha preso "
                    + t.getName()
                    + ": il Regno Unito non completa (vittoria "
                    + (s == null ? "0 (nessun carico)" : round1(s.vittoria()))
                    + "% < "
                    + cfg.sogliaCompletaUk()
                    + ")";
          }
        } else {
          code = "NIENTE";
          choice = "niente";
          why = "bersaglio dell'ondata sconosciuto";
        }
      } else {
        // alone: only if it takes and holds alone
        final String theater = st.get("teatro").getAsString();
        Piano best = null;
        Stima bestS = null;
        double bestValue = -1;
        String missing = "NESSUN_BERSAGLIO";
        double closest = -1;
        for (final Territory t : bersagliAttivi(theater, true)) {
          final Piano plan = piano(t, player, garrison);
          final JsonObject a = new JsonObject();
          a.addProperty("territorio", t.getName());
          a.addProperty("unita", plan.carico().size());
          if (plan.carico().isEmpty()) {
            a.addProperty("esito", "NESSUN_CARICO");
            alt.add(a);
            if (closest < 0) {
              missing = "NESSUN_CARICO";
            }
            continue;
          }
          final Stima s = stima(plan, false);
          final ViaLibera v =
              viaLibera(
                  s.vittoria(), s.contrattaccoSolo(), cfg.sogliaViaLibera(), cfg.sogliaTenuta());
          a.addProperty("vittoria_pct", round1(s.vittoria()));
          a.addProperty("tenuta_uk_pct", round1(100 - s.contrattaccoSolo()));
          a.addProperty("esito", v.codice());
          alt.add(a);
          if (v.via() && valore(t) > bestValue) {
            bestValue = valore(t);
            best = plan;
            bestS = s;
          } else if (!v.via() && s.vittoria() > closest) {
            closest = s.vittoria();
            missing = v.codice();
          }
        }
        if (best != null) {
          code = "SOLO_UK";
          choice = "sbarco da solo su " + best.bersaglio().getName();
          why =
              String.format(
                  Locale.ROOT,
                  "nessuna ondata USA: il Regno Unito prende e tiene da solo (vittoria %.1f%%,"
                      + " tenuta %.1f%%)",
                  bestS.vittoria(),
                  100 - bestS.contrattaccoSolo());
          action.addProperty("tipo", "solo");
          action.addProperty("bersaglio", best.bersaglio().getName());
          action.addProperty("vittoria_pct", round1(bestS.vittoria()));
          action.addProperty("perdite_p80_tuv", round1(bestS.p80()));
          action.addProperty("tenuta_pct", round1(100 - bestS.contrattaccoSolo()));
          units = best.tutte();
        } else {
          code = missing.startsWith("MANC") ? missing : "NIENTE";
          choice = "niente";
          why = "nessuna ondata USA e nessuno sbarco che il Regno Unito prende e tiene da solo";
        }
      }
      final String id =
          RegistroDecisioni.nuovoId(st, round, player.getName(), "rinforzo_testa_di_ponte");
      action.addProperty("id", id);
      action.addProperty("codice", code);
      action.addProperty("eseguito", false);
      st.add("azione_uk", action);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id, round, player.getName(), "rinforzo_testa_di_ponte", alt, choice, code, why, par));
      lines.add("  Regno Unito: " + choice + " (" + why + ")");
      return units;
    }

    // ------------------------------------------------------------------------ cap

    /** 4: the cap on Hard's transports (the balanced load's idle empty usable transport). */
    private void tetto() {
      final int idle = p.porto() == null ? 0 : p.fermiVuoti();
      final boolean cap = tettoTrasporti(idle);
      if (cap) {
        props.set(KEY_TETTO + player.getName(), String.valueOf(round));
      } else {
        props.set(KEY_TETTO + player.getName(), (Serializable) null);
      }
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("porto", p.porto() == null ? "" : p.porto().getName());
      a.addProperty("trasporti_fermi_vuoti", idle);
      alt.add(a);
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), "tetto_trasporti");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              "tetto_trasporti",
              alt,
              cap ? "Hard non compra trasporti" : "Hard compra come sempre",
              cap ? "TRASPORTO_FERMO_VUOTO" : "NESSUN_FERMO_VUOTO",
              cap
                  ? idle
                      + " trasporti fermi e vuoti entro 2 zone dal porto: niente trasporti a Hard"
                  : "nessun trasporto fermo e vuoto utilizzabile",
              parametri()));
      final JsonObject open = new JsonObject();
      open.addProperty("id", id);
      open.addProperty("round", round);
      open.addProperty("nazione", player.getName());
      open.addProperty("attivo", cap);
      st.add("tetto_aperto", open);
      if (cap) {
        lines.add("  Tetto ai trasporti: " + idle + " fermi e vuoti -> Hard non ne compra");
      }
    }

    // ---------------------------------------------------------------------- combat

    Set<Unit> combattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final Set<Unit> moved = new LinkedHashSet<>();
      if (r5()) {
        try {
          if (usa()) {
            // step R6 (B2): the squadron's battle for Gibraltar's sea on its way
            if (SquadraGibilterra.attivo(data, player)) {
              moved.addAll(SquadraGibilterra.combattimento(this, delegate));
            }
            // step R5 (A3): the naval battle of Gibraltar first
            moved.addAll(AlleatiR5.battaglia(this, delegate));
          }
          moved.addAll(combattimentoOnde(moved));
        } finally {
          AlleatiR5.partite(data, player, partite);
        }
        return moved;
      }
      return combattimentoOnde(moved);
    }

    private Set<Unit> combattimentoOnde(final Set<Unit> moved) {
      JsonObject plan = null;
      String key = null;
      if (usa() && st.has("ondata")) {
        plan = st.getAsJsonObject("ondata");
        key = "ondata";
      } else if (!usa() && st.has("azione_uk")) {
        plan = st.getAsJsonObject("azione_uk");
        key = "azione_uk";
        if (!plan.has("tipo")
            || !List.of("completamento", "solo", "logoramento")
                .contains(plan.get("tipo").getAsString())) {
          return moved;
        }
      }
      if (plan == null
          || plan.get("round").getAsInt() != round
          || plan.get("eseguito").getAsBoolean()) {
        return moved;
      }
      final Territory t = data.getMap().getTerritoryOrNull(plan.get("bersaglio").getAsString());
      if (t == null || !c.nemico(t.getOwner())) {
        return moved;
      }
      final int garrison =
          plan.has("presidio_base") ? plan.get("presidio_base").getAsInt() : presidioBase();
      // step R4 (B2): the African wave of the fast units first, then the main wave without them
      if (usa() && (r4() || r5()) && st.has("ondata_africa")) {
        moved.addAll(ondataAfrica(garrison));
      }
      filtro = filtroOndata(plan);
      final Piano piano = piano(t, player, garrison);
      filtro = null;
      final int landed = esegui(piano, t, moved);
      // step R6 (C3): the squadron's support moves with the main wave
      if (landed > 0
          && plan.has("supporto_r6")
          && plan.get("supporto_r6").getAsBoolean()
          && SquadraGibilterra.attivo(data, player)) {
        moved.addAll(SquadraGibilterra.eseguiSupporto(this, piano, t));
      }
      plan.addProperty("eseguito", landed > 0);
      plan.addProperty("unita_sbarcate", landed);
      plan.addProperty("trasporti_usati", piano.viaggi().size());
      final JsonArray ships = new JsonArray();
      piano.viaggi().forEach(v -> ships.add(v.trasporto().getId().toString()));
      plan.add("trasporti_ids", ships);
      st.add(key, plan);
      final String tipo = usa() || r5() ? "sbarco" : "rinforzo_testa_di_ponte";
      final String id = RegistroDecisioni.nuovoId(st, round, player.getName(), tipo);
      plan.addProperty("id_sbarco", id);
      final JsonArray alt = new JsonArray();
      final JsonObject a = new JsonObject();
      a.addProperty("territorio", t.getName());
      a.addProperty("unita_previste", piano.carico().size());
      a.addProperty("unita_sbarcate", landed);
      a.addProperty("trasporti", piano.viaggi().size());
      a.addProperty(
          "vittoria_pct", plan.has("vittoria_pct") ? plan.get("vittoria_pct").getAsDouble() : -1);
      alt.add(a);
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.decisione(
              id,
              round,
              player.getName(),
              tipo,
              alt,
              t.getName(),
              landed > 0 ? (usa() ? "ESEGUITO" : plan.get("codice").getAsString()) : "NON_ESEGUITO",
              landed > 0
                  ? "sbarco con tutto il carico in un colpo: " + landed + " unita'"
                  : "sbarco non eseguito (mosse rifiutate dal motore)",
              parametri()));
      salva(props, alliance, st);
      lines.add(
          "  "
              + (usa() ? "SBARCO USA" : "SBARCO BRITANNICO")
              + " su "
              + t.getName()
              + ": "
              + landed
              + " unita' sbarcate da "
              + piano.viaggi().size()
              + " trasporti");
      return moved;
    }

    /** Loads, sails and unloads the plan; returns the land units unloaded on {@code t}. */
    int esegui(final Piano plan, final Territory t, final Set<Unit> moved) {
      int landed = 0;
      final Territory base = base();
      for (final Viaggio v : plan.viaggi()) {
        final Unit tr = v.trasporto();
        if (!v.daCaricare().isEmpty()) {
          if (base == null) {
            continue;
          }
          final Map<Unit, Unit> map = new HashMap<>();
          v.daCaricare().forEach(u -> map.put(u, tr));
          final Optional<String> error =
              moveDel.performMove(
                  new MoveDescription(v.daCaricare(), new Route(base, v.da()), map));
          if (error.isPresent()) {
            ProLogger.warn("[Pro+] R3a: carico non riuscito " + error.get());
            continue;
          }
        }
        final List<Unit> cargo = new ArrayList<>(v.carico());
        final List<Unit> group = new ArrayList<>(List.of(tr));
        group.addAll(cargo);
        Territory at = v.da();
        if (v.rotta().numberOfSteps() > 0) {
          final Optional<String> error = moveDel.performMove(new MoveDescription(group, v.rotta()));
          if (error.isPresent()) {
            ProLogger.warn("[Pro+] R3a: rotta non riuscita " + error.get());
            moved.addAll(group);
            continue;
          }
          at = v.rotta().getEnd();
        }
        moved.addAll(group);
        if (cargo.isEmpty()) {
          continue;
        }
        final Optional<String> error =
            moveDel.performMove(new MoveDescription(cargo, new Route(at, t)));
        if (error.isEmpty()) {
          landed += cargo.size();
          partite.addAll(cargo);
        } else {
          ProLogger.warn("[Pro+] R3a: scarico non riuscito " + error.get());
        }
      }
      return landed;
    }

    // ------------------------------------------------------------------ non combat

    Set<Unit> nonCombattimento(final IMoveDelegate delegate) {
      this.moveDel = delegate;
      final Set<Unit> held = new LinkedHashSet<>();
      if (usa()) {
        // step R6: the squadron's moves and holds (before R5's outcomes)
        if (SquadraGibilterra.attivo(data, player)) {
          held.addAll(SquadraGibilterra.nonCombattimento(this, delegate));
        }
        if (r5()) {
          AlleatiR5.esitoBattaglia(this);
        }
        esitoOndata("ondata");
        if (r5()) {
          esitoOndata("ondata_africa");
        }
      } else {
        held.addAll(esitoUk());
        if (r5()) {
          // step R5 (B3): the program's units left reinforce the US conquest of the round
          held.addAll(AlleatiR5.rinforzoUk(this));
          AlleatiR5.partite(data, player, partite);
        }
      }
      // the landing force still at the base stays (it defends the base)
      final Territory base = base();
      if (base != null && !toccaNemico(base)) {
        final List<Unit> force = new ArrayList<>(forzaAllaBase(base, true));
        if (r4() || r5()) {
          force.removeIf(delProgramma().negate());
        }
        held.addAll(force);
      }
      salva(props, alliance, st);
      return held;
    }

    /** The US wave's outcome after the battles: taken, losses against P80; the bridgehead. */
    private void esitoOndata(final String key) {
      if (!st.has(key)) {
        return;
      }
      final JsonObject w = st.getAsJsonObject(key);
      if (w.get("round").getAsInt() != round
          || !w.get("eseguito").getAsBoolean()
          || w.has("esito")) {
        return;
      }
      final Territory t = data.getMap().getTerritoryOrNull(w.get("bersaglio").getAsString());
      final boolean taken = t != null && dellAlleanza(data, alliance, t);
      final int lost = t == null ? -1 : c.perditaReale(round, t.getName());
      final JsonObject x = new JsonObject();
      x.addProperty("bersaglio", w.get("bersaglio").getAsString());
      x.addProperty("preso", taken);
      x.addProperty("perdite_reali_tuv", Math.max(0, lost));
      x.addProperty("perdite_p80_tuv", w.get("perdite_p80_tuv").getAsDouble());
      x.addProperty("vittoria_stimata_pct", w.get("vittoria_pct").getAsDouble());
      x.addProperty("tenuta_stimata_pct", w.get("tenuta_alleanza_pct").getAsDouble());
      x.addProperty("unita_sbarcate", w.get("unita_sbarcate").getAsInt());
      w.addProperty("esito", taken ? "preso" : "non preso");
      final String id =
          w.has("id_sbarco") ? w.get("id_sbarco").getAsString() : w.get("id").getAsString();
      RegistroDecisioni.scrivi(
          data, RegistroDecisioni.esito(id, round, player.getName(), "sbarco", x));
      lines.add(
          String.format(
              Locale.ROOT,
              "  Esito dello sbarco USA su %s: %s, perdite %d TUV (P80 %.0f)",
              w.get("bersaglio").getAsString(),
              taken ? "PRESO" : "NON PRESO",
              Math.max(0, lost),
              w.get("perdite_p80_tuv").getAsDouble()));
      final JsonObject pend = new JsonObject();
      pend.addProperty("id", id);
      pend.addProperty("tipo", "sbarco");
      pend.addProperty("nazione", player.getName());
      pend.addProperty("round", round);
      pend.add("bersaglio", w.get("bersaglio"));
      pend.add("tenuta_stimata", w.get("tenuta_alleanza_pct"));
      if (taken) {
        pend.addProperty("controllo", "tenuta");
        arr(st, "pendenti").add(pend);
        final JsonObject h = new JsonObject();
        h.addProperty("territorio", t.getName());
        h.addProperty("round", round);
        h.addProperty("nazione", player.getName());
        h.addProperty("id", id);
        arr(st, "teste_di_ponte").add(h);
      }
      final JsonObject sea = pend.deepCopy();
      sea.addProperty("controllo", "perse_in_mare");
      final JsonArray ids = new JsonArray();
      if (w.has("trasporti_ids")) {
        w.getAsJsonArray("trasporti_ids").forEach(ids::add);
      }
      sea.add("unita", ids);
      sea.add("trasporti", ids.deepCopy());
      arr(st, "pendenti").add(sea);
    }

    /** The United Kingdom after its battles: outcome of a combat landing; the reinforcement. */
    private Set<Unit> esitoUk() {
      final Set<Unit> held = new LinkedHashSet<>();
      if (!st.has("azione_uk")) {
        return held;
      }
      final JsonObject a = st.getAsJsonObject("azione_uk");
      if (a.get("round").getAsInt() != round || !a.has("tipo") || a.has("esito")) {
        return held;
      }
      final String kind = a.get("tipo").getAsString();
      final Territory t = data.getMap().getTerritoryOrNull(a.get("bersaglio").getAsString());
      if (t == null) {
        return held;
      }
      final String id =
          a.has("id_sbarco") ? a.get("id_sbarco").getAsString() : a.get("id").getAsString();
      if ("rinforzo".equals(kind)) {
        final int sent = rinforzo(t, held);
        final JsonObject x = new JsonObject();
        x.addProperty("bersaglio", t.getName());
        x.addProperty("rinforzi_arrivati", sent);
        x.addProperty("tenuto_a_fine_turno", dellAlleanza(data, alliance, t));
        a.addProperty("esito", "rinforzo " + sent);
        a.addProperty("rinforzi_arrivati", sent);
        RegistroDecisioni.scrivi(
            data,
            RegistroDecisioni.esito(id, round, player.getName(), "rinforzo_testa_di_ponte", x));
        lines.add("  Rinforzo britannico di " + t.getName() + ": " + sent + " unita' arrivate");
        return held;
      }
      if (!a.get("eseguito").getAsBoolean()) {
        return held;
      }
      final boolean taken = dellAlleanza(data, alliance, t);
      final int lost = c.perditaReale(round, t.getName());
      final JsonObject x = new JsonObject();
      x.addProperty("bersaglio", t.getName());
      x.addProperty("preso", taken);
      x.addProperty("perdite_reali_tuv", Math.max(0, lost));
      x.addProperty(
          "perdite_p80_tuv",
          a.has("perdite_p80_tuv") ? a.get("perdite_p80_tuv").getAsDouble() : -1);
      x.addProperty(
          "vittoria_stimata_pct", a.has("vittoria_pct") ? a.get("vittoria_pct").getAsDouble() : -1);
      x.addProperty(
          "tenuta_stimata_pct", a.has("tenuta_pct") ? a.get("tenuta_pct").getAsDouble() : -1);
      a.addProperty("esito", taken ? "preso" : "non preso");
      RegistroDecisioni.scrivi(
          data,
          RegistroDecisioni.esito(
              id,
              round,
              player.getName(),
              "logoramento".equals(kind) ? "sbarco" : "rinforzo_testa_di_ponte",
              x));
      if (taken) {
        final JsonObject pend = new JsonObject();
        pend.addProperty("id", id);
        pend.addProperty("tipo", "rinforzo_testa_di_ponte");
        pend.addProperty("nazione", Programmi.USA);
        pend.addProperty("round", round);
        pend.addProperty("bersaglio", t.getName());
        pend.addProperty("controllo", "tenuta");
        if (a.has("tenuta_pct")) {
          pend.add("tenuta_stimata", a.get("tenuta_pct"));
        }
        arr(st, "pendenti").add(pend);
        final JsonObject h = new JsonObject();
        h.addProperty("territorio", t.getName());
        h.addProperty("round", round);
        h.addProperty("nazione", player.getName());
        h.addProperty("id", id);
        arr(st, "teste_di_ponte").add(h);
      }
      lines.add(
          "  Esito dello sbarco britannico su "
              + t.getName()
              + ": "
              + (taken ? "PRESO" : "NON PRESO")
              + ", perdite "
              + Math.max(0, lost)
              + " TUV");
      return held;
    }

    /** The British units at the base on British transports to the bridgehead (non-combat). */
    private int rinforzo(final Territory t, final Set<Unit> held) {
      if (!dellAlleanza(data, alliance, t)) {
        return 0;
      }
      final Piano plan = piano(t, player, presidioBase());
      final Set<Unit> moved = new LinkedHashSet<>();
      final int n = esegui(plan, t, moved);
      held.addAll(moved);
      return n;
    }

    @Nullable
    Territory luogoDi(final Unit u) {
      return luogo(u);
    }

    private @Nullable Territory luogo(final Unit u) {
      for (final Territory t : data.getMap().getTerritories()) {
        if (t.getUnitCollection().contains(u)) {
          return t;
        }
      }
      return null;
    }
  }

  // ================================================================================ helpers

  /** {@code t} is land of a nation of {@code alliance}. */
  static boolean dellAlleanza(final GameState data, final String alliance, final Territory t) {
    return t.getOwner() != null
        && !t.getOwner().isNull()
        && ProPlusSettings.allianceOf(data, t.getOwner()).map(alliance::equals).orElse(false);
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
    props.set(KEY_STATO + alliance, (Serializable) null);
    props.set(KEY_STATO + alliance, st.toString());
  }

  /** The state's summary for the strategist's summary file. */
  static JsonObject riepilogo(final GameProperties props, final String alliance) {
    final JsonObject st = stato(props, alliance);
    final JsonObject o = new JsonObject();
    for (final String k : List.of("round_teatro", "cambi", "teste_di_ponte")) {
      o.add(k, st.has(k) ? st.get(k).deepCopy() : new JsonArray());
    }
    o.addProperty("teatro", st.has("teatro") ? st.get("teatro").getAsString() : "");
    o.addProperty("base", st.has("base") ? st.get("base").getAsString() : "");
    return o;
  }

  static JsonObject obj(final JsonObject o, final String key) {
    if (!o.has(key) || !o.get(key).isJsonObject()) {
      o.add(key, new JsonObject());
    }
    return o.getAsJsonObject(key);
  }

  static JsonArray arr(final JsonObject o, final String key) {
    if (!o.has(key) || !o.get(key).isJsonArray()) {
      o.add(key, new JsonArray());
    }
    return o.getAsJsonArray(key);
  }

  private static double round1(final double x) {
    return Math.round(x * 10) / 10.0;
  }

  /** The land units of the USA or the United Kingdom by region (tables of the run). */
  public static Map<String, Integer> regioni(
      final GameState data, final GamePlayer player, @Nullable final String base) {
    final Config cfg = Config.get();
    final Map<String, Integer> out = new LinkedHashMap<>();
    for (final String k :
        List.of(
            "base",
            "patria",
            "mare",
            "europa_occidentale",
            "mediterraneo_nord_africa",
            "altrove")) {
      out.put(k, 0);
    }
    final List<String> home = cfg.patria().getOrDefault(player.getName(), List.of());
    for (final Territory t : data.getMap().getTerritories()) {
      final int n =
          t.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure()))
              .size();
      if (n == 0) {
        continue;
      }
      final String name = t.getName();
      final String k;
      if (t.isWater()) {
        k = "mare";
      } else if (name.equals(base)) {
        k = "base";
      } else if (home.contains(name)) {
        k = "patria";
      } else if (cfg.europaOccidentale().contains(name)) {
        k = "europa_occidentale";
      } else if (cfg.mediterraneo().contains(name)) {
        k = "mediterraneo_nord_africa";
      } else {
        k = "altrove";
      }
      out.merge(k, n, Integer::sum);
    }
    return out;
  }
}
