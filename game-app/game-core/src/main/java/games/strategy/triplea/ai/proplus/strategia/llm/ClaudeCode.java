package games.strategy.triplea.ai.proplus.strategia.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * The strategist through Claude Code installed on the PC (the user's Pro/Max subscription, no API
 * key): headless call {@code claude -p --output-format json} with the prompt on stdin, the fixed
 * part as system prompt file, no tools, model "haiku", timeout {@link #TIMEOUT}. Claude Code with
 * the free Claude plan does not work (Anthropic: "The free claude.ai plan does not include Claude
 * Code access"): the check call fails and the next transport is used.
 *
 * <p>Found on the PATH (like {@code where claude}) or in the usual install folders; checked once
 * per run of the game with a short call ({@link #stato}).
 */
public final class ClaudeCode {
  public static final Duration TIMEOUT = Duration.ofSeconds(120);

  /** System property: "true" = never look for Claude Code (tests of the panel). */
  public static final String DISABLED_PROPERTY = "proplus.stratega.senzaClaude";
  static final Duration TIMEOUT_PROVA = Duration.ofSeconds(60);
  static final String MODELLO = "haiku";

  /** Runs a process (replaced by a fake in the tests). */
  @FunctionalInterface
  public interface Processo {
    record Esito(int codice, String uscita, String errori, boolean scaduto) {}

    Esito esegui(List<String> command, String stdin, Duration timeout) throws IOException;
  }

  /** What the check found. */
  public record Stato(@Nullable Path comando, boolean pronto, String descrizione) {}

  private static volatile @Nullable Stato cached;

  private final Processo processo;
  private final Function<String, String> env;
  private final Predicate<Path> exists;
  private final boolean windows;

  public ClaudeCode(
      final Processo processo,
      final Function<String, String> env,
      final Predicate<Path> exists,
      final boolean windows) {
    this.processo = processo;
    this.env = env;
    this.exists = exists;
    this.windows = windows;
  }

  /** The real one: real processes, environment and file system. */
  public static ClaudeCode reale() {
    return new ClaudeCode(
        ClaudeCode::run,
        System::getenv,
        Files::isRegularFile,
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
  }

  /** The check of this run of the game (found? logged in?), made once. */
  public static Stato stato() {
    if (Boolean.getBoolean(DISABLED_PROPERTY)) {
      return new Stato(null, false, "Claude Code non trovato");
    }
    Stato s = cached;
    if (s == null) {
      s = reale().verifica();
      cached = s;
    }
    return s;
  }

  /** Forgets the check (panel: "Prova connessione" after installing or logging in). */
  public static void dimentica() {
    cached = null;
  }

  /** Finds the command and makes the short check call. */
  public Stato verifica() {
    final Optional<Path> command = trova();
    if (command.isEmpty()) {
      return new Stato(null, false, "Claude Code non trovato");
    }
    try {
      final Processo.Esito e =
          processo.esegui(
              comando(command.get(), null, false), "Rispondi solo con la parola OK.", TIMEOUT_PROVA);
      final String problem = problema(e);
      return problem.isEmpty()
          ? new Stato(command.get(), true, "Claude Code trovato e collegato")
          : new Stato(command.get(), false, "Claude Code trovato ma non utilizzabile: " + problem);
    } catch (final IOException e) {
      return new Stato(
          command.get(), false, "Claude Code trovato ma non si avvia: " + e.getMessage());
    }
  }

  /** The {@code claude} command: PATH first (like {@code where}), then the usual folders. */
  public Optional<Path> trova() {
    final Set<Path> candidates = new LinkedHashSet<>();
    final List<String> names =
        windows ? List.of("claude.exe", "claude.cmd", "claude.bat", "claude") : List.of("claude");
    final String path = env.apply("PATH");
    if (path != null) {
      for (final String dir : path.split(windows ? ";" : File.pathSeparator)) {
        final String d = dir.strip().replace("\"", "");
        if (!d.isEmpty()) {
          names.forEach(n -> candidates.add(Path.of(d, n)));
        }
      }
    }
    final String home = env.apply(windows ? "USERPROFILE" : "HOME");
    if (home != null) {
      // native installer (Windows: %USERPROFILE%\.local\bin\claude.exe)
      candidates.add(Path.of(home, ".local", "bin", windows ? "claude.exe" : "claude"));
      if (!windows) {
        candidates.add(Path.of(home, ".claude", "local", "claude"));
      }
    }
    if (windows) {
      final String appData = env.apply("APPDATA");
      if (appData != null) {
        // npm's default global folder
        candidates.add(Path.of(appData, "npm", "claude.cmd"));
      }
    } else {
      candidates.add(Path.of("/usr/local/bin/claude"));
      candidates.add(Path.of("/opt/homebrew/bin/claude"));
    }
    return candidates.stream().filter(exists).findFirst();
  }

  /**
   * Asks the strategist.
   *
   * @throws ErroreStratega no answer (not found, not logged in, timeout, error)
   */
  public Risposta chiama(final Richiesta r) {
    final Stato s = stato();
    if (!s.pronto() || s.comando() == null) {
      throw new ErroreStratega(s.descrizione());
    }
    return chiama(s.comando(), r);
  }

  Risposta chiama(final Path command, final Richiesta r) {
    Path system = null;
    final long start = System.nanoTime();
    try {
      system = Files.createTempFile("proplus-stratega-", ".md");
      Files.writeString(system, r.sistema(), StandardCharsets.UTF_8);
      final Processo.Esito e = processo.esegui(comando(command, system, true), r.utente(), TIMEOUT);
      final String problem = problema(e);
      if (!problem.isEmpty()) {
        throw new ErroreStratega("Claude Code: " + problem);
      }
      final JsonObject json = JsonParser.parseString(e.uscita().strip()).getAsJsonObject();
      final JsonObject usage =
          json.has("usage") ? json.getAsJsonObject("usage") : new JsonObject();
      return new Risposta(
          json.get("result").getAsString(),
          "Claude Code",
          MODELLO,
          number(usage, "input_tokens"),
          number(usage, "output_tokens"),
          number(usage, "cache_read_input_tokens"),
          number(usage, "cache_creation_input_tokens"),
          json.has("total_cost_usd") ? json.get("total_cost_usd").getAsDouble() : 0,
          true,
          (System.nanoTime() - start) / 1_000_000);
    } catch (final IOException e) {
      throw new ErroreStratega("Claude Code non si avvia: " + e.getMessage(), e);
    } catch (final ErroreStratega e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new ErroreStratega("risposta di Claude Code non leggibile", e);
    } finally {
      if (system != null) {
        try {
          Files.deleteIfExists(system);
        } catch (final IOException e) {
          // temp file left behind
        }
      }
    }
  }

  /**
   * The command line: print mode, JSON result, model haiku, no built-in tools, no MCP servers, no
   * session saved; the full call replaces Claude Code's system prompt with the fixed part.
   */
  List<String> comando(final Path command, @Nullable final Path systemFile, final boolean full) {
    final List<String> cmd = new ArrayList<>();
    cmd.add(command.toString());
    cmd.addAll(
        List.of(
            "-p",
            "--output-format",
            "json",
            "--model",
            MODELLO,
            "--tools",
            "",
            "--strict-mcp-config",
            "--no-session-persistence",
            "--max-turns",
            full ? "2" : "1"));
    if (systemFile != null) {
      cmd.add("--system-prompt-file");
      cmd.add(systemFile.toString());
    }
    return cmd;
  }

  /** Why the result is not usable ("" = usable). */
  static String problema(final Processo.Esito e) {
    if (e.scaduto()) {
      return "nessuna risposta entro il tempo massimo";
    }
    final String out = e.uscita().strip();
    try {
      final JsonObject json = JsonParser.parseString(out).getAsJsonObject();
      final boolean error = json.has("is_error") && json.get("is_error").getAsBoolean();
      if (error || e.codice() != 0 || !json.has("result")) {
        final String result = json.has("result") ? json.get("result").getAsString() : "";
        return explain(result.isEmpty() ? e.errori() : result);
      }
      return "";
    } catch (final RuntimeException ex) {
      return explain(out.isEmpty() ? e.errori() : out);
    }
  }

  private static String explain(final String text) {
    final String t = text.strip();
    final String lower = t.toLowerCase(Locale.ROOT);
    if (lower.contains("not logged in") || lower.contains("login")) {
      return "non collegato a un account (apri Claude Code e fai il login)";
    }
    if (lower.contains("does not have access") || lower.contains("subscription")) {
      return "l'account non include Claude Code (serve Pro o Max)";
    }
    return t.length() > 160 ? t.substring(0, 160) + "..." : t.isEmpty() ? "errore" : t;
  }

  private static long number(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
  }

  /** Real process: stdin written, stdout/stderr read in parallel, killed on timeout. */
  private static Processo.Esito run(
      final List<String> command, final String stdin, final Duration timeout) throws IOException {
    final Path dir = Files.createTempDirectory("proplus-stratega");
    final Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
    final CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
    final CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
    try (OutputStream in = p.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      // the process closed stdin early: its output tells why
    }
    try {
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        return new Processo.Esito(-1, "", "", true);
      }
      return new Processo.Esito(
          p.exitValue(), out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS), false);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
      throw new IOException("interrotto", e);
    } catch (final java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException e) {
      throw new IOException("uscita non leggibile", e);
    } finally {
      try {
        Files.deleteIfExists(dir);
      } catch (final IOException e) {
        // temp folder left behind
      }
    }
  }

  private static String read(final InputStream in) {
    try (in) {
      final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      in.transferTo(buffer);
      return buffer.toString(StandardCharsets.UTF_8);
    } catch (final IOException e) {
      return "";
    }
  }
}
