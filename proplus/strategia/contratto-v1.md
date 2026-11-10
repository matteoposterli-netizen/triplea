# Contratto strategico v1 (Pro+)

La parte deterministica di Pro+ fa il **COME** (acquisti, mosse, battaglie, sbarchi: congelata). Il
**COSA/PERCHE'** lo decide un **decisore**, una volta per alleanza all'inizio di ogni round (il primo
turno Pro+ dell'alleanza nel round). Oggi il decisore e' a regole (`DecisoreRegole`, lo stato maggiore
degli step 3a-3d); domani potra' essere un LLM. Tutto passa da due JSON:

- **ingresso** = stato strategico (`Ingresso.of`), salvato come `stato-R<round>-<alleanza>.json`;
- **uscita** = direttive (`Direttive`), salvate come `direttive-R<round>-<alleanza>.json`.

I due file sono nella cartella della partita del diario (`diario/<data>_<mappa>/`), anche
nell'autoplay. Esempi reali (Europe 1940, Asse A / Alleati 1, seme 1, round 2) in `esempi/`.

## Decisori

| Nome | Classe | Cosa fa |
|---|---|---|
| `regole` (default) | `DecisoreRegole` | piano e cambio piano, postura, contro-obiettivi, rischio e obiettivi della fase del file dati, operazioni tutte "lancia" senza perdita accettata, **strumenti di ritardo tutti spenti**, ruoli del piano, lettura del nemico |
| `file` | `DecisoreFile` | legge le direttive da un file JSON (vedi sotto); se manca la direttiva del round decidono le regole (con avviso) |
| `auto` / `claude` / `chiave` | `DecisoreLlm` | stratega LLM (step INTEGRAZIONE LLM): Claude Code installato, poi la chiave API (Claude Haiku, DeepSeek, Groq); qualsiasi errore = regole per quel round. Vedi CLAUDE.md "Stratega LLM" e `prompt-stratega-v1.md` nelle risorse di game-core |

Scelta per alleanza: pannello "Impostazioni Pro+" (riga "Stratega": Automatico / Claude installato / Chiave
API / File / Solo regole + percorso) e autoplay `--decisore Allies=file:/percorso,Axis=regole` o
`Allies=llm` (chiave API da `ANTHROPIC_API_KEY`). Salvata nella partita (proprieta' `Pro+ decisore:
<alleanza>` e `Pro+ decisore file: <alleanza>`).

**File delle direttive** (`DecisoreFile`): il percorso e' una cartella o un file.
- cartella: `direttive-R<round>-<alleanza>.json` (gli stessi nomi scritti dal diario: una partita
  registrata si puo' modificare e rigiocare);
- file: `{"<alleanza>": {"<round>": {...}}}` oppure `{"<round>": {...}}` (round `"3"` o `"R3"`),
  oppure un solo oggetto di direttive (con `round`/`alleanza` facoltativi che devono coincidere).
- File non JSON ⇒ direttive non valide (ripiego sulle regole con l'errore nell'avviso).

## Ingresso (stato strategico), schema v1

JSON compatto, obiettivo <= ~5.000 token. Misurato nell'autoplay in CI (12 partite Pro+ da 12 round, 288
ingressi): media 6.969 caratteri (~1.742 token stimati a 4 caratteri/token), massimo 8.900 (~2.225). Solo letture del gioco; le stime sono rapide (nessuna
simulazione di battaglia, quindi nessun dado consumato).

| Campo | Contenuto |
|---|---|
| `versione`, `alleanza`, `nemico`, `round` | |
| `ordine_turni` | nazioni nell'ordine della sequenza di gioco |
| `nazioni` | {alleanza: [nazioni]} |
| `fronti` | fronti del file di strategia |
| `vc` | per alleanza `tenute` (nomi), `misura_corsa`, `obiettivo`, `tendenza_3_round`, `round_alla_vittoria` (corsa dello step 3d-1); `corsa` (esito) |
| `economia` | per nazione `pu` (cassa) e `reddito` |
| `tuv` | per fronte e alleanza TUV di `terra`, `mare`, `aria` |
| `capitali` | nazione, territorio, proprietario, `forza_nemica` (0-100, stima rapida dei nemici del proprietario) |
| `fabbriche` | per alleanza `totale` e `perse` (nomi) |
| `vc_contese` | VC con nemici di terra confinanti o in mano all'altra parte (`in_origine`) |
| `piano` | id, nome, fase, stato (IN LINEA/IN RITARDO/IN CRISI), round in vigore, cambi fatti, tappe con stato, reddito, reddito R1, reddito nemico, bilancio TUV, operazioni in stallo, ruoli |
| `piani_ammessi` | piano in vigore e subentri con punteggio delle regole (tappe, forze, contrasto), `ammesso` e `perche_no` (paletti), `contrasta` |
| `lettura_nemico` | piano piu' probabile, probabilita' dei piani, indizi principali |
| `operazioni` | per operazione del piano nel round: nazioni, bersagli, zone di raccolta, truppe pronte nei porti, trasporti in zona, truppe a bordo, scorta, trasporti richiesti, scorta minima, `perdita_attesa_trasporti_stima` (TUV flotta × forza nemica sulla zona), `in_stallo`, `ultimo_rapporto` (ultimo turno: stato, win% sul bersaglio, tenuta stimata della testa di ponte, accumulo) |
| `minacce` | top 5 capitali/fabbriche/VC nostre per `forza_nemica` |
| `eventi` | `battaglie_maggiori` (top 5 per TUV, round prima e round in corso), `conquiste`, `guerre_nuove` (coppie entrate in guerra dalla decisione precedente) |
| `strumenti` | per strumento di ritardo: nazioni, fronti, zone di passaggio nemiche (blocco navale), `attiva_dalle_regole` (sempre false) |
| `paletti` | valori dei paletti (sotto) |

Estratto reale (`esempi/stato-R2-Allies.json`):
```json
"vc": {"Allies": {"tenute": ["Eastern United States", "Egypt", "Novgorod", "Ontario", "Russia",
  "United Kingdom", "Volgograd"], "misura_corsa": 0, "obiettivo": 2, "tendenza_3_round": 0.0,
  "round_alla_vittoria": 99}, "Axis": {"tenute": ["France", "Germany", "Poland", "Southern Italy"],
  "misura_corsa": 4, "obiettivo": 8, "tendenza_3_round": 0.0, "round_alla_vittoria": 99},
  "corsa": "alla pari"},
"piani_ammessi": [{"id": "1", "nome": "Germania prima", "in_vigore": true, "punteggio": 0.61,
  "tappe": 0.67, "forze": 0.15, "contrasto": 0.0, "contrasta": ["A", "B", "C"]},
  {"id": "2", "nome": "Italia prima", "in_vigore": false, "punteggio": 0.29, "tappe": 0.33,
  "forze": 0.05, "contrasto": 0.0, "ammesso": true, "contrasta": ["D"]}],
"lettura_nemico": {"piu_probabile": "A", "probabilita_piani": {"A": 0.801, "B": 0.057, "C": 0.071,
  "D": 0.071}, "indizi_principali": ["quota del TUV di terra tedesco sul fronte Est (A Barbarossa, 96%)"]},
"operazioni": [{"id": "Overlord UK", "nazioni": ["British"], "bersagli": ["Normandy Bordeaux",
  "Holland Belgium", "Norway"], "raccolta": ["110 Sea Zone"], "truppe_pronte_nei_porti": 32,
  "trasporti_in_zona": 0, "truppe_a_bordo": 0, "scorta": 0, "trasporti_richiesti": 3,
  "scorta_minima": 2, "perdita_attesa_trasporti_stima": 0, "in_stallo": false}],
"minacce": [{"territorio": "Egypt", "di": "British", "forza_nemica": 100, "tipo": "vc"}]
```

## Uscita (direttive), schema v1

| Campo | Valori | Effetto |
|---|---|---|
| `versione` | 1 | altra versione ⇒ non valide |
| `piano` | id di un piano dell'alleanza | diverso dal piano in vigore solo se ammesso (subentro + paletti), altrimenti non valide |
| `postura` | OFFENSIVA, RALLENTAMENTO, CONTRASTO, DIFENSIVA, NESSUNA | DIFENSIVA = rischio −1 su tutti i fronti e quota del piano 0 negli acquisti (come oggi); piano senza contromosse (N) ⇒ NESSUNA |
| `rischio` | {fronte: −1..+1} e, schema **v1.1**, {nazione: {fronte: −1..+1}} | scostamento dal livello base (cursore) per fronte, per tutta l'alleanza; in v1.1 anche per nazione (prevale per quella nazione e quel fronte). Ampiezza massima `stato_maggiore.ampiezza_rischio` del file dati (1), mai oltre ±1. Le regole scrivono il modificatore della fase (v1, invariato). `versione` 1.1 quando c'e' almeno una nazione |
| `operazioni` | [{`id`, `decisione`: lancia/attendi/annulla, `perdita_trasporti_accettata_max_TUV`}] | lancia = come oggi; attendi = raccolta e trasporto si', nessuno sbarco sui suoi bersagli e bersagli non obiettivi nel round; annulla = operazione spenta nel round (le unita' tornano a Hard). Perdita accettata > 0: uno sbarco sul bersaglio scartato dalla regola dei trasporti esposti si fa se la perdita attesa e' entro il tetto. Operazioni non elencate = lancia, 0 |
| `bersagli` | [{`territorio` (terra o zona di mare) o `flotta` (nazione), `peso` 0-20, `nazioni`?, `critico`?, `origine`?}] | obiettivi con bonus di priorita'. `origine: "contromossa"` = contro-obiettivo (non conta nel valore strategico degli sbarchi); altrimenti obiettivo del piano |
| `tattiche` | [{`strumento`, `nazione`, `fronte` o `zona`, `attiva`}] | accende gli strumenti di ritardo (blocco_terrestre, ritirata_combattuta, blocco_navale, caccia_trasporti); senza fronte = tutti; `zona` = il suo fronte |
| `ruoli` | {nazione: testo} | solo informativo (diario) |
| `lettura_nemico` | {`piano`, `probabilita` 0..1} | usata dagli strumenti (blocco navale, caccia) |
| `motivazione` | max 3 frasi in italiano | diario |

**Campi assenti = come le regole** (stesso piano), oppure ordini base del nuovo piano se il decisore
cambia piano (`DecisoreRegole.base`: fase del file, operazioni lancia, postura OFFENSIVA, niente
tattiche). Un file puo' quindi contenere solo `postura` o solo `operazioni`.

Esempio reale delle regole (`esempi/direttive-R2-Allies.json`, ridotto):
```json
{"versione": 1, "alleanza": "Allies", "round": 2, "piano": "1", "postura": "RALLENTAMENTO",
 "rischio": {"Ovest": 1, "Est": -1, "Mediterraneo-Africa": -1, "Mari": 0, "Altro": 0},
 "operazioni": [{"id": "Overlord UK", "decisione": "lancia", "perdita_trasporti_accettata_max_TUV": 0}],
 "bersagli": [{"territorio": "Finland", "peso": 8.0, "origine": "contromossa",
   "punto_critico": "stack tedesco piu' avanzato e isolato"},
  {"territorio": "Romania", "peso": 8.0, "origine": "contromossa",
   "punto_critico": "Romania (petrolio, obiettivi nazionali tedeschi)"}],
 "tattiche": [], "lettura_nemico": {"piano": "A", "probabilita": 0.8},
 "motivazione": "Regole: postura RALLENTAMENTO perche' corsa alla pari (...) e piano nemico A Barbarossa letto al 80%. Piano 1 Germania prima. Strumenti di ritardo spenti."}
```

Esempio di file scritto a mano (`esempi/direttive-file-R1-Allies.json`, provato in autoplay R1):
```json
{"piano": "1", "postura": "DIFENSIVA",
 "tattiche": [{"strumento": "blocco_terrestre", "nazione": "Russians", "fronte": "Est", "attiva": true},
              {"strumento": "ritirata_combattuta", "nazione": "Russians", "attiva": true}],
 "operazioni": [{"id": "Overlord UK", "decisione": "attendi"}],
 "motivazione": "Prova a mano: Mosca prima di tutto. Overlord aspetta."}
```

## Validazione e paletti (`Direttive.valida`)

- **Non valide ⇒ ripiego su `DecisoreRegole`** con avviso nel diario: non un oggetto JSON, versione
  diversa da 1, piano sconosciuto, cambio piano non ammesso, postura sconosciuta, file illeggibile.
- **Fuori range ⇒ limitati** con avviso: rischio a −1..+1 (fronti sconosciuti ignorati), peso a 0..20,
  perdita accettata a 0..30, probabilita' a 0..1, motivazione tagliata a 3 frasi; bersagli, strumenti,
  nazioni e operazioni sconosciuti ignorati.
- **Paletti, qualunque decisore**: guarnigione della capitale (codice tattico, nessuna direttiva la
  tocca); soglia minima assoluta d'attacco 30% (`ProPlusTuning`; i livelli 1-5 hanno gia' minime
  45-65%); perdita di trasporti accettata <= 30 TUV per alleanza per turno (somma delle operazioni
  lanciate, ridotta in proporzione); max 2 cambi di piano per partita e >= 3 round tra due cambi.
- Tetto delle perdite di trasporti accettate (dallo step INTEGRAZIONE LLM): vale per ALLEANZA PER ROUND
  sommando tutti gli sbarchi di tutte le nazioni dell'alleanza (`TettoTrasporti`, proprieta' `Pro+ perdite
  trasporti accettate: <alleanza>`); prima valeva per singolo sbarco. Una voce per nazione e bersaglio (la
  stessa pianificazione ripetuta non conta due volte); uno sbarco accettato e poi scartato da un'altra regola
  resta contato (prudente).

## Diario e log

- Blocco dello stato maggiore: avvisi del contratto ("Avviso del contratto: ...") e riga "Direttive
  (<decisore>[, ripiego sulle regole]): piano, postura, rischio, operazioni, bersagli, tattiche" con
  "Motivazione: ...".
- Voce dello storico del round (proprieta' della partita e `decisioni.jsonl`): `direttive`, `decisore`
  {nome, ripiego, avvisi}, `guerre` (per le guerre nuove del round dopo); `stato_strategico` mostra
  postura, motivo, contro-obiettivi e tattiche delle direttive.
- Autoplay: `contratto` nel `riepilogo.json` (file di stato, caratteri medi/max, token stimati, ripieghi,
  direttive con tattiche), riga nel `riepilogo.md`, sezione "Contratto strategico" nel
  `riepilogo-generale.md`; nello zip i JSON di stato e direttive solo delle partite seme 1.

## Schema v1.1 e ordini del turno (step INTEGRAZIONE LLM, livello 2)

- **v1.1** = v1 + rischio per nazione: `"rischio": {"Ovest": 0, "Germans": {"Est": 1, "Ovest": -1}, "Italians":
  {"Mediterraneo-Africa": -1}}`. Una nazione senza valore proprio per un fronte usa quello dell'alleanza. Accettata
  `versione` 1 o 1.1. Diario: "Rischio effettivo per nazione e fronte (base N ...): Germans Ovest 4, Est 2 | ...";
  storico: `rischio_effettivo` {nazione: {fronte: livello 1-5}}.
- **Ordini del turno** (schema `ordini-turno-v1`, solo con lo stratega LLM): all'inizio del turno di ogni nazione Pro+
  con PU > 0 (escluse le nazioni di `stato_maggiore.nazioni_senza_ordini`: Europe 1940 `French`). Ingresso
  (`ordini-ingresso-R<N>-<nazione>.json` nel diario, scritto anche con le regole): `direttive` del round viste dalla
  nazione, `cambiamenti` dall'ultima decisione dell'alleanza (battaglie nuove, conquiste, stack/flotte nemiche con TUV
  cambiato >= 10, unita' alleate arrivate >= 6 TUV, rapporti delle operazioni), `muovono_dopo`, PU, reddito, stack,
  flotte, bersagli vicini, minacce, operazioni della nazione. Uscita: `{bersagli, operazioni, tattiche, motivazione}`
  (piano/postura/rischio ignorati con avviso). Fusione (`Direttive.applicaOrdini`): bersagli della nazione aggiunti
  (sostituiscono per lei quelli dello stratega sugli stessi territori), decisioni delle sole operazioni della nazione,
  `tattiche` (se presenti) sostituiscono quelle della nazione; stessi limiti e paletti. Salvati nella voce dello storico
  `ordini_turno.<nazione>` {ordini, direttive (fuse), decisore}; `PianoAttivo` della nazione legge le direttive fuse.
  Errore ⇒ la nazione gioca le direttive dello stratega (avviso nel diario).

## Ingresso, aggiunte dello step LLM-3

- `operazioni[].round_in_attesa` (round di fila in "attendi", assente se 0) e, da 3 round, `avviso` "operazione X in
  attesa da N round: motivare o lanciare" (anche negli ingressi degli ordini del turno).
- `obiettivi_nazionali` [{nazione, obiettivo, pu_per_turno, territori_richiesti, territori_tenuti, mancano,
  territori_da_prendere, altre_condizioni}] della alleanza (stratega) o della nazione (ordini del turno): obiettivi
  definiti da proprieta' di territori, gia' attivi o a <= 3 conquiste.

## Schema v1.2: decisione delle operazioni congiunte (step PROVALLM)

Versione minore: le direttive v1/v1.1 non cambiano. Si aggiunge un oggetto separato, chiesto SOLO nei punti di decisione
delle operazioni congiunte v2 (`OperazioniCongiunte`) quando l'alleanza ha la proprieta' `Pro+ operazione decisore:
<alleanza>` = `llm` | `llm:<modello>` (default spento = decide la formula v2; modello di default `claude-sonnet-5-5`).

```json
{"decisione_operazione": {"azione": "lancia", "prob_tenuta_stimata": 0.55, "motivazione": "..."}}
```

| Campo | Valori | Note |
|---|---|---|
| `azione` | `lancia` \| `rinvia` \| `procedi` \| `tieni` \| `ritira` \| `annulla` | ammesse per punto: `tappa1` lancia/rinvia/annulla; `tappa2` procedi/rinvia/annulla; `tenuta` tieni/ritira (`DecisioneOperazione.Punto`) |
| `prob_tenuta_stimata` | 0-1 | probabilita' che il bersaglio sia dell'alleanza all'inizio del turno di chi chiude nel round dopo la decisione, se l'azione viene eseguita (per le tappe comprende la presa) |
| `motivazione` | testo | max 300 caratteri tenuti |

- Parsing: `DecisioneOperazione.leggi` (JSON estratto dal testo come per lo stratega; oggetto `decisione_operazione` o campi alla
  radice). Azione sconosciuta o non ammessa nel punto, probabilita' mancante o fuori 0-1 ⇒ risposta non valida ⇒ formula.
- Ingresso (`OperazioniCongiunte.stato`, file `operazione-ingresso-R<N>-<nazione>-[finale_]<punto>.json` nel diario): bersaglio,
  punto, azioni ammesse, round, nazione, rinvii/massimo/round massimo, tetto di perdita e TUV gia' perso, valore e info del
  bersaglio, `numeri` del punto (forze proprie e alleate, win%, perdite attese, residui, rinforzi nemici tra le tappe),
  presidio, `contrattacco_massimo` per origine (territorio/zona, distanza, unita', TUV) + `acquisti_per_fabbrica`,
  `tenuta_per_impegno` (25/50/75/100% delle forze in portata, le piu' vicine per prime), `formula_v2` (azione e stima),
  `storico_nemico` (`StoricoNemico`: per i territori presi dall'alleanza, forze nemiche in portata e quante il nemico ne ha
  davvero mandate entro il turno successivo, se li ha ripresi, dove ha spostato le altre), `priorita_nemico` (territori di
  valore del nemico sotto pressione dell'alleanza), `partita` (VC e reddito).
- Paletti invariati e prima dell'LLM: nessuna chiamata se la tappa e' oltre il tetto di perdita o senza attaccanti (decide la
  formula); guarnigione della capitale nel codice; max rinvii e round massimo; tenuta chiesta solo sotto `soglia_rinforzi`.
- Paracadute (`DecisoreOperazione`): errore, timeout (45 s, 2 tentativi), risposta non valida, tetto 80 chiamate per partita,
  budget in USD della partita (`Pro+ operazione budget usd`, default 1; prossima chiamata stimata come 1,5 × costo medio, min
  $0,03) ⇒ decisione della formula, con il motivo. Uso in `Pro+ operazione uso`.

## Schema v1.3: Stratega LLM completo (step STRATEGA LLM)

Solo per la voce giocatore "Pro+ Stratega LLM (Europe 1940)" (`ProPlusStrategaAi`). Due livelli, stesso trasporto
(Anthropic Messages API, `output_config.format` json_schema strict, `effort: low`, niente `temperature`).

**Livello strategico** (`Stratega`, inizio round, prima nazione dello Stratega dell'alleanza; prompt
`prompt-stratega-v4.md`, `PromptStrategaV4`). Ingresso (contratto `1.3`): `round`, `alleanza`,
`nazioni_dello_stratega`, `ordine_dei_turni`, `operazioni_in_corso` [{operazione, tipo, bersaglio, apre, chiude,
stato, round_obiettivo, rinvii, ultima_tenuta_stimata_motore_pct, tenuta_dal_round, durata_tenuta,
proprietario_bersaglio, ultimi_eventi}], `operazioni_finite`, `candidati` (max 8, `StrategaCandidati`: id "C1"...,
tipo offensiva|sbarco|tenuta|rinforzo, bersaglio, proprietario, nazioni, ruoli, round_minimo, valore {valore_PU,
produzione_PU, victory_city, capitale}; conquiste: difensori, tappa1/tappa2 {nazione, attaccanti, win_pct,
perdita_attesa_TUV}, presa_stimata_pct, bersagli_successivi_possibili (sbarco), zona_raduno/raduno; tenute: difensori,
nemici_in_portata_TUV, tenuta_ora_50_pct/100_pct, rinforzi_possibili; tutti: presidio_atteso,
contrattacco_massimo (per origine + acquisti per fabbrica), tenuta_per_impegno 25/50/75/100%), `lettura_avversario`
{ammassamenti_per_fronte [{fronte, tuv_nemico, tuv_alleanza, variazione_tuv_nemico}], minacce_capitali_vc
[{territorio, capitale, tuv_nemico_in_portata, tuv_difensori, tenuta_100_pct}], storico_nemico_per_tipo {terra,
costa_sbarco, capitale}}, `partita` {reddito_per_nazione, victory_city_per_alleanza}.

Uscita:
```json
{"portafoglio": [{"azione": "avvia|continua|modifica|annulla", "candidato": "C2", "operazione": "",
  "round_obiettivo": 0, "tetto_perdita_TUV": 0, "durata_tenuta": 0, "bersaglio_successivo": "",
  "prob_tenuta_stimata": 0.55, "motivazione": "..."}],
 "fuori_lista": {"proponi": false, "tipo": "nessuno", "bersaglio": "", "apre": "", "chiude": "",
  "prob_tenuta_stimata": 0, "motivazione": ""},
 "rinforzi": [{"territorio": "Romania", "peso": 2}],
 "motivazione": "..."}
```
Validazione (`StrategaDecisione.leggi`: azione nota, `avvia` con candidato, altre con operazione, probabilita' 0-1,
testi tagliati a 300 caratteri) poi il motore: annulla/modifica/continua prima, poi gli avvii; rifiutati con motivo
(nel diario e in `rifiutate` del portafoglio): candidato sconosciuto, operazione non in corso, piu' di
`max_operazioni_per_alleanza`, bersaglio gia' in un'operazione attiva; `fuori_lista` validata da `StrategaFuoriLista`
(tipo, territorio, nazioni dello Stratega con ruoli, guerra con il proprietario, chi apre gioca prima di chi chiude,
zona di mare con terra dell'alleanza per lo sbarco, territorio dell'alleanza per tenuta/rinforzo, rinforzo non al
proprio territorio). `rinforzi`: max 4 territori propri/alleati o zone di mare, peso 1-3 → ammassamento del non
combattimento (`ProPlusTuning.stagingOrders`, risposta marcata come operazione).

**Ordini del turno** (punti di decisione delle operazioni durante il turno di ogni nazione con un ruolo: tappa1,
tappa2, tenuta) = decisione v1.2 (`decisione_operazione`); l'ingresso ha `tipo_operazione` e il prompt e'
`prompt-ordini-turno-v3.md` (`PromptOrdiniTurnoV3`). Ripiego (errore, timeout, non valida, tetto) = **Hard**: tappa →
rinvia, tenuta → ritira (non la formula), registrato con `ripiego_hard`.

## Schema v1.4: stima unica del motore (step COERENZA STIME)

Stessa uscita della v1.3 (portafoglio, fuori_lista, rinforzi, motivazione) e della v1.2 per gli ordini del turno. Prompt
`prompt-stratega-v5.md` (`PromptStrategaV5`) e `prompt-ordini-turno-v4.md` (`PromptOrdiniTurnoV4`): niente affermazioni
sulla stima del motore (pessimista/ottimista), niente spinta a lanciare contro i numeri; l'LLM usa `stima_motore` e se ne
discosta solo con un dato dello storico del nemico scritto nella motivazione.

Ingresso del livello strategico (contratto `1.4`), differenze dalla 1.3:
- ogni candidato ha `stima_motore` (`StimaUnica.Stima.json()`): `funzione`, `calcolata_al_round`, `per_il_round`;
  conquiste `attaccanti_tappa1`, `win_tappa1_pct`, `perdita_attesa_tappa1_TUV`, `attaccanti_tappa2`, `win_tappa2_pct`,
  `perdita_attesa_tappa2_TUV`, `tuv_difensori`, `tuv_rinforzi_nemici_tra_le_tappe`, `presa_pct`; tutti `presidio`,
  `contrattacco_contato_TUV`, `contrattacco_massimo_TUV`, `impegno_nemico`, `base_impegno`, `tenuta_pct`, `successo_pct`;
  `tetto_perdita_TUV` del candidato (proporzionale al valore); tenute/rinforzi `rinforzi_possibili` (quelli che il motore
  puo' muovere). TOLTI: `tappa1`/`tappa2`/`presa_stimata_pct` della stima rapida, `tenuta_ora_50_pct`,
  `tenuta_ora_100_pct`, `tenuta_50_con_rinforzi_pct`, `nemici_in_portata_TUV` (fonte del 100% contro 0-29% della run
  42775993327). Restano `contrattacco_massimo` e `tenuta_per_impegno` (stesso presidio della stima);
- `candidati_scartati` [{tipo, bersaglio, nazioni, motivo}]: Hard lo prende da sola nel round, infattibile (raccolta oltre
  l'orizzonte, zona di raduno o raccolta aerea non sicura), perdita attesa della tappa 1 oltre il tetto, tenuta sotto
  `tenuta_minima_candidato` anche con i rinforzi;
- `operazioni_in_corso[]`: `stima_motore_ora` (ricalcolata a ogni round con la stessa funzione, anche se il candidato non
  e' piu' in lista), `stima_motore_al_lancio`, `presa_senza_operazione`.

Ordini del turno (dinamiche): `numeri.stima_motore` (stessa funzione), `stima_motore_azione` {azione, prob_tenuta_stimata,
nota} al posto di `formula_v2`.

Step C1-bis (stesso contratto `1.4`, campi in piu'): `stima_motore.valore_alleanza` {`formula`, `valore_bersaglio_PU`,
`presa_pct`, `tenuta_pct`, `bersaglio_atteso_PU`, `tuv_nemico_distrutto`, `tuv_perso`, `totale_PU`}; `valore` del candidato
con `indice_valore_PU` (ordina i candidati, ex `valore_PU`), `valore_bersaglio_PU` e `componenti_PU` {`round_tenuta`,
`reddito_PU`, `obiettivi_nazionali_PU`, `victory_city_PU`, `capitale_PU` (+ `capitale_cassa_PU`,
`capitale_reddito_per_round_PU`), `totale_PU`}. Formula e razionale in `stratega-llm.json` (`valore_pu`).
