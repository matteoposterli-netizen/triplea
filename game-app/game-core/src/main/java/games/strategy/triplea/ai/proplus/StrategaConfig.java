package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Step STRATEGA LLM: the engine's limits and the values of the general operations, from {@value
 * #RESOURCE}. Reasonable hand-picked values, not tuned. No knob of Hard is here.
 */
public record StrategaConfig(
    int maxOperazioni,
    double quotaPuMaxPct,
    int maxCandidati,
    int orizzonteRound,
    int durataOperazioneRound,
    double tettoPerditaDefault,
    double tettoPerditaMax,
    int tenutaRoundDefault,
    int durataTenutaMax,
    double sogliaRinforzi,
    double sogliaRitirata,
    int aereiCopertura,
    double quotaAcquistiNemici,
    int riserveTerra,
    int trasportiSbarco,
    int maxRinvii,
    double sogliaChiusura,
    double tolleranzaDifensori,
    double valoreVc,
    double valoreCapitale,
    double valoreObiettivoNazionale,
    double minacciaTenuta50Max,
    double valoreMinimoTenuta,
    List<String> tipiTerra,
    List<String> tipiTrasporto,
    List<String> nazioniSenzaRuoli,
    String modello,
    double budgetDefaultUsd,
    int tettoChiamateStrategiche) {

  static final String RESOURCE = "proplus/strategia/stratega-llm.json";

  private static @Nullable StrategaConfig cache;

  public static synchronized StrategaConfig get() {
    if (cache == null) {
      try (InputStream in = StrategaConfig.class.getClassLoader().getResourceAsStream(RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException("manca " + RESOURCE);
        }
        cache =
            parse(
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject());
      } catch (final IOException e) {
        throw new IllegalStateException("File dello stratega non valido: " + e, e);
      }
    }
    return cache;
  }

  static StrategaConfig parse(final JsonObject o) {
    return new StrategaConfig(
        o.get("max_operazioni_per_alleanza").getAsInt(),
        o.get("quota_pu_max_pct").getAsDouble(),
        o.get("max_candidati").getAsInt(),
        o.get("orizzonte_round").getAsInt(),
        o.get("durata_operazione_round").getAsInt(),
        o.get("tetto_perdita_default").getAsDouble(),
        o.get("tetto_perdita_max").getAsDouble(),
        o.get("tenuta_round_default").getAsInt(),
        o.get("durata_tenuta_max").getAsInt(),
        o.get("soglia_rinforzi").getAsDouble(),
        o.get("soglia_ritirata").getAsDouble(),
        o.get("aerei_copertura").getAsInt(),
        o.get("quota_acquisti_nemici").getAsDouble(),
        o.get("riserve_terra").getAsInt(),
        o.get("trasporti_sbarco").getAsInt(),
        o.get("max_rinvii").getAsInt(),
        o.get("soglia_chiusura").getAsDouble(),
        o.get("tolleranza_difensori").getAsDouble(),
        o.get("valore_vc").getAsDouble(),
        o.get("valore_capitale").getAsDouble(),
        o.get("valore_obiettivo_nazionale").getAsDouble(),
        o.get("minaccia_tenuta_50_max").getAsDouble(),
        o.get("valore_minimo_tenuta").getAsDouble(),
        strings(o, "tipi_terra"),
        strings(o, "tipi_trasporto"),
        strings(o, "nazioni_senza_ruoli"),
        o.get("modello").getAsString(),
        o.get("budget_default_usd").getAsDouble(),
        o.get("tetto_chiamate_strategiche").getAsInt());
  }

  private static List<String> strings(final JsonObject o, final String key) {
    final List<String> list = new ArrayList<>();
    if (o.has(key)) {
      o.getAsJsonArray(key).forEach(e -> list.add(e.getAsString()));
    }
    return List.copyOf(list);
  }
}
