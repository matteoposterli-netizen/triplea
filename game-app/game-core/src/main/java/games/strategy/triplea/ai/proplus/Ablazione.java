package games.strategy.triplea.ai.proplus;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Ablation switches (step ABLAZIONE): turn off one Pro+ component at a time, to measure what each
 * one is worth against "Hard (AI)". Saved in the game per alliance ({@code "Pro+ ablazione:
 * <alliance>"} = comma separated ids of the components turned off, or {@value #NUDO} = all of
 * them). No property = every component on = the behavior before this step.
 *
 * <p>With every component off ("Pro+ nudo") Pro+ must play as Hard: that is the check that the
 * hooks of the Pro classes add no bug.
 */
@UtilityClass
public final class Ablazione {
  public static final String PROPERTY_PREFIX = "Pro+ ablazione: ";

  /** Every component off. */
  public static final String NUDO = "nudo";

  /** A Pro+ component that can be turned off. */
  public enum Componente {
    /** The general staff: plans, phases, objectives, war orders, postures (no plan at all). */
    PIANO("piano"),
    /** Posture RALLENTAMENTO (an even race: the enemy plan's critical points as objectives). */
    RALLENTAMENTO("rallentamento"),
    /** Delay tools (land/naval blocks, fighting retreat, transport hunt), whatever the decider. */
    STRUMENTI_RITARDO("strumenti_ritardo"),
    /** The plan's risk modifier per front (every front at the alliance's slider level). */
    RISCHIO_FRONTE("rischio_fronte"),
    /** The plan's fleet objectives ({@code flotta}: sea zones with the enemy nation's ships). */
    BERSAGLI_FLOTTA("bersagli_flotta"),
    /** Transports after a landing ({@link TrasportiSbarco}): Hard's "transports exposed" rule. */
    TRASPORTI_SBARCO("trasporti_sbarco"),
    /** Safe stops and escort of the amphibious operations: every zone counts as safe. */
    SOSTE_SICURE("soste_sicure"),
    /** Guard rail of 30 TUV of transports lost per alliance per round ({@link TettoTrasporti}). */
    TETTO_TRASPORTI("tetto_trasporti"),
    /** The plan's purchase orders (mix, mandatory purchases, operations' purchases). */
    LOGISTICA_ACQUISTI("logistica_acquisti"),
    /** The plan's staging (gathering bonus of the non-combat move, planes). */
    AMMASSAMENTO("ammassamento"),
    /** The plan's amphibious operations (Hard moves the transports). */
    OPERAZIONI_ANFIBIE("operazioni_anfibie"),
    /** Two-step bridgehead and the same target of a combined operation. */
    TESTA_DI_PONTE("testa_di_ponte"),
    /** Victory cities in the territory values and in the attack priority. */
    VICTORY_CITY("victory_city"),
    /** Attack thresholds of the risk slider (Hard's 90/65 with normal dice). */
    SOGLIE_RISCHIO("soglie_rischio"),
    /** Step 2d: attacks Hard drops as "not worth it" kept by the risk level / objective rule. */
    NON_CONVIENE("non_conviene"),
    /** Garrison of the capital (step 3b-bis). */
    GUARNIGIONE_CAPITALE("guarnigione_capitale"),
    /** Only the enemies that can really attack before the next turn (step 3c). */
    MINACCIA_REALE("minaccia_reale"),
    /** Landings dropped when scrambled air sinks the transports (Hard Beta's check). */
    SCRAMBLE_SBARCHI("scramble_sbarchi"),
    /** Amphibious strafes dropped below the target's minimum win% (step 3c-bis). */
    STRAFE_ANFIBIO("strafe_anfibio");

    private final String id;

    Componente(final String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    public static Optional<Componente> of(final String id) {
      return Arrays.stream(values()).filter(c -> c.id.equals(id)).findFirst();
    }
  }

  private static final Map<String, Set<Componente>> PARSED = new ConcurrentHashMap<>();

  /**
   * The components turned off by {@code text} ({@value #NUDO} = all); unknown ids are an error.
   *
   * @throws IllegalArgumentException with the unknown id
   */
  public static Set<Componente> parse(final String text) {
    final Set<Componente> off = EnumSet.noneOf(Componente.class);
    for (final String raw : text.split(",")) {
      final String id = raw.trim().toLowerCase(Locale.ROOT);
      if (id.isEmpty()) {
        continue;
      }
      if (id.equals(NUDO)) {
        off.addAll(EnumSet.allOf(Componente.class));
        continue;
      }
      off.add(
          Componente.of(id)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "componente sconosciuto: "
                              + id
                              + " (validi: "
                              + NUDO
                              + ", "
                              + Arrays.stream(Componente.values())
                                  .map(Componente::id)
                                  .collect(Collectors.joining(", "))
                              + ")")));
    }
    return off;
  }

  /** The components turned off for {@code alliance} (none without the property). */
  public static Set<Componente> spenti(final GameProperties props, final String alliance) {
    if (!(props.get(PROPERTY_PREFIX + alliance) instanceof String text) || text.isBlank()) {
      return Set.of();
    }
    return PARSED.computeIfAbsent(
        text,
        t -> {
          try {
            return Set.copyOf(parse(t));
          } catch (final IllegalArgumentException e) {
            // a game saved with an unknown id: the known ones only
            return Arrays.stream(t.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .flatMap(
                    s ->
                        s.equals(NUDO)
                            ? Arrays.stream(Componente.values())
                            : Componente.of(s).stream())
                .collect(Collectors.toUnmodifiableSet());
          }
        });
  }

  /** Writes the components turned off for {@code alliance} ({@code text} empty = none). */
  public static void set(final GameProperties props, final String alliance, final String text) {
    parse(text);
    final String key = PROPERTY_PREFIX + alliance;
    // unbind first: set() appends the key to the property ordering each time
    props.set(key, (Serializable) null);
    if (!text.isBlank()) {
      props.set(key, text.trim());
    }
  }

  /** Whether {@code c} is on for {@code alliance}. */
  public static boolean attivo(
      final GameProperties props, final String alliance, final Componente c) {
    return !spenti(props, alliance).contains(c);
  }

  /** Whether {@code c} is on for {@code player} (a player outside the alliances: always). */
  public static boolean attivo(
      final GameState data, final @Nullable GamePlayer player, final Componente c) {
    if (player == null) {
      return true;
    }
    return ProPlusSettings.allianceOf(data, player)
        .map(a -> attivo(data.getProperties(), a, c))
        .orElse(true);
  }

  /** Whether {@code c} is on for {@code proData}'s player (before initialize: always). */
  public static boolean attivo(final @Nullable ProData proData, final Componente c) {
    if (proData == null || proData.getData() == null || proData.getPlayer() == null) {
      return true;
    }
    return attivo(proData.getData(), proData.getPlayer(), c);
  }
}
