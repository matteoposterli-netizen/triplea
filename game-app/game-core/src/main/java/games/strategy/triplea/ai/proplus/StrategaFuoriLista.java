package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Candidato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Tipo;
import games.strategy.triplea.delegate.Matches;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Step STRATEGA LLM: the one operation outside the candidates the strategist may propose per round,
 * checked by the engine (target, type, nations, war, coast) and, if valid, turned into a candidate.
 */
@UtilityClass
final class StrategaFuoriLista {

  /** Why the proposal is refused (empty = valid). */
  static Optional<String> valida(
      final GameData data, final String alliance, final Set<String> nations, final JsonObject f) {
    final Optional<Tipo> tipo = Tipo.of(f.get("tipo").getAsString());
    if (tipo.isEmpty()) {
      return Optional.of("tipo sconosciuto: " + f.get("tipo").getAsString());
    }
    if (!tipo.get().conquista()
        && !StrategaInterruttori.TENUTE.acceso(data.getProperties(), alliance)) {
      return Optional.of("tenute e rinforzi spenti per " + alliance + " (interruttore 'tenute')");
    }
    final Territory t = data.getMap().getTerritoryOrNull(f.get("bersaglio").getAsString());
    if (t == null || t.isWater()) {
      return Optional.of("territorio di terra sconosciuto: " + f.get("bersaglio").getAsString());
    }
    final String a = f.get("apre").getAsString();
    final String c = f.get("chiude").getAsString().isEmpty() ? a : f.get("chiude").getAsString();
    final List<String> roles = StrategaConfig.get().nazioniSenzaRuoli();
    for (final String n : List.of(a, c)) {
      if (!nations.contains(n)) {
        return Optional.of(n + " non e' una nazione dello stratega di " + alliance);
      }
      if (roles.contains(n)) {
        return Optional.of(n + " non puo' avere ruoli nelle operazioni");
      }
    }
    final GamePlayer pa = data.getPlayerList().getPlayerId(a);
    final GamePlayer pc = data.getPlayerList().getPlayerId(c);
    if (pa == null || pc == null) {
      return Optional.of("nazione sconosciuta");
    }
    final Optional<String> ownerAlliance = ProPlusSettings.allianceOf(data, t.getOwner());
    if (ownerAlliance.isEmpty()) {
      return Optional.of(t.getName() + " e' di un neutrale (" + t.getOwner().getName() + ")");
    }
    if (tipo.get().conquista() == ownerAlliance.get().equals(alliance)) {
      return Optional.of(
          t.getName()
              + (tipo.get().conquista() ? " e' gia' dell'alleanza" : " non e' dell'alleanza"));
    }
    if (tipo.get().conquista()) {
      if (a.equals(c)) {
        return Optional.of("un'operazione di conquista richiede due nazioni diverse");
      }
      final List<String> order = StrategaCandidati.ordine(data);
      if (order.indexOf(a) > order.indexOf(c)) {
        return Optional.of(a + " gioca dopo " + c + ": chi apre deve giocare prima nel round");
      }
      if (t.getOwner().isNull()
          || Matches.isTerritoryAllied(pa).test(t)
          || !OperazioniCongiunte.guerraPossibile(data, pa, t.getOwner())
          || !OperazioniCongiunte.guerraPossibile(data, pc, t.getOwner())) {
        // step R1 (A4): war now or possible by the map's rules, for each of the two nations
        return Optional.of(
            t.getName() + " non e' di un nemico in guerra (o che puo' entrarci) con " + a + " e " + c);
      }
      if (tipo.get() == Tipo.SBARCO && zona(data, t, pa).isEmpty()) {
        return Optional.of(
            t.getName()
                + ": nessuna zona di mare confinante con il bersaglio e con una terra dell'alleanza");
      }
    } else {
      if (!Matches.isTerritoryAllied(pa).test(t) || t.getOwner().isNull()) {
        return Optional.of(t.getName() + " non e' dell'alleanza");
      }
      if (tipo.get() == Tipo.RINFORZO && t.getOwner().getName().equals(a)) {
        return Optional.of("un rinforzo va al territorio di un alleato, non di " + a);
      }
    }
    return Optional.empty();
  }

  /** The proposal as a candidate (after {@link #valida}). */
  static Candidato candidato(final GameData data, final JsonObject f, final StrategaCandidati gen) {
    final Tipo tipo = Tipo.of(f.get("tipo").getAsString()).orElseThrow();
    final Territory t = data.getMap().getTerritoryOrNull(f.get("bersaglio").getAsString());
    final String a = f.get("apre").getAsString();
    final String c = f.get("chiude").getAsString().isEmpty() ? a : f.get("chiude").getAsString();
    final GamePlayer pa = data.getPlayerList().getPlayerId(a);
    String gather = "";
    String zone = "";
    if (tipo == Tipo.SBARCO) {
      final Territory[] z = zona(data, t, pa).orElseThrow();
      zone = z[0].getName();
      gather = z[1].getName();
    }
    final int round = data.getSequence().getRound();
    final JsonObject j = new JsonObject();
    j.addProperty("tipo", tipo.id());
    j.addProperty("bersaglio", t.getName());
    j.addProperty("fuori_lista", true);
    return new Candidato(
        "F",
        tipo,
        t.getName(),
        a,
        c,
        tipo.conquista() ? round + 1 : round,
        gather,
        zone,
        List.of(),
        gen.valore(t),
        0,
        j);
  }

  /** A sea zone next to {@code t} and to a land of the alliance, and that land. */
  private static Optional<Territory[]> zona(
      final GameData data, final Territory t, final GamePlayer p) {
    for (final Territory z : data.getMap().getNeighbors(t, Matches.territoryIsWater())) {
      for (final Territory land : data.getMap().getNeighbors(z, Matches.territoryIsLand())) {
        if (!land.equals(t)
            && !land.getOwner().isNull()
            && Matches.isTerritoryAllied(p).test(land)) {
          return Optional.of(new Territory[] {z, land});
        }
      }
    }
    return Optional.empty();
  }
}
