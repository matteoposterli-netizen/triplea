package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
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
import javax.annotation.Nullable;

/**
 * Step R4 "STRATEGIA COMPLETA": the values of {@code impegno.r4} in {@code stratega-llm.json} (each
 * {@code {"valore", "origine"}}: chosen or from the map, none tuned).
 */
public record ConfigR4(
    boolean assedioDiDefault,
    boolean alleatiDiDefault,
    String nazioneAssedio,
    String capitaleRussa,
    List<String> fabbricheRusse,
    double sogliaMosca,
    int produzioneFabbricaCatturata,
    Bombardamento bombardamento,
    String nazioneItalia,
    List<String> italiaLivello1,
    List<String> italiaLivello2,
    String portoItalia,
    List<String> basiAfricaItalia,
    List<String> tipiFlottaItalia,
    String gibilterra,
    List<String> bersagliAfrica,
    List<String> bersagliFrancia,
    List<String> nordAfrica,
    int movimentoVeloci,
    String fabbricaSudafrica,
    List<String> tipiSudafrica,
    List<String> neutraliEst,
    List<String> passaggiSud,
    int distanzaSbarramento,
    Map<String, Integer> puTerritori,
    List<Obiettivo> obiettivi) {

  /** The strategic bombing values (A3). */
  public record Bombardamento(
      String bombardiere,
      String scorta,
      int dado,
      int bonus,
      int aaColpisceSu,
      int maxComprati,
      int maxScorteComprate,
      double sogliaBaseSicura) {

    /**
     * Expected damage of one bomber over the factory's own AA: (1 - aa/dice) x (dice+1)/2+bonus.
     */
    double dannoAtteso() {
      return (1.0 - (double) aaColpisceSu / dado) * ((dado + 1) / 2.0 + bonus);
    }
  }

  /**
   * A national objective of the map (C1): {@code tipo} "ciascuno" (value for each territory held),
   * "almeno" (value with at least {@code minimo} held) or "altro" (conditions that are not
   * ownership: the territory taken does not change them); {@code diretto} = owned by the nation,
   * otherwise by its alliance.
   */
  public record Obiettivo(
      String id,
      String nazione,
      int valore,
      String tipo,
      boolean diretto,
      List<String> territori,
      int minimo) {}

  private static @Nullable ConfigR4 cache;

  public static synchronized ConfigR4 get() {
    if (cache == null) {
      try (InputStream in =
          ConfigR4.class.getClassLoader().getResourceAsStream(StrategaConfig.RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException("manca " + StrategaConfig.RESOURCE);
        }
        cache =
            parse(
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("impegno")
                    .getAsJsonObject("r4"));
      } catch (final IOException e) {
        throw new IllegalStateException("File dello stratega non valido: " + e, e);
      }
    }
    return cache;
  }

  static ConfigR4 parse(final JsonObject o) {
    final JsonObject b = v(o, "bombardamento").getAsJsonObject();
    final Map<String, Integer> pu = new LinkedHashMap<>();
    v(o, "pu_territori")
        .getAsJsonObject()
        .entrySet()
        .forEach(e -> pu.put(e.getKey(), e.getValue().getAsInt()));
    final List<Obiettivo> objectives = new ArrayList<>();
    for (final JsonElement e : v(o, "obiettivi_nazionali").getAsJsonArray()) {
      final JsonObject x = e.getAsJsonObject();
      objectives.add(
          new Obiettivo(
              x.get("id").getAsString(),
              x.get("nazione").getAsString(),
              x.get("valore").getAsInt(),
              x.get("tipo").getAsString(),
              !x.has("possesso") || "diretto".equals(x.get("possesso").getAsString()),
              x.has("territori") ? strings(x.getAsJsonArray("territori")) : List.of(),
              x.has("minimo") ? x.get("minimo").getAsInt() : 0));
    }
    return new ConfigR4(
        v(o, "assedio_acceso_di_default").getAsBoolean(),
        v(o, "alleati_r4_acceso_di_default").getAsBoolean(),
        v(o, "nazione_assedio").getAsString(),
        v(o, "capitale_russa").getAsString(),
        strings(v(o, "fabbriche_russe").getAsJsonArray()),
        v(o, "soglia_mosca").getAsDouble(),
        v(o, "produzione_fabbrica_catturata").getAsInt(),
        new Bombardamento(
            b.get("bombardiere").getAsString(),
            b.get("scorta").getAsString(),
            b.get("dado").getAsInt(),
            b.get("bonus").getAsInt(),
            b.get("aa_colpisce_su").getAsInt(),
            b.get("max_comprati_per_turno").getAsInt(),
            b.get("max_scorte_comprate_per_turno").getAsInt(),
            b.get("soglia_base_sicura").getAsDouble()),
        v(o, "nazione_italia").getAsString(),
        strings(v(o, "italia_livello1").getAsJsonArray()),
        strings(v(o, "italia_livello2").getAsJsonArray()),
        v(o, "porto_italia").getAsString(),
        strings(v(o, "basi_africa_italia").getAsJsonArray()),
        strings(v(o, "tipi_flotta_italia").getAsJsonArray()),
        v(o, "gibilterra").getAsString(),
        strings(v(o, "bersagli_africa").getAsJsonArray()),
        strings(v(o, "bersagli_francia").getAsJsonArray()),
        strings(v(o, "nord_africa").getAsJsonArray()),
        v(o, "movimento_veloci").getAsInt(),
        v(o, "fabbrica_sudafrica").getAsString(),
        strings(v(o, "tipi_sudafrica").getAsJsonArray()),
        strings(v(o, "neutrali_est").getAsJsonArray()),
        strings(v(o, "passaggi_sud").getAsJsonArray()),
        v(o, "distanza_sbarramento").getAsInt(),
        Map.copyOf(pu),
        List.copyOf(objectives));
  }

  private static JsonElement v(final JsonObject o, final String key) {
    final JsonObject x = o.getAsJsonObject(key);
    if (x == null) {
      throw new IllegalStateException("manca impegno.r4." + key);
    }
    return x.get("valore");
  }

  private static List<String> strings(final JsonArray a) {
    final List<String> out = new ArrayList<>();
    a.forEach(e -> out.add(e.getAsString()));
    return List.copyOf(out);
  }
}
