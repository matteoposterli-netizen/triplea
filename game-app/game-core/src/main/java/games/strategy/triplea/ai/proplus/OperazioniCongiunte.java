package games.strategy.triplea.ai.proplus;

import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.ProPurchaseOrders;
import games.strategy.triplea.ai.pro.ProWarDecision;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.logging.ProLogger;
import games.strategy.triplea.ai.pro.util.ProMatches;
import games.strategy.triplea.ai.pro.util.ProOddsCalculator;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Definizione;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Registro;
import games.strategy.triplea.ai.proplus.OperazioneCongiunta.Stato;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.util.TuvUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Engine of the hand-written joint operations ({@link OperazioneCongiunta}, step PROVA DI VALORE).
 * Played only in the real game (never in the AI's simulations), before the Pro AI's own move of the
 * phase; the Pro AI then leaves the units the operation moved or keeps where they are ({@link
 * ProPlusTuning#heldUnits}, {@link ProPlusTuning#combatHeldUnits}).
 *
 * <ul>
 *   <li>start (first turn of a nation of the operation): target round from what is feasible, then
 *       fixed (postponed only by the steps' conditions);
 *   <li>preparation / positioning: reserves bought ({@link #ordiniAcquisto}) and brought to the
 *       gathering place by the non-combat move with safe stops;
 *   <li>step 1 (the nation that opens, combat move of the target round): attack when the expected
 *       value of the WHOLE operation (both steps, enemy reinforcements between them) is positive
 *       and the expected loss is within the cap, even if the battle alone is lost;
 *   <li>step 2 (the nation that closes, same round): attack when the defenders left and the TUV the
 *       opener lost are within the tolerance and the win% reaches the closing threshold; otherwise
 *       postpone one round or cancel.
 * </ul>
 */
public final class OperazioniCongiunte {

  /** What the operation did in a phase: units it keeps, diary lines and their events. */
  public record Esito(Set<Unit> tenute, List<String> righe, List<JsonObject> eventi) {
    public static final Esito NESSUNO = new Esito(Set.of(), List.of(), List.of());

    public boolean isEmpty() {
      return tenute.isEmpty() && righe.isEmpty();
    }
  }

  /** Enemy win% above which a gathering place or stop is not safe (Equilibrato: 100 − 60). */
  static final double SOGLIA_SICUREZZA = 40;

  /** Enemy win% on an own capital above which troops do not leave it (as the operations). */
  static final double GUARDIA_CAPITALE = ProPlusTuning.CAPITAL_GUARD_THRESHOLD;

  /** Win% of the second step above which it attacks whatever the defenders left. */
  public static final double WIN_SICURA = 80;

  /** Escorts a convoy may take from its zone. */
  private static final int MAX_SCORTA = 4;

  private final ProData proData;
  private final @Nullable ProOddsCalculator calc;
  private final @Nullable IMoveDelegate moveDel;
  private final GameData data;
  private final GamePlayer player;
  private final GameMap map;
  private final Definizione def;
  private final Registro reg;
  private final int round;
  private final Set<Unit> held = new LinkedHashSet<>();
  private final List<String> righe = new ArrayList<>();
  private final List<JsonObject> eventi = new ArrayList<>();
  private @Nullable ProTerritoryManager enemy;

  /**
   * Prefix of the saved keys of the steps being played: "" for the first target, {@link #FINALE}
   * for the final target of a bridgehead (variant v2, France: Paris).
   */
  private String pre = "";

  /** Prefix of the saved keys of the steps on the final target (variant v2). */
  static final String FINALE = "finale_";

  private OperazioniCongiunte(
      final ProData proData,
      final @Nullable ProOddsCalculator calc,
      final @Nullable IMoveDelegate moveDel,
      final Definizione def) {
    this.proData = proData;
    this.calc = calc;
    this.moveDel = moveDel;
    this.data = proData.getData();
    this.player = proData.getPlayer();
    this.map = data.getMap();
    this.def = def;
    this.reg = Registro.leggi(data.getProperties(), def.id());
    this.round = data.getSequence().getRound();
  }

  // ===================================================================== entry points (ProPlusAi)

  /**
   * Start of the turn of a nation of the operation (real game, purchase phase): the first time, the
   * target round from what is feasible; then the state of the round, the outcome of a taken target.
   */
  public static Esito aggiorna(final ProData proData, final ProOddsCalculator calc) {
    return OperazioneCongiunta.per(proData.getData(), proData.getPlayer())
        .map(d -> new OperazioniCongiunte(proData, calc, null, d).update())
        .orElse(Esito.NESSUNO);
  }

  /**
   * The reserves this nation buys this turn (mandatory purchases), while the operation prepares.
   */
  public static Optional<ProPurchaseOrders> ordiniAcquisto(final ProData proData) {
    final GameState data = proData.getData();
    final Optional<Definizione> def = OperazioneCongiunta.per(data, proData.getPlayer());
    if (def.isEmpty()) {
      return Optional.empty();
    }
    final OperazioneCongiunta.Riserva r = def.get().riserve().get(proData.getPlayer().getName());
    if (r == null || r.acquisti().isEmpty()) {
      return Optional.empty();
    }
    final Registro reg = Registro.leggi(data.getProperties(), def.get().id());
    final int round = data.getSequence().getRound();
    if (reg.stato() == Stato.TENUTA) {
      return ordiniTenuta(proData, def.get(), r, reg);
    }
    if (reg.isNuovo() || reg.stato().isFinale() || round >= reg.roundObiettivo()) {
      return Optional.empty();
    }
    final List<ProPurchaseOrders.Mandatory> mandatory = new ArrayList<>();
    final int transports = transportsNear(proData, def.get());
    final int wanted = def.get().trasporti().getOrDefault(proData.getPlayer().getName(), 0);
    for (final OperazioneCongiunta.Acquisto a : r.acquisti()) {
      final boolean transport = a.tipi().stream().anyMatch(t -> t.contains("transport"));
      if (transport && transports >= wanted) {
        continue;
      }
      mandatory.add(new ProPurchaseOrders.Mandatory(a.categoria(), a.tipi(), a.minimo()));
    }
    if (mandatory.isEmpty()) {
      return Optional.empty();
    }
    final List<String> staging = new ArrayList<>(def.get().raccoltaChiude());
    def.get().porto().ifPresent(p -> staging.add(0, p.territorio()));
    return Optional.of(
        new ProPurchaseOrders(
            "operazione "
                + def.get().nome()
                + ": riserve per il R"
                + reg.roundObiettivo()
                + " ("
                + reg.stato().testo()
                + ")",
            List.of(),
            List.copyOf(mandatory),
            r.piazzamento(),
            List.copyOf(staging),
            r.quota()));
  }

  /**
   * Variant v2: while the target is held and its estimated hold at the enemy's turn (last
   * non-combat estimate) is under {@code soglia_rinforzi}, {@code tenuta.riserve} land units placed
   * first in the target (if it has a factory) or at the nation's placement, nearest to the target.
   */
  private static Optional<ProPurchaseOrders> ordiniTenuta(
      final ProData proData,
      final Definizione def,
      final OperazioneCongiunta.Riserva r,
      final Registro reg) {
    final double estimate =
        reg.json().has("ultima_stima_tenuta")
            ? reg.json().get("ultima_stima_tenuta").getAsDouble()
            : 100;
    if (def.tenuta().riserve() <= 0 || estimate >= def.tenuta().sogliaRinforzi()) {
      return Optional.empty();
    }
    final List<String> types =
        r.acquisti().stream()
            .filter(a -> a.tipi().stream().noneMatch(t -> t.contains("transport")))
            .flatMap(a -> a.tipi().stream())
            .distinct()
            .toList();
    if (types.isEmpty()) {
      return Optional.empty();
    }
    final String held =
        reg.json().has("tenuta_bersaglio")
            ? reg.json().get("tenuta_bersaglio").getAsString()
            : def.bersaglio();
    final List<String> placement = new ArrayList<>();
    placement.add(held);
    placement.addAll(r.piazzamento());
    return Optional.of(
        new ProPurchaseOrders(
            String.format(
                Locale.ROOT,
                "operazione %s: riserve per la tenuta di %s (tenuta stimata %.0f%% < %.0f%%)",
                def.nome(),
                held,
                estimate,
                def.tenuta().sogliaRinforzi()),
            List.of(),
            List.of(new ProPurchaseOrders.Mandatory("terra", types, def.tenuta().riserve())),
            List.copyOf(placement),
            List.of(held),
            r.quota()));
  }

  /**
   * Combat move of the target round: step 1 for the nation that opens, step 2 for the one that
   * closes. In the positioning round the units at the gathering place stay out of the Pro AI's
   * attacks.
   */
  public static Esito combattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate moveDel) {
    return OperazioneCongiunta.per(proData.getData(), proData.getPlayer())
        .map(d -> new OperazioniCongiunte(proData, calc, moveDel, d).combat())
        .orElse(Esito.NESSUNO);
  }

  /** Non-combat move: reserves to the gathering place, the closer's completion, the outcome. */
  public static Esito nonCombattimento(
      final ProData proData, final ProOddsCalculator calc, final IMoveDelegate moveDel) {
    return OperazioneCongiunta.per(proData.getData(), proData.getPlayer())
        .map(d -> new OperazioniCongiunte(proData, calc, moveDel, d).nonCombat())
        .orElse(Esito.NESSUNO);
  }

  /**
   * War the operation needs: the opener declares war on the target's owner from the first round the
   * map allows ({@code guerra_da_round}), until the operation ends. Empty otherwise.
   */
  public static Optional<ProWarDecision> guerra(final ProData proData, final GamePlayer enemy) {
    final GameState data = proData.getData();
    final Optional<Definizione> def = OperazioneCongiunta.per(data, proData.getPlayer());
    if (def.isEmpty() || !def.get().isApre(proData.getPlayer().getName())) {
      return Optional.empty();
    }
    final Integer from = def.get().guerraDaRound().get(proData.getPlayer().getName());
    final Territory target = data.getMap().getTerritoryOrNull(def.get().bersaglio());
    if (from == null || target == null || !target.getOwner().equals(enemy)) {
      return Optional.empty();
    }
    final Registro reg = Registro.leggi(data.getProperties(), def.get().id());
    if (reg.isNuovo() || reg.stato().isFinale() || data.getSequence().getRound() < from) {
      return Optional.empty();
    }
    return Optional.of(
        new ProWarDecision(
            ProWarDecision.Kind.DECLARE,
            "per l'operazione congiunta " + def.get().nome() + " (R" + reg.roundObiettivo() + ")"));
  }

  /**
   * Priority bonus of an access territory the closer must take first (the operation's value), while
   * the operation prepares; 0 otherwise.
   */
  public static double bonusAccesso(final ProData proData, final Territory t) {
    final GameState data = proData.getData();
    final Optional<Definizione> def = OperazioneCongiunta.per(data, proData.getPlayer());
    if (def.isEmpty()
        || !def.get().isChiude(proData.getPlayer().getName())
        || !def.get().accesso().contains(t.getName())) {
      return 0;
    }
    final Registro reg = Registro.leggi(data.getProperties(), def.get().id());
    return reg.isNuovo() || reg.stato().isFinale() ? 0 : def.get().valore();
  }

  // ================================================================================== start

  private Esito update() {
    final Territory target = map.getTerritoryOrNull(def.bersaglio());
    if (target == null) {
      return Esito.NESSUNO;
    }
    if (reg.isNuovo()) {
      final Fattibilita f = fattibilita(target);
      reg.setRoundObiettivo(f.round());
      reg.set("fattibilita", f.testo());
      reg.set("avvio_round", round);
      reg.set("avvio_nazione", player.getName());
      reg.setRinvii(0);
      reg.setStato(Stato.PREPARAZIONE);
      final JsonObject e = event("avvio");
      e.addProperty("round_obiettivo", f.round());
      e.addProperty("fattibilita", f.testo());
      note(
          e,
          String.format(
              Locale.ROOT,
              "Operazione %s (%s apre, %s chiude, bersaglio %s): avvio al R%d, round obiettivo R%d"
                  + " - fattibilita': %s",
              def.nome(),
              def.apre(),
              def.chiude(),
              def.bersaglio(),
              round,
              f.round(),
              f.testo()));
    }
    // outcome of a taken target: held after 1 and 2 rounds (checked at the opener's turn)
    if (def.isApre(player.getName()) && reg.flag("presa")) {
      final int taken = reg.intOf("round_presa", 0);
      for (final int after : new int[] {1, 2}) {
        final String key = "tenuto_dopo_" + after;
        if (round == taken + after && !reg.json().has(key)) {
          final boolean held = Matches.isTerritoryAllied(player).test(target);
          reg.set(key, held);
          final JsonObject e = event("tenuta");
          e.addProperty("dopo_round", after);
          e.addProperty("tenuto", held);
          note(
              e,
              String.format(
                  Locale.ROOT,
                  "Operazione %s: %s %s dopo %d round",
                  def.nome(),
                  def.bersaglio(),
                  held ? "ancora dell'alleanza" : "perso",
                  after));
        }
      }
    }
    if (reg.stato() == Stato.TENUTA) {
      tenutaUpdate();
    }
    if (!reg.stato().isFinale() && reg.stato() != Stato.TENUTA) {
      if (round > reg.roundObiettivo()) {
        // the target round passed without the steps (eg the opener had no turn): postpone
        postpone(
            "round obiettivo R" + reg.roundObiettivo() + " passato senza tappe", Stato.ESECUZIONE);
      }
      if (!reg.stato().isFinale() && reg.stato() != Stato.TENUTA) {
        final Stato now =
            round < reg.roundObiettivo() - 1
                ? Stato.PREPARAZIONE
                : round == reg.roundObiettivo() - 1 ? Stato.POSIZIONAMENTO : Stato.ESECUZIONE;
        if (now != reg.stato()) {
          reg.setStato(now);
          final JsonObject e = event("stato");
          note(
              e,
              String.format(
                  Locale.ROOT,
                  "Operazione %s: %s (round obiettivo R%d)",
                  def.nome(),
                  now.testo(),
                  reg.roundObiettivo()));
        }
      }
    }
    reg.salva(data.getProperties());
    return result();
  }

  /** Target round and why. */
  record Fattibilita(int round, String testo) {}

  /**
   * The target round from what is feasible at the start: planes of the opener to their gathering
   * place, land units of the closer next to the target (one more round if an access territory is
   * enemy), or, with a shuttle, the war the map allows plus the crossing.
   */
  private Fattibilita fattibilita(final Territory target) {
    final List<String> why = new ArrayList<>();
    int needed = round + 1;
    final GamePlayer opener = data.getPlayerList().getPlayerId(def.apre());
    final GamePlayer closer = data.getPlayerList().getPlayerId(def.chiude());
    if (def.aereiApre() > 0 && opener != null) {
      final List<Integer> rounds = new ArrayList<>();
      for (final Territory t : map.getTerritories()) {
        for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(opener).and(Matches.unitIsAir()))) {
          if (u.getUnitAttachment().getAttack(opener) <= 0) {
            continue;
          }
          final int move = Math.max(1, u.getUnitAttachment().getMovement(opener));
          final int dist =
              def.raccoltaApre().stream()
                  .map(map::getTerritoryOrNull)
                  .filter(Objects::nonNull)
                  .mapToInt(r -> map.getDistance(t, r))
                  .filter(d -> d >= 0)
                  .min()
                  .orElse(99);
          rounds.add(dist == 0 ? 0 : (dist + move - 1) / move);
        }
      }
      rounds.sort(Integer::compare);
      final int k = Math.min(Math.min(2, def.aereiApre()), rounds.size());
      final int air = k == 0 ? 99 : rounds.get(k - 1);
      needed = Math.max(needed, round + air);
      why.add("aerei " + def.apre() + " alla raccolta in " + air + " round");
    }
    if (def.zonaRaccolta().isEmpty() && closer != null) {
      final boolean access =
          def.accesso().stream()
              .map(map::getTerritoryOrNull)
              .filter(Objects::nonNull)
              .anyMatch(Matches.isTerritoryEnemy(closer));
      int land = 99;
      for (final Territory t : map.getTerritories()) {
        if (t.isWater()
            || !t.anyUnitsMatch(
                Matches.unitIsOwnedBy(closer).and(Matches.unitIsLand()).and(attacks(closer)))) {
          continue;
        }
        final int d = map.getDistance(t, target, Matches.territoryIsLand());
        if (d > 0) {
          land = Math.min(land, d - 1);
        }
      }
      // by sea: the closer's transports and troops can land on the target this turn
      final boolean bySea = !landing(target, closer).isEmpty();
      final int rounds = bySea ? 0 : land + (access ? 1 : 0);
      needed = Math.max(needed, round + rounds);
      why.add(
          "truppe "
              + def.chiude()
              + " accanto al bersaglio in "
              + rounds
              + " round"
              + (bySea
                  ? " (sbarco possibile da subito)"
                  : access ? " (accesso nemico: " + String.join(", ", def.accesso()) + ")" : ""));
    }
    if (def.porto().isPresent()
        && opener != null
        && def.porto().get().nazione().equals(def.apre())) {
      final OperazioneCongiunta.Porto p = def.porto().get();
      final Territory owner = target;
      final boolean atWar =
          !owner.getOwner().isNull()
              && data.getRelationshipTracker().isAtWar(opener, owner.getOwner());
      final int war =
          atWar ? round : Math.max(round, def.guerraDaRound().getOrDefault(def.apre(), round));
      final Territory from = map.getTerritoryOrNull(p.zone().get(0));
      final Territory to = map.getTerritoryOrNull(p.zonaArrivo());
      final int dist =
          from == null || to == null ? 99 : map.getDistance(from, to, Matches.territoryIsWater());
      final int crossing = dist < 0 ? 99 : (dist + 1) / 2;
      needed = Math.max(needed, war + crossing);
      why.add(
          (atWar ? "gia' in guerra" : "guerra possibile dal R" + war)
              + ", traversata "
              + p.zone().get(0)
              + " -> "
              + p.zonaArrivo()
              + " "
              + dist
              + " zone = "
              + crossing
              + " round");
    }
    final int clamped = Math.max(def.roundMin(), Math.min(def.roundMax(), needed));
    if (clamped != needed) {
      why.add("limitato a R" + def.roundMin() + "-R" + def.roundMax());
    }
    return new Fattibilita(clamped, String.join("; ", why));
  }

  // ================================================================================== combat

  private Esito combat() {
    if (calc == null || moveDel == null || reg.isNuovo() || reg.stato().isFinale()) {
      return Esito.NESSUNO;
    }
    final Territory target = map.getTerritoryOrNull(def.bersaglio());
    if (target == null) {
      return Esito.NESSUNO;
    }
    if (reg.stato() == Stato.TENUTA) {
      try {
        tenutaCombat();
      } finally {
        pre = "";
        reg.salva(data.getProperties());
      }
      return result();
    }
    if (round == reg.roundObiettivo() - 1) {
      // positioning: the units gathered stay out of the Pro AI's attacks; not in the round of a
      // postponement (fix of step PROVA2: the closer, after the opener postponed in the same
      // round, was "positioning" and its troops and transports at the gathering place were
      // withheld, so the Pro AI's combat move saw no landing on the target)
      if (reg.intOf("rinvio_round", 0) == round) {
        note(
            event("posizionamento_libero"),
            "Operazione "
                + def.nome()
                + ": rinvio in questo round, le unita' in raccolta di "
                + player.getName()
                + " restano a Hard per il combattimento");
        reg.salva(data.getProperties());
        return result();
      }
      held.addAll(gathered());
      return result();
    }
    if (round != reg.roundObiettivo()) {
      return Esito.NESSUNO;
    }
    try {
      if (def.isApre(player.getName()) && reg.intOf("tappa1_round", 0) != round) {
        step1(target);
      } else if (def.isChiude(player.getName())
          && reg.intOf("tappa1_round", 0) == round
          && reg.intOf("tappa2_round", 0) != round) {
        step2(target);
      }
    } finally {
      reg.salva(data.getProperties());
    }
    return result();
  }

  /** Step 1: the opener weakens the target, judged on the value of the whole operation. */
  private void step1(final Territory target) {
    final GamePlayer closer = data.getPlayerList().getPlayerId(def.chiude());
    if (Matches.isTerritoryAllied(player).test(target) && v2()) {
      // variant v2: taken without the operation (eg by the Pro AI): the operation holds it
      taken(target, "senza operazione (" + target.getOwner().getName() + ")");
      return;
    }
    if (Matches.isTerritoryAllied(player).test(target)) {
      // taken without the operation (eg by the Pro AI): it waits for the enemy to take it back
      // (no postponement counted); past the last round it was superseded
      if (round >= def.roundMax()) {
        reg.setStato(Stato.ANNULLATA);
        reg.set("motivo_fine", "superata: bersaglio dell'alleanza fino al round massimo");
        final JsonObject e = event("annullata");
        e.addProperty("motivo", "superata: bersaglio gia' dell'alleanza fino al round massimo");
        note(
            e,
            "Operazione "
                + def.nome()
                + " ANNULLATA (superata): "
                + def.bersaglio()
                + " e' dell'alleanza fino al R"
                + def.roundMax());
        return;
      }
      reg.setRoundObiettivo(round + 1);
      reg.set("attese", reg.intOf("attese", 0) + 1);
      final JsonObject e = event("attesa");
      e.addProperty("round_obiettivo", round + 1);
      note(
          e,
          "Operazione "
              + def.nome()
              + " IN ATTESA: "
              + def.bersaglio()
              + " e' gia' dell'alleanza (preso senza l'operazione), tappa 1 al R"
              + (round + 1)
              + " se il nemico lo riprende");
      return;
    }
    final List<Unit> defenders = defenders(target);
    if (defenders.isEmpty()) {
      // nothing to weaken: the closer may take it (step 2 decides)
      final JsonObject e = event("tappa1");
      if (v2() && closer != null) {
        // variant v2: worth it only if the closer's troops can hold it against the counterattack
        final List<Unit> garrison =
            alliedAttackers(target, closer).stream().filter(Matches.unitIsLand()).toList();
        final StimaTenuta hold = stimaTenuta(target, garrison);
        final double ev = valoreAlleanza(def.valore(), 1, 1, 0, 0, hold.tenuta(), tuv(garrison));
        e.addProperty("tenuta_stimata", Math.round(hold.tenuta()));
        e.addProperty("contrattacco_tuv", Math.round(tuv(hold.attaccanti())));
        e.addProperty("contrattacco_acquisti_tuv", Math.round(tuv(hold.acquisti())));
        e.addProperty("presidio", OperazioniAnfibie.unitsText(garrison));
        e.addProperty("tuv_presidio", Math.round(tuv(garrison)));
        e.addProperty("valore_alleanza", Math.round(ev));
        if (ev <= 0) {
          final String why =
              String.format(
                  Locale.ROOT,
                  "contrattacco atteso: tenuta stimata %.0f%%, valore d'alleanza %.1f <= 0",
                  hold.tenuta(),
                  ev);
          e.addProperty("eseguita", false);
          e.addProperty("motivo", why);
          note(
              e,
              String.format(
                  Locale.ROOT,
                  "Operazione %s, tappa 1 di %s NON eseguita: %s senza difensori ma %s -"
                      + " contrattacco %s (TUV %.0f) + acquisti %s (TUV %.0f) contro %s (TUV"
                      + " %.0f)",
                  def.nome(),
                  player.getName(),
                  target.getName(),
                  why,
                  hold.attaccanti().isEmpty()
                      ? "nessuno"
                      : OperazioniAnfibie.unitsText(hold.attaccanti()),
                  tuv(hold.attaccanti()),
                  hold.acquisti().isEmpty()
                      ? "nessuno"
                      : OperazioniAnfibie.unitsText(hold.acquisti()),
                  tuv(hold.acquisti()),
                  garrison.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(garrison),
                  tuv(garrison)));
          postpone("tappa 1: " + why, Stato.ESECUZIONE);
          return;
        }
      }
      e.addProperty("eseguita", true);
      e.addProperty("motivo", "nessun difensore: tappa 1 non necessaria");
      reg.set(k("avviata"), true);
      reg.set(k("avvii"), reg.intOf(k("avvii"), 0) + 1);
      reg.set(k("tappa1_round"), round);
      final JsonObject t1 = new JsonObject();
      t1.addProperty("round", round);
      t1.addProperty("tuv_attaccanti", 0);
      t1.addProperty("tuv_difensori", 0);
      t1.addProperty("tuv_residui_stimati", 0);
      t1.addProperty("perdita_attesa", 0);
      t1.addProperty("win", 100);
      reg.set(k("tappa1"), t1);
      note(
          e,
          "Operazione "
              + def.nome()
              + ", tappa 1 di "
              + player.getName()
              + ": "
              + def.bersaglio()
              + " senza difensori, nessun attacco necessario; tappa 2 di "
              + def.chiude()
              + " nello stesso round");
      return;
    }
    final Attacco a1 = attackers(target, player, true, false);
    final ProBattleResult r1 =
        a1.units().isEmpty()
            ? null
            : a1.hasLand()
                ? calc.calculateBattleResults(proData, target, a1.units(), defenders, Set.of())
                : calc.callBattleCalcWithRetreatAir(
                    proData, target, a1.units(), defenders, Set.of());
    final double tuvA1 = tuv(a1.units());
    final double loss1 = r1 == null ? 0 : tuvA1 - tuv(r1.getAverageAttackersRemaining());
    final double defTuv = tuv(defenders);
    final Collection<Unit> left =
        r1 == null ? defenders : new ArrayList<>(r1.getAverageDefendersRemaining());
    final List<Unit> reinforcements = enemyReinforcements(target, closer);
    final List<Unit> defenders2 = new ArrayList<>(left);
    defenders2.addAll(reinforcements);
    final List<Unit> a2 = closer == null ? List.of() : alliedAttackers(target, closer);
    final ProBattleResult r2 =
        a2.isEmpty() || closer == null
            ? null
            : calc.calculateBattleResults(proData, target, a2, defenders2, Set.of());
    final double win1 = r1 == null ? 0 : r1.getWinPercentage();
    final double take1 = a1.hasLand() ? win1 / 100 : 0;
    final double win2 = r2 == null ? 0 : r2.getWinPercentage();
    final double take = take1 + (1 - take1) * win2 / 100;
    final double evV1 =
        take * def.valore()
            + (r1 == null ? 0 : r1.getTuvSwing())
            + (1 - take1) * (r2 == null ? 0 : r2.getTuvSwing());
    // variant v2: the value of the alliance counts the enemy's counterattack on the target held by
    // the survivors of the steps and the planned garrison
    final double loss2 = r2 == null ? 0 : tuv(a2) - tuv(r2.getAverageAttackersRemaining());
    final List<Unit> garrison = new ArrayList<>();
    StimaTenuta hold = null;
    double ev = evV1;
    double evNoHold = evV1;
    if (v2()) {
      final Collection<Unit> survivors =
          r2 != null
              ? r2.getAverageAttackersRemaining()
              : r1 == null ? List.of() : r1.getAverageAttackersRemaining();
      survivors.stream().filter(Matches.unitIsLand()).forEach(garrison::add);
      if (garrison.isEmpty() && take > 0) {
        // the battle calculator's representative run often keeps only planes (land units are
        // taken as casualties first), but a capture needs a land unit: at least the strongest one
        final List<Unit> land =
            (a2.isEmpty() ? a1.units() : a2).stream().filter(Matches.unitIsLand()).toList();
        land.stream()
            .max(
                Comparator.comparingInt((Unit u) -> u.getUnitAttachment().getDefense(u.getOwner()))
                    .thenComparing(u -> -tuv(List.of(u))))
            .ifPresent(garrison::add);
      }
      if (closer != null) {
        reach(target, closer).stream()
            .filter(Matches.unitIsLand())
            .filter(u -> !a2.contains(u) && !garrison.contains(u))
            .forEach(garrison::add);
      }
      hold = stimaTenuta(target, garrison);
      evNoHold = valoreAlleanza(def.valore(), take, take1, loss1, loss2, 100, 0);
      ev = valoreAlleanza(def.valore(), take, take1, loss1, loss2, hold.tenuta(), tuv(garrison));
    }
    final JsonObject e = event("tappa1");
    if (hold != null) {
      e.addProperty("tenuta_stimata", Math.round(hold.tenuta()));
      e.addProperty("contrattacco_tuv", Math.round(tuv(hold.attaccanti())));
      e.addProperty("contrattacco_acquisti_tuv", Math.round(tuv(hold.acquisti())));
      e.addProperty("contrattacco", OperazioniAnfibie.unitsText(hold.attaccanti()));
      e.addProperty("contrattacco_acquisti", OperazioniAnfibie.unitsText(hold.acquisti()));
      e.addProperty("presidio", OperazioniAnfibie.unitsText(garrison));
      e.addProperty("tuv_presidio", Math.round(tuv(garrison)));
      e.addProperty("valore_alleanza", Math.round(ev));
      e.addProperty("valore_senza_contrattacco", Math.round(evNoHold));
    }
    e.addProperty("attaccanti", OperazioniAnfibie.unitsText(a1.units()));
    e.addProperty("tuv_attaccanti", Math.round(tuvA1));
    e.addProperty("difensori", OperazioniAnfibie.unitsText(defenders));
    e.addProperty("tuv_difensori", Math.round(defTuv));
    e.addProperty("win", Math.round(win1));
    e.addProperty("perdita_attesa", Math.round(loss1));
    e.addProperty("tuv_residui_stimati", Math.round(tuv(left)));
    e.addProperty("rinforzi_nemici", Math.round(tuv(reinforcements)));
    e.addProperty("attaccanti_tappa2", OperazioniAnfibie.unitsText(a2));
    e.addProperty("win_tappa2_stimata", Math.round(win2));
    e.addProperty("presa_stimata", Math.round(take * 100));
    e.addProperty("valore_atteso", Math.round(ev));
    final String numbers =
        String.format(
                Locale.ROOT,
                "attaccanti %s (TUV %.0f) contro %s (TUV %.0f): win%% %.0f, perdita attesa %.0f TUV"
                    + " (tetto %.0f), difensori residui stimati TUV %.0f + rinforzi nemici %.0f; tappa"
                    + " 2 di %s con %s: win%% %.0f; presa stimata %.0f%%, valore atteso"
                    + " dell'operazione %.1f",
                a1.units().isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(a1.units()),
                tuvA1,
                defenders.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(defenders),
                defTuv,
                win1,
                loss1,
                def.tettoPerdita(),
                tuv(left),
                tuv(reinforcements),
                def.chiude(),
                a2.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(a2),
                win2,
                take * 100,
                ev)
            + (hold == null
                ? ""
                : String.format(
                    Locale.ROOT,
                    "; contrattacco atteso %s (TUV %.0f) + acquisti possibili %s (TUV %.0f) contro"
                        + " presidio %s (TUV %.0f): tenuta stimata %.0f%%; valore d'alleanza"
                        + " %.1f (senza contrattacco %.1f)",
                    hold.attaccanti().isEmpty()
                        ? "nessuno"
                        : OperazioniAnfibie.unitsText(hold.attaccanti()),
                    tuv(hold.attaccanti()),
                    hold.acquisti().isEmpty()
                        ? "nessuno"
                        : OperazioniAnfibie.unitsText(hold.acquisti()),
                    tuv(hold.acquisti()),
                    garrison.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(garrison),
                    tuv(garrison),
                    hold.tenuta(),
                    ev,
                    evNoHold));
    final String why =
        a1.units().isEmpty()
            ? "nessuna unita' di " + player.getName() + " puo' attaccare"
            : loss1 > def.tettoPerdita()
                ? String.format(
                    Locale.ROOT, "perdita attesa %.0f > tetto %.0f", loss1, def.tettoPerdita())
                : ev <= 0 && hold != null && evNoHold > 0
                    ? String.format(
                        Locale.ROOT,
                        "contrattacco atteso: tenuta stimata %.0f%%, valore d'alleanza %.1f <= 0",
                        hold.tenuta(),
                        ev)
                    : ev <= 0
                        ? String.format(
                            Locale.ROOT,
                            hold == null
                                ? "valore atteso %.1f <= 0"
                                : "valore d'alleanza %.1f <= 0",
                            ev)
                        : "";
    e.addProperty("eseguita", why.isEmpty());
    if (!why.isEmpty()) {
      e.addProperty("motivo", why);
      note(
          e,
          "Operazione "
              + def.nome()
              + ", tappa 1 di "
              + player.getName()
              + " NON eseguita: "
              + why
              + " - "
              + numbers);
      postpone("tappa 1: " + why, Stato.ESECUZIONE);
      return;
    }
    final List<Unit> moved = execute(target, a1);
    e.addProperty("mosse", OperazioniAnfibie.unitsText(moved));
    if (moved.isEmpty()) {
      e.addProperty("eseguita", false);
      e.addProperty("motivo", "nessuna mossa riuscita");
      note(e, "Operazione " + def.nome() + ", tappa 1: nessuna mossa riuscita - " + numbers);
      postpone("tappa 1: nessuna mossa riuscita", Stato.ESECUZIONE);
      return;
    }
    held.addAll(moved);
    reg.set(k("avviata"), true);
    reg.set(k("avvii"), reg.intOf(k("avvii"), 0) + 1);
    reg.set(k("tappa1_round"), round);
    final JsonObject t1 = new JsonObject();
    t1.addProperty("round", round);
    t1.addProperty("tuv_attaccanti", tuv(moved));
    t1.addProperty("tuv_difensori", defTuv);
    t1.addProperty("tuv_residui_stimati", tuv(left));
    t1.addProperty("perdita_attesa", loss1);
    t1.addProperty("win", win1);
    if (hold != null) {
      t1.addProperty("tenuta_stimata", hold.tenuta());
    }
    t1.addProperty("ids", String.join(",", moved.stream().map(u -> u.getId().toString()).toList()));
    reg.set(k("tappa1"), t1);
    note(
        e,
        "Operazione "
            + def.nome()
            + ", tappa 1 di "
            + player.getName()
            + " su "
            + def.bersaglio()
            + " ESEGUITA: "
            + numbers);
  }

  /** Step 2: the closer takes the target if the first step left it within the tolerance. */
  private void step2(final Territory target) {
    final JsonObject t1 = reg.oggetto(k("tappa1")).orElse(new JsonObject());
    final GamePlayer opener = data.getPlayerList().getPlayerId(def.apre());
    final int[] battle1 = opener == null ? new int[2] : battleLoss(target, opener);
    final double lost1 = Math.max(battle1[0], lostTuv(t1));
    reg.set(k("tappa2_round"), round);
    reg.set(k("tappa1_tuv_perso"), (int) Math.round(lost1));
    reg.set(k("tappa1_tuv_inflitto"), battle1[1]);
    if (Matches.isTerritoryAllied(player).test(target)) {
      final JsonObject e = event("tappa2");
      e.addProperty("eseguita", false);
      e.addProperty("motivo", "bersaglio gia' preso dalla tappa 1");
      e.addProperty("tuv_perso_tappa1", Math.round(lost1));
      note(
          e,
          String.format(
              Locale.ROOT,
              "Operazione %s, tappa 2 di %s: %s gia' preso da %s (TUV perso nella tappa 1 %.0f)"
                  + ", rinforzo nel non combattimento",
              def.nome(),
              player.getName(),
              def.bersaglio(),
              def.apre(),
              lost1));
      return;
    }
    final List<Unit> defenders = defenders(target);
    final double left = tuv(defenders);
    final double estimated =
        t1.has("tuv_residui_stimati") ? t1.get("tuv_residui_stimati").getAsDouble() : left;
    final Attacco a2 = attackers(target, player, false, true);
    final ProBattleResult r2 =
        a2.units().isEmpty()
            ? null
            : calc.calculateBattleResults(proData, target, a2.units(), defenders, Set.of());
    final double win2 = r2 == null ? 0 : r2.getWinPercentage();
    final double loss2 = r2 == null ? 0 : tuv(a2.units()) - tuv(r2.getAverageAttackersRemaining());
    final double tolerated = estimated * (1 + def.tolleranzaDifensori());
    final JsonObject e = event("tappa2");
    e.addProperty("difensori_residui", OperazioniAnfibie.unitsText(defenders));
    e.addProperty("tuv_residui", Math.round(left));
    e.addProperty("tuv_residui_stimati", Math.round(estimated));
    e.addProperty("tuv_tollerati", Math.round(tolerated));
    e.addProperty("tuv_perso_tappa1", Math.round(lost1));
    e.addProperty("attaccanti", OperazioniAnfibie.unitsText(a2.units()));
    e.addProperty("win", Math.round(win2));
    e.addProperty("perdita_attesa", Math.round(loss2));
    final String numbers =
        String.format(
            Locale.ROOT,
            "difensori residui %s (TUV %.0f, stimati %.0f, tollerati %.0f), TUV perso nella tappa 1"
                + " %.0f (tetto %.0f); attaccanti %s: win%% %.0f (soglia %.0f), perdita attesa %.0f",
            defenders.isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(defenders),
            left,
            estimated,
            tolerated,
            lost1,
            def.tettoPerdita(),
            a2.units().isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(a2.units()),
            win2,
            def.sogliaChiusura(),
            loss2);
    final List<String> fails =
        condizioneTappa2(def, player.getName(), a2.units().isEmpty(), left, estimated, lost1, win2);
    if (!fails.isEmpty()) {
      final String why = String.join(", ", fails);
      e.addProperty("eseguita", false);
      e.addProperty("motivo", why);
      note(
          e,
          "Operazione "
              + def.nome()
              + ", tappa 2 di "
              + player.getName()
              + " NON eseguita: "
              + why
              + " - "
              + numbers);
      postpone("tappa 2: " + why, Stato.ESECUZIONE);
      return;
    }
    // the operation moves what it can itself (units in reach, landing from the gathering zone);
    // then the Pro AI's combat move attacks the target first, down to the closing threshold,
    // with every other unit it can bring (its landings and bombardment included): forzaBersaglio
    final List<Unit> moved = execute(target, attackers(target, player, false, false));
    held.addAll(moved);
    if (!moved.isEmpty()) {
      reg.set(k("tappa2_eseguita_round"), round);
    }
    reg.set(k("tappa2_forzata_round"), round);
    e.addProperty("eseguita", true);
    e.addProperty("mosse", OperazioniAnfibie.unitsText(moved));
    e.addProperty(
        "esecuzione", "mosse dell'operazione + combattimento di Hard forzato sul bersaglio");
    note(
        e,
        "Operazione "
            + def.nome()
            + ", tappa 2 di "
            + player.getName()
            + " su "
            + def.bersaglio()
            + " APPROVATA ("
            + (moved.isEmpty()
                ? "nessuna mossa dell'operazione"
                : "mosse dell'operazione: " + OperazioniAnfibie.unitsText(moved))
            + "; poi attacco del combattimento di Hard con priorita' massima e soglia minima "
            + Math.round(def.sogliaChiusura())
            + "%): "
            + numbers);
  }

  /**
   * Variant v2: the value of the alliance of a launch = value of the target × P(taken) × P(held
   * against the counterattack) − expected losses (the TUV of step 1, of step 2 when step 1 does not
   * take it, and the garrison lost with the hold). The operation goes on only when it is positive.
   *
   * @param take probability that the target is taken by the two steps
   * @param take1 probability that step 1 takes it alone
   * @param loss1 expected TUV lost in step 1
   * @param loss2 expected TUV lost in step 2
   * @param tenuta estimated hold% at the enemy's turns
   * @param tuvPresidio TUV of the garrison (survivors and the planned reinforcements)
   */
  public static double valoreAlleanza(
      final double valore,
      final double take,
      final double take1,
      final double loss1,
      final double loss2,
      final double tenuta,
      final double tuvPresidio) {
    final double hold = tenuta / 100;
    return take * valore * hold - (loss1 + (1 - take1) * loss2 + (1 - hold) * tuvPresidio);
  }

  /**
   * Test hook: the counterattack this nation's operation expects on {@code target} (enemy units in
   * reach and the units the enemies may buy for it). Empty without an operation.
   */
  public static Optional<Minaccia> minacciaPer(
      final ProData proData, final ProOddsCalculator calc, final Territory target) {
    return OperazioneCongiunta.per(proData.getData(), proData.getPlayer())
        .map(d -> new OperazioniCongiunte(proData, calc, null, d).minaccia(target));
  }

  /**
   * Condition of step 2: the reasons not to attack (empty = attack). Defenders left within the
   * estimate of step 1 plus the tolerance (or a sure win), TUV the opener lost within the cap, win%
   * at least the closing threshold, some unit able to attack.
   */
  public static List<String> condizioneTappa2(
      final Definizione def,
      final String nation,
      final boolean noAttackers,
      final double left,
      final double estimated,
      final double lost1,
      final double win2) {
    final double tolerated = estimated * (1 + def.tolleranzaDifensori());
    final List<String> fails = new ArrayList<>();
    if (noAttackers) {
      fails.add("nessuna unita' di " + nation + " puo' attaccare");
    }
    if (left > tolerated && win2 < WIN_SICURA) {
      fails.add(
          String.format(
              Locale.ROOT, "difensori residui TUV %.0f > tollerati %.0f", left, tolerated));
    }
    if (lost1 > def.tettoPerdita()) {
      fails.add(
          String.format(
              Locale.ROOT, "TUV perso nella tappa 1 %.0f > tetto %.0f", lost1, def.tettoPerdita()));
    }
    if (win2 < def.sogliaChiusura()) {
      fails.add(String.format(Locale.ROOT, "win%% %.0f < soglia %.0f", win2, def.sogliaChiusura()));
    }
    return fails;
  }

  /**
   * Postpones the operation one round (step conditions not met), or cancels it after {@code
   * max_rinvii} postponements or past {@code round_max}.
   */
  private void postpone(final String why, final Stato next) {
    if (!pre.isEmpty()) {
      // the final target (variant v2): no target round, the bridgehead stays held; tried again
      // next round
      reg.set(k("rinvii"), reg.intOf(k("rinvii"), 0) + 1);
      final JsonObject e = event("rinvio_finale");
      e.addProperty("motivo", why);
      note(
          e,
          String.format(
              Locale.ROOT,
              "Operazione %s: tappa su %s RINVIATA (%s); si tiene la testa di ponte %s",
              def.nome(),
              def.bersaglioFinale(),
              why,
              def.bersaglio()));
      return;
    }
    final int target = Math.max(round, reg.roundObiettivo()) + 1;
    if (reg.rinvii() >= def.maxRinvii() || target > def.roundMax()) {
      reg.setStato(Stato.ANNULLATA);
      reg.set("motivo_fine", why);
      final JsonObject e = event("annullata");
      e.addProperty("motivo", why);
      e.addProperty("rinvii", reg.rinvii());
      note(
          e,
          String.format(
              Locale.ROOT,
              "Operazione %s ANNULLATA (%s; rinvii %d di %d, round massimo R%d)",
              def.nome(),
              why,
              reg.rinvii(),
              def.maxRinvii(),
              def.roundMax()));
      return;
    }
    reg.setRinvii(reg.rinvii() + 1);
    reg.set("rinvio_round", round);
    reg.setRoundObiettivo(target);
    reg.setStato(next);
    final JsonObject e = event("rinvio");
    e.addProperty("motivo", why);
    e.addProperty("round_obiettivo", target);
    note(
        e,
        String.format(
            Locale.ROOT,
            "Operazione %s RINVIATA al R%d (%s; rinvio %d di %d)",
            def.nome(),
            target,
            why,
            reg.rinvii(),
            def.maxRinvii()));
  }

  /** TUV of the units of step 1 no longer on the map. */
  private double lostTuv(final JsonObject t1) {
    if (!t1.has("ids")) {
      return 0;
    }
    final Set<String> alive = new HashSet<>();
    map.getTerritories().forEach(t -> t.getUnits().forEach(u -> alive.add(u.getId().toString())));
    final Set<String> ids = new HashSet<>(List.of(t1.get("ids").getAsString().split(",")));
    final double before = t1.get("tuv_attaccanti").getAsDouble();
    final List<Unit> survivors = new ArrayList<>();
    map.getTerritories()
        .forEach(
            t ->
                t.getUnits().stream()
                    .filter(u -> ids.contains(u.getId().toString()))
                    .forEach(survivors::add));
    return Math.max(0, before - tuv(survivors));
  }

  // ------------------------------------------------------------------------------- attackers

  /** Units of a step: by land/air (Pro AI routes) and by sea (loaded at the gathering place). */
  record Attacco(
      List<Unit> units,
      List<Unit> direct,
      Map<Unit, List<Unit>> landing,
      @Nullable Territory zone) {
    boolean hasLand() {
      return units.stream().anyMatch(Matches.unitIsLand());
    }
  }

  /**
   * The units of {@code owner} that can attack {@code target} now: land and air units the Pro AI
   * finds in reach (planes only where they can land after), and with a landing the troops at the
   * gathering place (and aboard) on own transports in the gathering zone. From an own capital only
   * while it stays guarded.
   */
  private Attacco attackers(
      final Territory target,
      final GamePlayer owner,
      final boolean opener,
      final boolean withHardLandings) {
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateAttackOptions();
    final ProTerritory patd = tm.getAttackOptions().getTerritoryMap().get(target);
    final List<Unit> direct = new ArrayList<>();
    if (patd != null) {
      patd.getMaxUnits().stream()
          .filter(Matches.unitIsOwnedBy(owner))
          .filter(Matches.unitIsNotSea())
          .filter(attacks(owner))
          .filter(u -> !Matches.unitIsAir().test(u) || opener || def.aereiApre() == 0 || true)
          .forEach(direct::add);
    }
    final Map<Unit, List<Unit>> landing = new java.util.LinkedHashMap<>();
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    if (zone != null && map.getNeighbors(zone).contains(target)) {
      final List<String> places = opener ? def.raccoltaApre() : def.raccoltaChiude();
      final List<Unit> transports =
          zone.getMatches(Matches.unitIsOwnedBy(owner).and(OperazioniAnfibie::isTransport));
      final List<Unit> ashore = new ArrayList<>();
      for (final String name : places) {
        final Territory land = map.getTerritoryOrNull(name);
        if (land != null && !land.isWater() && map.getNeighbors(zone).contains(land)) {
          final List<Unit> waiting =
              new ArrayList<>(
                  land.getMatches(
                      Matches.unitIsOwnedBy(owner)
                          .and(Matches.unitIsLand())
                          .and(attacks(owner))
                          .and(Matches.unitHasMovementLeft())
                          .and(u -> u.getUnitAttachment().getTransportCost() > 0)));
          waiting.removeAll(direct);
          ashore.addAll(guardCapital(land, waiting));
        }
      }
      ashore.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getAttack(owner))
              .thenComparing(u -> u.getType().getName()));
      for (final Unit t : transports) {
        final List<Unit> cargo =
            new ArrayList<>(
                t.getTransporting().stream().filter(Matches.unitIsOwnedBy(owner)).toList());
        int room = TransportTracker.getAvailableCapacity(t);
        for (final java.util.Iterator<Unit> it = ashore.iterator(); it.hasNext(); ) {
          final Unit u = it.next();
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost <= room) {
            cargo.add(u);
            room -= cost;
            it.remove();
          }
        }
        if (!cargo.isEmpty()) {
          landing.put(t, cargo);
        }
      }
    }
    final List<Unit> all = new ArrayList<>(direct);
    landing.values().forEach(all::addAll);
    if (withHardLandings && patd != null) {
      // estimate only: the troops the Pro AI's combat move can land from transports in reach
      patd.getMaxAmphibUnits().stream()
          .filter(Matches.unitIsOwnedBy(owner))
          .filter(attacks(owner))
          .filter(u -> !all.contains(u))
          .forEach(all::add);
    }
    return new Attacco(List.copyOf(all), List.copyOf(direct), landing, zone);
  }

  /** The units of {@code land} that may leave it: all unless it is an own capital at risk. */
  private List<Unit> guardCapital(final Territory land, final List<Unit> chosen) {
    if (chosen.isEmpty() || !land.isOwnedBy(player) || !isCapital(land, player)) {
      return chosen;
    }
    final ProTerritory max = enemyMax(land);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return chosen;
    }
    final List<Unit> allowed = new ArrayList<>(chosen);
    allowed.sort(Comparator.comparingInt((Unit u) -> u.getUnitAttachment().getDefense(player)));
    while (!allowed.isEmpty()) {
      final List<Unit> defenders = new ArrayList<>(land.getMatches(Matches.isUnitAllied(player)));
      defenders.removeAll(allowed);
      final double win =
          calc.calculateBattleResults(
                  proData, land, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
              .getWinPercentage();
      if (win <= GUARDIA_CAPITALE) {
        return allowed;
      }
      allowed.remove(allowed.size() - 1);
    }
    return allowed;
  }

  /**
   * The closer's units that could attack the target in its turn, as the opener's Pro AI sees them
   * (allied attack options); with a landing also its troops at the gathering place that its
   * transports in the gathering zone can carry.
   */
  private List<Unit> alliedAttackers(final Territory target, final GamePlayer ally) {
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateAttackOptions();
    final List<Unit> units = new ArrayList<>();
    final ProTerritory max = tm.getAlliedAttackOptions().getMax(target);
    if (max != null) {
      max.getMaxUnits().stream()
          .filter(Matches.unitIsOwnedBy(ally))
          .filter(Matches.unitIsNotSea())
          .filter(attacks(ally))
          .forEach(units::add);
      max.getMaxAmphibUnits().stream()
          .filter(Matches.unitIsOwnedBy(ally))
          .filter(attacks(ally))
          .filter(u -> !units.contains(u))
          .forEach(units::add);
    }
    // the Pro AI sees no movement in the ally's units that moved in its last turn (their movement
    // comes back at the start of its turn): their reach with full movement
    reach(target, ally).stream().filter(u -> !units.contains(u)).forEach(units::add);
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    if (zone != null && map.getNeighbors(zone).contains(target)) {
      int room = 0;
      for (final Unit t :
          zone.getMatches(Matches.unitIsOwnedBy(ally).and(OperazioniAnfibie::isTransport))) {
        room += TransportTracker.getAvailableCapacity(t);
        t.getTransporting().stream().filter(Matches.unitIsOwnedBy(ally)).forEach(units::add);
      }
      for (final String name : def.raccoltaChiude()) {
        final Territory land = map.getTerritoryOrNull(name);
        if (land == null || land.isWater() || !map.getNeighbors(zone).contains(land)) {
          continue;
        }
        for (final Unit u :
            land.getMatches(
                Matches.unitIsOwnedBy(ally).and(Matches.unitIsLand()).and(attacks(ally)))) {
          final int cost = u.getUnitAttachment().getTransportCost();
          if (cost > 0 && cost <= room && !units.contains(u)) {
            units.add(u);
            room -= cost;
          }
        }
      }
    }
    return units;
  }

  /**
   * Units of {@code ally} that can reach {@code target} with their full movement: land units
   * through the alliance's land (adjacent, or blitzers with 2 moves through a friendly territory),
   * planes that can strike it and land on the alliance's land.
   */
  private List<Unit> reach(final Territory target, final GamePlayer ally) {
    final List<Unit> units = new ArrayList<>();
    final Predicate<Territory> friendly =
        t -> !t.isWater() && Matches.isTerritoryAllied(ally).test(t) && !t.equals(target);
    int landingDistance = 99;
    for (final Territory t : map.getNeighbors(target, 6)) {
      if (friendly.test(t)) {
        landingDistance = Math.min(landingDistance, map.getDistance(target, t));
      }
    }
    for (final Territory t : map.getNeighbors(target, 6)) {
      if (t.isWater() || !Matches.isTerritoryAllied(ally).test(t)) {
        continue;
      }
      final int landDistance =
          map.getDistance(t, target, x -> x.equals(target) || friendly.test(x));
      final int airDistance = map.getDistance(t, target);
      for (final Unit u : t.getMatches(Matches.unitIsOwnedBy(ally).and(attacks(ally)))) {
        final int move = u.getUnitAttachment().getMovement(ally);
        if (Matches.unitIsAir().test(u)) {
          if (airDistance + landingDistance <= move) {
            units.add(u);
          }
        } else if (Matches.unitIsLand().test(u) && landDistance > 0 && landDistance <= move) {
          units.add(u);
        }
      }
    }
    landing(target, ally).stream().filter(u -> !units.contains(u)).forEach(units::add);
    return units;
  }

  /**
   * Troops of {@code ally} that its transports can land on {@code target} in one turn: transports
   * whose movement reaches a zone next to the target, with their cargo or troops of a friendly
   * coast next to their zone (estimate: no enemy fleet in the way).
   */
  private List<Unit> landing(final Territory target, final GamePlayer ally) {
    final List<Unit> units = new ArrayList<>();
    final Set<Territory> unloadZones =
        new HashSet<>(map.getNeighbors(target, Matches.territoryIsWater()));
    if (unloadZones.isEmpty()) {
      return units;
    }
    for (final Territory z : map.getNeighbors(target, 4, Matches.territoryIsWater())) {
      if (!z.isWater()) {
        continue;
      }
      for (final Unit t :
          z.getMatches(Matches.unitIsOwnedBy(ally).and(OperazioniAnfibie::isTransport))) {
        final int move = t.getUnitAttachment().getMovement(ally);
        final int dist =
            unloadZones.stream()
                .mapToInt(u -> u.equals(z) ? 0 : map.getDistance(z, u, Matches.territoryIsWater()))
                .filter(d -> d >= 0)
                .min()
                .orElse(99);
        if (dist > move) {
          continue;
        }
        int room = t.getUnitAttachment().getTransportCapacity();
        for (final Unit c : t.getTransporting()) {
          if (Matches.unitIsOwnedBy(ally).test(c) && attacks(ally).test(c)) {
            units.add(c);
          }
          room -= c.getUnitAttachment().getTransportCost();
        }
        for (final Territory coast : map.getNeighbors(z)) {
          if (coast.isWater()
              || !Matches.isTerritoryAllied(ally).test(coast)
              || coast.equals(target)) {
            continue;
          }
          for (final Unit c :
              coast.getMatches(
                  Matches.unitIsOwnedBy(ally).and(Matches.unitIsLand()).and(attacks(ally)))) {
            final int cost = c.getUnitAttachment().getTransportCost();
            if (cost > 0 && cost <= room && !units.contains(c)) {
              units.add(c);
              room -= cost;
            }
          }
        }
      }
    }
    return units;
  }

  /** The enemy units that fight in {@code target} (no infrastructure). */
  private List<Unit> defenders(final Territory target) {
    return target.getMatches(Matches.enemyUnit(player).and(Matches.unitIsNotInfrastructure()));
  }

  /**
   * Enemy units that can move into the target between the opener's and the closer's turns (the Pro
   * AI's enemy defense options of the nations that play in between).
   */
  private List<Unit> enemyReinforcements(
      final Territory target, final @Nullable GamePlayer closer) {
    if (closer == null) {
      return List.of();
    }
    final Set<GamePlayer> between = new HashSet<>();
    for (final GamePlayer p : ProUtils.getOtherPlayersInTurnOrder(player)) {
      if (p.equals(closer)) {
        break;
      }
      if (!data.getRelationshipTracker().isAllied(player, p)) {
        between.add(p);
      }
    }
    if (between.isEmpty()) {
      return List.of();
    }
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyDefenseOptions();
    final ProTerritory max = tm.getEnemyDefendOptions().getMax(target);
    if (max == null) {
      return List.of();
    }
    final List<Unit> units = new ArrayList<>();
    max.getMaxUnits().stream()
        .filter(u -> between.contains(u.getOwner()))
        .filter(u -> !target.getUnitCollection().contains(u))
        .forEach(units::add);
    max.getMaxAmphibUnits().stream()
        .filter(u -> between.contains(u.getOwner()))
        .filter(u -> !units.contains(u))
        .forEach(units::add);
    return units;
  }

  /** Moves the units of a step into the target: land and air by route, troops by landing. */
  private List<Unit> execute(final Territory target, final Attacco a) {
    final List<Unit> moved = new ArrayList<>();
    for (final Unit u : a.direct()) {
      final Territory from = proData.getUnitTerritory(u);
      if (from == null || from.equals(target)) {
        continue;
      }
      final Optional<Route> route = routeFor(u, from, target, true);
      if (route.isPresent() && move(List.of(u), route.get(), null)) {
        moved.add(u);
      }
    }
    if (a.zone() != null) {
      for (final Map.Entry<Unit, List<Unit>> e : a.landing().entrySet()) {
        final Unit transport = e.getKey();
        final List<Unit> aboard = new ArrayList<>();
        for (final Unit u : e.getValue()) {
          if (transport.getTransporting().contains(u)) {
            aboard.add(u);
            continue;
          }
          final Territory from = proData.getUnitTerritory(u);
          if (from != null
              && moveOk(
                  new MoveDescription(
                      List.of(u), new Route(from, a.zone()), Map.of(u, transport)))) {
            aboard.add(u);
          }
        }
        if (!aboard.isEmpty() && move(aboard, new Route(a.zone(), target), null)) {
          moved.addAll(aboard);
          moved.add(transport);
        }
      }
    }
    return moved;
  }

  // ============================================================================== non-combat

  private Esito nonCombat() {
    if (calc == null || moveDel == null || reg.isNuovo()) {
      return Esito.NESSUNO;
    }
    final Territory target = map.getTerritoryOrNull(def.bersaglio());
    if (target == null) {
      return Esito.NESSUNO;
    }
    try {
      if (reg.stato() != Stato.TENUTA) {
        outcome(target);
      }
      if (reg.stato() == Stato.TENUTA) {
        final Territory fin = map.getTerritoryOrNull(def.bersaglioFinale());
        if (fin != null && reg.intOf(FINALE + "tappa1_round", 0) == round) {
          pre = FINALE;
          outcome(fin);
          pre = "";
        }
        if (reg.stato() == Stato.TENUTA) {
          tenutaNonCombat();
        }
      } else if (!reg.stato().isFinale()) {
        prepare(target);
      }
      germanPresence(target);
    } catch (final RuntimeException e) {
      ProLogger.warn("[Pro+] Joint operation " + def.id() + " non-combat failed: " + e);
    } finally {
      pre = "";
      reg.salva(data.getProperties());
    }
    return result();
  }

  /**
   * After the steps of the target round: the closer reinforces a target the opener took (troops by
   * landing, planes landing there), then the outcome (taken or not) closes the operation.
   */
  private void outcome(final Territory target) {
    if (round != reg.intOf(k("tappa1_round"), 0) || reg.stato().isFinale()) {
      return;
    }
    final boolean allied = Matches.isTerritoryAllied(player).test(target);
    if (def.isApre(player.getName())) {
      if (allied && !reg.flag(k("presa"))) {
        taken(target, player.getName());
      }
      return;
    }
    if (!def.isChiude(player.getName()) || reg.intOf(k("tappa2_round"), 0) != round) {
      return;
    }
    if (reg.intOf(k("tappa2_forzata_round"), 0) == round) {
      final int[] battle2 = battleLoss(target, player);
      reg.set(k("tappa2_tuv_perso"), battle2[0]);
      reg.set(k("tappa2_tuv_inflitto"), battle2[1]);
    }
    if (allied) {
      if (!reg.flag(k("presa"))) {
        taken(target, player.getName());
      } else {
        reinforce(target);
      }
      if (!v2()) {
        reg.setStato(Stato.COMPLETATA);
        reg.set("motivo_fine", "bersaglio preso");
        note(
            event("completata"),
            "Operazione " + def.nome() + " COMPLETATA: " + def.bersaglio() + " preso al R" + round);
      }
    } else if (reg.intOf(k("tappa2_forzata_round"), 0) == round) {
      if (attackedThisRound(target) || reg.intOf(k("tappa2_eseguita_round"), 0) == round) {
        reg.set(k("tappa2_eseguita_round"), round);
        if (!pre.isEmpty()) {
          note(
              event("finale_non_presa"),
              "Operazione "
                  + def.nome()
                  + ": tappa su "
                  + target.getName()
                  + " eseguita, bersaglio non preso; si tiene la testa di ponte "
                  + def.bersaglio());
        } else if (v2()) {
          fail("tappa 2 eseguita, bersaglio non preso");
        } else {
          reg.setStato(Stato.COMPLETATA);
          reg.set("motivo_fine", "tappa 2 eseguita, bersaglio non preso");
          note(
              event("completata"),
              "Operazione "
                  + def.nome()
                  + " COMPLETATA senza presa: "
                  + def.bersaglio()
                  + " resta nemico dopo l'attacco della tappa 2");
        }
      } else {
        postpone("tappa 2: il combattimento non ha attaccato il bersaglio", Stato.ESECUZIONE);
      }
    }
  }

  /**
   * TUV lost by {@code attacker} and by the defenders in its battles in {@code target} this round
   * (battle records): {attacker lost, defender lost}.
   */
  private int[] battleLoss(final Territory target, final GamePlayer attacker) {
    final int[] lost = new int[2];
    if (data.getBattleRecordsList() == null) {
      return lost;
    }
    final var records = data.getBattleRecordsList().getBattleRecordsMapCopy().get(round);
    if (records == null) {
      return lost;
    }
    for (final games.strategy.triplea.delegate.data.BattleRecord r :
        games.strategy.triplea.delegate.data.BattleRecords.getAllRecords(records)) {
      final List<games.strategy.triplea.delegate.data.BattleRecord> one = List.of(r);
      if (games.strategy.triplea.delegate.data.BattleRecords
          .getWereThereBattlesInTerritoriesMatching(one, attacker, null, "any", List.of(target))) {
        lost[0] +=
            games.strategy.triplea.delegate.data.BattleRecords.getLostTuvForBattleRecords(
                one, true, true);
        lost[1] +=
            games.strategy.triplea.delegate.data.BattleRecords.getLostTuvForBattleRecords(
                one, false, true);
      }
    }
    return lost;
  }

  /** Whether this nation fought a battle in {@code target} this round (battle records). */
  private boolean attackedThisRound(final Territory target) {
    if (data.getBattleRecordsList() == null) {
      return false;
    }
    final var records = data.getBattleRecordsList().getBattleRecordsMapCopy().get(round);
    return records != null
        && games.strategy.triplea.delegate.data.BattleRecords
            .getWereThereBattlesInTerritoriesMatching(
                games.strategy.triplea.delegate.data.BattleRecords.getAllRecords(records),
                player,
                null,
                "any",
                List.of(target));
  }

  /**
   * Step 2 approved this round for this nation: the Pro AI's combat move attacks {@code t} first
   * (top priority), down to the closing threshold, and keeps it even if "not worth it".
   */
  public static boolean forzaBersaglio(final ProData proData, final Territory t) {
    final GameState data = proData.getData();
    final Optional<Definizione> def = OperazioneCongiunta.per(data, proData.getPlayer());
    if (def.isEmpty() || !def.get().isChiude(proData.getPlayer().getName())) {
      return false;
    }
    final String prefix;
    if (def.get().bersaglio().equals(t.getName())) {
      prefix = "";
    } else if (!def.get().bersaglioFinale().isEmpty()
        && def.get().bersaglioFinale().equals(t.getName())) {
      prefix = FINALE;
    } else {
      return false;
    }
    final Registro reg = Registro.leggi(data.getProperties(), def.get().id());
    return reg.intOf(prefix + "tappa2_forzata_round", 0) == data.getSequence().getRound()
        && !reg.stato().isFinale();
  }

  /** The closing threshold of the operation forcing {@code t} ({@link #forzaBersaglio}). */
  public static double sogliaForzata(final ProData proData) {
    return OperazioneCongiunta.per(proData.getData(), proData.getPlayer())
        .map(Definizione::sogliaChiusura)
        .orElse(0.0);
  }

  /** Priority bonus of a forced target: far above any other target. */
  static final double BONUS_FORZATO = 1000;

  private void taken(final Territory target, final String by) {
    reg.set(k("presa"), true);
    reg.set(k("round_presa"), round);
    reg.set(k("presa_da"), by);
    if (by.startsWith("senza operazione")) {
      reg.set(k("presa_senza_operazione"), true);
    }
    final JsonObject e = event("presa");
    e.addProperty("da", by);
    e.addProperty("bersaglio", target.getName());
    note(
        e,
        "Operazione "
            + def.nome()
            + ": "
            + target.getName()
            + (by.startsWith("senza operazione")
                ? " gia' dell'alleanza al R" + round + ", preso " + by
                : " preso da " + by + " al R" + round));
    if (v2()) {
      reg.setStato(Stato.TENUTA);
      reg.set("tenuta_bersaglio", target.getName());
      reg.set("tenuta_dal", round);
      reg.set("tenute", reg.intOf("tenute", 0) + 1);
      reg.json().remove("ultima_stima_tenuta");
      final JsonObject h = event("tenuta_inizio");
      h.addProperty("bersaglio", target.getName());
      note(
          h,
          String.format(
              Locale.ROOT,
              "Operazione %s: TENUTA di %s dal R%d per %d round (presidio bloccato, rinforzi e"
                  + " copertura aerea sotto il %.0f%% di tenuta stimata)",
              def.nome(),
              target.getName(),
              round,
              def.tenuta().round(),
              def.tenuta().sogliaRinforzi()));
    }
  }

  /** Variant v2: the operation ends without the target (not taken, or lost while held). */
  private void fail(final String why) {
    reg.setStato(Stato.FALLITA);
    reg.set("motivo_fine", why);
    if (reg.json().has("tenuta_bersaglio")) {
      reg.set("tenuta_esito", "fallita");
    }
    final JsonObject e = event("fallita");
    e.addProperty("motivo", why);
    note(e, "Operazione " + def.nome() + " FALLITA: " + why + " (le unita' tornano a Hard)");
  }

  /** The closer's troops and planes into a target the opener took this round. */
  private void reinforce(final Territory target) {
    final List<Unit> moved = new ArrayList<>();
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    if (zone != null && map.getNeighbors(zone).contains(target)) {
      final Attacco a = attackers(target, player, false, false);
      for (final Map.Entry<Unit, List<Unit>> e : a.landing().entrySet()) {
        final List<Unit> aboard = new ArrayList<>();
        for (final Unit u : e.getValue()) {
          if (e.getKey().getTransporting().contains(u)) {
            aboard.add(u);
            continue;
          }
          final Territory from = proData.getUnitTerritory(u);
          if (from != null
              && moveOk(
                  new MoveDescription(List.of(u), new Route(from, zone), Map.of(u, e.getKey())))) {
            aboard.add(u);
          }
        }
        if (!aboard.isEmpty() && move(aboard, new Route(zone, target), null)) {
          moved.addAll(aboard);
          held.add(e.getKey());
        }
      }
    }
    // planes that can land there (a territory taken by an ally earlier in the round)
    for (final String name : def.raccoltaChiude()) {
      final Territory from = map.getTerritoryOrNull(name);
      if (from == null) {
        continue;
      }
      for (final Unit u :
          from.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsAir())
                  .and(attacks(player))
                  .and(Matches.unitHasMovementLeft()))) {
        if (moved.stream().filter(Matches.unitIsAir()).count() >= 2) {
          break;
        }
        final Optional<Route> route = routeFor(u, from, target, false);
        if (route.isPresent() && move(List.of(u), route.get(), null)) {
          moved.add(u);
        }
      }
    }
    held.addAll(moved);
    final JsonObject e = event("rinforzo");
    e.addProperty("unita", OperazioniAnfibie.unitsText(moved));
    note(
        e,
        "Operazione "
            + def.nome()
            + ": rinforzo di "
            + player.getName()
            + " su "
            + def.bersaglio()
            + (moved.isEmpty() ? ": nessuna unita'" : ": " + OperazioniAnfibie.unitsText(moved)));
  }

  /** Reserves to the gathering place (preparation and positioning). */
  private void prepare(final Territory target) {
    final List<String> moves = new ArrayList<>();
    if (def.isApre(player.getName()) && def.aereiApre() > 0) {
      gatherPlanes(target, moves);
    }
    def.porto().filter(p -> p.nazione().equals(player.getName())).ifPresent(p -> shuttle(p, moves));
    if (def.isChiude(player.getName()) && def.zonaRaccolta().isEmpty()) {
      landTowardTarget(target, moves);
    }
    if (!def.zonaRaccolta().isEmpty()) {
      gatherFleet(moves);
    }
    // the reserves already at the gathering place stay there
    held.addAll(gathered());
    final JsonObject e = event("riserve");
    e.addProperty("mosse", String.join("; ", moves));
    e.addProperty("in_raccolta", OperazioniAnfibie.unitsText(gathered()));
    note(
        e,
        String.format(
            Locale.ROOT,
            "Operazione %s (%s, R%d): riserve di %s - %s; in raccolta: %s",
            def.nome(),
            reg.stato().testo(),
            reg.roundObiettivo(),
            player.getName(),
            moves.isEmpty() ? "nessuna mossa" : String.join("; ", moves),
            gathered().isEmpty() ? "nessuna unita'" : OperazioniAnfibie.unitsText(gathered())));
  }

  /**
   * The units of this nation already at the gathering place: planes of the opener at its places,
   * land units at the gathering places, own transports (and cargo) in the gathering zone.
   */
  private List<Unit> gathered() {
    final List<Unit> units = new ArrayList<>();
    final List<String> places =
        def.isApre(player.getName()) ? def.raccoltaApre() : def.raccoltaChiude();
    for (final String name : places) {
      final Territory t = map.getTerritoryOrNull(name);
      if (t == null || t.isWater()) {
        continue;
      }
      final Predicate<Unit> mine = Matches.unitIsOwnedBy(player).and(attacks(player));
      if (def.isApre(player.getName()) && def.aereiApre() > 0) {
        units.addAll(t.getMatches(mine.and(Matches.unitIsAir())));
      }
      if (!def.zonaRaccolta().isEmpty() || def.isChiude(player.getName())) {
        units.addAll(t.getMatches(mine.and(Matches.unitIsLand())));
      }
    }
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    if (zone != null) {
      for (final Unit t :
          zone.getMatches(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isTransport))) {
        units.add(t);
        units.addAll(t.getTransporting());
      }
    }
    return units;
  }

  /** The opener's planes to a safe gathering place in range (up to {@code aerei_apre}). */
  private void gatherPlanes(final Territory target, final List<String> moves) {
    final List<Territory> places =
        def.raccoltaApre().stream()
            .map(map::getTerritoryOrNull)
            .filter(Objects::nonNull)
            .filter(t -> !t.isWater() && Matches.isTerritoryAllied(player).test(t))
            .toList();
    if (places.isEmpty()) {
      return;
    }
    int there = 0;
    for (final Territory p : places) {
      there +=
          p.getMatches(Matches.unitIsOwnedBy(player).and(Matches.unitIsAir()).and(attacks(player)))
              .size();
    }
    final List<Unit> planes = new ArrayList<>();
    for (final Territory t : map.getTerritories()) {
      if (places.contains(t)) {
        continue;
      }
      planes.addAll(
          t.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsAir())
                  .and(attacks(player))
                  .and(Matches.unitHasMovementLeft())));
    }
    planes.sort(Comparator.comparingInt(u -> nearest(proData.getUnitTerritory(u), places)));
    for (final Unit u : planes) {
      if (there >= def.aereiApre()) {
        break;
      }
      final Territory from = proData.getUnitTerritory(u);
      if (from == null) {
        continue;
      }
      // the nearest safe place in range this turn
      boolean done = false;
      for (final Territory p :
          places.stream().sorted(Comparator.comparingInt(p -> map.getDistance(from, p))).toList()) {
        final Optional<Route> route = routeFor(u, from, p, false);
        if (route.isEmpty() || !safeLand(p, List.of(u))) {
          continue;
        }
        if (move(List.of(u), route.get(), moves)) {
          there++;
          done = true;
          break;
        }
      }
      if (!done && stepToward(u, from, places, moves)) {
        // in range of none: toward the nearest one, to a safe friendly territory on the way
        there++;
      }
    }
  }

  /** A plane one step toward the gathering places: the friendly safe land nearest to them. */
  private boolean stepToward(
      final Unit u, final Territory from, final List<Territory> places, final List<String> moves) {
    final int now = nearest(from, places);
    final int range = u.getMovementLeft().intValue();
    final List<Territory> options =
        map.getNeighbors(from, range).stream()
            .filter(t -> !t.isWater() && Matches.isTerritoryAllied(player).test(t))
            .filter(t -> nearest(t, places) < now)
            .sorted(Comparator.comparingInt(t -> nearest(t, places)))
            .toList();
    for (final Territory t : options) {
      final Optional<Route> route = routeFor(u, from, t, false);
      if (route.isPresent() && safeLand(t, List.of(u)) && move(List.of(u), route.get(), moves)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The closer's land units toward the target: to the friendly gathering place nearest to the
   * target, one move at a time, only where they stay safe.
   */
  private void landTowardTarget(final Territory target, final List<String> moves) {
    final List<Territory> places =
        def.raccoltaChiude().stream()
            .map(map::getTerritoryOrNull)
            .filter(Objects::nonNull)
            .filter(t -> !t.isWater() && Matches.isTerritoryAllied(player).test(t))
            .sorted(
                Comparator.comparingInt(t -> map.getDistance(t, target, Matches.territoryIsLand())))
            .toList();
    if (places.isEmpty()) {
      return;
    }
    final Territory best = places.get(0);
    for (final Territory from : places) {
      if (from.equals(best)) {
        continue;
      }
      final List<Unit> units =
          from.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(attacks(player))
                  .and(Matches.unitHasMovementLeft()));
      if (units.isEmpty()) {
        continue;
      }
      final Optional<Route> route =
          map.getRouteForUnits(
              from, best, ProMatches.territoryCanMoveLandUnitsAndIsAllied(player), units, player);
      if (route.isEmpty() || !safeLand(best, units)) {
        continue;
      }
      final List<Unit> can =
          units.stream()
              .filter(u -> route.get().getMovementCost(u).compareTo(u.getMovementLeft()) <= 0)
              .toList();
      if (!can.isEmpty()) {
        move(new ArrayList<>(can), route.get(), moves);
      }
    }
  }

  /**
   * A shuttle of the nation's troops: transports in a port zone load the troops of the port and
   * sail toward the arrival zone, stopping at the farthest safe zone; on arrival the troops go
   * ashore; empty transports go back to the port. Only when the nation may enter the zones (eg the
   * Americans once at war).
   */
  private void shuttle(final OperazioneCongiunta.Porto p, final List<String> moves) {
    final Territory port = map.getTerritoryOrNull(p.territorio());
    final Territory arrival = map.getTerritoryOrNull(p.arrivo());
    final Territory arrivalZone = map.getTerritoryOrNull(p.zonaArrivo());
    if (port == null || arrival == null || arrivalZone == null) {
      return;
    }
    if (!Matches.isTerritoryAllied(player).test(arrival)) {
      moves.add("arrivo " + arrival.getName() + " non amico: navetta ferma");
      return;
    }
    final List<Territory> portZones =
        p.zone().stream().map(map::getTerritoryOrNull).filter(Objects::nonNull).toList();
    final List<Unit> transports = new ArrayList<>();
    for (final Territory z : map.getTerritories()) {
      if (z.isWater()) {
        transports.addAll(
            z.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(OperazioniAnfibie::isTransport)
                    .and(Matches.unitHasMovementLeft())));
      }
    }
    final int wanted = Math.max(1, def.trasporti().getOrDefault(player.getName(), 2));
    transports.sort(
        Comparator.comparingInt(
            t ->
                Math.min(
                    nearest(proData.getUnitTerritory(t), portZones),
                    nearest(proData.getUnitTerritory(t), List.of(arrivalZone)))));
    int used = 0;
    for (final Unit t : transports) {
      if (used >= wanted) {
        break;
      }
      final Territory at = proData.getUnitTerritory(t);
      if (at == null) {
        continue;
      }
      final boolean loaded = t.getTransporting().stream().anyMatch(Matches.unitIsOwnedBy(player));
      if (!loaded && nearest(at, portZones) > 2 && !at.equals(arrivalZone)) {
        continue; // too far from the operation: the Pro AI keeps it
      }
      used++;
      if (at.equals(arrivalZone)) {
        if (loaded) {
          final List<Unit> cargo =
              t.getTransporting().stream().filter(Matches.unitIsOwnedBy(player)).toList();
          move(new ArrayList<>(cargo), new Route(arrivalZone, arrival), moves);
          held.addAll(cargo);
        }
        if (safeSea(arrivalZone, List.of(t))) {
          held.add(t);
        }
        continue;
      }
      if (!loaded && portZones.contains(at)) {
        // load only when the convoy has a safe stop toward the arrival this turn
        if (safeStop(t, at, arrivalZone).isPresent()) {
          loadAt(t, port, at, moves);
        } else {
          moves.add("nessuna sosta sicura da " + at.getName() + ": nessun imbarco");
        }
      }
      final boolean nowLoaded =
          t.getTransporting().stream().anyMatch(Matches.unitIsOwnedBy(player));
      final Territory dest = nowLoaded ? arrivalZone : portZones.get(0);
      if (!nowLoaded && portZones.contains(at)) {
        held.add(t); // empty in port: waits for troops
        continue;
      }
      sail(t, at, dest, nowLoaded ? arrival : null, moves);
    }
  }

  /** Loads the nation's land troops waiting at {@code port} on {@code transport}. */
  private void loadAt(
      final Unit transport, final Territory port, final Territory zone, final List<String> moves) {
    final List<Unit> waiting =
        new ArrayList<>(
            port.getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsLand())
                    .and(attacks(player))
                    .and(Matches.unitHasMovementLeft())
                    .and(u -> u.getUnitAttachment().getTransportCost() > 0)));
    waiting.removeAll(held);
    waiting.sort(
        Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getTransportCost())
            .thenComparing(u -> u.getType().getName()));
    int room = TransportTracker.getAvailableCapacity(transport);
    final List<Unit> chosen = new ArrayList<>();
    for (final Unit u : waiting) {
      final int cost = u.getUnitAttachment().getTransportCost();
      if (cost <= room) {
        chosen.add(u);
        room -= cost;
      }
    }
    final List<Unit> loaded = new ArrayList<>();
    for (final Unit u : guardCapital(port, chosen)) {
      if (moveOk(new MoveDescription(List.of(u), new Route(port, zone), Map.of(u, transport)))) {
        loaded.add(u);
      }
    }
    if (!loaded.isEmpty()) {
      moves.add("imbarco a " + port.getName() + ": " + OperazioniAnfibie.unitsText(loaded));
      held.addAll(loaded);
    }
  }

  /**
   * A transport (with its cargo and the warships of its zone as escort) toward {@code dest},
   * stopping at the farthest safe zone of the route; at {@code dest} the cargo goes ashore at
   * {@code ashore}.
   */
  private void sail(
      final Unit transport,
      final Territory from,
      final Territory dest,
      final @Nullable Territory ashore,
      final List<String> moves) {
    final List<Unit> group = new ArrayList<>();
    group.add(transport);
    group.addAll(transport.getTransporting());
    final List<Unit> escort =
        from
            .getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(OperazioniAnfibie::isWarship)
                    .and(Matches.unitHasMovementLeft()))
            .stream()
            .filter(u -> !held.contains(u))
            .limit(MAX_SCORTA)
            .toList();
    final Optional<Route> planned = safeStop(transport, from, dest);
    if (planned.isPresent()) {
      final Route route = planned.get();
      final Territory stop = route.getEnd();
      final List<Unit> ships = escort;
      if (!move(new ArrayList<>(group), route, moves)) {
        return;
      }
      held.addAll(group);
      final List<Unit> came = new ArrayList<>();
      for (final Unit s : ships) {
        if (route.getMovementCost(s).compareTo(s.getMovementLeft()) <= 0
            && moveOk(new MoveDescription(List.of(s), route))) {
          came.add(s);
        }
      }
      held.addAll(came);
      if (!came.isEmpty()) {
        moves.add("scorta " + OperazioniAnfibie.unitsText(came));
      }
      if (stop.equals(dest) && ashore != null) {
        final List<Unit> cargo =
            transport.getTransporting().stream().filter(Matches.unitIsOwnedBy(player)).toList();
        if (!cargo.isEmpty() && Matches.isTerritoryAllied(player).test(ashore)) {
          move(new ArrayList<>(cargo), new Route(dest, ashore), moves);
        }
      }
      return;
    }
    moves.add(
        "nessuna sosta sicura " + from.getName() + " -> " + dest.getName() + ": trasporto fermo");
    if (safeSea(from, List.of(transport))) {
      held.addAll(group);
    }
  }

  /**
   * The route of {@code transport} toward {@code dest} to the farthest safe stop in its range this
   * turn (with the warships of its zone as escort), if any.
   */
  private Optional<Route> safeStop(
      final Unit transport, final Territory from, final Territory dest) {
    final Optional<Route> full =
        map.getRouteForUnits(
            from,
            dest,
            ProMatches.territoryCanMoveSeaUnitsThrough(player, false),
            List.of(transport),
            player);
    if (full.isEmpty()) {
      return Optional.empty();
    }
    final List<Unit> escort =
        from
            .getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(OperazioniAnfibie::isWarship)
                    .and(Matches.unitHasMovementLeft()))
            .stream()
            .filter(u -> !held.contains(u))
            .limit(MAX_SCORTA)
            .toList();
    final List<Territory> steps = full.get().getAllTerritories();
    final int range = transport.getMovementLeft().intValue();
    for (int i = Math.min(range, steps.size() - 1); i >= 1; i--) {
      if (safeSea(steps.get(i), concat(List.of(transport), escort))) {
        return Optional.of(new Route(steps.subList(0, i + 1)));
      }
    }
    return Optional.empty();
  }

  /**
   * The fleet joins at the gathering zone: own transports within 2 zones and own warships within 2
   * zones (escort), if the zone is safe with them and the allied ships already there.
   */
  private void gatherFleet(final List<String> moves) {
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    if (zone == null) {
      return;
    }
    final List<Unit> candidates = new ArrayList<>();
    for (final Territory z : map.getNeighbors(zone, 2, Matches.territoryIsWater())) {
      z
          .getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsSea())
                  .and(Matches.unitHasMovementLeft())
                  .and(Matches.unitIsCarrier().negate()))
          .stream()
          .filter(u -> !held.contains(u))
          .filter(u -> OperazioniAnfibie.isWarship(u) || u.getTransporting().isEmpty())
          .forEach(candidates::add);
    }
    final List<Unit> transports =
        candidates.stream().filter(OperazioniAnfibie::isTransport).toList();
    final List<Unit> warships = candidates.stream().filter(OperazioniAnfibie::isWarship).toList();
    final int wanted = def.trasporti().getOrDefault(player.getName(), 0);
    final long already =
        zone.getUnitCollection()
            .countMatches(Matches.unitIsOwnedBy(player).and(OperazioniAnfibie::isTransport));
    final List<Unit> going =
        new ArrayList<>(transports.stream().limit(Math.max(0, wanted - already)).toList());
    going.addAll(warships.stream().limit(MAX_SCORTA).toList());
    if (going.isEmpty()) {
      return;
    }
    if (!safeSea(zone, going)) {
      moves.add("flotta non riunita in " + zone.getName() + ": zona non sicura");
      return;
    }
    final List<Unit> joined = new ArrayList<>();
    for (final Unit u : going) {
      final Territory from = proData.getUnitTerritory(u);
      if (from == null) {
        continue;
      }
      final Optional<Route> route =
          map.getRouteForUnit(
              from, zone, ProMatches.territoryCanMoveSeaUnitsThrough(player, false), u, player);
      if (route.isPresent()
          && route.get().getMovementCost(u).compareTo(u.getMovementLeft()) <= 0
          && moveOk(new MoveDescription(List.of(u), route.get()))) {
        joined.add(u);
      }
    }
    held.addAll(joined);
    if (!joined.isEmpty()) {
      moves.add("flotta riunita in " + zone.getName() + ": " + OperazioniAnfibie.unitsText(joined));
    }
  }

  /**
   * Egypt: at least one land unit of the opener in a target the alliance holds (national objective
   * of the map), when one can reach it.
   */
  private void germanPresence(final Territory target) {
    if (!def.isApre(player.getName())
        || !reg.flag("presa")
        || !Matches.isTerritoryAllied(player).test(target)
        || target.anyUnitsMatch(Matches.unitIsOwnedBy(player).and(Matches.unitIsLand()))) {
      return;
    }
    for (final Territory from : map.getNeighbors(target, 2)) {
      if (from.isWater()) {
        continue;
      }
      for (final Unit u :
          from.getMatches(
              Matches.unitIsOwnedBy(player)
                  .and(Matches.unitIsLand())
                  .and(Matches.unitHasMovementLeft()))) {
        final Optional<Route> route =
            map.getRouteForUnit(
                from, target, ProMatches.territoryCanMoveLandUnitsAndIsAllied(player), u, player);
        if (route.isPresent()
            && route.get().getMovementCost(u).compareTo(u.getMovementLeft()) <= 0) {
          final List<String> moves = new ArrayList<>();
          if (move(List.of(u), route.get(), moves)) {
            held.add(u);
            final JsonObject e = event("presenza");
            e.addProperty("unita", OperazioniAnfibie.unitsText(List.of(u)));
            note(
                e,
                "Operazione "
                    + def.nome()
                    + ": unita' di "
                    + player.getName()
                    + " in "
                    + def.bersaglio()
                    + " ("
                    + String.join("; ", moves)
                    + ")");
            return;
          }
        }
      }
    }
  }

  // ===================================================================== hold (variant v2)

  /** Variant v2 ("presa e tenuta"): the operation counts the counterattack and holds the target. */
  private boolean v2() {
    return def.tenuta().attiva();
  }

  /** A saved key of the steps being played (first or final target). */
  private String k(final String key) {
    return pre + key;
  }

  private @Nullable Territory tenutaTarget() {
    return reg.json().has("tenuta_bersaglio")
        ? map.getTerritoryOrNull(reg.json().get("tenuta_bersaglio").getAsString())
        : null;
  }

  /**
   * The enemy's counterattack on a target held by the alliance: the units that can reach it at the
   * enemy's next turns (the Pro AI's enemy attack options, the target counted as the alliance's)
   * and the units the enemies can buy and place at their factories in reach of it ({@link
   * #enemyPurchases}).
   */
  public record Minaccia(List<Unit> attaccanti, List<Unit> acquisti, List<Unit> bombardamento) {
    public List<Unit> tutti() {
      return concat(attaccanti, acquisti);
    }
  }

  /** Estimated hold of a target: 100 − enemy win% of the counterattack against the defenders. */
  record StimaTenuta(double tenuta, List<Unit> attaccanti, List<Unit> acquisti) {}

  /** The counterattack expected on {@code target} once it is the alliance's. */
  Minaccia minaccia(final Territory target) {
    final ProTerritoryManager tm = new ProTerritoryManager(calc, proData);
    tm.populateEnemyAttackOptions(List.of(target), List.of(target));
    final ProTerritory max = tm.getEnemyAttackOptions().getMax(target);
    final List<Unit> attackers = new ArrayList<>();
    final List<Unit> bombard = new ArrayList<>();
    if (max != null) {
      max.getMaxUnits().stream().filter(Matches.unitIsNotInfrastructure()).forEach(attackers::add);
      max.getMaxAmphibUnits().stream()
          .filter(Matches.unitIsNotInfrastructure())
          .filter(u -> !attackers.contains(u))
          .forEach(attackers::add);
      bombard.addAll(max.getMaxBombardUnits());
    }
    return new Minaccia(List.copyOf(attackers), enemyPurchases(target), List.copyOf(bombard));
  }

  /** Hold% of {@code target} with {@code defenders} against the counterattack {@code m}. */
  private double tenuta(
      final Territory target, final Minaccia m, final Collection<Unit> defenders) {
    final List<Unit> attackers = m.tutti();
    if (attackers.isEmpty()) {
      return 100;
    }
    if (defenders.isEmpty()) {
      return 0;
    }
    return 100
        - calc.calculateBattleResults(
                proData, target, attackers, new ArrayList<>(defenders), m.bombardamento())
            .getWinPercentage();
  }

  private StimaTenuta stimaTenuta(final Territory target, final Collection<Unit> defenders) {
    final Minaccia m = minaccia(target);
    return new StimaTenuta(tenuta(target, m, defenders), m.attaccanti(), m.acquisti());
  }

  /**
   * Units the enemies at war with this nation can buy and place in reach of {@code target} for
   * their following turn: for each factory of theirs within 2 land moves of the target, its
   * production as capacity, filled with the land unit of best attack per PU that can reach the
   * target from there, within {@code quota_acquisti_nemici} of their PUs now (the rest goes to
   * other fronts). Temporary units, never on the map.
   */
  List<Unit> enemyPurchases(final Territory target) {
    final double share = def.tenuta().quotaAcquistiNemici();
    final List<Unit> units = new ArrayList<>();
    if (share <= 0) {
      return units;
    }
    for (final GamePlayer e : data.getPlayerList().getPlayers()) {
      if (e.isNull()
          || e.equals(player)
          || !data.getRelationshipTracker().isAtWar(player, e)
          || e.getProductionFrontier() == null) {
        continue;
      }
      double budget = e.getResources().getQuantity(games.strategy.triplea.Constants.PUS) * share;
      final List<Territory> factories =
          map.getTerritories().stream()
              .filter(ProMatches.territoryHasFactoryAndIsNotConqueredOwnedLand(e))
              .filter(f -> !f.equals(target))
              .filter(
                  f -> {
                    final int d = map.getDistance(f, target, Matches.territoryIsLand());
                    return d > 0 && d <= 2;
                  })
              .sorted(
                  Comparator.comparingInt(
                          (Territory f) -> map.getDistance(f, target, Matches.territoryIsLand()))
                      .thenComparing(Territory::getName))
              .toList();
      for (final Territory f : factories) {
        final int distance = map.getDistance(f, target, Matches.territoryIsLand());
        games.strategy.engine.data.UnitType best = null;
        int bestCost = 0;
        double bestRatio = 0;
        for (final games.strategy.engine.data.ProductionRule rule :
            e.getProductionFrontier().getRules()) {
          if (rule.getResults().size() != 1
              || !(rule.getResults().keySet().iterator().next()
                  instanceof games.strategy.engine.data.UnitType type)) {
            continue;
          }
          final var ua = type.getUnitAttachment();
          final int cost =
              rule.getCosts()
                  .getInt(
                      data.getResourceList()
                          .getResourceOrThrow(games.strategy.triplea.Constants.PUS));
          if (ua.isSea()
              || ua.isAir()
              || ua.getAttack(e) <= 0
              || cost <= 0
              || ua.getMovement(e) < distance
              || rule.getResults().getInt(type) != 1) {
            continue;
          }
          final double ratio = (double) ua.getAttack(e) / cost;
          if (ratio > bestRatio || (ratio == bestRatio && cost < bestCost)) {
            best = type;
            bestCost = cost;
            bestRatio = ratio;
          }
        }
        if (best == null) {
          continue;
        }
        final int capacity =
            Math.max(0, games.strategy.triplea.attachments.TerritoryAttachment.getProduction(f));
        final int n = (int) Math.min(capacity, Math.floor(budget / bestCost));
        if (n <= 0) {
          continue;
        }
        units.addAll(best.createTemp(n, e));
        budget -= (double) n * bestCost;
      }
    }
    return units;
  }

  /** This nation's units that fight in {@code t} (no infrastructure, no planes: they leave). */
  private List<Unit> garrison(final Territory t) {
    return t.getMatches(
        Matches.unitIsOwnedBy(player)
            .and(Matches.unitIsNotInfrastructure())
            .and(Matches.unitIsLand()));
  }

  /** The alliance's units that would defend {@code t}. */
  private List<Unit> alliedDefenders(final Territory t) {
    return t.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotInfrastructure()));
  }

  /**
   * Start of the turn of a nation of the operation while the target is held: lost ⇒ FALLITA; held
   * for {@code tenuta.round} rounds (checked at the closer's turn, after every enemy has played as
   * many turns) ⇒ COMPLETATA, or for a bridgehead the final target becomes possible.
   */
  private void tenutaUpdate() {
    final Territory t = tenutaTarget();
    if (t == null) {
      return;
    }
    final int since = reg.intOf("tenuta_dal", round);
    if (!Matches.isTerritoryAllied(player).test(t)) {
      reg.set("tenuta_persa_round", round);
      fail(
          String.format(
              Locale.ROOT,
              "%s perso (controllato da %s) al R%d dopo %d round di tenuta",
              t.getName(),
              t.getOwner().getName(),
              round,
              round - since));
      return;
    }
    if (!def.isChiude(player.getName()) || round - since < def.tenuta().round()) {
      return;
    }
    final boolean bridgehead =
        !def.bersaglioFinale().isEmpty() && t.getName().equals(def.bersaglio());
    if (bridgehead) {
      if (!reg.flag("testa_di_ponte_tenuta")) {
        reg.set("testa_di_ponte_tenuta", true);
        reg.set("testa_di_ponte_round", round);
        final JsonObject e = event("testa_di_ponte");
        note(
            e,
            String.format(
                Locale.ROOT,
                "Operazione %s: testa di ponte %s tenuta %d round; tappa su %s quando la presa e"
                    + " la tenuta lo giustificano",
                def.nome(),
                t.getName(),
                round - since,
                def.bersaglioFinale()));
      }
      return;
    }
    reg.setStato(Stato.COMPLETATA);
    reg.set("motivo_fine", t.getName() + " tenuto " + (round - since) + " round");
    reg.set("tenuta_esito", "completata");
    final JsonObject e = event("completata");
    e.addProperty("bersaglio", t.getName());
    note(
        e,
        String.format(
            Locale.ROOT,
            "Operazione %s COMPLETATA: %s tenuto %d round (dal R%d); le unita' tornano a Hard",
            def.nome(),
            t.getName(),
            round - since,
            since));
  }

  /**
   * Combat move while the target is held: the garrison stays out of the Pro AI's attacks (unless
   * the hold was estimated lost for sure); with a bridgehead held long enough, the steps on the
   * final target (same logic as the first one).
   */
  private void tenutaCombat() {
    final Territory t = tenutaTarget();
    if (t == null || !Matches.isTerritoryAllied(player).test(t)) {
      return;
    }
    final double last =
        reg.json().has("ultima_stima_tenuta")
            ? reg.json().get("ultima_stima_tenuta").getAsDouble()
            : 100;
    if (last <= def.tenuta().sogliaRitirata()) {
      if (reg.intOf("ritirata_round", 0) != round) {
        reg.set("ritirata_round", round);
        reg.set("ritirate", reg.intOf("ritirate", 0) + 1);
        final JsonObject e = event("ritirata");
        e.addProperty("tenuta_stimata", Math.round(last));
        note(
            e,
            String.format(
                Locale.ROOT,
                "Operazione %s: presidio di %s in %s lasciato a Hard (tenuta stimata %.0f%% <="
                    + " %.0f%%: tenuta persa)",
                def.nome(),
                player.getName(),
                t.getName(),
                last,
                def.tenuta().sogliaRitirata()));
      }
    } else {
      held.addAll(garrison(t));
    }
    final Territory fin = map.getTerritoryOrNull(def.bersaglioFinale());
    if (fin == null || !reg.flag("testa_di_ponte_tenuta") || !t.getName().equals(def.bersaglio())) {
      return;
    }
    pre = FINALE;
    if (def.isApre(player.getName()) && reg.intOf(k("tappa1_round"), 0) != round) {
      reg.set(k("tentativi_valutati"), reg.intOf(k("tentativi_valutati"), 0) + 1);
      step1(fin);
    } else if (def.isChiude(player.getName())
        && reg.intOf(k("tappa1_round"), 0) == round
        && reg.intOf(k("tappa2_round"), 0) != round) {
      step2(fin);
    }
    pre = "";
  }

  /**
   * Non-combat move while the target is held: the garrison stays; the shuttle and the fleet go on;
   * under {@code soglia_rinforzi} of estimated hold at the enemy's turn, reinforcements (landing
   * from the gathering zone, land units in reach, then planes as air cover) until the estimate
   * reaches it. The estimate is saved for the purchase and the next combat move.
   */
  private void tenutaNonCombat() {
    final Territory t = tenutaTarget();
    if (t == null || !Matches.isTerritoryAllied(player).test(t)) {
      return;
    }
    held.addAll(garrison(t));
    final List<String> moves = new ArrayList<>();
    def.porto().filter(p -> p.nazione().equals(player.getName())).ifPresent(p -> shuttle(p, moves));
    if (!def.zonaRaccolta().isEmpty()) {
      gatherFleet(moves);
    }
    final Minaccia m = minaccia(t);
    final double before = tenuta(t, m, alliedDefenders(t));
    double now = before;
    final List<Unit> came = new ArrayList<>();
    if (before < def.tenuta().sogliaRinforzi()) {
      now = reinforceHold(t, m, came, moves);
    }
    held.addAll(came);
    reg.set("ultima_stima_tenuta", (int) Math.round(now));
    reg.set("ultima_stima_round", round);
    final JsonObject e = event("stima_tenuta");
    e.addProperty("bersaglio", t.getName());
    e.addProperty("tenuta_prima", Math.round(before));
    e.addProperty("tenuta_stimata", Math.round(now));
    e.addProperty("contrattacco_tuv", Math.round(tuv(m.attaccanti())));
    e.addProperty("contrattacco_acquisti_tuv", Math.round(tuv(m.acquisti())));
    e.addProperty("tuv_difensori", Math.round(tuv(alliedDefenders(t))));
    e.addProperty("rinforzi", OperazioniAnfibie.unitsText(came));
    e.addProperty("tuv_rinforzi", Math.round(tuv(came)));
    note(
        e,
        String.format(
            Locale.ROOT,
            "Operazione %s, tenuta di %s (R%d, dal R%d): contrattacco atteso %s (TUV %.0f) +"
                + " acquisti possibili %s (TUV %.0f) contro %s (TUV %.0f): tenuta stimata %.0f%%%s"
                + "; rinforzi di %s: %s%s",
            def.nome(),
            t.getName(),
            round,
            reg.intOf("tenuta_dal", round),
            m.attaccanti().isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(m.attaccanti()),
            tuv(m.attaccanti()),
            m.acquisti().isEmpty() ? "nessuno" : OperazioniAnfibie.unitsText(m.acquisti()),
            tuv(m.acquisti()),
            OperazioniAnfibie.unitsText(alliedDefenders(t)),
            tuv(alliedDefenders(t)),
            now,
            before == now ? "" : String.format(Locale.ROOT, " (prima dei rinforzi %.0f%%)", before),
            player.getName(),
            came.isEmpty()
                ? before < def.tenuta().sogliaRinforzi()
                    ? "nessuna unita' disponibile"
                    : "non servono"
                : OperazioniAnfibie.unitsText(came),
            moves.isEmpty() ? "" : " (" + String.join("; ", moves) + ")"));
  }

  /**
   * Reinforcements into the held target {@code t}, in order, while the estimated hold is under the
   * threshold: troops landed from the gathering zone, land units within 2 moves through the
   * alliance's land (not out of an own capital at risk), then up to {@code aerei_copertura} planes
   * that can land there. None if even all of them together would leave the hold at or under {@code
   * soglia_ritirata} (units not thrown into a lost target). Returns the hold% after them.
   */
  private double reinforceHold(
      final Territory t, final Minaccia m, final List<Unit> came, final List<String> moves) {
    final double threshold = def.tenuta().sogliaRinforzi();
    // candidates: landing from the gathering zone, land units in reach, planes as air cover
    final Territory zone = map.getTerritoryOrNull(def.zonaRaccolta());
    final Map<Unit, List<Unit>> landing =
        zone != null && map.getNeighbors(zone).contains(t)
            ? attackers(t, player, def.isApre(player.getName()), false).landing()
            : Map.of();
    final Map<Territory, List<Unit>> land = new java.util.LinkedHashMap<>();
    for (final Territory from :
        map.getNeighbors(t, 2).stream()
            .filter(x -> !x.isWater() && Matches.isTerritoryAllied(player).test(x))
            .sorted(
                Comparator.comparingInt((Territory x) -> map.getDistance(x, t))
                    .thenComparing(Territory::getName))
            .toList()) {
      final List<Unit> units =
          new ArrayList<>(
              from.getMatches(
                  Matches.unitIsOwnedBy(player)
                      .and(Matches.unitIsLand())
                      .and(Matches.unitIsNotInfrastructure())
                      .and(Matches.unitHasMovementLeft())
                      .and(Matches.unitIsBeingTransported().negate())));
      units.removeAll(held);
      landing.values().forEach(units::removeAll);
      units.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(player))
              .thenComparing(u -> u.getType().getName()));
      final List<Unit> can =
          guardCapital(from, units).stream()
              .filter(u -> routeFor(u, from, t, false).isPresent())
              .toList();
      if (!can.isEmpty()) {
        land.put(from, can);
      }
    }
    final List<Unit> air = new ArrayList<>();
    if (def.tenuta().aereiCopertura() > 0) {
      for (final Territory x : map.getNeighbors(t, 6)) {
        if (x.isWater()) {
          continue;
        }
        x
            .getMatches(
                Matches.unitIsOwnedBy(player)
                    .and(Matches.unitIsAir())
                    .and(Matches.unitHasMovementLeft())
                    .and(u -> u.getUnitAttachment().getDefense(player) > 0))
            .stream()
            .filter(u -> !held.contains(u))
            .filter(u -> routeFor(u, x, t, false).isPresent())
            .forEach(air::add);
      }
      air.sort(
          Comparator.comparingInt((Unit u) -> -u.getUnitAttachment().getDefense(player))
              .thenComparing(u -> u.getId().toString()));
    }
    final List<Unit> all = new ArrayList<>(alliedDefenders(t));
    landing.values().forEach(all::addAll);
    land.values().forEach(all::addAll);
    all.addAll(air.stream().limit(def.tenuta().aereiCopertura()).toList());
    final double best = tenuta(t, m, all);
    if (best <= def.tenuta().sogliaRitirata()) {
      moves.add(
          String.format(
              Locale.ROOT,
              "rinforzi inutili: tenuta stimata %.0f%% anche con tutte le unita' in portata",
              best));
      return tenuta(t, m, alliedDefenders(t));
    }
    // 1) landing from the gathering zone (troops at the gathering place, on own transports)
    for (final Map.Entry<Unit, List<Unit>> e : landing.entrySet()) {
      final List<Unit> aboard = new ArrayList<>();
      for (final Unit u : e.getValue()) {
        if (e.getKey().getTransporting().contains(u)) {
          aboard.add(u);
          continue;
        }
        final Territory from = proData.getUnitTerritory(u);
        if (from != null
            && moveOk(
                new MoveDescription(List.of(u), new Route(from, zone), Map.of(u, e.getKey())))) {
          aboard.add(u);
        }
      }
      if (!aboard.isEmpty() && move(aboard, new Route(zone, t), moves)) {
        came.addAll(aboard);
        held.add(e.getKey());
      }
    }
    double now = tenuta(t, m, alliedDefenders(t));
    // 2) land units in reach through the alliance's land
    for (final Map.Entry<Territory, List<Unit>> e : land.entrySet()) {
      if (now >= threshold) {
        break;
      }
      final Territory from = e.getKey();
      final List<Unit> moving = new ArrayList<>();
      for (final Unit u : e.getValue()) {
        final Optional<Route> route = routeFor(u, from, t, false);
        if (route.isPresent() && move(List.of(u), route.get(), null)) {
          moving.add(u);
        }
      }
      if (!moving.isEmpty()) {
        moves.add(
            OperazioniAnfibie.unitsText(moving) + " " + from.getName() + " -> " + t.getName());
        came.addAll(moving);
        now = tenuta(t, m, alliedDefenders(t));
      }
    }
    // 3) air cover: planes that can land in the target
    int planes = 0;
    for (final Unit u : air) {
      if (planes >= def.tenuta().aereiCopertura() || now >= threshold) {
        break;
      }
      final Territory from = proData.getUnitTerritory(u);
      if (from == null) {
        continue;
      }
      final Optional<Route> route = routeFor(u, from, t, false);
      if (route.isPresent() && move(List.of(u), route.get(), moves)) {
        came.add(u);
        planes++;
        now = tenuta(t, m, alliedDefenders(t));
      }
    }
    return now;
  }

  // ================================================================================== helpers

  private Optional<Route> routeFor(
      final Unit u, final Territory from, final Territory to, final boolean combat) {
    final Predicate<Territory> can =
        Matches.unitIsAir().test(u)
            ? ProMatches.territoryCanMoveAirUnitsAndNoAa(data, player, combat)
            : Matches.unitIsSea().test(u)
                ? ProMatches.territoryCanMoveSeaUnitsThrough(player, combat)
                : ProMatches.territoryCanMoveLandUnitsThrough(player, u, from, combat, List.of());
    final Optional<Route> route = map.getRouteForUnit(from, to, can, u, player);
    if (route.isEmpty() || route.get().getMovementCost(u).compareTo(u.getMovementLeft()) > 0) {
      return Optional.empty();
    }
    return route;
  }

  private boolean move(
      final List<Unit> units, final Route route, final @Nullable List<String> moves) {
    if (!moveOk(new MoveDescription(units, route))) {
      return false;
    }
    if (moves != null) {
      moves.add(
          OperazioniAnfibie.unitsText(units)
              + " "
              + route.getStart().getName()
              + " -> "
              + route.getEnd().getName());
    }
    return true;
  }

  private boolean moveOk(final MoveDescription m) {
    final Optional<String> error = moveDel.performMove(m);
    if (error.isPresent()) {
      ProLogger.warn(
          "[Pro+] Joint operation "
              + def.id()
              + ": could not move "
              + m.getUnits()
              + " over "
              + m.getRoute()
              + ": "
              + error.get());
      return false;
    }
    return true;
  }

  /** Whether {@code land} with {@code adding} stays under the enemy's safety threshold. */
  private boolean safeLand(final Territory land, final List<Unit> adding) {
    final ProTerritory max = enemyMax(land);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return true;
    }
    final List<Unit> defenders = new ArrayList<>(land.getMatches(Matches.isUnitAllied(player)));
    adding.stream().filter(u -> !defenders.contains(u)).forEach(defenders::add);
    return calc.calculateBattleResults(
                proData, land, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
            .getWinPercentage()
        <= SOGLIA_SICUREZZA;
  }

  /**
   * Whether {@code zone} with {@code adding} and the allied ships there is a safe stop: no
   * transports without a warship, enemy win% within the threshold.
   */
  private boolean safeSea(final Territory zone, final List<Unit> adding) {
    final List<Unit> defenders =
        new ArrayList<>(zone.getMatches(Matches.isUnitAllied(player).and(Matches.unitIsNotLand())));
    adding.stream()
        .filter(u -> !defenders.contains(u) && !Matches.unitIsLand().test(u))
        .forEach(defenders::add);
    final ProTerritory max = enemyMax(zone);
    if (max == null || max.getMaxUnits().isEmpty()) {
      return true;
    }
    if (defenders.stream().noneMatch(OperazioniAnfibie::isWarship)) {
      return false;
    }
    return calc.calculateBattleResults(
                proData, zone, max.getMaxUnits(), defenders, max.getMaxBombardUnits())
            .getWinPercentage()
        <= SOGLIA_SICUREZZA;
  }

  private @Nullable ProTerritory enemyMax(final Territory t) {
    if (enemy == null) {
      enemy = new ProTerritoryManager(calc, proData);
      final Set<Territory> check = new HashSet<>();
      for (final Territory x : map.getTerritories()) {
        check.add(x);
      }
      enemy.populateEnemyAttackOptions(List.of(), check);
    }
    return enemy.getEnemyAttackOptions().getMax(t);
  }

  private int nearest(final @Nullable Territory from, final Collection<Territory> places) {
    if (from == null) {
      return 99;
    }
    return places.stream()
        .mapToInt(p -> map.getDistance(from, p))
        .filter(d -> d >= 0)
        .min()
        .orElse(99);
  }

  private double tuv(final Collection<Unit> units) {
    return TuvUtils.getTuv(units, proData.getUnitValueMap());
  }

  private static Predicate<Unit> attacks(final GamePlayer owner) {
    return u ->
        u.getUnitAttachment().getAttack(owner) > 0 && Matches.unitIsNotInfrastructure().test(u);
  }

  private static boolean isCapital(final Territory t, final GamePlayer p) {
    return games.strategy.triplea.attachments.TerritoryAttachment.get(t)
        .flatMap(games.strategy.triplea.attachments.TerritoryAttachment::getCapital)
        .map(c -> c.equals(p.getName()))
        .orElse(false);
  }

  /** Transports of the nation within 2 zones of the gathering zone or of its port zones. */
  static int transportsNear(final ProData proData, final Definizione def) {
    final GameMap map = proData.getData().getMap();
    final Set<Territory> zones = new HashSet<>();
    final List<String> names = new ArrayList<>();
    if (!def.zonaRaccolta().isEmpty()) {
      names.add(def.zonaRaccolta());
    }
    def.porto().ifPresent(p -> names.addAll(p.zone()));
    for (final String n : names) {
      final Territory z = map.getTerritoryOrNull(n);
      if (z != null) {
        zones.add(z);
        zones.addAll(map.getNeighbors(z, 2, Matches.territoryIsWater()));
      }
    }
    int n = 0;
    for (final Territory z : zones) {
      n +=
          z.getUnitCollection()
              .countMatches(
                  Matches.unitIsOwnedBy(proData.getPlayer()).and(OperazioniAnfibie::isTransport));
    }
    return n;
  }

  private static <T> List<T> concat(final List<T> a, final List<T> b) {
    final List<T> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  private JsonObject event(final String type) {
    final JsonObject e = new JsonObject();
    e.addProperty("evento", "operazione_congiunta");
    e.addProperty("operazione", def.id());
    e.addProperty("tipo", type);
    e.addProperty("round", round);
    e.addProperty("nazione", player.getName());
    e.addProperty("stato", reg.stato().testo());
    e.addProperty("fase", pre.isEmpty() ? "bersaglio" : "finale");
    return e;
  }

  /** A diary line with its event, also kept in the operation's saved list of events. */
  private void note(final JsonObject event, final String text) {
    event.addProperty("testo", text);
    event.addProperty("stato", reg.stato().testo());
    righe.add(text);
    eventi.add(event);
    reg.evento(event);
    ProLogger.info("[Pro+] " + text);
  }

  private Esito result() {
    return new Esito(Set.copyOf(held), List.copyOf(righe), List.copyOf(eventi));
  }

  /** Eg "1 fighter, 2 infantry" (for tests and logs). */
  static String text(final Collection<Unit> units) {
    return OperazioniAnfibie.unitsText(units);
  }

  static BigDecimal movementLeft(final Unit u) {
    return u.getMovementLeft();
  }
}
