package games.strategy.triplea.ai.proplus.strategia.llm;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The strategist's settings of THIS computer user: provider, API keys, preferred mode. Saved only
 * in the user profile ({@code <home>/.triplea-proplus/stratega.properties}, readable only by the
 * owner where the file system allows it): never in the game folder, in a saved game, in the diary
 * or in a log. The folder can be moved with the system property {@value #FOLDER_PROPERTY} (tests).
 *
 * <p>A key not saved here is read from the provider's environment variable ({@link
 * Fornitore#variabile()}, the autoplay in CI).
 */
public final class ProfiloStratega {
  public static final String FOLDER_PROPERTY = "proplus.stratega.cartella";

  /** File next to the game a friend can drop the key in; imported once, then offered to delete. */
  public static final String FILE_DA_IMPORTARE = "chiave-stratega.txt";

  static final String FILE_NAME = "stratega.properties";
  private static final String KEY_PROVIDER = "fornitore";
  private static final String KEY_MODE = "modalita";
  private static final String KEY_PREFIX = "chiave.";

  private final Path file;
  private final Function<String, String> env;
  private final Properties values = new Properties();

  ProfiloStratega(final Path file, final Function<String, String> env) {
    this.file = file;
    this.env = env;
    if (Files.isRegularFile(file)) {
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        values.load(in);
      } catch (final IOException e) {
        // unreadable profile = no saved settings
      }
    }
  }

  /** The profile of the current user. */
  public static ProfiloStratega carica() {
    return new ProfiloStratega(cartella().resolve(FILE_NAME), System::getenv);
  }

  public static Path cartella() {
    final String folder = System.getProperty(FOLDER_PROPERTY);
    return folder != null && !folder.isBlank()
        ? Path.of(folder)
        : Path.of(System.getProperty("user.home"), ".triplea-proplus");
  }

  public Fornitore fornitore() {
    return Fornitore.daId(values.getProperty(KEY_PROVIDER, "")).orElse(Fornitore.ANTHROPIC);
  }

  public void setFornitore(final Fornitore f) {
    values.setProperty(KEY_PROVIDER, f.id());
  }

  /**
   * The mode used when a game has none saved; {@link Modalita#REGOLE} until the user picks one in
   * the panel (so tests and games of users who never configured the strategist stay as before).
   */
  public Modalita modalitaPredefinita() {
    return Modalita.daId(values.getProperty(KEY_MODE, "")).orElse(Modalita.REGOLE);
  }

  public void setModalitaPredefinita(final Modalita m) {
    values.setProperty(KEY_MODE, m.id());
  }

  private static final String KEY_BUDGET_STRATEGA = "stratega_llm_tetto_usd";

  /**
   * Step STRATEGA LLM: cap in USD per game of the "Pro+ Stratega LLM" player ({@code fallback} if
   * none saved or not valid).
   */
  public double tettoPartitaUsd(final double fallback) {
    try {
      final double v =
          Double.parseDouble(values.getProperty(KEY_BUDGET_STRATEGA, "").strip().replace(',', '.'));
      return v > 0 && v <= 100 ? v : fallback;
    } catch (final NumberFormatException e) {
      return fallback;
    }
  }

  public void setTettoPartitaUsd(final double usd) {
    values.setProperty(KEY_BUDGET_STRATEGA, String.format(java.util.Locale.ROOT, "%.2f", usd));
  }

  private static final String KEY_IMPEGNO = "impegno";

  /**
   * Step R2b: the user turned on the commitment ("Impegno") of the "Pro+ Stratega LLM" player for
   * the games of this PC (both alliances). Off unless set.
   */
  public boolean impegno() {
    return "si".equalsIgnoreCase(values.getProperty(KEY_IMPEGNO, "").strip())
        || "true".equalsIgnoreCase(values.getProperty(KEY_IMPEGNO, "").strip());
  }

  public void setImpegno(final boolean on) {
    values.setProperty(KEY_IMPEGNO, on ? "si" : "no");
  }

  /** The key saved for {@code f}. */
  public Optional<String> chiaveSalvata(final Fornitore f) {
    final String k = values.getProperty(KEY_PREFIX + f.id(), "").strip();
    return k.isEmpty() ? Optional.empty() : Optional.of(k);
  }

  /** The key saved for {@code f}, else its environment variable. */
  public Optional<String> chiave(final Fornitore f) {
    final Optional<String> saved = chiaveSalvata(f);
    if (saved.isPresent()) {
      return saved;
    }
    final @Nullable String v = env.apply(f.variabile());
    return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.strip());
  }

  public void setChiave(final Fornitore f, final String key) {
    values.setProperty(KEY_PREFIX + f.id(), key.strip());
  }

  public void rimuoviChiave(final Fornitore f) {
    values.remove(KEY_PREFIX + f.id());
  }

  /** Writes the profile (owner-only permissions where supported). */
  public void salva() throws IOException {
    Files.createDirectories(file.getParent());
    if (!Files.exists(file)
        && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      values.store(
          out, "Stratega Pro+: impostazioni e chiavi API di questo utente. Non condividere.");
    }
  }

  /**
   * Imports {@value #FILE_DA_IMPORTARE} from {@code gameFolder} when no key is saved for its
   * provider (recognized from the key's prefix; unknown prefix = the current provider). Returns the
   * provider of the imported key; the file is NOT deleted (the panel asks first).
   */
  public Optional<Fornitore> importaDa(final Path gameFolder) throws IOException {
    final Path source = gameFolder.resolve(FILE_DA_IMPORTARE);
    if (!Files.isRegularFile(source)) {
      return Optional.empty();
    }
    final Optional<Map.Entry<Fornitore, String>> read = leggiFile(source, fornitore());
    if (read.isEmpty() || chiaveSalvata(read.get().getKey()).isPresent()) {
      return Optional.empty();
    }
    setChiave(read.get().getKey(), read.get().getValue());
    setFornitore(read.get().getKey());
    if (modalitaPredefinita() == Modalita.REGOLE) {
      setModalitaPredefinita(Modalita.AUTOMATICO);
    }
    salva();
    return Optional.of(read.get().getKey());
  }

  /**
   * The key in a text file: the first non-empty line that is not a comment ("#"); "provider=key"
   * also accepted (eg "groq=gsk_...").
   */
  static Optional<Map.Entry<Fornitore, String>> leggiFile(
      final Path source, final Fornitore fallback) throws IOException {
    for (final String raw : Files.readAllLines(source, StandardCharsets.UTF_8)) {
      final String line = raw.replace("﻿", "").strip();
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      final int eq = line.indexOf('=');
      if (eq > 0) {
        final Optional<Fornitore> named = Fornitore.daId(line.substring(0, eq));
        if (named.isPresent()) {
          return Optional.of(Map.entry(named.get(), line.substring(eq + 1).strip()));
        }
      }
      return Optional.of(Map.entry(Fornitore.daChiave(line).orElse(fallback), line));
    }
    return Optional.empty();
  }

  /** Eg "\u2022\u2022\u2022\u20227f3a" (only the last 4 characters). */
  public static String maschera(final String key) {
    final String k = key == null ? "" : key.strip();
    return k.length() <= 4
        ? "\u2022\u2022\u2022\u2022"
        : "\u2022\u2022\u2022\u2022" + k.substring(k.length() - 4);
  }
}
