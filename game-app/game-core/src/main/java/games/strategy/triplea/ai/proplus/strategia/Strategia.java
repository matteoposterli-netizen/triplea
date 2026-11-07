package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * The strategy data file of one map (resource {@code proplus/strategia/<file>.json}): fronts with
 * the map's territory names and, per alliance, the plans the alliance's general staff can follow.
 *
 * <p>Fields of a plan used in game (step 3a): {@code fasi} (per range of rounds: risk modifier per
 * front, weighted objectives). Step 3c: {@code tappe} (the plan's progress) and {@code firma} (the
 * enemy reading the plan), observation only. Read but not used: {@code punti_critici}, {@code
 * ruoli}.
 */
public final class Strategia {
  /** Front of the territories no front lists (modifier 0). */
  public static final String ALTRO = "Altro";

  @Getter private final String gioco;
  private final List<String> fronti;
  private final Map<String, String> frontByTerritory;
  private final @Nullable String seaFront;
  private final Map<String, Alleanza> alleanze;

  /** Unit categories of the purchase mixes: name → unit types (in buying rotation order). */
  @Getter private final Map<String, List<String>> categorie;

  private Strategia(
      final String gioco,
      final List<String> fronti,
      final Map<String, String> frontByTerritory,
      final @Nullable String seaFront,
      final Map<String, Alleanza> alleanze,
      final Map<String, List<String>> categorie) {
    this.gioco = gioco;
    this.fronti = fronti;
    this.frontByTerritory = frontByTerritory;
    this.seaFront = seaFront;
    this.alleanze = alleanze;
    this.categorie = categorie;
  }

  /** Front names in file order, then {@link #ALTRO}. */
  public List<String> getFronti() {
    return fronti;
  }

  /** Front of {@code t}: listed by name, else the sea front for sea zones, else {@link #ALTRO}. */
  public String fronteOf(final Territory t) {
    final String front = frontByTerritory.get(t.getName());
    if (front != null) {
      return front;
    }
    return t.isWater() && seaFront != null ? seaFront : ALTRO;
  }

  /** Whether some front lists {@code t} (by name, or as a sea zone). */
  public boolean isMapped(final Territory t) {
    return frontByTerritory.containsKey(t.getName()) || (t.isWater() && seaFront != null);
  }

  public Optional<Alleanza> alleanza(final String alliance) {
    return Optional.ofNullable(alleanze.get(alliance));
  }

  /** Land territories {@code front} lists by name (empty for the sea front or an unknown one). */
  public List<String> territoriDelFronte(final String front) {
    return frontByTerritory.entrySet().stream()
        .filter(e -> e.getValue().equals(front))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  /** Unit type names the categories use that {@code data} has no unit type for. */
  public Set<String> unknownUnitTypes(final GameState data) {
    final Set<String> unknown = new TreeSet<>();
    categorie.values().stream()
        .flatMap(List::stream)
        .filter(u -> data.getUnitTypeList().getUnitType(u).isEmpty())
        .forEach(unknown::add);
    return unknown;
  }

  /** Names the file uses that are not territories of {@code map} (typos, other map version). */
  public Set<String> unknownTerritories(final GameMap map) {
    final Set<String> unknown = new TreeSet<>();
    for (final String name : frontByTerritory.keySet()) {
      if (map.getTerritoryOrNull(name) == null) {
        unknown.add(name);
      }
    }
    for (final Alleanza alleanza : alleanze.values()) {
      for (final Piano piano : alleanza.piani()) {
        for (final Fase fase : piano.fasi()) {
          for (final Obiettivo o : fase.obiettivi()) {
            if (o.territorio() != null && map.getTerritoryOrNull(o.territorio()) == null) {
              unknown.add(o.territorio());
            }
          }
        }
        for (final Tappa t : piano.tappe()) {
          t.territori().stream()
              .filter(name -> map.getTerritoryOrNull(name) == null)
              .forEach(unknown::add);
        }
        for (final Firma f : piano.firma()) {
          f.territori().stream()
              .filter(name -> map.getTerritoryOrNull(name) == null)
              .forEach(unknown::add);
          f.altrove().stream()
              .filter(name -> map.getTerritoryOrNull(name) == null)
              .forEach(unknown::add);
        }
        for (final List<Logistica> orders : piano.logistica().values()) {
          for (final Logistica l : orders) {
            for (final String name : l.territoriNominati()) {
              if (map.getTerritoryOrNull(name) == null) {
                unknown.add(name);
              }
            }
          }
        }
      }
    }
    return unknown;
  }

  /** Land territories of {@code map} no front lists: they go to {@link #ALTRO}. */
  public Set<String> unmappedTerritories(final GameMap map) {
    final Set<String> unmapped = new TreeSet<>();
    for (final Territory t : map.getTerritories()) {
      if (!isMapped(t)) {
        unmapped.add(t.getName());
      }
    }
    return unmapped;
  }

  /** The plans of one alliance; {@code automatico} is the plan "Automatico" stands for. */
  public record Alleanza(String automatico, List<Piano> piani) {
    public Optional<Piano> piano(final String id) {
      return piani.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /**
     * The plan "Automatico" stands for: {@link #automatico}, else the first plan; never an
     * experimental plan ({@link Piano#sperimentale}).
     */
    public Optional<Piano> pianoAutomatico() {
      return piano(automatico)
          .filter(p -> !p.sperimentale())
          .or(() -> piani.stream().filter(p -> !p.sperimentale()).findFirst());
    }
  }

  /**
   * One plan of an alliance.
   *
   * @param ruoli task per nation (nation name in the map XML → text)
   * @param tappe milestones: a territory expected to be taken by a round
   * @param firma clues that reveal the plan to the enemy, with a weight
   * @param puntiCritici what puts the plan in trouble
   * @param logistica per nation (name in the map XML), purchase / placement / staging orders by
   *     range of rounds (step 3b)
   * @param sperimentale shown as "(sperimentale)" in the settings dialog, never chosen by
   *     "Automatico" (step 3c)
   */
  public record Piano(
      String id,
      String nome,
      String descrizione,
      List<Fase> fasi,
      Map<String, String> ruoli,
      List<Tappa> tappe,
      List<Firma> firma,
      List<PuntoCritico> puntiCritici,
      Map<String, List<Logistica>> logistica,
      boolean sperimentale) {

    /** Eg: "B Sea Lion (sperimentale)": the label of the settings dialog. */
    public String etichettaPannello() {
      return sperimentale ? label() + " (sperimentale)" : label();
    }

    /** The phase that covers {@code round}, if any. */
    public Optional<Fase> fase(final int round) {
      return fasi.stream().filter(f -> f.covers(round)).findFirst();
    }

    /**
     * The war declarations of the plan (every phase's), for {@code nation} declaring war on {@code
     * enemy}: the latest "from round" wins if several phases name the pair.
     */
    public Optional<Dichiarazione> dichiarazione(final String nation, final String enemy) {
      return fasi.stream()
          .flatMap(f -> f.dichiarazioni().stream())
          .filter(d -> d.da().equals(nation) && d.a().equals(enemy))
          .max(Comparator.comparingInt(Dichiarazione::dalRound));
    }

    /** The orders of {@code nation} for {@code round}, if any. */
    public Optional<Logistica> logistica(final String nation, final int round) {
      return logistica.getOrDefault(nation, List.of()).stream()
          .filter(l -> l.covers(round))
          .findFirst();
    }

    /** Eg: "A Barbarossa". */
    public String label() {
      return id + " " + nome;
    }
  }

  /**
   * Rounds {@code daRound}..{@code aRound} (inclusive; {@link Integer#MAX_VALUE} = to the end) of a
   * plan: risk modifier per front (a front not listed: 0) and objectives.
   */
  public record Fase(
      int daRound,
      int aRound,
      Map<String, Integer> fronti,
      List<Obiettivo> obiettivi,
      List<Dichiarazione> dichiarazioni) {
    public boolean covers(final int round) {
      return round >= daRound && round <= aRound;
    }

    /** Modifier of {@code front}, limited to -1..+1. */
    public int modificatore(final String front) {
      return Math.max(-1, Math.min(1, fronti.getOrDefault(front, 0)));
    }

    /** Eg: "R1-2", "R3+", "R1". */
    public String label() {
      if (aRound == Integer.MAX_VALUE) {
        return "R" + daRound + "+";
      }
      return aRound == daRound ? "R" + daRound : "R" + daRound + "-" + aRound;
    }
  }

  /**
   * An objective of a phase: a territory, or ({@code flotta}) every sea zone with sea units of that
   * nation. {@code nazioni} empty = for every nation of the alliance.
   */
  public record Obiettivo(
      @Nullable String territorio,
      @Nullable String flotta,
      double peso,
      Set<String> nazioni,
      boolean critico) {
    public boolean appliesTo(final String nation) {
      return nazioni.isEmpty() || nazioni.contains(nation);
    }

    /** Eg: "France", "flotta British". */
    public String label() {
      return territorio != null ? territorio : "flotta " + flotta;
    }
  }

  /**
   * Purchase, placement and staging orders of one nation for rounds {@code daRound}..{@code aRound}
   * (inclusive; {@link Integer#MAX_VALUE} = to the end).
   *
   * @param mix target share of the PUs left after urgent defense, per category (percent, file
   *     order); the rest of the PUs (if the shares sum to less than 100) is spent as Hard does
   * @param obbligati purchases to make first, whatever the mix
   * @param piazzamento territories (factories or sea zones) to place the plan's purchases in first
   * @param fronteAmmassamento front the reserves move towards (for the diary; and its land
   *     territories when {@code territoriAmmassamento} is empty)
   * @param territoriAmmassamento gathering territories and sea zones the reserves move towards
   * @param forza strength of the pull towards the gathering territories (1 = normal, 0 = none)
   * @param aerei whether the air units land towards the gathering territories too (step 3b-bis)
   * @param quotaMinimaPiano percent of the turn's PUs reserved for the plan's purchases: the
   *     defense of threatened land may take more only for a factory or the capital at risk of being
   *     taken (step 3b-bis); 0 = no reserve
   */
  public record Logistica(
      int daRound,
      int aRound,
      Map<String, Double> mix,
      List<Obbligo> obbligati,
      List<String> piazzamento,
      @Nullable String fronteAmmassamento,
      List<String> territoriAmmassamento,
      double forza,
      boolean aerei,
      double quotaMinimaPiano,
      boolean lineaDelFronte,
      double pesoAmmassamento,
      List<Raccolta> altreRaccolte,
      Map<String, Integer> presidioMinimo,
      List<String> imbarco) {
    public boolean covers(final int round) {
      return round >= daRound && round <= aRound;
    }

    /** Eg: "R1-3", "R4+". */
    public String label() {
      if (aRound == Integer.MAX_VALUE) {
        return "R" + daRound + "+";
      }
      return aRound == daRound ? "R" + daRound : "R" + daRound + "-" + aRound;
    }

    List<String> territoriNominati() {
      final List<String> names = new ArrayList<>(piazzamento);
      names.addAll(territoriAmmassamento);
      altreRaccolte.forEach(r -> names.addAll(r.territori()));
      names.addAll(presidioMinimo.keySet());
      names.addAll(imbarco);
      return names;
    }
  }

  /**
   * War declaration of the plan (step 3b-ter): nation {@code da} may declare war on {@code a} only
   * from round {@code dalRound} ("never before"), and does it from then on when ready.
   */
  public record Dichiarazione(String da, String a, int dalRound) {}

  /**
   * An extra gathering group of the staging orders (step 3b-ter): {@code peso} relative to the main
   * one; {@code lineaDelFronte} = the front line of {@code fronte}, computed each turn.
   */
  public record Raccolta(
      @Nullable String fronte, List<String> territori, double peso, boolean lineaDelFronte) {}

  /** At least {@code minimo} units of {@code categoria}, bought before the mix. */
  public record Obbligo(String categoria, int minimo) {}

  /**
   * A milestone of a plan (step 3c): its territories taken by the alliance by round {@code
   * entroRound}; {@code tutti} = all of them, else one is enough.
   */
  public record Tappa(String nome, List<String> territori, boolean tutti, int entroRound) {
    /** Eg: "France R1", "Libya+Tobruk R5", "prima zona di sbarco R5". */
    public String label() {
      return nome + " R" + entroRound;
    }
  }

  /**
   * A clue of a plan's signature (step 3c), read by the enemy's general staff: a measure {@code
   * tipo} of the units of {@code nazioni}, turned into 0..1 by a linear ramp from {@code da} (0) to
   * {@code a} (1; {@code a < da} = the less, the stronger), counted with {@code peso} in rounds
   * {@code daRound}..{@code aRound} (step 3c-bis: outside them the clue keeps its remembered value,
   * see {@link Valutazione}). Measures:
   *
   * <ul>
   *   <li>{@value #QUOTA_TUV_FRONTE}: share of the nations' land TUV on {@code fronte} (0..1);
   *   <li>{@value #QUOTA_ACQUISTI}: share of the PUs of the nations' last two purchases spent on
   *       {@code unita} / {@code classe} (0..1; not counted before they bought anything);
   *   <li>{@value #UNITA_IN}: units of {@code unita} / {@code classe} in {@code territori} (land
   *       and sea zones; none listed = everywhere); with {@code altrove}, the share in {@code
   *       territori} of those in {@code territori} + {@code altrove} (0..1; none in both = not
   *       counted);
   *   <li>{@value #PIAZZAMENTI} (3c-bis): TUV of the units of {@code unita} / {@code classe} the
   *       nations placed in their last two placements in {@code territori}, as a share of all their
   *       placed TUV, or of the placed TUV in {@code territori} + {@code altrove} (0..1; nothing
   *       placed = not counted);
   *   <li>{@value #ATTACCHI} (3c-bis): territories in {@code territori} where the nations fought a
   *       battle as attacker in this round or the last one (against {@code contro}, if listed);
   *       with {@code altrove}, the share in {@code territori} of those in both (none = not
   *       counted);
   *   <li>{@value #IN_GUERRA} (3c-bis): 1 if one of the nations is at war with one of {@code
   *       contro}, else 0.
   * </ul>
   *
   * <p>Classes: {@code terra}, {@code aerei}, {@code navi}, {@code navi_da_guerra}, {@code
   * trasporti}, {@code trasporti_carichi} (3c-bis: transports carrying units).
   */
  public record Firma(
      String indizio,
      String tipo,
      List<String> nazioni,
      @Nullable String fronte,
      List<String> territori,
      List<String> unita,
      List<String> classi,
      List<String> altrove,
      List<String> contro,
      double da,
      double a,
      double peso,
      int daRound,
      int aRound) {
    public static final String QUOTA_TUV_FRONTE = "quota_tuv_fronte";
    public static final String QUOTA_ACQUISTI = "quota_acquisti";
    public static final String UNITA_IN = "unita_in";
    public static final String PIAZZAMENTI = "piazzamenti";
    public static final String ATTACCHI = "attacchi";
    public static final String IN_GUERRA = "in_guerra";
    static final Set<String> TIPI =
        Set.of(QUOTA_TUV_FRONTE, QUOTA_ACQUISTI, UNITA_IN, PIAZZAMENTI, ATTACCHI, IN_GUERRA);
    public static final String TRASPORTI_CARICHI = "trasporti_carichi";
    static final Set<String> CLASSI =
        Set.of("terra", "aerei", "navi", "navi_da_guerra", "trasporti", TRASPORTI_CARICHI);

    /** Whether the clue is an event (a battle) rather than a state: remembered differently. */
    public boolean isEvento() {
      return ATTACCHI.equals(tipo) && altrove.isEmpty();
    }

    public boolean covers(final int round) {
      return round >= daRound && round <= aRound;
    }

    /** {@code x} on the ramp {@link #da} → {@link #a}, limited to 0..1. */
    public double valore(final double x) {
      if (a == da) {
        return x >= a ? 1 : 0;
      }
      return Math.max(0, Math.min(1, (x - da) / (a - da)));
    }
  }

  public record PuntoCritico(
      String descrizione,
      String tipo,
      List<String> territori,
      List<String> zone,
      List<String> unita,
      @Nullable String nazione) {}

  // ------------------------------------------------------------------------------------ parsing

  /** Reads a strategy file; throws {@link IllegalArgumentException} if it is malformed. */
  public static Strategia parse(final Reader reader) {
    final JsonObject root;
    try {
      root = JsonParser.parseReader(reader).getAsJsonObject();
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException("JSON non valido: " + e.getMessage(), e);
    }
    final List<String> fronti = new ArrayList<>();
    final Map<String, String> frontByTerritory = new LinkedHashMap<>();
    String seaFront = null;
    for (final JsonElement e : array(root, "fronti")) {
      final JsonObject front = e.getAsJsonObject();
      final String name = string(front, "nome");
      if (name == null || fronti.contains(name) || ALTRO.equals(name)) {
        throw new IllegalArgumentException("fronte senza nome o ripetuto: " + name);
      }
      fronti.add(name);
      for (final String territory : strings(front, "territori")) {
        final String previous = frontByTerritory.putIfAbsent(territory, name);
        if (previous != null) {
          throw new IllegalArgumentException(
              territory + " e' in due fronti: " + previous + ", " + name);
        }
      }
      if (front.has("zone_di_mare") && front.get("zone_di_mare").getAsBoolean()) {
        seaFront = name;
      }
    }
    fronti.add(ALTRO);
    final Map<String, List<String>> categorie = new LinkedHashMap<>();
    if (root.has("categorie")) {
      root.getAsJsonObject("categorie")
          .entrySet()
          .forEach(
              c ->
                  categorie.put(
                      c.getKey(), strings(root.getAsJsonObject("categorie"), c.getKey())));
    }
    final Map<String, Alleanza> alleanze = new LinkedHashMap<>();
    final JsonObject alliances =
        root.has("alleanze") ? root.getAsJsonObject("alleanze") : new JsonObject();
    for (final Map.Entry<String, JsonElement> e : alliances.entrySet()) {
      final JsonObject alliance = e.getValue().getAsJsonObject();
      final List<Piano> piani = new ArrayList<>();
      for (final JsonElement p : array(alliance, "piani")) {
        final Piano piano = piano(p.getAsJsonObject(), categorie.keySet());
        if (piani.stream().anyMatch(other -> other.id().equals(piano.id()))) {
          throw new IllegalArgumentException("piano ripetuto: " + e.getKey() + " " + piano.id());
        }
        piani.add(piano);
      }
      alleanze.put(
          e.getKey(),
          new Alleanza(
              Optional.ofNullable(string(alliance, "automatico")).orElse(""), List.copyOf(piani)));
    }
    return new Strategia(
        Optional.ofNullable(string(root, "gioco")).orElse(""),
        List.copyOf(fronti),
        Map.copyOf(frontByTerritory),
        seaFront,
        Map.copyOf(alleanze),
        Collections.unmodifiableMap(categorie));
  }

  private static Piano piano(final JsonObject p, final Set<String> categories) {
    final String id = string(p, "id");
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("piano senza id");
    }
    final List<Fase> fasi = new ArrayList<>();
    for (final JsonElement f : array(p, "fasi")) {
      fasi.add(fase(f.getAsJsonObject()));
    }
    final Map<String, String> ruoli = new LinkedHashMap<>();
    if (p.has("ruoli")) {
      p.getAsJsonObject("ruoli")
          .entrySet()
          .forEach(r -> ruoli.put(r.getKey(), r.getValue().getAsString()));
    }
    final List<Tappa> tappe = new ArrayList<>();
    for (final JsonElement t : array(p, "tappe")) {
      tappe.add(tappa(t.getAsJsonObject(), id));
    }
    final List<Firma> firma = new ArrayList<>();
    final Set<String> clueNames = new HashSet<>();
    for (final JsonElement t : array(p, "firma")) {
      final Firma f = firma(t.getAsJsonObject(), id);
      // the name keys the clue's memory (step 3c-bis)
      if (!clueNames.add(f.indizio())) {
        throw new IllegalArgumentException("indizio ripetuto nel piano " + id + ": " + f.indizio());
      }
      firma.add(f);
    }
    final List<PuntoCritico> puntiCritici = new ArrayList<>();
    for (final JsonElement t : array(p, "punti_critici")) {
      final JsonObject o = t.getAsJsonObject();
      puntiCritici.add(
          new PuntoCritico(
              Optional.ofNullable(string(o, "descrizione")).orElse(""),
              Optional.ofNullable(string(o, "tipo")).orElse(""),
              strings(o, "territori"),
              strings(o, "zone"),
              strings(o, "unita"),
              string(o, "nazione")));
    }
    final Map<String, List<Logistica>> logistica = new LinkedHashMap<>();
    if (p.has("logistica")) {
      for (final Map.Entry<String, JsonElement> e : p.getAsJsonObject("logistica").entrySet()) {
        final List<Logistica> orders = new ArrayList<>();
        for (final JsonElement l : e.getValue().getAsJsonArray()) {
          orders.add(logistica(l.getAsJsonObject(), categories, id + " " + e.getKey()));
        }
        logistica.put(e.getKey(), List.copyOf(orders));
      }
    }
    return new Piano(
        id,
        Optional.ofNullable(string(p, "nome")).orElse(id),
        Optional.ofNullable(string(p, "descrizione")).orElse(""),
        List.copyOf(fasi),
        Map.copyOf(ruoli),
        List.copyOf(tappe),
        List.copyOf(firma),
        List.copyOf(puntiCritici),
        Map.copyOf(logistica),
        p.has("sperimentale") && p.get("sperimentale").getAsBoolean());
  }

  private static Tappa tappa(final JsonObject o, final String plan) {
    final List<String> territori = new ArrayList<>(strings(o, "territori"));
    Optional.ofNullable(string(o, "territorio")).ifPresent(territori::add);
    if (territori.isEmpty() || !o.has("entro_round")) {
      throw new IllegalArgumentException(
          "tappa senza territori o senza entro_round nel piano " + plan + ": " + o);
    }
    final boolean tutti = !"uno".equals(string(o, "richiesti"));
    final String nome =
        Optional.ofNullable(string(o, "nome"))
            .orElseGet(() -> String.join(tutti ? "+" : " o ", territori));
    return new Tappa(nome, List.copyOf(territori), tutti, o.get("entro_round").getAsInt());
  }

  private static Firma firma(final JsonObject o, final String plan) {
    final String tipo = string(o, "tipo");
    if (tipo == null || !Firma.TIPI.contains(tipo)) {
      throw new IllegalArgumentException(
          "indizio di tipo sconosciuto nel piano " + plan + ": " + tipo + " " + Firma.TIPI);
    }
    final List<String> nazioni = new ArrayList<>(strings(o, "nazioni"));
    Optional.ofNullable(string(o, "nazione")).ifPresent(nazioni::add);
    final List<String> classi = new ArrayList<>(strings(o, "classi"));
    Optional.ofNullable(string(o, "classe")).ifPresent(classi::add);
    for (final String c : classi) {
      if (!Firma.CLASSI.contains(c)) {
        throw new IllegalArgumentException("classe sconosciuta nel piano " + plan + ": " + c);
      }
    }
    final String fronte = string(o, "fronte");
    final List<String> contro = strings(o, "contro");
    if (nazioni.isEmpty()
        || !o.has("da")
        || !o.has("a")
        || (Firma.QUOTA_TUV_FRONTE.equals(tipo) && fronte == null)
        || (Firma.IN_GUERRA.equals(tipo) && contro.isEmpty())) {
      throw new IllegalArgumentException(
          "indizio incompleto nel piano "
              + plan
              + " (servono nazioni, da, a, per "
              + Firma.QUOTA_TUV_FRONTE
              + " il fronte e per "
              + Firma.IN_GUERRA
              + " 'contro'): "
              + o);
    }
    final List<String> territori = new ArrayList<>(strings(o, "territori"));
    territori.addAll(strings(o, "zone"));
    final List<String> altrove = new ArrayList<>(strings(o, "altrove"));
    altrove.addAll(strings(o, "zone_altrove"));
    return new Firma(
        Optional.ofNullable(string(o, "indizio")).orElse(tipo),
        tipo,
        List.copyOf(nazioni),
        fronte,
        List.copyOf(territori),
        strings(o, "unita"),
        List.copyOf(classi),
        List.copyOf(altrove),
        contro,
        o.get("da").getAsDouble(),
        o.get("a").getAsDouble(),
        o.has("peso") ? o.get("peso").getAsDouble() : 1,
        o.has("da_round") ? o.get("da_round").getAsInt() : 1,
        o.has("a_round") && !o.get("a_round").isJsonNull()
            ? o.get("a_round").getAsInt()
            : Integer.MAX_VALUE);
  }

  private static Logistica logistica(
      final JsonObject l, final Set<String> categories, final String where) {
    final int from = l.has("da_round") ? l.get("da_round").getAsInt() : 1;
    final int to =
        l.has("a_round") && !l.get("a_round").isJsonNull()
            ? l.get("a_round").getAsInt()
            : Integer.MAX_VALUE;
    final JsonObject acquisti =
        l.has("acquisti") ? l.getAsJsonObject("acquisti") : new JsonObject();
    final Map<String, Double> mix = new LinkedHashMap<>();
    if (acquisti.has("mix")) {
      for (final Map.Entry<String, JsonElement> e : acquisti.getAsJsonObject("mix").entrySet()) {
        checkCategory(e.getKey(), categories, where);
        final double percent = e.getValue().getAsDouble();
        if (percent < 0) {
          throw new IllegalArgumentException("mix negativo: " + where + " " + e.getKey());
        }
        mix.put(e.getKey(), percent);
      }
    }
    if (mix.values().stream().mapToDouble(Double::doubleValue).sum() > 100.5) {
      throw new IllegalArgumentException("mix oltre il 100%: " + where);
    }
    final List<Obbligo> obbligati = new ArrayList<>();
    for (final JsonElement e : array(acquisti, "obbligati")) {
      final JsonObject o = e.getAsJsonObject();
      final String category = string(o, "categoria");
      checkCategory(category, categories, where);
      obbligati.add(new Obbligo(category, o.has("minimo") ? o.get("minimo").getAsInt() : 1));
    }
    final JsonObject staging =
        l.has("ammassamento") ? l.getAsJsonObject("ammassamento") : new JsonObject();
    final double quota =
        l.has("quota_minima_piano") ? l.get("quota_minima_piano").getAsDouble() : 0;
    if (quota < 0 || quota > 100) {
      throw new IllegalArgumentException("quota_minima_piano fuori da 0-100: " + where);
    }
    final List<Raccolta> extra = new ArrayList<>();
    for (final JsonElement e : array(l, "altre_raccolte")) {
      final JsonObject r = e.getAsJsonObject();
      extra.add(
          new Raccolta(
              string(r, "fronte"),
              strings(r, "territori"),
              r.has("peso") ? r.get("peso").getAsDouble() : 100,
              "linea_del_fronte".equals(string(r, "modo"))));
    }
    final Map<String, Integer> presidio = new LinkedHashMap<>();
    if (l.has("presidio_minimo")) {
      final JsonObject g = l.getAsJsonObject("presidio_minimo");
      final int units = g.has("unita") ? g.get("unita").getAsInt() : 1;
      strings(g, "territori").forEach(t -> presidio.put(t, units));
    }
    return new Logistica(
        from,
        to,
        Collections.unmodifiableMap(mix),
        List.copyOf(obbligati),
        strings(l, "piazzamento"),
        string(staging, "fronte"),
        strings(staging, "territori"),
        staging.has("forza") ? staging.get("forza").getAsDouble() : 1,
        staging.has("aerei") && staging.get("aerei").getAsBoolean(),
        quota,
        "linea_del_fronte".equals(string(staging, "modo")),
        staging.has("peso") ? staging.get("peso").getAsDouble() : 100,
        List.copyOf(extra),
        Collections.unmodifiableMap(presidio),
        strings(l, "imbarco"));
  }

  private static void checkCategory(
      final @Nullable String category, final Set<String> categories, final String where) {
    if (category == null || !categories.contains(category)) {
      throw new IllegalArgumentException(
          "categoria sconosciuta in "
              + where
              + ": "
              + category
              + " (categorie: "
              + categories
              + ")");
    }
  }

  private static Fase fase(final JsonObject f) {
    final int from = f.has("da_round") ? f.get("da_round").getAsInt() : 1;
    final int to =
        f.has("a_round") && !f.get("a_round").isJsonNull()
            ? f.get("a_round").getAsInt()
            : Integer.MAX_VALUE;
    final Map<String, Integer> fronti = new LinkedHashMap<>();
    if (f.has("fronti")) {
      f.getAsJsonObject("fronti")
          .entrySet()
          .forEach(e -> fronti.put(e.getKey(), e.getValue().getAsInt()));
    }
    final List<Obiettivo> obiettivi = new ArrayList<>();
    for (final JsonElement e : array(f, "obiettivi")) {
      final JsonObject o = e.getAsJsonObject();
      final String territorio = string(o, "territorio");
      final String flotta = string(o, "flotta");
      if ((territorio == null) == (flotta == null)) {
        throw new IllegalArgumentException("obiettivo: serve 'territorio' oppure 'flotta': " + o);
      }
      obiettivi.add(
          new Obiettivo(
              territorio,
              flotta,
              o.has("peso") ? o.get("peso").getAsDouble() : 0,
              Set.copyOf(strings(o, "nazioni")),
              o.has("critico") && o.get("critico").getAsBoolean()));
    }
    final List<Dichiarazione> dichiarazioni = new ArrayList<>();
    for (final JsonElement e : array(f, "dichiarazioni")) {
      final JsonObject d = e.getAsJsonObject();
      final String da = string(d, "da");
      final String a = string(d, "a");
      if (da == null || a == null || !d.has("dal_round")) {
        throw new IllegalArgumentException("dichiarazione: servono 'da', 'a', 'dal_round': " + d);
      }
      dichiarazioni.add(new Dichiarazione(da, a, d.get("dal_round").getAsInt()));
    }
    return new Fase(
        from, to, Map.copyOf(fronti), List.copyOf(obiettivi), List.copyOf(dichiarazioni));
  }

  private static JsonArray array(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }

  private static List<String> strings(final JsonObject o, final String key) {
    final List<String> list = new ArrayList<>();
    array(o, key).forEach(e -> list.add(e.getAsString()));
    return List.copyOf(list);
  }

  private static @Nullable String string(final JsonObject o, final String key) {
    return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
  }
}
