package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Step R3a: the register of the commitment's decisions, one JSON line per decision in the game's
 * diary folder ({@value #FILE}): id, round, nation, type, the alternatives with their numbers, the
 * choice, the reason (a code from {@link #MOTIVI} and a sentence), the parameters used. A decision
 * that can be checked later gets an {@code esito} line with the same id.
 *
 * <p>The last rows of a game are also kept in memory (tests; the diary may be off).
 */
public final class RegistroDecisioni {
  private RegistroDecisioni() {}

  public static final String FILE = "impegno-decisioni.jsonl";

  /** The decision types of step R3a. */
  public static final Set<String> TIPI =
      Set.of(
          "teatro",
          "bersaglio_sbarco",
          "via_libera_o_attesa",
          "sbarco",
          "rinforzo_testa_di_ponte",
          "cambio_base",
          "acquisto_programma",
          "tetto_trasporti",
          "spola",
          "campagna_africa",
          "campagna_testa_di_ponte",
          "unita_trattenute",
          // step R4
          "obiettivo_assedio",
          "rinforzo_fabbrica",
          "bombardamento",
          "italia_africa",
          "gibilterra_asse",
          "snodo_usa",
          "africa_sudafrica",
          "neutrali_est",
          "sbarramento_sud");

  /** The closed list of reason codes. */
  public static final Set<String> MOTIVI =
      Set.of(
          // teatro
          "PRIMA_SCELTA",
          "UNICO_APERTO",
          "PUNTEGGIO_MAGGIORE",
          "NESSUNO_APERTO",
          "INERZIA_ROUND",
          "ONDATA_IN_MARE",
          "INVARIATO",
          // via libera, bersaglio, sbarco
          "VIA_LIBERA",
          "MANCA_VITTORIA",
          "MANCA_TENUTA",
          "MANCANO_VITTORIA_E_TENUTA",
          "NESSUN_CARICO",
          "NESSUN_BERSAGLIO",
          "NON_IN_GUERRA",
          "VALORE_MAGGIORE",
          "ESEGUITO",
          "NON_ESEGUITO",
          // Regno Unito
          "RINFORZO",
          "COMPLETAMENTO",
          "SOLO_UK",
          "NIENTE",
          // base
          "TESTA_DI_PONTE_TENUTA",
          "TESTA_DI_PONTE_PERSA",
          "CAMBIO_TEATRO",
          // programmi, tetto, spola, campagne, trattenute
          "CARICO_BILANCIATO",
          "TRASPORTO_FERMO_VUOTO",
          "NESSUN_FERMO_VUOTO",
          "SPOLA",
          "OBIETTIVO_SCELTO",
          "PREPARAZIONE",
          // step R4
          "FABBRICHE_PRIMA",
          "MOSCA_FABBRICHE_PRESE",
          "MOSCA_70",
          "GUADAGNO_MAGGIORE",
          "NESSUN_CANDIDATO",
          "FABBRICA_CATTURATA_VICINA",
          "NESSUNA_FABBRICA_CATTURATA",
          "BOMBARDA",
          "NON_RAGGIUNGIBILE",
          "DANNO_MASSIMO",
          "NESSUN_BOMBARDIERE",
          "COMPRA_BOMBARDIERI",
          "AFRICA_PRIMA",
          "EST_EGITTO",
          "SPOLA_AFRICA",
          "FLOTTA",
          "AFRICA",
          "FRANCIA",
          "MISTO",
          "GIBILTERRA",
          "REGNO_UNITO",
          "COMPRA_SUDAFRICA",
          "SUDAFRICA_CHIUSO",
          "PRESO",
          "NON_PRESO",
          "PRESIDIO",
          "NESSUNA_MINACCIA");

  private static final int MAX_MEMORIA = 5000;
  private static final Map<GameState, List<JsonObject>> MEMORIA =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** A new id "R<round>-<nation>-<type>-<n>" (sequence kept in the alliance's state). */
  static String nuovoId(final JsonObject st, final int round, final String nation, final String t) {
    final int n = st.has("seq") ? st.get("seq").getAsInt() + 1 : 1;
    st.addProperty("seq", n);
    return "R" + round + "-" + nation + "-" + t + "-" + n;
  }

  /** A decision row. */
  static JsonObject decisione(
      final String id,
      final int round,
      final String nation,
      final String tipo,
      final JsonArray alternative,
      final String scelta,
      final String codice,
      final String frase,
      final JsonObject parametri) {
    if (!TIPI.contains(tipo) || !MOTIVI.contains(codice)) {
      throw new IllegalArgumentException("tipo o motivo fuori elenco: " + tipo + " / " + codice);
    }
    final JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("riga", "decisione");
    o.addProperty("round", round);
    o.addProperty("nazione", nation);
    o.addProperty("tipo", tipo);
    o.add("alternative", alternative);
    o.addProperty("scelta", scelta);
    final JsonObject m = new JsonObject();
    m.addProperty("codice", codice);
    m.addProperty("frase", frase);
    o.add("motivo", m);
    o.add("parametri", parametri);
    return o;
  }

  /** The outcome row of decision {@code id}. */
  static JsonObject esito(
      final String id,
      final int round,
      final String nation,
      final String tipo,
      final JsonObject x) {
    final JsonObject o = new JsonObject();
    o.addProperty("id", id);
    o.addProperty("riga", "esito");
    o.addProperty("round", round);
    o.addProperty("nazione", nation);
    o.addProperty("tipo", tipo);
    o.add("esito", x);
    return o;
  }

  /** Appends {@code row} to the register (diary file and memory). Never throws. */
  static void scrivi(final GameState data, final JsonObject row) {
    final List<JsonObject> rows = MEMORIA.computeIfAbsent(data, k -> new ArrayList<>());
    synchronized (rows) {
      rows.add(row);
      if (rows.size() > MAX_MEMORIA) {
        rows.remove(0);
      }
    }
    try {
      DiaryRecorder.cartella(data)
          .ifPresent(
              folder -> {
                try {
                  Files.writeString(
                      folder.resolve(FILE),
                      row + "\n",
                      StandardCharsets.UTF_8,
                      StandardOpenOption.CREATE,
                      StandardOpenOption.APPEND);
                } catch (final IOException e) {
                  ProLogger.warn("[Pro+] Registro delle decisioni non scritto: " + e);
                }
              });
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Registro delle decisioni: " + e);
    }
  }

  /** The rows of the game kept in memory (the last {@value #MAX_MEMORIA}). */
  public static List<JsonObject> righe(final GameState data) {
    final List<JsonObject> rows = MEMORIA.get(data);
    if (rows == null) {
      return List.of();
    }
    synchronized (rows) {
      return List.copyOf(rows);
    }
  }
}
