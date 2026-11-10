package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.experimental.UtilityClass;

/**
 * Step C2 (G): tracking of the units an offensive or landing of the strategist counts for a later
 * step. At the launch their ids are saved ({@code unita_contate}); at the start of each turn of a
 * strategist's nation each one is located: on the map where it was, moved closer to the target,
 * moved away by Hard, held by another operation, at its owner's capital (the capital's guard), or
 * lost. When the engine's estimate falls by {@value #CALO} points or more from the launch the cause
 * is written ({@code cali}): counted units gone and why, defenders and counterattack then and now.
 */
@UtilityClass
final class Tracciamento {

  /** Fall of the success (points) whose cause is written. */
  static final double CALO = 30;

  /** At the launch: the counted units with where they are (registry {@code unita_contate}). */
  static void avvio(final Registro reg, final Collection<Unit> counted, final GameMap map) {
    final Map<UUID, Territory> where = luoghi(map);
    final JsonArray a = new JsonArray();
    for (final Unit u : counted) {
      final JsonObject x = new JsonObject();
      x.addProperty("id", u.getId().toString());
      x.addProperty("tipo", u.getType().getName());
      x.addProperty("nazione", u.getOwner().getName());
      final Territory t = where.get(u.getId());
      x.addProperty("luogo", t == null ? "" : t.getName());
      a.add(x);
    }
    reg.json().add("unita_contate", a);
  }

  /** Start of the turn of {@code player} (purchase): one tracking entry per operation. */
  static void aggiorna(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return;
    }
    final GameProperties props = data.getProperties();
    final GameMap map = data.getMap();
    final Map<UUID, Territory> where = luoghi(map);
    final int round = data.getSequence().getRound();
    for (final Definizione d : OperazioneCongiunta.dinamiche(props, alliance.get())) {
      final Registro reg = Registro.leggi(props, d.id());
      if (reg.isNuovo() || reg.stato().isFinale() || !reg.json().has("unita_contate")) {
        continue;
      }
      final Territory target = map.getTerritoryOrNull(d.bersaglio());
      if (target == null) {
        continue;
      }
      final Set<Unit> other = StimaUnica.riservate(data, alliance.get(), d.id());
      final Set<UUID> otherIds = new LinkedHashSet<>();
      other.forEach(u -> otherIds.add(u.getId()));
      final JsonObject last =
          reg.json().has("tracciamento_posizioni")
              ? reg.json().getAsJsonObject("tracciamento_posizioni")
              : new JsonObject();
      final JsonObject now = new JsonObject();
      final Map<String, Integer> counts = new LinkedHashMap<>();
      final List<String> details = new ArrayList<>();
      for (final JsonElement e : reg.json().getAsJsonArray("unita_contate")) {
        final JsonObject x = e.getAsJsonObject();
        final String id = x.get("id").getAsString();
        final String before =
            last.has(id) ? last.get(id).getAsString() : x.get("luogo").getAsString();
        if (before.isEmpty()) {
          continue; // already lost
        }
        final Territory at = where.get(UUID.fromString(id));
        final String state = stato(map, target, at, before, otherIds.contains(UUID.fromString(id)));
        counts.merge(state, 1, Integer::sum);
        now.addProperty(id, at == null ? "" : at.getName());
        if (!"ferma".equals(state)) {
          details.add(
              x.get("tipo").getAsString()
                  + " "
                  + before
                  + (at == null ? "" : " -> " + at.getName())
                  + ": "
                  + state);
        }
      }
      reg.json().add("tracciamento_posizioni", now);
      final JsonObject entry = new JsonObject();
      entry.addProperty("round", round);
      entry.addProperty("turno", player.getName());
      counts.forEach(entry::addProperty);
      entry.add("dettagli", StrategaCandidati.array(details.stream().limit(12).toList()));
      final JsonArray all =
          reg.json().has("tracciamento")
              ? reg.json().getAsJsonArray("tracciamento")
              : new JsonArray();
      all.add(entry);
      reg.json().add("tracciamento", all);
      reg.salva(props);
    }
  }

  /** Where a tracked unit is now compared with its last position. */
  static String stato(
      final GameMap map,
      final Territory target,
      final Territory at,
      final String before,
      final boolean otherOperation) {
    if (at == null) {
      return "persa";
    }
    if (otherOperation) {
      return "altra_operazione";
    }
    if (at.getName().equals(before)) {
      return capitale(at) ? "guardia_capitale" : "ferma";
    }
    final Territory from = map.getTerritoryOrNull(before);
    final int was = from == null ? -1 : map.getDistance(from, target);
    final int is = map.getDistance(at, target);
    if (capitale(at)) {
      return "guardia_capitale";
    }
    return was >= 0 && is >= 0 && is < was ? "avvicinata" : "spostata_da_hard";
  }

  private static boolean capitale(final Territory t) {
    return !t.isWater()
        && TerritoryAttachment.get(t)
            .map(
                a ->
                    a.isCapital()
                        && a.getCapital().filter(t.getOwner().getName()::equals).isPresent())
            .orElse(false);
  }

  /**
   * The engine's estimate {@code now} of an operation in progress: if its success fell by {@value
   * #CALO} points or more from the launch, the cause (once per round) in {@code cali}.
   */
  static Optional<String> calo(
      final GameState data,
      final Registro reg,
      final StimaUnica.Stima now,
      final JsonObject nowJson) {
    if (!reg.json().has("stima_avvio") || !reg.json().get("stima_avvio").isJsonObject()) {
      return Optional.empty();
    }
    final JsonObject launch = reg.json().getAsJsonObject("stima_avvio");
    final double before = StimaUnica.successo(launch);
    final double after = StimaUnica.successo(nowJson);
    final int round = data.getSequence().getRound();
    if (before < 0 || after < 0 || before - after < CALO) {
      return Optional.empty();
    }
    final JsonArray cali =
        reg.json().has("cali") ? reg.json().getAsJsonArray("cali") : new JsonArray();
    for (final JsonElement e : cali) {
      if (e.getAsJsonObject().get("round").getAsInt() == round) {
        return Optional.empty();
      }
    }
    final Set<UUID> stillCounted = new LinkedHashSet<>();
    now.tappa1().forEach(u -> stillCounted.add(u.getId()));
    now.tappa2().forEach(u -> stillCounted.add(u.getId()));
    final Map<UUID, Territory> where = luoghi(data.getMap());
    final Map<String, Integer> gone = new LinkedHashMap<>();
    final Map<String, String> lastState = new HashMap<>();
    if (reg.json().has("tracciamento")) {
      for (final JsonElement e : reg.json().getAsJsonArray("tracciamento")) {
        for (final JsonElement d : e.getAsJsonObject().getAsJsonArray("dettagli")) {
          final String text = d.getAsString();
          final int colon = text.lastIndexOf(": ");
          if (colon > 0) {
            lastState.put(text.substring(0, text.indexOf(' ')), text.substring(colon + 2));
          }
        }
      }
    }
    int counted = 0;
    if (reg.json().has("unita_contate")) {
      for (final JsonElement e : reg.json().getAsJsonArray("unita_contate")) {
        final JsonObject x = e.getAsJsonObject();
        counted++;
        final UUID id = UUID.fromString(x.get("id").getAsString());
        if (stillCounted.contains(id)) {
          continue;
        }
        final Territory at = where.get(id);
        final String why;
        if (at == null) {
          why = "persa";
        } else if (capitale(at)) {
          why = "guardia_capitale";
        } else if (!at.getName().equals(x.get("luogo").getAsString())) {
          why = "spostata (da Hard o da un'altra operazione)";
        } else {
          why = "ancora li' ma non piu' contata (percorso o portata)";
        }
        gone.merge(why, 1, Integer::sum);
      }
    }
    final JsonObject c = new JsonObject();
    c.addProperty("round", round);
    c.addProperty("successo_avvio_pct", Math.round(before));
    c.addProperty("successo_ora_pct", Math.round(after));
    c.addProperty("presa_avvio_pct", pct(launch, "presa_pct"));
    c.addProperty("presa_ora_pct", pct(nowJson, "presa_pct"));
    c.addProperty("tenuta_avvio_pct", pct(launch, "tenuta_pct"));
    c.addProperty("tenuta_ora_pct", pct(nowJson, "tenuta_pct"));
    c.addProperty("unita_contate_avvio", counted);
    c.addProperty("unita_contate_ora", stillCounted.size());
    final JsonObject g = new JsonObject();
    gone.forEach(g::addProperty);
    c.add("unita_non_piu_contate", g);
    c.addProperty("tuv_difensori_avvio", pct(launch, "tuv_difensori"));
    c.addProperty("tuv_difensori_ora", Math.round(now.tuvDifensori()));
    c.addProperty("tuv_contrattacco_avvio", pct(launch, "contrattacco_contato_TUV"));
    c.addProperty("tuv_contrattacco_ora", Math.round(now.tuvContrattacco()));
    final String text =
        String.format(
            Locale.ROOT,
            "calo %d -> %d punti: presa %d -> %d%%, tenuta %d -> %d%%; unita' contate %d -> %d%s;"
                + " difensori %d -> %d TUV, contrattacco %d -> %d TUV",
            Math.round(before),
            Math.round(after),
            c.get("presa_avvio_pct").getAsLong(),
            c.get("presa_ora_pct").getAsLong(),
            c.get("tenuta_avvio_pct").getAsLong(),
            c.get("tenuta_ora_pct").getAsLong(),
            counted,
            stillCounted.size(),
            gone.isEmpty() ? "" : " (" + gone + ")",
            c.get("tuv_difensori_avvio").getAsLong(),
            Math.round(now.tuvDifensori()),
            c.get("tuv_contrattacco_avvio").getAsLong(),
            Math.round(now.tuvContrattacco()));
    c.addProperty("causa", causa(c, gone));
    c.addProperty("testo", text);
    cali.add(c);
    reg.json().add("cali", cali);
    return Optional.of(text + " - causa: " + c.get("causa").getAsString());
  }

  /** The main cause of a fall: the counted units gone, or what changed in the estimate. */
  static String causa(final JsonObject c, final Map<String, Integer> gone) {
    final long takeFall = c.get("presa_avvio_pct").getAsLong() - c.get("presa_ora_pct").getAsLong();
    final long holdFall =
        c.get("tenuta_avvio_pct").getAsLong() - c.get("tenuta_ora_pct").getAsLong();
    if (takeFall >= holdFall) {
      if (!gone.isEmpty()) {
        final String top =
            gone.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        return "presa: unita' contate non piu' disponibili, soprattutto " + top;
      }
      return c.get("tuv_difensori_ora").getAsLong() > c.get("tuv_difensori_avvio").getAsLong()
          ? "presa: difensori nemici aumentati"
          : "presa: stima delle tappe (stesse unita')";
    }
    return c.get("tuv_contrattacco_ora").getAsLong() > c.get("tuv_contrattacco_avvio").getAsLong()
        ? "tenuta: contrattacco nemico aumentato"
        : "tenuta: presidio stimato piu' debole";
  }

  private static long pct(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? Math.round(o.get(key).getAsDouble()) : 0;
  }

  private static Map<UUID, Territory> luoghi(final GameMap map) {
    final Map<UUID, Territory> where = new HashMap<>();
    for (final Territory t : map.getTerritories()) {
      for (final Unit u : t.getUnits()) {
        where.put(u.getId(), t);
      }
    }
    return where;
  }
}
