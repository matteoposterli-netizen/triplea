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
      // step PF1: a profile saved before this panel (no marker, or the P11/P12 marker) gets both
      // recommended boxes on (the user's earlier choice is not kept: the boxes changed meaning)
      if (!VERSIONE_PANNELLO.equals(values.getProperty(KEY_VERSIONE_PANNELLO, "").strip())) {
        values.remove(KEY_ALLEATI_CONSIGLIATI);
        values.remove(KEY_ASSE_CONSIGLIATO);
        normalizza();
      } else if ("si".equalsIgnoreCase(values.getProperty(KEY_ALLEATI_CONSIGLIATI, "").strip())
          && !values.containsKey(KEY_PONTE_ALLEATO)) {
        // step P15: a PF1 profile with the box saved on gets ponte_alleato too (the box keeps its
        // choice, its switches gain the new one)
        setPonteAlleato(true);
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

  private static final String KEY_ASSEDIO = "assedio";
  private static final String KEY_SQUADRA = "squadra_gibilterra";
  private static final String KEY_CONVOGLIO = "italia_convoglio";
  private static final String KEY_COMPITI = "compiti";

  /**
   * Step P1/P2: "Compiti (USA + Regno Unito)" for the Allies (turns on "impegno" and "compiti").
   * Off unless set.
   */
  public boolean compiti() {
    return si(KEY_COMPITI);
  }

  public void setCompiti(final boolean on) {
    values.setProperty(KEY_COMPITI, on ? "si" : "no");
  }

  private static final String KEY_USA_PRONTI = "usa_pronti";
  private static final String KEY_NORVEGIA_PRESTO = "norvegia_presto";
  private static final String KEY_RUSSIA_FRONTE = "russia_fronte";

  /** Step P4: "USA pronti prima della guerra" for the Allies. Off unless set. */
  public boolean usaPronti() {
    return si(KEY_USA_PRONTI);
  }

  public void setUsaPronti(final boolean on) {
    values.setProperty(KEY_USA_PRONTI, on ? "si" : "no");
  }

  /** Step P4: "Norvegia presto" for the Allies. Off unless set. */
  public boolean norvegiaPresto() {
    return si(KEY_NORVEGIA_PRESTO);
  }

  public void setNorvegiaPresto(final boolean on) {
    values.setProperty(KEY_NORVEGIA_PRESTO, on ? "si" : "no");
  }

  /** Step P4: "Fronte russo" for the Allies. Off unless set. */
  public boolean russiaFronte() {
    return si(KEY_RUSSIA_FRONTE);
  }

  public void setRussiaFronte(final boolean on) {
    values.setProperty(KEY_RUSSIA_FRONTE, on ? "si" : "no");
  }

  private static final String KEY_USA_PROPORZIONE = "usa_proporzione";
  private static final String KEY_NORVEGIA_SCORTA = "norvegia_scorta";

  /** Step P5: "USA in proporzione" for the Allies. Off unless set. */
  public boolean usaProporzione() {
    return si(KEY_USA_PROPORZIONE);
  }

  public void setUsaProporzione(final boolean on) {
    values.setProperty(KEY_USA_PROPORZIONE, on ? "si" : "no");
  }

  /** Step P5: "Norvegia scortata" for the Allies (turns on "norvegia_presto" too). */
  public boolean norvegiaScorta() {
    return si(KEY_NORVEGIA_SCORTA);
  }

  public void setNorvegiaScorta(final boolean on) {
    values.setProperty(KEY_NORVEGIA_SCORTA, on ? "si" : "no");
  }

  private static final String KEY_SBARCO_CONCENTRATO = "sbarco_concentrato";
  private static final String KEY_NORVEGIA_ESPOSTA = "norvegia_esposta";

  /** Step P6: "Sbarco USA concentrato" for the Allies. Off unless set. */
  public boolean sbarcoConcentrato() {
    return si(KEY_SBARCO_CONCENTRATO);
  }

  public void setSbarcoConcentrato(final boolean on) {
    values.setProperty(KEY_SBARCO_CONCENTRATO, on ? "si" : "no");
  }

  /** Step P6: "Norvegia anche esposta" for the Allies (turns on "norvegia_presto" too). */
  public boolean norvegiaEsposta() {
    return si(KEY_NORVEGIA_ESPOSTA);
  }

  public void setNorvegiaEsposta(final boolean on) {
    values.setProperty(KEY_NORVEGIA_ESPOSTA, on ? "si" : "no");
  }

  private static final String KEY_NIENTE_NEUTRALI = "niente_neutrali";
  private static final String KEY_PONTE_UK = "ponte_uk";
  private static final String KEY_PONTE_PRONTI = "ponte_pronti";

  /** Step P7: "Niente neutrali" for the Allies. Off unless set. */
  public boolean nienteNeutrali() {
    return si(KEY_NIENTE_NEUTRALI);
  }

  public void setNienteNeutrali(final boolean on) {
    values.setProperty(KEY_NIENTE_NEUTRALI, on ? "si" : "no");
  }

  /** Step P7: "Ponte del Regno Unito" for the Allies. Off unless set. */
  public boolean ponteUk() {
    return si(KEY_PONTE_UK);
  }

  public void setPonteUk(final boolean on) {
    values.setProperty(KEY_PONTE_UK, on ? "si" : "no");
  }

  /** Step P7: "Truppe britanniche pronte" for the Allies. Off unless set. */
  public boolean pontePronti() {
    return si(KEY_PONTE_PRONTI);
  }

  public void setPontePronti(final boolean on) {
    values.setProperty(KEY_PONTE_PRONTI, on ? "si" : "no");
  }

  private static final String KEY_PONTE_CONCENTRATO = "ponte_concentrato";

  /** Step P9+P10: "Ponte concentrato" for the Allies. Off unless set. */
  public boolean ponteConcentrato() {
    return si(KEY_PONTE_CONCENTRATO);
  }

  public void setPonteConcentrato(final boolean on) {
    values.setProperty(KEY_PONTE_CONCENTRATO, on ? "si" : "no");
  }

  private static final String KEY_TARANTO = "taranto";

  /** Step PF1: "taranto" for the Allies (part of "Alleati consigliati"). Off unless set. */
  public boolean taranto() {
    return si(KEY_TARANTO);
  }

  public void setTaranto(final boolean on) {
    values.setProperty(KEY_TARANTO, on ? "si" : "no");
  }

  static final String KEY_PONTE_ALLEATO = "ponte_alleato";

  /** Step P15: "ponte_alleato" for the Allies (part of "Alleati consigliati"). Off unless set. */
  public boolean ponteAlleato() {
    return si(KEY_PONTE_ALLEATO);
  }

  public void setPonteAlleato(final boolean on) {
    values.setProperty(KEY_PONTE_ALLEATO, on ? "si" : "no");
  }

  private static final String KEY_REGNO_UNITO_P3 = "regno_unito_p3";

  /**
   * Step P3: "Regno Unito: Il Cairo e ponte" for the Allies (turns on "impegno", "compiti" and
   * "regno_unito_p3"). Off unless set.
   */
  public boolean regnoUnitoP3() {
    return si(KEY_REGNO_UNITO_P3);
  }

  public void setRegnoUnitoP3(final boolean on) {
    values.setProperty(KEY_REGNO_UNITO_P3, on ? "si" : "no");
  }

  /**
   * Step R6c: "Italia in convoglio" for the Axis (turns on "impegno", "assedio" and
   * "italia_convoglio"). Off unless set.
   */
  public boolean italiaConvoglio() {
    return si(KEY_CONVOGLIO);
  }

  public void setItaliaConvoglio(final boolean on) {
    values.setProperty(KEY_CONVOGLIO, on ? "si" : "no");
  }

  /** Step R6b (D): "Assedio" for the Axis (turns on "impegno" and "assedio"). Off unless set. */
  public boolean assedio() {
    return si(KEY_ASSEDIO);
  }

  public void setAssedio(final boolean on) {
    values.setProperty(KEY_ASSEDIO, on ? "si" : "no");
  }

  /**
   * Step R6b (D): "Squadra di Gibilterra" for the Allies (turns on "impegno" and
   * "squadra_gibilterra"). Off unless set.
   */
  public boolean squadraGibilterra() {
    return si(KEY_SQUADRA);
  }

  public void setSquadraGibilterra(final boolean on) {
    values.setProperty(KEY_SQUADRA, on ? "si" : "no");
  }

  /**
   * Step P11: marker written by {@link #salva()}. A profile file without it, or with an older one
   * (P11, P12), gets both recommended boxes on when loaded ({@link #normalizza()}).
   */
  static final String KEY_VERSIONE_PANNELLO = "pannello";

  static final String VERSIONE_PANNELLO = "pf1";

  /** Step PF1: the box "Alleati consigliati" as the user left it ("si"/"no"). */
  static final String KEY_ALLEATI_CONSIGLIATI = "alleati_consigliati";

  /** Step PF1: the box "Asse consigliato" as the user left it ("si"/"no"). */
  static final String KEY_ASSE_CONSIGLIATO = "asse_consigliato";

  /**
   * Step PF1: the box "Alleati consigliati" (Allies of "Pro+ (Europe 1940)"): on by default (no
   * profile, old profile, never set). On = the final Allies of step P12 + taranto: {@code
   * usa_proporzione}, {@code sbarco_concentrato}, {@code niente_neutrali}, {@code norvegia_presto},
   * {@code norvegia_esposta}, {@code ponte_concentrato}, {@code taranto}, {@code ponte_alleato}
   * (step P15).
   */
  public boolean alleatiConsigliati() {
    return !"no".equalsIgnoreCase(values.getProperty(KEY_ALLEATI_CONSIGLIATI, "").strip());
  }

  /**
   * Step PF1: the box and, for the old player type "Pro+ Stratega LLM", the same switches in the
   * profile: on = the eight, every other switch of the Allies off; off = every switch of the Allies
   * off (Hard).
   */
  public void setAlleatiConsigliati(final boolean on) {
    values.setProperty(KEY_ALLEATI_CONSIGLIATI, on ? "si" : "no");
    setUsaProporzione(on);
    setSbarcoConcentrato(on);
    setNienteNeutrali(on);
    setNorvegiaPresto(on);
    setNorvegiaEsposta(on);
    setPonteConcentrato(on);
    setTaranto(on);
    setPonteAlleato(on);
    setImpegno(false);
    setSquadraGibilterra(false);
    setCompiti(false);
    setRegnoUnitoP3(false);
    setUsaPronti(false);
    setRussiaFronte(false);
    setNorvegiaScorta(false);
    setPonteUk(false);
    setPontePronti(false);
  }

  /**
   * Step PF1: the box "Asse consigliato (assedio di Mosca)": on by default (no profile, old
   * profile, never set). On = {@code impegno} + {@code assedio} for the Axis.
   */
  public boolean asseConsigliato() {
    return !"no".equalsIgnoreCase(values.getProperty(KEY_ASSE_CONSIGLIATO, "").strip());
  }

  /** Step PF1: the box and the profile's Axis switches: on = impegno + assedio, off = Hard. */
  public void setAsseConsigliato(final boolean on) {
    values.setProperty(KEY_ASSE_CONSIGLIATO, on ? "si" : "no");
    setAssedio(on);
    setItaliaConvoglio(false);
    setImpegno(false);
  }

  /**
   * Writes the two boxes into the profile's switches (the old player type "Pro+ Stratega LLM" reads
   * those): each side is exactly its recommended combination or all off.
   */
  public void normalizza() {
    final boolean allies = alleatiConsigliati();
    final boolean axis = asseConsigliato();
    setAlleatiConsigliati(allies);
    setAsseConsigliato(axis);
  }

  private boolean si(final String key) {
    final String v = values.getProperty(key, "").strip();
    return "si".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v);
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
    values.setProperty(KEY_VERSIONE_PANNELLO, VERSIONE_PANNELLO);
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
