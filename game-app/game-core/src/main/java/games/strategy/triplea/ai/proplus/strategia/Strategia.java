package games.strategy.triplea.ai.proplus.strategia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.Territory;
import java.io.Reader;
import java.util.ArrayList;
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
 * front, weighted objectives). Read but not used yet: {@code tappe}, {@code firma}, {@code
 * punti_critici}, {@code ruoli}.
 */
public final class Strategia {
  /** Front of the territories no front lists (modifier 0). */
  public static final String ALTRO = "Altro";

  @Getter private final String gioco;
  private final List<String> fronti;
  private final Map<String, String> frontByTerritory;
  private final @Nullable String seaFront;
  private final Map<String, Alleanza> alleanze;

  private Strategia(
      final String gioco,
      final List<String> fronti,
      final Map<String, String> frontByTerritory,
      final @Nullable String seaFront,
      final Map<String, Alleanza> alleanze) {
    this.gioco = gioco;
    this.fronti = fronti;
    this.frontByTerritory = frontByTerritory;
    this.seaFront = seaFront;
    this.alleanze = alleanze;
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

    /** The plan "Automatico" stands for: {@link #automatico}, else the first plan. */
    public Optional<Piano> pianoAutomatico() {
      return piano(automatico).or(() -> piani.stream().findFirst());
    }
  }

  /**
   * One plan of an alliance.
   *
   * @param ruoli task per nation (nation name in the map XML → text)
   * @param tappe milestones: a territory expected to be taken by a round
   * @param firma clues that reveal the plan to the enemy, with a weight
   * @param puntiCritici what puts the plan in trouble
   */
  public record Piano(
      String id,
      String nome,
      String descrizione,
      List<Fase> fasi,
      Map<String, String> ruoli,
      List<Tappa> tappe,
      List<Firma> firma,
      List<PuntoCritico> puntiCritici) {

    /** The phase that covers {@code round}, if any. */
    public Optional<Fase> fase(final int round) {
      return fasi.stream().filter(f -> f.covers(round)).findFirst();
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
      int daRound, int aRound, Map<String, Integer> fronti, List<Obiettivo> obiettivi) {
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
      @Nullable String territorio, @Nullable String flotta, double peso, Set<String> nazioni) {
    public boolean appliesTo(final String nation) {
      return nazioni.isEmpty() || nazioni.contains(nation);
    }

    /** Eg: "France", "flotta British". */
    public String label() {
      return territorio != null ? territorio : "flotta " + flotta;
    }
  }

  public record Tappa(String territorio, int entroRound) {}

  public record Firma(
      String indizio,
      String tipo,
      List<String> territori,
      List<String> zone,
      List<String> unita,
      @Nullable String nazione,
      double peso) {}

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
    final Map<String, Alleanza> alleanze = new LinkedHashMap<>();
    final JsonObject alliances =
        root.has("alleanze") ? root.getAsJsonObject("alleanze") : new JsonObject();
    for (final Map.Entry<String, JsonElement> e : alliances.entrySet()) {
      final JsonObject alliance = e.getValue().getAsJsonObject();
      final List<Piano> piani = new ArrayList<>();
      for (final JsonElement p : array(alliance, "piani")) {
        final Piano piano = piano(p.getAsJsonObject());
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
        Map.copyOf(alleanze));
  }

  private static Piano piano(final JsonObject p) {
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
      final JsonObject o = t.getAsJsonObject();
      tappe.add(new Tappa(string(o, "territorio"), o.get("entro_round").getAsInt()));
    }
    final List<Firma> firma = new ArrayList<>();
    for (final JsonElement t : array(p, "firma")) {
      final JsonObject o = t.getAsJsonObject();
      firma.add(
          new Firma(
              Optional.ofNullable(string(o, "indizio")).orElse(""),
              Optional.ofNullable(string(o, "tipo")).orElse(""),
              strings(o, "territori"),
              strings(o, "zone"),
              strings(o, "unita"),
              string(o, "nazione"),
              o.has("peso") ? o.get("peso").getAsDouble() : 1));
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
    return new Piano(
        id,
        Optional.ofNullable(string(p, "nome")).orElse(id),
        Optional.ofNullable(string(p, "descrizione")).orElse(""),
        List.copyOf(fasi),
        Map.copyOf(ruoli),
        List.copyOf(tappe),
        List.copyOf(firma),
        List.copyOf(puntiCritici));
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
              Set.copyOf(strings(o, "nazioni"))));
    }
    return new Fase(from, to, Map.copyOf(fronti), List.copyOf(obiettivi));
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
