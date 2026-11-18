package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Step P1: the data of the "compito" (task) mechanism, read from {@value #RESOURCE}. An alliance
 * has a direction; a nation has a task, an ORDERED list of {@link Risultato results} (a condition
 * on the board, a target round, constraints). No code knows a nation: the list is data, the engine
 * ({@link Compiti}) is generic.
 */
public record Compito(
    String nazione,
    String porto,
    List<String> fabbricheTruppe,
    List<Risultato> risultati,
    @Nullable Double quotaPrima,
    @Nullable Double quotaDopo) {

  public Compito(
      final String nazione,
      final String porto,
      final List<String> fabbricheTruppe,
      final List<Risultato> risultati) {
    this(nazione, porto, fabbricheTruppe, risultati, null, null);
  }

  public static final String RESOURCE = "proplus/strategia/compiti.json";

  /** The result types the engine knows (generic conditions). */
  public static final String CONTROLLO_E_FLOTTA = "controllo_e_flotta";

  public static final String SCELTA = "scelta";
  public static final String PRESA = "presa";

  /** Step P2: the alliance has chosen a direction ({@link Alleanza}). */
  public static final String DIREZIONE_ALLEANZA = "direzione_alleanza";

  /**
   * Step P2: shared result "testa di ponte tenuta": a territory of the direction's theatre taken
   * and still allied at the end of the next round; several nations contribute.
   */
  public static final String TESTA_DI_PONTE = "testa_di_ponte";

  /**
   * Step P2: a territory held (enemy realistic attack <= {@code soglia_tenuta}) or taken back; a
   * continuous result, served every turn before the current one ({@code vincolo}: if it does not
   * hold the task takes no PU).
   */
  public static final String TENUTA = "tenuta";

  static final List<String> TIPI =
      List.of(CONTROLLO_E_FLOTTA, SCELTA, PRESA, DIREZIONE_ALLEANZA, TESTA_DI_PONTE, TENUTA);

  /**
   * One result of a task.
   *
   * @param tipo {@link #CONTROLLO_E_FLOTTA} (territory of the alliance AND a naval force of the
   *     nation in {@code zona} that the zone can hold), {@link #SCELTA} (one of {@code opzioni}:
   *     label → capital, held {@code tenutaRound} rounds), {@link #PRESA} (the territory chosen by
   *     result {@code territorioDa} owned by the alliance)
   * @param traguardo the target round (0 = none)
   * @param mantieni a reached result that stops holding becomes the current one again
   */
  public record Risultato(
      String id,
      String nome,
      String tipo,
      @Nullable String territorio,
      @Nullable String zona,
      Map<String, String> opzioni,
      int tenutaRound,
      @Nullable String territorioDa,
      int traguardo,
      boolean mantieni,
      boolean vincolo,
      List<String> soloDirezioni) {

    public Risultato(
        final String id,
        final String nome,
        final String tipo,
        @Nullable final String territorio,
        @Nullable final String zona,
        final Map<String, String> opzioni,
        final int tenutaRound,
        @Nullable final String territorioDa,
        final int traguardo,
        final boolean mantieni) {
      this(
          id,
          nome,
          tipo,
          territorio,
          zona,
          opzioni,
          tenutaRound,
          territorioDa,
          traguardo,
          mantieni,
          false,
          List.of());
    }

    /** A continuous result (served every turn, not in the list's order). */
    public boolean continuo() {
      return TENUTA.equals(tipo);
    }

    /** The result applies with the alliance's direction {@code direction} (null = any). */
    public boolean valePer(@Nullable final String direction) {
      return soloDirezioni.isEmpty() || (direction != null && soloDirezioni.contains(direction));
    }
  }

  /** The engine's parameters ({@code parametri}). */
  public record Parametri(
      double sogliaAttacco,
      double sogliaTrasporti,
      double quotaPrima,
      double quotaDopo,
      List<String> tipiMare,
      List<String> tipiTruppe,
      String tipoTrasporto,
      int maxPassiAcquistoMare,
      int maxTruppeStima,
      int puPerTurnoDistanza,
      int margineCambioBersaglio,
      int maxTurniDistanza,
      double sogliaPresidioCapitale,
      double sogliaTenuta,
      int roundAccumuloMax,
      int roundABordoMax,
      double pesoUrgenzaPu,
      double ostacoliMinimiPu,
      List<String> tipiDifesa) {}

  /**
   * Step P2: a direction of an alliance: its capitals in order (the first not allied is the next
   * one), its theatre (the territories of its bridgeheads and results) and the nations that
   * contribute to its shared result.
   */
  public record Direzione(
      String nome, List<String> capitali, List<String> teatro, List<String> contributori) {}

  /** Step P2: the alliance level ({@link Alleanza}). */
  public record Livello(int tenutaRound, Map<String, Direzione> direzioni) {}

  /** The whole file. */
  public record Dati(
      boolean accesoDiDefault,
      Parametri parametri,
      Map<String, String> direzioni,
      Map<String, List<String>> nazioniAttive,
      Map<String, Compito> compiti,
      Map<String, Livello> livelli) {

    public Dati(
        final boolean accesoDiDefault,
        final Parametri parametri,
        final Map<String, String> direzioni,
        final Map<String, List<String>> nazioniAttive,
        final Map<String, Compito> compiti) {
      this(accesoDiDefault, parametri, direzioni, nazioniAttive, compiti, Map.of());
    }

    /** Step P2: the alliance level of {@code alliance}, if the data has one. */
    public Optional<Livello> livello(final String alliance) {
      return Optional.ofNullable(livelli.get(alliance));
    }

    /** The task of {@code nation} in {@code alliance}, if the step plays it for that nation. */
    public Optional<Compito> di(final String alliance, final String nation) {
      if (!nazioniAttive.getOrDefault(alliance, List.of()).contains(nation)) {
        return Optional.empty();
      }
      return Optional.ofNullable(compiti.get(alliance + "|" + nation));
    }
  }

  private static @Nullable Dati cache;

  public static synchronized Dati dati() {
    if (cache == null) {
      try (InputStream in = Compito.class.getClassLoader().getResourceAsStream(RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException("manca " + RESOURCE);
        }
        cache =
            parse(
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject());
      } catch (final IOException e) {
        throw new IllegalStateException("File dei compiti non valido: " + e, e);
      }
    }
    return cache;
  }

  static Dati parse(final JsonObject o) {
    final JsonObject p = o.getAsJsonObject("parametri");
    final Parametri par =
        new Parametri(
            v(p, "soglia_attacco").getAsDouble(),
            v(p, "soglia_trasporti").getAsDouble(),
            v(p, "quota_prima_del_primo_risultato").getAsDouble(),
            v(p, "quota_dopo").getAsDouble(),
            strings(v(p, "tipi_mare")),
            strings(v(p, "tipi_truppe")),
            v(p, "tipo_trasporto").getAsString(),
            v(p, "max_passi_acquisto_mare").getAsInt(),
            v(p, "max_truppe_stima").getAsInt(),
            v(p, "pu_per_turno_di_distanza").getAsInt(),
            v(p, "margine_cambio_bersaglio_pu").getAsInt(),
            v(p, "max_turni_distanza").getAsInt(),
            v(p, "soglia_presidio_capitale").getAsDouble(),
            p.has("soglia_tenuta") ? v(p, "soglia_tenuta").getAsDouble() : 40,
            p.has("round_accumulo_max") ? v(p, "round_accumulo_max").getAsInt() : 2,
            p.has("round_a_bordo_max") ? v(p, "round_a_bordo_max").getAsInt() : 2,
            p.has("peso_urgenza_pu") ? v(p, "peso_urgenza_pu").getAsDouble() : 0,
            p.has("ostacoli_minimi_pu") ? v(p, "ostacoli_minimi_pu").getAsDouble() : 10,
            p.has("tipi_difesa") ? strings(v(p, "tipi_difesa")) : List.of("infantry"));
    final Map<String, String> directions = new LinkedHashMap<>();
    final Map<String, List<String>> active = new LinkedHashMap<>();
    final Map<String, Compito> tasks = new LinkedHashMap<>();
    final Map<String, Livello> levels = new LinkedHashMap<>();
    for (final var a : o.getAsJsonObject("alleanze").entrySet()) {
      final JsonObject ao = a.getValue().getAsJsonObject();
      directions.put(a.getKey(), ao.get("direzione").getAsString());
      active.put(a.getKey(), strings(ao.get("nazioni_attive")));
      if (ao.has("direzioni")) {
        final Map<String, Direzione> dirs = new LinkedHashMap<>();
        for (final var d : ao.getAsJsonObject("direzioni").entrySet()) {
          final JsonObject dobj = d.getValue().getAsJsonObject();
          dirs.put(
              d.getKey(),
              new Direzione(
                  d.getKey(),
                  strings(dobj.get("capitali")),
                  strings(dobj.get("teatro")),
                  strings(dobj.get("contributori"))));
        }
        levels.put(
            a.getKey(),
            new Livello(
                ao.has("tenuta_direzione_round") ? ao.get("tenuta_direzione_round").getAsInt() : 3,
                java.util.Collections.unmodifiableMap(dirs)));
      }
      for (final var n : ao.getAsJsonObject("nazioni").entrySet()) {
        final JsonObject no = n.getValue().getAsJsonObject();
        final List<Risultato> results = new ArrayList<>();
        for (final JsonElement e : no.getAsJsonArray("risultati")) {
          results.add(risultato(e.getAsJsonObject()));
        }
        tasks.put(
            a.getKey() + "|" + n.getKey(),
            new Compito(
                n.getKey(),
                no.get("porto").getAsString(),
                strings(no.get("fabbriche_truppe")),
                List.copyOf(results),
                no.has("quota_prima_del_primo_risultato")
                    ? no.get("quota_prima_del_primo_risultato").getAsDouble()
                    : null,
                no.has("quota_dopo") ? no.get("quota_dopo").getAsDouble() : null));
      }
    }
    return new Dati(
        v(o, "compiti_acceso_di_default").getAsBoolean(),
        par,
        Map.copyOf(directions),
        Map.copyOf(active),
        Map.copyOf(tasks),
        Map.copyOf(levels));
  }

  private static Risultato risultato(final JsonObject r) {
    final Map<String, String> options = new LinkedHashMap<>();
    if (r.has("opzioni")) {
      r.getAsJsonObject("opzioni")
          .entrySet()
          .forEach(e -> options.put(e.getKey(), e.getValue().getAsString()));
    }
    final String tipo = r.get("tipo").getAsString();
    if (!TIPI.contains(tipo)) {
      throw new IllegalStateException("tipo di risultato sconosciuto: " + tipo);
    }
    return new Risultato(
        r.get("id").getAsString(),
        r.get("nome").getAsString(),
        tipo,
        r.has("territorio") ? r.get("territorio").getAsString() : null,
        r.has("zona") ? r.get("zona").getAsString() : null,
        java.util.Collections.unmodifiableMap(options),
        r.has("tenuta_round") ? r.get("tenuta_round").getAsInt() : 0,
        r.has("territorio_da") ? r.get("territorio_da").getAsString() : null,
        r.has("traguardo_round") ? r.get("traguardo_round").getAsInt() : 0,
        r.has("mantieni") && r.get("mantieni").getAsBoolean(),
        r.has("vincolo") && r.get("vincolo").getAsBoolean(),
        r.has("solo_direzioni") ? strings(r.get("solo_direzioni")) : List.of());
  }

  private static JsonElement v(final JsonObject o, final String key) {
    final JsonElement e = o.get(key);
    return e.isJsonObject() && e.getAsJsonObject().has("valore")
        ? e.getAsJsonObject().get("valore")
        : e;
  }

  private static List<String> strings(final JsonElement e) {
    final List<String> out = new ArrayList<>();
    e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
    return List.copyOf(out);
  }

  /** The index of the result with {@code id}, -1 if none. */
  public int indice(final String id) {
    for (int i = 0; i < risultati.size(); i++) {
      if (risultati.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }
}
