package games.strategy.triplea.ai.proplus.diario;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Italian names for the diary text: nations, alliances, units, reasons. Unknown names stay. */
@UtilityClass
final class Italian {
  private static final Map<String, String> NATIONS =
      Map.ofEntries(
          Map.entry("Germans", "Germania"),
          Map.entry("Italians", "Italia"),
          Map.entry("Japanese", "Giappone"),
          Map.entry("Russians", "URSS"),
          Map.entry("British", "Regno Unito"),
          Map.entry("UK_Pacific", "Regno Unito (Pacifico)"),
          Map.entry("Americans", "USA"),
          Map.entry("French", "Francia"),
          Map.entry("Chinese", "Cina"),
          Map.entry("ANZAC", "ANZAC"),
          Map.entry("Dutch", "Olanda"),
          Map.entry("Neutral_True", "Neutrali puri"),
          Map.entry("Neutral_Axis", "Neutrali filo-Asse"),
          Map.entry("Neutral_Allies", "Neutrali filo-Alleati"));

  private static final Map<String, String> ALLIANCES =
      Map.of("Axis", "Asse", "Allies", "Alleati", "Neutral", "Neutrali");

  /** Unit type -> {singular, plural}. */
  private static final Map<String, String[]> UNITS =
      Map.ofEntries(
          Map.entry("infantry", new String[] {"fanteria", "fanterie"}),
          Map.entry("mech_infantry", new String[] {"fanteria meccanizzata", "fanterie mecc."}),
          Map.entry("artillery", new String[] {"artiglieria", "artiglierie"}),
          Map.entry("armour", new String[] {"carro", "carri"}),
          Map.entry("armor", new String[] {"carro", "carri"}),
          Map.entry("tank", new String[] {"carro", "carri"}),
          Map.entry("fighter", new String[] {"caccia", "caccia"}),
          Map.entry("tactical_bomber", new String[] {"bomb. tattico", "bomb. tattici"}),
          Map.entry("bomber", new String[] {"bomb. strategico", "bomb. strategici"}),
          Map.entry("transport", new String[] {"trasporto", "trasporti"}),
          Map.entry("submarine", new String[] {"sottomarino", "sottomarini"}),
          Map.entry("destroyer", new String[] {"cacciatorpediniere", "cacciatorpediniere"}),
          Map.entry("cruiser", new String[] {"incrociatore", "incrociatori"}),
          Map.entry("carrier", new String[] {"portaerei", "portaerei"}),
          Map.entry("battleship", new String[] {"corazzata", "corazzate"}),
          Map.entry("aaGun", new String[] {"contraerea", "contraeree"}),
          Map.entry("factory_major", new String[] {"fabbrica maggiore", "fabbriche maggiori"}),
          Map.entry("factory_minor", new String[] {"fabbrica minore", "fabbriche minori"}),
          Map.entry("factory_upgrade", new String[] {"potenz. fabbrica", "potenz. fabbrica"}),
          Map.entry("harbour", new String[] {"base navale", "basi navali"}),
          Map.entry("airfield", new String[] {"base aerea", "basi aeree"}));

  private static final Map<String, String> REASONS =
      Map.ofEntries(
          Map.entry("below_minimum", "win% massima sotto la soglia minima"),
          Map.entry(
              "strafe_not_enough",
              "attacco di logoramento per l'alleato insufficiente (win% sotto la desiderata"
                  + " o nessuna unita' di terra superstite)"),
          Map.entry("negative_value", "valore d'attacco nullo o negativo"),
          Map.entry(
              "defensive_stance",
              "capitale minacciata (posizione difensiva): valore basso vicino alla capitale"),
          Map.entry("convoy_cant_hold", "zona convoglio vuota che non si puo' tenere"),
          Map.entry(
              "neutral_cant_hold",
              "neutrale che non si puo' tenere (nessuna superiorita' schiacciante)"),
          Map.entry("amphib_low_value", "sbarco di basso valore che non si puo' tenere"),
          Map.entry(
              "neutral_exposes_attackers",
              "neutrale: gli attaccanti lascerebbero scoperti territori nemici confinanti"),
          Map.entry("no_transports_left", "trasporti gia' tutti usati"),
          Map.entry(
              "not_enough_units",
              "unita' insufficienti dopo aver servito i bersagli con priorita' piu' alta"),
          Map.entry(
              "units_not_worth",
              "con le unita' assegnate non conviene (vince troppo di rado, non tenibile o valore"
                  + " negativo)"),
          Map.entry("capital_defense", "unita' tenute a difesa della capitale"),
          Map.entry("stage_priority", "scartato nella scelta delle priorita'"),
          Map.entry("stage_selection", "scartato nella scelta dei bersagli"),
          Map.entry("stage_units", "scartato nell'assegnazione delle unita'"),
          Map.entry("stage_capital", "scartato nel controllo della difesa della capitale"),
          Map.entry(
              "final_checks",
              "scartato nelle verifiche finali (trasporti esposti, sbarchi bloccati o senza"
                  + " battaglia navale vinta)"),
          Map.entry("not_executed", "pianificato ma la mossa non e' stata eseguita"));

  private static final Map<String, String> PURCHASE_STEPS =
      Map.ofEntries(
          Map.entry("defend_land", "difesa dei territori minacciati"),
          Map.entry("capital_emergency", "difesa d'emergenza della capitale"),
          Map.entry("aa", "contraerea"),
          Map.entry("land", "unita' di terra per i territori prioritari"),
          Map.entry("defend_sea", "difesa delle zone di mare"),
          Map.entry("factory", "nuova fabbrica"),
          Map.entry("sea", "flotta, trasporti e sbarchi"),
          Map.entry("sea_saving_for_fleet", "flotta (risparmia PU per una flotta)"),
          Map.entry("remaining", "PU avanzati: unita' di maggior valore"),
          Map.entry("upgrade", "PU avanzati: unita' migliori al posto di altre"),
          Map.entry("factory_extra", "PU avanzati: fabbrica"),
          Map.entry("done", "totale"),
          Map.entry("error", "acquisto rifiutato dal gioco"));

  static String nation(final String player) {
    return NATIONS.getOrDefault(player, player);
  }

  static String alliance(final String alliance) {
    return ALLIANCES.getOrDefault(alliance, alliance);
  }

  static String reason(final String code) {
    return REASONS.getOrDefault(code, code);
  }

  static String purchaseStep(final String code) {
    return PURCHASE_STEPS.getOrDefault(code, code);
  }

  static String units(final Map<String, Integer> counts) {
    if (counts.isEmpty()) {
      return "nessuna";
    }
    return counts.entrySet().stream()
        .map(e -> unit(e.getKey(), e.getValue()))
        .collect(Collectors.joining(", "));
  }

  static String unit(final String type, final int count) {
    final String[] names = UNITS.get(type);
    if (names == null) {
      return count + " " + type;
    }
    return count + " " + (Math.abs(count) == 1 ? names[0] : names[1]);
  }

  static String percent(final double value) {
    return String.format(Locale.ROOT, "%.0f%%", value);
  }

  static String number(final double value) {
    return Math.abs(value - Math.rint(value)) < 0.05
        ? String.format(Locale.ROOT, "%.0f", value)
        : String.format(Locale.ROOT, "%.1f", value);
  }
}
