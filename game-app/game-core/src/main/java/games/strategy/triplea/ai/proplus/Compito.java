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
public record Compito(String nazione, String porto, List<String> fabbricheTruppe, List<Risultato> risultati) {

  public static final String RESOURCE = "proplus/strategia/compiti.json";

  /** The result types the engine knows (generic conditions). */
  public static final String CONTROLLO_E_FLOTTA = "controllo_e_flotta";

  public static final String SCELTA = "scelta";
  public static final String PRESA = "presa";

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
      boolean mantieni) {}

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
      double sogliaPresidioCapitale) {}

  /** The whole file. */
  public record Dati(
      boolean accesoDiDefault,
      Parametri parametri,
      Map<String, String> direzioni,
      Map<String, List<String>> nazioniAttive,
      Map<String, Compito> compiti) {

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
            v(p, "soglia_presidio_capitale").getAsDouble());
    final Map<String, String> directions = new LinkedHashMap<>();
    final Map<String, List<String>> active = new LinkedHashMap<>();
    final Map<String, Compito> tasks = new LinkedHashMap<>();
    for (final var a : o.getAsJsonObject("alleanze").entrySet()) {
      final JsonObject ao = a.getValue().getAsJsonObject();
      directions.put(a.getKey(), ao.get("direzione").getAsString());
      active.put(a.getKey(), strings(ao.get("nazioni_attive")));
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
                List.copyOf(results)));
      }
    }
    return new Dati(
        v(o, "compiti_acceso_di_default").getAsBoolean(),
        par,
        Map.copyOf(directions),
        Map.copyOf(active),
        Map.copyOf(tasks));
  }

  private static Risultato risultato(final JsonObject r) {
    final Map<String, String> options = new LinkedHashMap<>();
    if (r.has("opzioni")) {
      r.getAsJsonObject("opzioni").entrySet().forEach(e -> options.put(e.getKey(), e.getValue().getAsString()));
    }
    final String tipo = r.get("tipo").getAsString();
    if (!List.of(CONTROLLO_E_FLOTTA, SCELTA, PRESA).contains(tipo)) {
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
        r.has("mantieni") && r.get("mantieni").getAsBoolean());
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
