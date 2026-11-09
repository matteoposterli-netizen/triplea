package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProTuning;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Check against Hard (step PROVA DI VALORE): every answer of the Pro+ tuning is compared with the
 * answer of Hard's {@link ProTuning#DEFAULT} to the same question, and the differences counted per
 * round, apart from the answers a joint operation gives ({@link ProPlusTuning#operationAnswer}).
 * With "Pro+ nudo" (every component off) the expected count outside the operation is zero, as the
 * test {@code NudoHardTest} checks for one turn; this counter checks every turn of every game.
 *
 * <p>Never changes an answer: the Pro+ answer is always the one returned. Hard's answer is computed
 * on the same arguments (the defaults are pure functions of them).
 */
public final class ControlloTuning implements InvocationHandler {
  /** Game property (per nation) with the counts per round (JSON, JDK strings only). */
  public static final String PROPERTY_PREFIX = "Pro+ controllo nudo: ";

  /** Examples of differences kept per round. */
  private static final int MAX_ESEMPI = 5;

  private final ProPlusTuning plus;

  /** Round → counts. */
  private final Map<Integer, Conteggio> rounds = new TreeMap<>();

  private int round = 0;

  /** Counts of one round: questions, differences out of the operation, the operation's answers. */
  static final class Conteggio {
    int domande;
    int differenze;
    int operazione;
    final JsonArray esempi = new JsonArray();
  }

  private ControlloTuning(final ProPlusTuning plus) {
    this.plus = plus;
  }

  /** {@code plus} answering through the check. */
  public static ProTuning wrap(final ProPlusTuning plus) {
    return (ProTuning)
        Proxy.newProxyInstance(
            ProTuning.class.getClassLoader(),
            new Class<?>[] {ProTuning.class},
            new ControlloTuning(plus));
  }

  /** The Pro+ tuning inside a check, null if {@code tuning} is not one. */
  static @Nullable ProPlusTuning inner(final @Nullable ProTuning tuning) {
    if (tuning != null
        && Proxy.isProxyClass(tuning.getClass())
        && Proxy.getInvocationHandler(tuning) instanceof ControlloTuning c) {
      return c.plus;
    }
    return null;
  }

  /** The check inside {@code tuning}, if any. */
  public static @Nullable ControlloTuning of(final @Nullable ProTuning tuning) {
    if (tuning != null
        && Proxy.isProxyClass(tuning.getClass())
        && Proxy.getInvocationHandler(tuning) instanceof ControlloTuning c) {
      return c;
    }
    return null;
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return method.invoke(plus, args);
    }
    ProPlusTuning.takeOperationMark();
    final Object mine = call(method, plus, args);
    final boolean operation = ProPlusTuning.takeOperationMark();
    final Object hard = call(method, ProTuning.DEFAULT, args);
    final int r = roundOf(args);
    synchronized (rounds) {
      final Conteggio c = rounds.computeIfAbsent(r, k -> new Conteggio());
      c.domande++;
      if (!Objects.equals(mine, hard)) {
        if (operation) {
          c.operazione++;
        } else {
          c.differenze++;
          if (c.esempi.size() < MAX_ESEMPI) {
            final String text = method.getName() + ": " + mine + " invece di " + hard;
            c.esempi.add(text.length() > 200 ? text.substring(0, 200) + "..." : text);
          }
        }
      }
    }
    return mine;
  }

  /** The round of the question (from a ProData or game argument), else the last one seen. */
  private int roundOf(final @Nullable Object[] args) {
    if (args != null) {
      for (final Object a : args) {
        if (a instanceof ProData p && p.getData() != null) {
          round = p.getData().getSequence().getRound();
          return round;
        }
        if (a instanceof GameState g) {
          round = g.getSequence().getRound();
          return round;
        }
      }
    }
    return round > 0 ? round : plus.ownerRound();
  }

  private static Object call(final Method method, final ProTuning target, final Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (final InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** Totals of every round: questions, differences out of the operation, operation's answers. */
  public record Risultato(int domande, int differenze, int operazione) {}

  public Risultato risultato() {
    return new Risultato(domande(), differenze(), operazione());
  }

  /** Total differences out of the operation's answers, every round. */
  public int differenze() {
    synchronized (rounds) {
      return rounds.values().stream().mapToInt(c -> c.differenze).sum();
    }
  }

  public int domande() {
    synchronized (rounds) {
      return rounds.values().stream().mapToInt(c -> c.domande).sum();
    }
  }

  public int operazione() {
    synchronized (rounds) {
      return rounds.values().stream().mapToInt(c -> c.operazione).sum();
    }
  }

  /**
   * Writes the counts in the game ({@code "Pro+ controllo nudo: <nation>"} = {"R1": {domande,
   * differenze, operazione, esempi}, ...}), read by the autoplay.
   */
  public void salva(final GameProperties props, final String nation) {
    final JsonObject json = new JsonObject();
    final Object before = props.get(PROPERTY_PREFIX + nation);
    if (before instanceof String text && !text.isBlank()) {
      try {
        JsonParser.parseString(text)
            .getAsJsonObject()
            .entrySet()
            .forEach(e -> json.add(e.getKey(), e.getValue()));
      } catch (final RuntimeException e) {
        // a broken record starts over
      }
    }
    synchronized (rounds) {
      rounds.forEach(
          (r, c) -> {
            final JsonObject o = new JsonObject();
            o.addProperty("domande", c.domande);
            o.addProperty("differenze", c.differenze);
            o.addProperty("operazione", c.operazione);
            o.add("esempi", c.esempi.deepCopy());
            json.add("R" + r, o);
          });
    }
    props.set(PROPERTY_PREFIX + nation, json.toString());
  }
}
