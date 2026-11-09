package org.triplea.proplus.autoplay;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Parameters of one autoplay game, from the command line.
 *
 * <pre>
 * --mappa PATH         map XML (required), eg. ww2eur40_2nd_edition.xml
 * --round N            last round played (default 12)
 * --piano A=X,...      plan per alliance, eg. Axis=A,Allies=1 (default: "auto" = Automatico)
 * --rischio A=L,...    base risk level per alliance, 1-5 or name (default Equilibrato)
 * --tipo A=T,...       player type per alliance: proplus | hard (default proplus)
 * --decisore A=D,...   Pro+ decider per alliance: regole | file:PATH | llm (default regole; PATH =
 *                      directives file or folder, see DecisoreFile; llm = LLM strategist through
 *                      the API key, Claude Haiku with ANTHROPIC_API_KEY of the environment;
 *                      llm:MODEL = another model of the provider, eg llm:claude-sonnet-5-5)
 * --ablazione A=C+C,.. Pro+ components turned off per alliance (step ABLAZIONE): ids of
 *                      games.strategy.triplea.ai.proplus.Ablazione joined by "+", or "nudo" (all)
 * --seme N             seed of the game dice (default 1; the AI's own simulations stay random)
 * --output DIR         output folder (default autoplay-output)
 * --minuti N           wall-clock limit; the game stops and the summary is partial (default 55)
 * --nome TEXT          name of the game in the summaries (default: from the other parameters)
 * </pre>
 */
record AutoplayOptions(
    Path mappa,
    int round,
    Map<String, String> piani,
    Map<String, String> rischi,
    Map<String, String> tipi,
    Map<String, String> decisori,
    Map<String, String> ablazioni,
    long seme,
    Path output,
    int minuti,
    @Nullable String nome) {

  static final String PROPLUS = "proplus";
  static final String HARD = "hard";
  static final String LLM = "llm";

  static AutoplayOptions parse(final String[] args) {
    final Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      final String key = args[i];
      if (!key.startsWith("--") || i + 1 >= args.length) {
        throw new IllegalArgumentException("Parametro non valido: " + key);
      }
      values.put(key.substring(2), args[++i]);
    }
    final String map = values.get("mappa");
    if (map == null) {
      throw new IllegalArgumentException("Manca --mappa <file XML della mappa>");
    }
    final Map<String, String> types = pairs(values.get("tipo"));
    types.replaceAll((alliance, type) -> type(type));
    return new AutoplayOptions(
        Path.of(map),
        Integer.parseInt(values.getOrDefault("round", "12")),
        pairs(values.get("piano")),
        pairs(values.get("rischio")),
        types,
        deciders(values.get("decisore")),
        ablations(values.get("ablazione")),
        Long.parseLong(values.getOrDefault("seme", "1")),
        Path.of(values.getOrDefault("output", "autoplay-output")),
        Integer.parseInt(values.getOrDefault("minuti", "55")),
        values.get("nome"));
  }

  /** Player type of {@code alliance}: {@link #PROPLUS} unless set to {@link #HARD}. */
  String tipo(final String alliance) {
    return tipi.getOrDefault(alliance, PROPLUS);
  }

  Optional<String> piano(final String alliance) {
    return Optional.ofNullable(piani.get(alliance));
  }

  Optional<String> rischio(final String alliance) {
    return Optional.ofNullable(rischi.get(alliance));
  }

  /** {@code regole}, {@code file:PATH} or {@code llm} of {@code alliance} (default regole). */
  String decisore(final String alliance) {
    return decisori.getOrDefault(alliance, "regole");
  }

  /**
   * Components turned off for {@code alliance}, comma separated as in the game property (empty =
   * none).
   */
  String ablazione(final String alliance) {
    return ablazioni.getOrDefault(alliance, "");
  }

  /** "Axis=rallentamento+rischio_fronte,Allies=nudo": every id must be a known component. */
  static Map<String, String> ablations(@Nullable final String text) {
    final Map<String, String> map = pairs(text);
    map.replaceAll(
        (alliance, a) -> {
          final String list = a.replace('+', ',');
          try {
            games.strategy.triplea.ai.proplus.Ablazione.parse(list);
          } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Ablazione non valida per " + alliance + ": " + e.getMessage(), e);
          }
          return list;
        });
    return map;
  }

  /** "Allies=file:/x/y,Axis=regole": each value is "regole" or "file:PATH". */
  static Map<String, String> deciders(@Nullable final String text) {
    final Map<String, String> map = pairs(text);
    map.forEach(
        (alliance, d) -> {
          if (!d.equals("regole")
              && !d.equals(LLM)
              && !(d.startsWith(LLM + ":") && d.length() > LLM.length() + 1)
              && !(d.startsWith("file:") && d.length() > "file:".length())) {
            throw new IllegalArgumentException(
                "Decisore non valido per "
                    + alliance
                    + ": "
                    + d
                    + " (regole | file:PERCORSO | llm | llm:MODELLO)");
          }
        });
    return map;
  }

  /** "Axis=A,Allies=1" -> {Axis: A, Allies: 1}. */
  static Map<String, String> pairs(@Nullable final String text) {
    final Map<String, String> map = new LinkedHashMap<>();
    if (text == null || text.isBlank()) {
      return map;
    }
    for (final String item : text.split(",")) {
      final String[] kv = item.split("=", 2);
      if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
        throw new IllegalArgumentException("Atteso ALLEANZA=VALORE, trovato: " + item);
      }
      map.put(kv[0].trim(), kv[1].trim());
    }
    return map;
  }

  private static String type(final String text) {
    final String t = text.toLowerCase(Locale.ROOT).replace("+", "plus").replace("-", "");
    return switch (t) {
      case PROPLUS, "pro", "plus" -> PROPLUS;
      case HARD -> HARD;
      default -> throw new IllegalArgumentException("Tipo di giocatore non valido: " + text);
    };
  }
}
