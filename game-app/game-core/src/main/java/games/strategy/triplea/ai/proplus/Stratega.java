package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Candidato;
import games.strategy.triplea.ai.proplus.StrategaCandidati.Tipo;
import games.strategy.triplea.ai.proplus.diario.DiaryRecorder;
import games.strategy.triplea.ai.proplus.strategia.llm.PromptStrategaV4;
import games.strategy.triplea.ai.proplus.strategia.llm.Risposta;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Step STRATEGA LLM: the LLM strategist of the player type "Pro+ Stratega LLM (Europe 1940)".
 *
 * <p>The LLM decides WHAT and WHY with real levers (operations launched, continued, changed,
 * cancelled; priority reinforcements); the engine computes and carries them out (HOW) with the
 * joint operations' machinery ({@link OperazioniCongiunte}); Hard does the rest of the tactics. The
 * knobs of Pro+ stay fixed at Hard's values ("nudo") and the LLM never controls them.
 *
 * <p>Two levels: the strategic one here (start of the round, per alliance: portfolio chosen from
 * the candidates computed by {@link StrategaCandidati}) and the turn orders at the operations'
 * decision points ({@link DecisoreOperazione} with prompt-ordini-turno-v3). Without a key, on an
 * error, a timeout or the game's cap reached: no new operation (Hard), the decision points fall
 * back to Hard, and a warning once in the game log.
 */
public final class Stratega {
  public static final String PLAYER_LABEL = "Pro+ Stratega LLM (Europe 1940)";

  static final String KEY_NAZIONI = "Pro+ stratega llm nazioni: ";
  static final String KEY_ROUND = "Pro+ stratega llm round: ";
  static final String KEY_PORTAFOGLIO = "Pro+ stratega llm portafoglio: ";
  static final String KEY_LETTURA = "Pro+ stratega llm lettura: ";
  static final String KEY_RINFORZI = "Pro+ stratega llm rinforzi: ";
  public static final String KEY_AVVISO = "Pro+ stratega llm avviso";
  public static final String KEY_BUDGET = "Pro+ stratega llm budget usd";
  public static final String KEY_MODELLO = "Pro+ stratega llm modello";
  public static final String KEY_USO = DecisoreOperazione.KEY_USO_STRATEGICO;

  /** The warning written once in the game log when the LLM can't be used. */
  public static final String AVVISO = "Stratega LLM non disponibile: gioco con le regole";

  private Stratega() {}

  // ============================================================================ registration

  /**
   * Called when a "Pro+ Stratega LLM" player joins a game (and on every turn, cheap): the nation is
   * recorded for its alliance, the alliance plays Pro+ nudo (= Hard), its operations' decision
   * points are asked to the LLM, the cap of the game is set once.
   */
  public static void registra(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return;
    }
    final GameProperties props = data.getProperties();
    final Set<String> nations = nazioni(props, alliance.get());
    if (!nations.contains(player.getName())) {
      nations.add(player.getName());
      props.set(KEY_NAZIONI + alliance.get(), (Serializable) null);
      props.set(KEY_NAZIONI + alliance.get(), String.join(",", nations));
    }
    // the knobs at Hard's values: every Pro+ component off for the alliance
    if (!Ablazione.spenti(props, alliance.get())
        .containsAll(java.util.EnumSet.allOf(Ablazione.Componente.class))) {
      Ablazione.set(props, alliance.get(), Ablazione.NUDO);
    }
    if (DecisoreOperazione.modello(props, alliance.get()).isEmpty()) {
      DecisoreOperazione.set(props, alliance.get(), DecisoreOperazione.LLM + ":" + modello(props));
    }
    if (!(props.get(KEY_BUDGET) instanceof String)) {
      final double cap =
          games.strategy.triplea.ai.proplus.strategia.llm.ProfiloStratega.carica()
              .tettoPartitaUsd(StrategaConfig.get().budgetDefaultUsd());
      setBudget(props, cap);
    }
  }

  /** The nations of {@code alliance} played by the strategist (turn order not guaranteed). */
  public static Set<String> nazioni(final GameProperties props, final String alliance) {
    final Set<String> set = new LinkedHashSet<>();
    if (props.get(KEY_NAZIONI + alliance) instanceof String s && !s.isBlank()) {
      for (final String n : s.split(",")) {
        if (!n.isBlank()) {
          set.add(n.strip());
        }
      }
    }
    return set;
  }

  /** Whether {@code player} is played by the strategist in this game. */
  public static boolean gioca(final GameState data, final GamePlayer player) {
    return ProPlusSettings.allianceOf(data, player)
        .map(a -> nazioni(data.getProperties(), a).contains(player.getName()))
        .orElse(false);
  }

  /** The cap in USD of the game (both levels together). */
  public static void setBudget(final GameProperties props, final double usd) {
    props.set(KEY_BUDGET, (Serializable) null);
    props.set(KEY_BUDGET, String.format(Locale.ROOT, "%.4f", usd));
    DecisoreOperazione.setBudget(props, usd);
  }

  public static double budget(final GameProperties props) {
    if (props.get(KEY_BUDGET) instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (final NumberFormatException e) {
        // default
      }
    }
    return StrategaConfig.get().budgetDefaultUsd();
  }

  /** The model of both levels (game property, else the data file: Sonnet 5.5). */
  public static String modello(final GameProperties props) {
    return props.get(KEY_MODELLO) instanceof String s && !s.isBlank()
        ? s
        : StrategaConfig.get().modello();
  }

  public static void setModello(final GameProperties props, final String model) {
    props.set(KEY_MODELLO, (Serializable) null);
    if (model != null && !model.isBlank()) {
      props.set(KEY_MODELLO, model.strip());
    }
  }

  // ============================================================================ strategic level

  /** What the strategist did at a turn start: lines for the diary, a warning for the game log. */
  public record Esito(List<String> righe, Optional<String> avviso) {
    static final Esito NESSUNO = new Esito(List.of(), Optional.empty());
  }

  /**
   * Start of the turn of a nation played by the strategist (real game, purchase phase, before the
   * operations update): the first nation of the alliance to play in a round decides the portfolio
   * of the round.
   */
  public static Esito inizioTurno(
      final ProData proData,
      final ProOddsCalculator calc,
      final GameData data,
      final GamePlayer p) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, p);
    if (alliance.isEmpty() || !gioca(data, p)) {
      return Esito.NESSUNO;
    }
    final GameProperties props = data.getProperties();
    final int round = data.getSequence().getRound();
    if (props.get(KEY_ROUND + alliance.get()) instanceof Integer done && done >= round) {
      return Esito.NESSUNO;
    }
    props.set(KEY_ROUND + alliance.get(), (Serializable) null);
    props.set(KEY_ROUND + alliance.get(), round);
    return new Round(proData, calc, data, alliance.get(), round).decidi();
  }

  /** One decision of the strategic level. */
  private static final class Round {
    private final ProData proData;
    private final ProOddsCalculator calc;
    private final GameData data;
    private final GameProperties props;
    private final String alliance;
    private final int round;
    private final StrategaConfig cfg = StrategaConfig.get();
    private final List<String> righe = new ArrayList<>();
    private final JsonObject diario = new JsonObject();
    private Optional<String> avviso = Optional.empty();

    Round(
        final ProData proData,
        final ProOddsCalculator calc,
        final GameData data,
        final String alliance,
        final int round) {
      this.proData = proData;
      this.calc = calc;
      this.data = data;
      this.props = data.getProperties();
      this.alliance = alliance;
      this.round = round;
    }

    Esito decidi() {
      diario.addProperty("tipo", "strategico");
      diario.addProperty("round", round);
      diario.addProperty("alleanza", alliance);
      final JsonObject portfolio = portafoglio(props, alliance);
      chiudiFinite(portfolio);
      // no key, cap reached...: no call, no candidates (the round plays as Hard)
      final Optional<DecisoreOperazione.Trasporto> transport = trasporto();
      if (transport.isEmpty()) {
        return chiudi(Optional.empty(), portfolio);
      }
      final List<String> nations = List.copyOf(nazioni(props, alliance));
      final StrategaCandidati gen = new StrategaCandidati(proData, calc, alliance, nations);
      List<Candidato> candidates = List.of();
      JsonObject reading = new JsonObject();
      try {
        candidates = gen.calcola();
        reading = gen.letturaAvversario(lettura(props, alliance));
        if (reading.has("_fronti_ora")) {
          props.set(KEY_LETTURA + alliance, (Serializable) null);
          props.set(KEY_LETTURA + alliance, reading.get("_fronti_ora").toString());
          reading.remove("_fronti_ora");
        }
      } catch (final RuntimeException e) {
        ProLogger.warn("[Pro+] Stratega: candidati non calcolati: " + e);
        righe.add("Stratega LLM: candidati non calcolati (" + e + ")");
      }
      final JsonObject state = stato(candidates, reading, portfolio);
      diario.add("stato_inviato", riassuntoStato(state));
      diario.addProperty("caratteri_stato", state.toString().length());
      DiaryRecorder.operationInput(
          data, String.format(Locale.ROOT, "stratega-ingresso-R%d-%s.json", round, alliance), state);
      final Optional<JsonObject> answer = chiedi(transport.get(), state);
      if (answer.isPresent()) {
        applica(answer.get(), candidates, gen, portfolio);
      }
      return chiudi(answer, portfolio);
    }

    private Esito chiudi(final Optional<JsonObject> answer, final JsonObject portfolio) {
      if (answer.isEmpty()) {
        // parachute: no new operation (Hard); the ones in progress go on with Hard's fallbacks
        props.set(KEY_RINFORZI + alliance, (Serializable) null);
        righe.add(
            "Stratega LLM ("
                + alliance
                + ", R"
                + round
                + "): nessuna decisione, nessuna operazione nuova ("
                + diario.get("ripiego").getAsString()
                + ")");
      }
      if (!portfolio.has("rifiutate")) {
        portfolio.add("rifiutate", new JsonArray());
      }
      portfolio.getAsJsonArray("rifiutate").addAll(rifiutate);
      final String key = answer.isPresent() ? "round_decisi" : "round_senza_llm";
      portfolio.addProperty(key, (portfolio.has(key) ? portfolio.get(key).getAsInt() : 0) + 1);
      salvaPortafoglio(props, alliance, portfolio);
      diario.add("uso_partita", usoTotale(props));
      StrategaDiario.scrivi(data, diario, righe);
      return new Esito(List.copyOf(righe), avviso);
    }

    // ------------------------------------------------------------------- the LLM's answer

    /** The model, if a call can be made now (key, calls and cap of the game); else a fallback. */
    private Optional<DecisoreOperazione.Trasporto> trasporto() {
      final JsonObject u = DecisoreOperazione.usoDi(props, KEY_USO);
      final int calls = u.get("chiamate").getAsInt();
      final double spent =
          u.get("costo_usd").getAsDouble()
              + DecisoreOperazione.uso(props).get("costo_usd").getAsDouble();
      final double cap = budget(props);
      final double next =
          Math.max(0.03, calls > 0 ? u.get("costo_usd").getAsDouble() / calls * 1.5 : 0);
      if (calls >= cfg.tettoChiamateStrategiche()) {
        ripiego(u, DecisoreOperazione.RIPIEGO_TETTO, calls + " chiamate");
        return Optional.empty();
      }
      if (spent + next > cap) {
        ripiego(
            u,
            DecisoreOperazione.RIPIEGO_BUDGET,
            String.format(Locale.ROOT, "speso $%.3f, tetto della partita $%.2f", spent, cap));
        return Optional.empty();
      }
      final DecisoreOperazione.Trasporto t;
      try {
        t = DecisoreOperazione.fabbrica.apply(modello(props));
      } catch (final RuntimeException e) {
        ripiego(u, DecisoreOperazione.RIPIEGO_NON_DISPONIBILE, String.valueOf(e.getMessage()));
        return Optional.empty();
      }
      final Optional<String> na = t.nonDisponibile();
      if (na.isPresent()) {
        ripiego(u, DecisoreOperazione.RIPIEGO_NON_DISPONIBILE, na.get());
        return Optional.empty();
      }
      return Optional.of(t);
    }

    private Optional<JsonObject> chiedi(
        final DecisoreOperazione.Trasporto t, final JsonObject state) {
      final JsonObject u = DecisoreOperazione.usoDi(props, KEY_USO);
      final int calls = u.get("chiamate").getAsInt();
      u.addProperty("chiamate", calls + 1);
      final Risposta r;
      try {
        r = t.chiama(PromptStrategaV4.richiesta(vincoli(), state));
      } catch (final RuntimeException e) {
        u.addProperty("errori", u.get("errori").getAsInt() + 1);
        return ripiego(u, DecisoreOperazione.RIPIEGO_ERRORE, String.valueOf(e.getMessage()));
      }
      DecisoreOperazione.conta(u, r);
      diario.addProperty("fonte", r.fonte());
      diario.addProperty("costo_usd", Math.round(r.costoUsd() * 100_000) / 100_000.0);
      diario.addProperty("token", r.tokenTotali());
      diario.addProperty("ms", r.millis());
      try {
        final JsonObject a = StrategaDecisione.leggi(r.testo());
        DecisoreOperazione.salvaUso(props, KEY_USO, u);
        return Optional.of(a);
      } catch (final RuntimeException e) {
        u.addProperty("risposte_non_valide", u.get("risposte_non_valide").getAsInt() + 1);
        return ripiego(u, DecisoreOperazione.RIPIEGO_NON_VALIDA, String.valueOf(e.getMessage()));
      }
    }

    private Optional<JsonObject> ripiego(
        final JsonObject u, final String why, final String detail) {
      final JsonObject f = u.getAsJsonObject("ripieghi");
      f.addProperty(why, (f.has(why) ? f.get(why).getAsInt() : 0) + 1);
      DecisoreOperazione.salvaUso(props, KEY_USO, u);
      final String d = detail.length() > 200 ? detail.substring(0, 200) + "..." : detail;
      diario.addProperty("ripiego", why + ": " + d);
      if (!(props.get(KEY_AVVISO) instanceof String)) {
        props.set(KEY_AVVISO, why + ": " + d);
        avviso = Optional.of(AVVISO + " (" + why + ")");
      }
      return Optional.empty();
    }

    /**
     * Applies the portfolio: cancellations and changes first, then the launches, then the extra.
     */
    private void applica(
        final JsonObject a,
        final List<Candidato> candidates,
        final StrategaCandidati gen,
        final JsonObject portfolio) {
      diario.add("risposta", a.deepCopy());
      final JsonArray decisions = new JsonArray();
      final JsonArray defs = OperazioneCongiunta.dinamicheJson(props, alliance);
      final JsonArray voci = a.getAsJsonArray("portafoglio");
      // 1) continue / change / cancel the operations in progress
      for (final JsonElement e : voci) {
        final JsonObject v = e.getAsJsonObject();
        final String action = v.get("azione").getAsString();
        if ("avvia".equals(action)) {
          continue;
        }
        final String id = v.get("operazione").getAsString();
        final JsonObject rec = registro(decisions, v);
        final Optional<JsonObject> def = trova(defs, id);
        if (def.isEmpty()) {
          rifiuta(rec, "operazione " + id + " non in corso");
          continue;
        }
        final Registro reg = Registro.leggi(props, id);
        switch (action) {
          case "annulla" -> {
            if (!reg.stato().isFinale()) {
              reg.setStato(Stato.ANNULLATA);
              reg.set(
                  "motivo_fine", "stratega: annulla (" + v.get("motivazione").getAsString() + ")");
              final JsonObject ev = new JsonObject();
              ev.addProperty("round", round);
              ev.addProperty("tipo", "annullata");
              ev.addProperty("motivo", "decisione dello stratega");
              reg.evento(ev);
              reg.salva(props);
            }
            rec.addProperty("esito", "annullata");
            righe.add(
                "Stratega: ANNULLA "
                    + def.get().get("nome").getAsString()
                    + " - "
                    + v.get("motivazione").getAsString());
          }
          case "modifica" -> {
            modifica(def.get(), reg, v, rec);
            righe.add(
                "Stratega: MODIFICA "
                    + def.get().get("nome").getAsString()
                    + " - "
                    + rec.get("esito").getAsString());
          }
          default -> rec.addProperty("esito", "continua");
        }
        aggiornaPrevisione(portfolio, id, v);
      }
      // 2) launches from the candidates, within the engine's limits
      for (final JsonElement e : voci) {
        final JsonObject v = e.getAsJsonObject();
        if (!"avvia".equals(v.get("azione").getAsString())) {
          continue;
        }
        final JsonObject rec = registro(decisions, v);
        final String cid = v.get("candidato").getAsString();
        final Optional<Candidato> c =
            candidates.stream().filter(x -> x.id().equalsIgnoreCase(cid.strip())).findFirst();
        if (c.isEmpty()) {
          rifiuta(rec, "candidato " + cid + " sconosciuto");
          continue;
        }
        avvia(c.get(), v, rec, gen, defs, portfolio, false);
      }
      // 3) at most one operation outside the list, validated by the engine
      final JsonObject f = a.getAsJsonObject("fuori_lista");
      if (f.get("proponi").getAsBoolean()) {
        final JsonObject rec = f.deepCopy();
        rec.addProperty("azione", "fuori_lista");
        decisions.add(rec);
        final Optional<String> no =
            StrategaFuoriLista.valida(data, alliance, nazioni(props, alliance), f);
        if (no.isPresent()) {
          rifiuta(rec, no.get());
          righe.add("Stratega: operazione fuori lista RIFIUTATA (" + no.get() + ")");
        } else {
          final Candidato c = StrategaFuoriLista.candidato(data, f, gen);
          final JsonObject v = new JsonObject();
          v.addProperty("round_obiettivo", 0);
          v.addProperty("tetto_perdita_TUV", 0);
          v.addProperty("durata_tenuta", 0);
          v.addProperty("bersaglio_successivo", "");
          v.add("prob_tenuta_stimata", f.get("prob_tenuta_stimata"));
          v.add("motivazione", f.get("motivazione"));
          avvia(c, v, rec, gen, defs, portfolio, true);
        }
      }
      // 4) priority reinforcements (the engine chooses the units)
      final JsonArray reinf = new JsonArray();
      for (final JsonElement e : a.getAsJsonArray("rinforzi")) {
        final JsonObject r = e.getAsJsonObject();
        final String name = r.get("territorio").getAsString();
        final Territory t = data.getMap().getTerritoryOrNull(name);
        final GamePlayer any =
            data.getPlayerList().getPlayerId(nazioni(props, alliance).iterator().next());
        if (t == null || any == null || (!t.isWater() && !Matches.isTerritoryAllied(any).test(t))) {
          continue;
        }
        final JsonObject x = new JsonObject();
        x.addProperty("territorio", name);
        x.addProperty("peso", Math.max(1, Math.min(3, r.get("peso").getAsInt())));
        reinf.add(x);
        if (reinf.size() >= 4) {
          break;
        }
      }
      props.set(KEY_RINFORZI + alliance, (Serializable) null);
      if (!reinf.isEmpty()) {
        final JsonObject rr = new JsonObject();
        rr.addProperty("round", round);
        rr.add("territori", reinf);
        props.set(KEY_RINFORZI + alliance, rr.toString());
      }
      OperazioneCongiunta.setDinamiche(props, alliance, defs);
      diario.add("decisioni", decisions);
      diario.add("rinforzi", reinf);
      righe.add(
          "Stratega LLM ("
              + alliance
              + ", R"
              + round
              + ", "
              + (diario.has("fonte") ? diario.get("fonte").getAsString() : "LLM")
              + "): "
              + a.get("motivazione").getAsString());
    }

    private void avvia(
        final Candidato c,
        final JsonObject v,
        final JsonObject rec,
        final StrategaCandidati gen,
        final JsonArray defs,
        final JsonObject portfolio,
        final boolean outside) {
      final long active =
          defs.asList().stream()
              .map(e -> e.getAsJsonObject().get("id").getAsString())
              .filter(id -> !Registro.leggi(props, id).stato().isFinale())
              .count();
      if (active >= cfg.maxOperazioni()) {
        rifiuta(rec, "gia' " + active + " operazioni attive (massimo " + cfg.maxOperazioni() + ")");
        return;
      }
      final boolean duplicate =
          defs.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(d -> !Registro.leggi(props, d.get("id").getAsString()).stato().isFinale())
              .anyMatch(d -> d.get("bersaglio").getAsString().equals(c.bersaglio()));
      if (duplicate) {
        rifiuta(rec, "c'e' gia' un'operazione attiva su " + c.bersaglio());
        return;
      }
      final String next = v.get("bersaglio_successivo").getAsString().strip();
      if (!next.isEmpty() && (c.tipo() != Tipo.SBARCO || !c.successivi().contains(next))) {
        rec.addProperty(
            "nota", "bersaglio successivo " + next + " ignorato (non tra quelli possibili)");
      }
      final String id =
          OperazioneCongiunta.PREFISSO_STRATEGA
              + c.tipo().id()
              + "-R"
              + round
              + "-"
              + c.bersaglio().replaceAll("[^A-Za-z0-9]+", "_");
      final JsonObject def =
          gen.definizione(
              c,
              id,
              v.get("round_obiettivo").getAsInt(),
              v.get("tetto_perdita_TUV").getAsDouble(),
              v.get("durata_tenuta").getAsInt(),
              c.tipo() == Tipo.SBARCO && c.successivi().contains(next) ? next : "");
      defs.add(def);
      if (!c.tipo().conquista()) {
        // hold and reinforcement: the operation starts in TENUTA on the territory
        final Registro reg = Registro.leggi(props, id);
        reg.setStato(Stato.TENUTA);
        reg.setRoundObiettivo(round);
        reg.setRinvii(0);
        reg.set("avvio_round", round);
        reg.set("tenuta_bersaglio", c.bersaglio());
        reg.set("tenuta_dal", round);
        reg.set("tenute", 1);
        final JsonObject ev = new JsonObject();
        ev.addProperty("round", round);
        ev.addProperty("tipo", "tenuta_inizio");
        ev.addProperty("bersaglio", c.bersaglio());
        ev.addProperty(
            "testo",
            StrategaCandidati.nome(c.tipo(), c.bersaglio()) + ": avvio, TENUTA dal R" + round);
        reg.evento(ev);
        reg.salva(props);
      }
      rec.addProperty("esito", "avviata");
      rec.addProperty("operazione", id);
      final JsonObject p = new JsonObject();
      p.addProperty("id", id);
      p.addProperty("tipo", c.tipo().id());
      p.addProperty("bersaglio", c.bersaglio());
      p.addProperty("apre", c.apre());
      p.addProperty("chiude", c.chiude());
      p.addProperty("avvio_round", round);
      p.addProperty("durata", def.getAsJsonObject("tenuta").get("round").getAsInt());
      p.addProperty("fuori_lista", outside);
      p.add("prob_tenuta_stimata", v.get("prob_tenuta_stimata"));
      p.add("motivazione", v.get("motivazione"));
      p.add("previsioni", new JsonArray());
      portfolio.getAsJsonArray("operazioni").add(p);
      aggiornaPrevisione(portfolio, id, v);
      righe.add(
          String.format(
              Locale.ROOT,
              "Stratega: AVVIA %s%s (%s; dal R%d) - tenuta stimata %.0f%%: %s",
              StrategaCandidati.nome(c.tipo(), c.bersaglio()),
              outside ? " [fuori lista]" : "",
              c.apre().equals(c.chiude()) ? c.apre() : c.apre() + " -> " + c.chiude(),
              def.get("round_min").getAsInt(),
              v.get("prob_tenuta_stimata").getAsDouble() * 100,
              v.get("motivazione").getAsString()));
    }

    private void modifica(
        final JsonObject def, final Registro reg, final JsonObject v, final JsonObject rec) {
      final List<String> changes = new ArrayList<>();
      final int r = v.get("round_obiettivo").getAsInt();
      if (r > 0
          && (reg.stato() == Stato.PREPARAZIONE || reg.stato() == Stato.POSIZIONAMENTO)
          && r >= round) {
        final int max = r + cfg.durataOperazioneRound() - 1;
        def.addProperty("round_min", r);
        def.addProperty("round_max", max);
        reg.setRoundObiettivo(r);
        changes.add("round obiettivo R" + r);
      }
      final int cap = v.get("tetto_perdita_TUV").getAsInt();
      if (cap > 0) {
        def.addProperty("tetto_perdita", Math.min(cfg.tettoPerditaMax(), cap));
        changes.add("tetto di perdita " + (int) Math.min(cfg.tettoPerditaMax(), cap));
      }
      final int hold = v.get("durata_tenuta").getAsInt();
      if (hold > 0) {
        def.getAsJsonObject("tenuta").addProperty("round", Math.min(cfg.durataTenutaMax(), hold));
        changes.add("durata tenuta " + Math.min(cfg.durataTenutaMax(), hold));
      }
      reg.salva(props);
      rec.addProperty(
          "esito", changes.isEmpty() ? "nessuna modifica applicabile" : String.join(", ", changes));
    }

    /** Ended operations leave the list the engine runs; their outcome goes in the portfolio. */
    private void chiudiFinite(final JsonObject portfolio) {
      final JsonArray defs = OperazioneCongiunta.dinamicheJson(props, alliance);
      final JsonArray keep = new JsonArray();
      for (final JsonElement e : defs) {
        final String id = e.getAsJsonObject().get("id").getAsString();
        final Registro reg = Registro.leggi(props, id);
        if (reg.stato().isFinale()) {
          for (final JsonElement p : portfolio.getAsJsonArray("operazioni")) {
            final JsonObject o = p.getAsJsonObject();
            if (o.get("id").getAsString().equals(id) && !o.has("esito")) {
              o.addProperty("esito", reg.stato().testo());
              o.addProperty("fine_round", round);
              if (reg.json().has("motivo_fine")) {
                o.add("motivo_fine", reg.json().get("motivo_fine"));
              }
              o.addProperty(
                  "presa",
                  reg.flag("presa") || o.get("tipo").getAsString().matches("tenuta|rinforzo"));
            }
          }
        } else {
          keep.add(e);
        }
      }
      if (keep.size() != defs.size()) {
        OperazioneCongiunta.setDinamiche(props, alliance, keep);
      }
      // the real outcome of the estimates made at the start of earlier rounds
      for (final JsonElement p : portfolio.getAsJsonArray("operazioni")) {
        final JsonObject o = p.getAsJsonObject();
        final Territory t = data.getMap().getTerritoryOrNull(o.get("bersaglio").getAsString());
        final GamePlayer me = data.getPlayerList().getPlayerId(o.get("apre").getAsString());
        if (t == null || me == null) {
          continue;
        }
        for (final JsonElement q : o.getAsJsonArray("previsioni")) {
          final JsonObject x = q.getAsJsonObject();
          if (!x.has("esito_reale") && round >= x.get("verifica_round").getAsInt()) {
            x.addProperty("esito_reale", Matches.isTerritoryAllied(me).test(t));
          }
        }
      }
    }

    // --------------------------------------------------------------------------- state sent

    private JsonObject stato(
        final List<Candidato> candidates, final JsonObject reading, final JsonObject portfolio) {
      final JsonObject s = new JsonObject();
      s.addProperty("contratto", PromptStrategaV4.CONTRATTO);
      s.addProperty("round", round);
      s.addProperty("alleanza", alliance);
      s.add(
          "nazioni_dello_stratega",
          StrategaCandidati.array(
              StrategaCandidati.ordine(data).stream()
                  .filter(nazioni(props, alliance)::contains)
                  .toList()));
      s.add("ordine_dei_turni", StrategaCandidati.array(StrategaCandidati.ordine(data)));
      final JsonArray ops = new JsonArray();
      for (final JsonElement e : OperazioneCongiunta.dinamicheJson(props, alliance)) {
        final JsonObject d = e.getAsJsonObject();
        final String id = d.get("id").getAsString();
        final Registro reg = Registro.leggi(props, id);
        final JsonObject o = new JsonObject();
        o.addProperty("operazione", id);
        o.addProperty("tipo", OperazioneCongiunta.tipoStratega(id));
        o.addProperty("bersaglio", d.get("bersaglio").getAsString());
        o.addProperty("apre", d.get("apre").getAsString());
        o.addProperty("chiude", d.get("chiude").getAsString());
        o.addProperty("stato", reg.stato().testo());
        o.addProperty("round_obiettivo", reg.roundObiettivo());
        o.addProperty("rinvii", reg.rinvii());
        if (reg.json().has("ultima_stima_tenuta")) {
          o.add("ultima_tenuta_stimata_motore_pct", reg.json().get("ultima_stima_tenuta"));
        }
        if (reg.json().has("tenuta_dal")) {
          o.addProperty("tenuta_dal_round", reg.intOf("tenuta_dal", 0));
          o.addProperty("durata_tenuta", d.getAsJsonObject("tenuta").get("round").getAsInt());
        }
        final Territory t = data.getMap().getTerritoryOrNull(d.get("bersaglio").getAsString());
        if (t != null) {
          o.addProperty("proprietario_bersaglio", t.getOwner().getName());
        }
        final JsonArray ev = reg.eventi();
        final JsonArray last = new JsonArray();
        for (int i = Math.max(0, ev.size() - 3); i < ev.size(); i++) {
          final JsonObject x = ev.get(i).getAsJsonObject();
          if (x.has("testo")) {
            final String text = x.get("testo").getAsString();
            last.add(text.length() > 220 ? text.substring(0, 220) + "..." : text);
          }
        }
        o.add("ultimi_eventi", last);
        ops.add(o);
      }
      s.add("operazioni_in_corso", ops);
      final JsonArray done = new JsonArray();
      for (final JsonElement e : portfolio.getAsJsonArray("operazioni")) {
        final JsonObject o = e.getAsJsonObject();
        if (o.has("esito")) {
          final JsonObject x = new JsonObject();
          x.add("operazione", o.get("id"));
          x.add("esito", o.get("esito"));
          x.add("fine_round", o.get("fine_round"));
          done.add(x);
        }
      }
      s.add("operazioni_finite", done);
      final JsonArray cands = new JsonArray();
      candidates.forEach(c -> cands.add(c.json()));
      s.add("candidati", cands);
      s.add("lettura_avversario", reading);
      s.add("partita", partita());
      return s;
    }

    private JsonObject partita() {
      final JsonObject o = new JsonObject();
      final JsonObject income = new JsonObject();
      for (final GamePlayer p : data.getPlayerList().getPlayers()) {
        if (!p.isNull()) {
          income.addProperty(
              p.getName(),
              games.strategy.triplea.ai.pro.util.ProUtils.getPlayerProduction(p, data));
        }
      }
      o.add("reddito_per_nazione", income);
      final JsonObject vc = new JsonObject();
      for (final Territory t : data.getMap().getTerritories()) {
        if (TerritoryAttachment.get(t).map(a -> a.getVictoryCity() > 0).orElse(false)) {
          ProPlusSettings.allianceOf(data, t.getOwner())
              .ifPresent(a -> vc.addProperty(a, (vc.has(a) ? vc.get(a).getAsInt() : 0) + 1));
        }
      }
      o.add("victory_city_per_alleanza", vc);
      return o;
    }

    private String vincoli() {
      return String.join(
          "\n",
          "- al massimo "
              + cfg.maxOperazioni()
              + " operazioni attive per alleanza (le altre vengono rifiutate);",
          "- PU riservabili per le riserve delle operazioni: al massimo il "
              + Math.round(cfg.quotaPuMaxPct())
              + "% dei PU del turno per nazione (la difesa urgente puo' usare il resto);",
          "- la guarnigione della capitale (25% di rischio nemico) non la lascia mai;",
          "- tetto di perdita per tappa: predefinito "
              + Math.round(cfg.tettoPerditaDefault())
              + " TUV, massimo "
              + Math.round(cfg.tettoPerditaMax())
              + "; durata della tenuta: predefinita "
              + cfg.tenutaRoundDefault()
              + " round, massimo "
              + cfg.durataTenutaMax()
              + ";",
          "- un'operazione rinviata piu' di "
              + cfg.maxRinvii()
              + " volte o oltre "
              + cfg.durataOperazioneRound()
              + " round dal round obiettivo si annulla da sola;",
          "- una sola operazione attiva per bersaglio; la Francia (nazione) non ha ruoli;",
          "- risposta non valida, assente o fuori tempo, o tetto di costo della partita raggiunto: nessuna"
              + " operazione nuova, l'alleanza gioca come Hard.");
    }

    // ------------------------------------------------------------------------- helpers

    private JsonObject registro(final JsonArray decisions, final JsonObject v) {
      final JsonObject rec = v.deepCopy();
      decisions.add(rec);
      return rec;
    }

    private final JsonArray rifiutate = new JsonArray();

    private void rifiuta(final JsonObject rec, final String why) {
      rec.addProperty("esito", "rifiutata");
      rec.addProperty("motivo_rifiuto", why);
      final JsonObject r = new JsonObject();
      r.addProperty("round", round);
      r.addProperty("azione", rec.has("azione") ? rec.get("azione").getAsString() : "");
      r.addProperty("tipo", rec.has("tipo") ? rec.get("tipo").getAsString() : "");
      r.addProperty(
          "voce",
          rec.has("candidato") && !rec.get("candidato").getAsString().isEmpty()
              ? rec.get("candidato").getAsString()
              : rec.has("bersaglio")
                  ? rec.get("bersaglio").getAsString()
                  : rec.has("operazione") ? rec.get("operazione").getAsString() : "");
      r.addProperty("motivo", why);
      rifiutate.add(r);
    }

    private void aggiornaPrevisione(
        final JsonObject portfolio, final String id, final JsonObject v) {
      for (final JsonElement e : portfolio.getAsJsonArray("operazioni")) {
        final JsonObject o = e.getAsJsonObject();
        if (!o.get("id").getAsString().equals(id)) {
          continue;
        }
        final JsonObject x = new JsonObject();
        x.addProperty("round", round);
        x.addProperty("azione", v.get("azione").getAsString());
        x.add("prob_tenuta_stimata", v.get("prob_tenuta_stimata"));
        // the outcome: target the alliance's at the end of the operation's span
        final int span =
            o.get("tipo").getAsString().matches("offensiva|sbarco")
                ? o.get("durata").getAsInt() + 1
                : o.get("durata").getAsInt();
        x.addProperty("verifica_round", round + Math.max(1, span));
        o.getAsJsonArray("previsioni").add(x);
      }
    }

    private static JsonObject riassuntoStato(final JsonObject state) {
      final JsonObject o = new JsonObject();
      o.add("operazioni_in_corso", state.get("operazioni_in_corso"));
      final JsonArray c = new JsonArray();
      for (final JsonElement e : state.getAsJsonArray("candidati")) {
        final JsonObject x = e.getAsJsonObject();
        final JsonObject y = new JsonObject();
        for (final String k :
            List.of(
                "id",
                "tipo",
                "bersaglio",
                "nazioni",
                "round_minimo",
                "presa_stimata_pct",
                "tenuta_ora_50_pct")) {
          if (x.has(k)) {
            y.add(k, x.get(k));
          }
        }
        if (x.has("tenuta_per_impegno")) {
          y.add("tenuta_per_impegno", x.get("tenuta_per_impegno"));
        }
        c.add(y);
      }
      o.add("candidati", c);
      o.add("lettura_avversario", state.get("lettura_avversario"));
      return o;
    }

    private static Optional<JsonObject> trova(final JsonArray defs, final String id) {
      for (final JsonElement e : defs) {
        if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
          return Optional.of(e.getAsJsonObject());
        }
      }
      return Optional.empty();
    }
  }

  // ============================================================================== portfolio

  /** The portfolio of {@code alliance}: every operation launched, with estimates and outcomes. */
  static JsonObject portafoglio(final GameProperties props, final String alliance) {
    if (props.get(KEY_PORTAFOGLIO + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // a fresh one
      }
    }
    final JsonObject o = new JsonObject();
    o.add("operazioni", new JsonArray());
    return o;
  }

  private static void salvaPortafoglio(
      final GameProperties props, final String alliance, final JsonObject p) {
    props.set(KEY_PORTAFOGLIO + alliance, (Serializable) null);
    props.set(KEY_PORTAFOGLIO + alliance, p.toString());
  }

  /** Every operation launched by the strategist of {@code alliance} (ended ones included). */
  public static List<JsonObject> storicoOperazioni(
      final GameProperties props, final String alliance) {
    final List<JsonObject> list = new ArrayList<>();
    portafoglio(props, alliance)
        .getAsJsonArray("operazioni")
        .forEach(e -> list.add(e.getAsJsonObject()));
    return list;
  }

  /** The target of operation {@code id} of {@code alliance} ("" if unknown). */
  static String bersaglioDi(final GameProperties props, final String alliance, final String id) {
    return storicoOperazioni(props, alliance).stream()
        .filter(o -> o.get("id").getAsString().equals(id))
        .map(o -> o.get("bersaglio").getAsString())
        .findFirst()
        .orElse("");
  }

  private static JsonObject lettura(final GameProperties props, final String alliance) {
    if (props.get(KEY_LETTURA + alliance) instanceof String s && !s.isBlank()) {
      try {
        return JsonParser.parseString(s).getAsJsonObject();
      } catch (final RuntimeException e) {
        // none
      }
    }
    return new JsonObject();
  }

  /** The priority reinforcements of this round for {@code alliance}: territory → weight 1-3. */
  public static Map<String, Integer> rinforzi(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()
        || !(data.getProperties().get(KEY_RINFORZI + alliance.get()) instanceof String s)) {
      return Map.of();
    }
    try {
      final JsonObject o = JsonParser.parseString(s).getAsJsonObject();
      if (o.get("round").getAsInt() != data.getSequence().getRound()) {
        return Map.of();
      }
      final Map<String, Integer> map = new java.util.LinkedHashMap<>();
      o.getAsJsonArray("territori")
          .forEach(
              e ->
                  map.put(
                      e.getAsJsonObject().get("territorio").getAsString(),
                      e.getAsJsonObject().get("peso").getAsInt()));
      return map;
    } catch (final RuntimeException e) {
      return Map.of();
    }
  }

  /** Usage of both levels together (calls, tokens, cost) and per level. */
  public static JsonObject usoTotale(final GameProperties props) {
    final JsonObject s = DecisoreOperazione.usoDi(props, KEY_USO);
    final JsonObject o = DecisoreOperazione.uso(props);
    final JsonObject t = new JsonObject();
    t.addProperty("chiamate", s.get("chiamate").getAsInt() + o.get("chiamate").getAsInt());
    t.addProperty(
        "costo_usd",
        Math.round((s.get("costo_usd").getAsDouble() + o.get("costo_usd").getAsDouble()) * 10_000)
            / 10_000.0);
    t.addProperty("tetto_usd", budget(props));
    t.add("strategico", s);
    t.add("ordini_del_turno", o);
    return t;
  }

  /**
   * The strategist's summary of the game (autoplay): portfolios with estimates and outcomes, the
   * operations' state and turn-order decisions, rejected proposals, cost of both levels.
   */
  public static JsonObject riepilogoPartita(final GameState data) {
    return StrategaDiario.riepilogo(data);
  }

  // ======================================================================= end of turn line

  /**
   * The line of the game log at the end of the turn of a nation of the strategist: the alliance's
   * active operations and their state (no estimates, no reasons).
   */
  public static String rigaFineTurno(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return "";
    }
    final GameProperties props = data.getProperties();
    final List<String> parts = new ArrayList<>();
    for (final JsonElement e : OperazioneCongiunta.dinamicheJson(props, alliance.get())) {
      final JsonObject d = e.getAsJsonObject();
      final String id = d.get("id").getAsString();
      final Registro reg = Registro.leggi(props, id);
      if (reg.stato().isFinale()) {
        continue;
      }
      final String nations =
          d.get("apre").getAsString().equals(d.get("chiude").getAsString())
              ? d.get("apre").getAsString()
              : d.get("apre").getAsString() + "+" + d.get("chiude").getAsString();
      final String state =
          switch (reg.stato()) {
            case PREPARAZIONE, POSIZIONAMENTO, ESECUZIONE ->
                reg.stato().testo() + ", tappe al R" + reg.roundObiettivo();
            case TENUTA ->
                "tenuta dal R"
                    + reg.intOf("tenuta_dal", 0)
                    + " ("
                    + d.getAsJsonObject("tenuta").get("round").getAsInt()
                    + " round)";
            default -> reg.stato().testo();
          };
      parts.add(d.get("nome").getAsString() + " [" + nations + "]: " + state);
    }
    return "Stratega LLM "
        + player.getName()
        + ": "
        + (parts.isEmpty() ? "nessuna operazione attiva" : String.join("; ", parts));
  }

  /** The operations that ended during this turn (cancelled, failed, completed), for the log. */
  public static List<String> finiteNelTurno(final GameState data, final GamePlayer player) {
    final Optional<String> alliance = ProPlusSettings.allianceOf(data, player);
    if (alliance.isEmpty()) {
      return List.of();
    }
    final int round = data.getSequence().getRound();
    final List<String> out = new ArrayList<>();
    for (final JsonElement e :
        OperazioneCongiunta.dinamicheJson(data.getProperties(), alliance.get())) {
      final JsonObject d = e.getAsJsonObject();
      final Registro reg = Registro.leggi(data.getProperties(), d.get("id").getAsString());
      if (!reg.stato().isFinale() || reg.flag("fine_annunciata")) {
        continue;
      }
      reg.set("fine_annunciata", true);
      reg.set("fine_round", round);
      reg.salva(data.getProperties());
      out.add(d.get("nome").getAsString() + ": " + reg.stato().testo().toUpperCase(Locale.ROOT));
    }
    return out;
  }

  static void diarioTurno(final GameData data, final List<String> lines) {
    if (!lines.isEmpty()) {
      DiaryRecorder.jointOperation(data, lines, List.of());
    }
  }
}
