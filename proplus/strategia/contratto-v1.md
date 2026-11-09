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
