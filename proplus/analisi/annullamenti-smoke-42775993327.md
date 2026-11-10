# Perche' lo Stratega LLM annulla: analisi della run [smoke] 42775993327 (commit 805c684)

Solo lettura dei diari gia' prodotti (`autoplay.zip` della release `autoplay-latest`, sha256 582ce8c0...db0c8):
`stratega.jsonl`, `stratega-riepilogo.json`, `stratega-ingresso-R*.json`, `operazione-ingresso-R*.json`,
`decisioni.jsonl`, `diario.txt` delle 12 partite (6 Asse Stratega contro Hard, 6 Alleati Stratega contro Hard, semi
501-506). Nessuna partita nuova, nessuna modifica al comportamento.

## Numeri di partenza (ricontati)

- 93 operazioni avviate: offensiva 49, rinforzo 36, tenuta 5, sbarco 3.
- **56 operazioni finite ANNULLATE** (non 51): 52 dal livello strategico (51 azioni `annulla` nei record + 1 nella
  chiamata R7 degli Alleati seme 503, il cui record manca nel diario, vedi "Anomalie") e 4 dagli ordini del turno
  (`tappa1` -> `annulla`). Il "51" del riepilogo [smoke] conta le azioni `annulla`, non le operazioni.
- Per tipo: offensiva 30, rinforzo 21, sbarco 3, tenuta 2.
- L'LLM VEDE sempre le operazioni in corso: in 55 annullamenti su 56 l'operazione era in `operazioni_in_corso` con
  stato, proprietario del bersaglio, rinvii, ultima tenuta del motore e ultimi 3 eventi (l'ultimo e' nel record
  mancante). "Non vede le operazioni o il loro stato" NON e' una causa.
- Il limite di 3 operazioni ha pesato poco: 1 avvio rifiutato per "gia' 3 attive", 1 annullamento che cita "libera
  uno slot" (#3, comunque bersaglio gia' preso).

## Classificazione per causa (una causa principale per operazione)

| Causa | Annullate | Di cui strategico / ordini | PU riserve | TUV spostati verso il raduno | Classe |
|---|---|---|---|---|---|
| C1 Bersaglio gia' preso da Hard (o da un alleato) prima delle tappe: lancio ridondante | **15** | 15 / 0 | 213 | 312 | artefatto |
| C2 Rinforzo con tenuta "con rinforzi" gonfiata nel candidato: morto al primo turno | **18** | 18 / 0 | 0 | 0 | artefatto |
| C3 Forze reali della tappa insufficienti (presa del candidato con tutte le unita' in portata) | **7** | 3 / 4 | 90 | 268 | artefatto (fattibilita') |
| C4 Raccolta o flotta non realizzata (zona non sicura, aerei "alla raccolta in 25 round") | **4** | 4 / 0 | 70 | 0 | artefatto (fattibilita') |
| C5 Candidato sparito dalla lista: l'LLM annulla perche' non lo vede piu' | **2** | 2 / 0 | 21 | 65 | artefatto |
| C6 Ripensamento sugli stessi numeri (avviata con tenuta 0 a ogni impegno, annullata citando la stessa tenuta 0) | **3** | 3 / 0 | 20 | 105 | LLM |
| G Giustificato: la situazione e' cambiata davvero (nemico da 22 a 102 TUV, tenuta da 93-99% a 0, territorio perso, riallocazione) | **7** | 7 / 0 | 56 | 45 | giustificato |
| **Totale** | **56** | 52 / 4 | **470** | **795** | |

**Risposta netta**: 46/56 (82%) sono artefatti del sistema, 3/56 (5%) incoerenza dell'LLM, 7/56 (12,5%) annullamenti
giustificati da un cambiamento reale. Nel merito l'annullamento e' quasi sempre CORRETTO (53/56): l'operazione era
inutile o morta; l'errore e' a monte, nel lancio. Quindi **non serve inerzia sugli annullamenti**: servono candidati
migliori.

**Oscillazione** (stesso bersaglio riavviato entro 2-3 round, numeri invariati): **6 operazioni annullate** in 5 catene
(gia' contate sopra nella loro causa principale):
- Asse 501: rinforzo France R9 -> annullato R10 -> rinforzo France di nuovo R12 (C2);
- Asse 502: offensiva Russia R7 -> annullata dagli ordini R8 -> di nuovo R10, mai eseguita (perdita attesa 234 e 121
  > tetto 60) (C3);
- Asse 503: offensiva Russia R6 -> annullata R7 -> di nuovo R10, ferma in preparazione fino a fine partita (C5);
- Asse 505: offensiva Volgograd R8 FALLITA -> di nuovo R10 -> annullata R11 "la presa e' gia' fallita una volta" (C6);
- Alleati 503: rinforzo Caucasus R5 -> annullato R6 -> rinforzo Caucasus R9 (altra nazione) -> annullato R10 (C2, 2
  operazioni).
Piu' una risposta contraddittoria (Alleati 506 R8: `continua` e `annulla` sulla stessa operazione Algeria). In
totale oscillazione + ripensamento = 8 operazioni (14%) nel senso largo; l'oscillazione in senso stretto e' l'11%.

**Gia' perse**: 6 operazioni contate come "annullate" avevano il bersaglio GIA' in mano nemica (Grecia, Egitto x2,
Southern France, Western Ukraine, Novgorod degli Alleati): erano FALLITE, ma la chiamata strategica di inizio round
arriva prima del controllo del motore che le chiude come FALLITA. Errore di contabilita', non di gioco.

### C1 - lancio ridondante con Hard (15 annullate + 10 "prese senza operazione")

Il candidato offensivo e' un territorio nemico che Hard, con le sue unita' ordinarie, prende NELLO STESSO round o in
quello dopo (France R1 in 6 partite su 6, Morocco, Normandy, Novgorod, Volgograd, Rostov, Holland Belgium, Bulgaria,
Poland, Egypt degli Alleati). L'operazione ha il round obiettivo >= round dopo, quindi arriva tardi: al round dopo
l'LLM vede `proprietario_bersaglio` = alleanza e annulla ("gia' tedesca, non c'e' nulla da prendere").
In altre 10 offensive l'LLM NON ha annullato e il motore le ha passate in TENUTA come "preso senza operazione": il
riepilogo [smoke] le conta come "eseguite" e 7 come "completate". Lo stesso bersaglio (France R1) e' stato annullato
in 3 partite e "completato" in 3, con la stessa situazione: incoerenza tra partite, non informazione nuova.
**Offensive davvero eseguite dal motore (tappa 1 lanciata): 3 su 49** (Poland Asse 501, Egypt Asse 505, Egypt Alleati
502), non 13. 25/49 offensive (51%) avevano un bersaglio che Hard ha preso da sola.

### C2 - rinforzi morti al primo turno (18)

Nel candidato `rinforzo` convivono due stime incompatibili:
- `tenuta_per_impegno` (stessa funzione del punto di decisione, `valutaTenuta`): quasi sempre 0-5%;
- `tenuta_ora_50_pct` e `tenuta_50_con_rinforzi_pct` dalla stima rapida di `StrategaCandidati.tenuta()`: prende i
  nemici dal piu' caro al 50% del TUV (spesso lascia fuori la fanteria), con `nemiciVicini` approssimato e con
  `rinforzi()` = TUTTE le unita' entro 2 di terra o raggio aereo (anche aaGun, unita' che Hard usera' in
  combattimento, aerei oltre `aerei_copertura` 2): 100% in 17 casi su 20.
L'LLM lancia citando "con i rinforzi la tenuta al 50% sale al 100%"; nello STESSO round, al punto di decisione della
nazione che rinforza, il motore calcola con gli stessi nemici e difensori (spesso gli stessi rinforzi) una tenuta
"con tutti i rinforzi" di 0-29% (Caucaso: 100% contro 7% con input identici; Egitto Alleati 99% contro 0%;
Slovakia Hungary 100% con "10 fanterie, 2 corazzati..." contro 1% con "2 caccia") e l'ufficiale degli ordini
ritira. Il round dopo lo stratega annulla. 19 rinforzi/tenute su 41 sono stati ritirati nel loro stesso round di
avvio. Costo in unita' e PU nullo (ritirati prima di muovere), costo vero: 2 chiamate LLM sprecate e uno dei 3 posti
occupato per un round, piu' 3 oscillazioni.

### C3 - forze della tappa insufficienti (7)

Il candidato stima la presa con tutte le unita' in portata a inizio round (es. Russia: "9 armour, 4 artillery, 1
bomber, 3 mech_infantry, 1 tactical_bomber", presa 100%); l'operazione pero' trattiene solo gli aerei di chi apre e
poche unita' di terra; alla tappa 1 ci sono "1 bomber, 1 tactical_bomber" contro 65 TUV (win 0), oppure la perdita
attesa supera il tetto (224 > 60). Annullate dagli ordini (4) o dopo 1-2 rinvii (3). Mosca e' stata proposta e lanciata
5 volte in 4 partite con presa 100%: mai eseguita.

### C4, C5, C6

- C4: Southern Italy lanciata con "aerei Russians alla raccolta in 25 round" (infattibile gia' al lancio); 2 sbarchi in
  Algeria con la zona di raduno non sicura (flotta mai riunita); Egypt Asse 501 due round senza unita' raccolte.
- C5: Russia (Asse 503) e Volgograd (Alleati 505) escono dagli 8 candidati e l'LLM annulla citando "non compare piu'
  tra i candidati". Un'operazione in corso non ha piu' i numeri aggiornati se esce dalla lista.
- C6: Volgograd (Asse 503 e 505), Novgorod (Asse 501 R11): avviate con `tenuta_per_impegno` 0/0/0/0 e
  `prob_tenuta_stimata` 0,3-0,6 ("lo storico del nemico e' basso"), annullate il round dopo citando la stessa tenuta 0.
  Il prompt v4 (punto 4: "la stima del motore e' troppo pessimista: 0-19% -> tenuto il 52%") spinge a lanciare
  contro i numeri del motore: 32 offensive/sbarchi su 52 sono stati avviati con tenuta del motore al 50% <= 5%; di
  questi 19 annullati, 3 completati.

## Offensive non eseguite (36/49 secondo il riepilogo [smoke])

Il riepilogo conta "eseguita" = presa (anche senza operazione) o tenuta. Con la stessa definizione:

| Motivo | Offensive |
|---|---|
| Annullate prima di qualsiasi presa: bersaglio gia' preso da Hard/alleato (C1) | 13 |
| Annullate: forze della tappa insufficienti / perdita > tetto (C3) | 7 |
| Annullate: raccolta mai fatta (C4, solo offensive) | 2 |
| Annullate: candidato sparito (C5) | 2 |
| Annullate: ripensamento sugli stessi numeri (C6) | 3 |
| Annullate: situazione cambiata (G) | 2 |
| In corso a fine partita: avviate al R12 (non potevano arrivare alle tappe) | 5 |
| In corso a fine partita: rinviata 2 volte per perdita > tetto (Russia Asse 502) / ferma in preparazione dal R10 (Russia Asse 503) | 2 |
| **Totale non eseguite** | **36** |

(Le 13 "eseguite" = 10 prese da Hard senza operazione + 3 tappe vere.) L'ordine di turno chi apre / chi chiude non e'
mai stato la causa: le tappe che si sono fermate si sono fermate per forze o perdita, non per l'ordine.
Avvii al R11-R12: 15 operazioni (5 offensive al R12 mai eseguite): artefatto della partita da 12 round, non conta in
una partita vera di Matteo.

## Costo degli annullamenti

- **PU**: le operazioni annullate hanno imposto 470 PU di acquisti "obbligati" (fanteria + artiglieria, 7 PU per
  nazione e round, piu' trasporti per gli sbarchi) su 721 PU di riserve di tutta la run (65%); pari al 3,5% dei
  13.621 PU spesi dalle nazioni dello Stratega. NON sono persi: le unita' esistono e tornano a Hard, ma il mix e il
  luogo di piazzamento non sono quelli che Hard avrebbe scelto. C1 da sola: 213 PU.
- **Unita'**: 78 unita' (circa 795 TUV, quasi tutti aerei) spostate verso raduni poi abbandonati; 1.077 TUV x round
  trattenuti in raccolta. Nessuna unita' persa in combattimento per un'operazione annullata (le tappe annullate non
  sono partite).
- **Chiamate**: ogni rinforzo C2 ha costato 1 chiamata degli ordini + una voce del portafoglio; il costo in $ e'
  trascurabile (la run intera $2,84).
- Il costo vero non e' in PU: e' che **per 46 operazioni su 56 lo Stratega non ha cambiato nulla rispetto a Hard**
  (bersagli presi comunque, rinforzi mai partiti, tappe mai lanciate).

## Correzioni minime proposte (NON implementate), per impatto

| # | Correzione | Annullamenti evitati (su 56) | Altro effetto |
|---|---|---|---|
| 1 | **Una sola stima di tenuta nei candidati rinforzo/tenuta**: `tenuta_ora` e `con_rinforzi` calcolate con `valutaTenuta` e con i rinforzi che il punto di decisione potra' davvero muovere (stessa logica di `OperazioniCongiunte.candidati`: movimento, capitale, `aerei_copertura`, niente aaGun); scartare il candidato se con i rinforzi resta < soglia | **18** (C2) + 3 oscillazioni | 19 ritiri nel round di avvio in meno |
| 2 | **Escludere dai candidati offensivi i bersagli che Hard attacca in questo round** (bersaglio nella lista degli attacchi di Hard con win >= soglia, o difensori <= forze ordinarie adiacenti); e se il bersaglio diventa dell'alleanza prima delle tappe, dire all'LLM "preso senza operazione: ora TENUTA" invece di farlo decidere | **15** (C1) | 10 "eseguite/completate" finte in meno: la misura diventa onesta |
| 3 | **Presa del candidato con le forze che l'operazione impegnera'** (aerei di chi apre + riserve di terra), con perdita attesa entro il tetto; niente candidato se la tappa 1 non sta nel tetto | **7** (C3) | +2 operazioni non piu' bloccate fino a fine partita (Mosca) |
| 4 | **Ordine a inizio round**: aggiornare lo stato delle operazioni (presa/FALLITA) PRIMA della chiamata strategica | 0 (contabilita') | 6 "annullate" diventano FALLITE; C1 passa in TENUTA da solo |
| 5 | **Fattibilita' come filtro** al lancio: niente candidato con raccolta oltre l'orizzonte o con zona di raduno non sicura | **4** (C4) | |
| 6 | **Prompt**: togliere o limitare il punto 4 ("motore troppo pessimista") per le offensive con tenuta 0 a ogni impegno; dire che `annulla` su un'operazione appena avviata senza numeri nuovi e' un errore | **3** (C6) | meno lanci contro i numeri del motore (32/52) |
| 7 | **Candidati persistenti**: le operazioni in corso hanno sempre i loro numeri aggiornati nello stato, anche fuori dagli 8 | **2** (C5) | |
| 8 | **Isteresi sul ri-avvio** (non sull'annullamento): stesso bersaglio non riavviabile per 3 round dopo annullata/FALLITA se i numeri non sono migliorati | 0 nuovi (le 6 oscillazioni sono gia' coperte da 1, 3, 6, 7) | rete di sicurezza |

Con 1+2+3 si evitano 40 annullamenti su 56 (71%); con 1-7 49 su 56 (88%). Restano i 7 giustificati.
**Inerzia sugli annullamenti: sconsigliata**: 53/56 annullamenti erano corretti nel merito; tenere vive quelle
operazioni significherebbe trattenere unita' su bersagli gia' presi o persi.

## Anomalie trovate (non corrette: fuori dal perimetro di questo step)

- **Record mancante**: Alleati seme 503, R7: la chiamata strategica e' avvenuta (12 chiamate nell'uso, 11 record nel
  diario), ha annullato l'offensiva su Volgograd (evento nel registro), poi un'eccezione dentro `applica` ha saltato
  `chiudi`: niente record in `stratega.jsonl`/`stratega.txt`, portafoglio di quel round non salvato, `round_decisi`
  11. L'eccezione va solo in `ProLogger.warn` (non nel log della partita): causa non ricostruibile dai file.
- **"Eseguite" nel riepilogo [smoke]** conta le prese senza operazione e tutti i rinforzi/tenute anche se ritirati:
  sovrastima l'uso reale (offensive 13 contro 3 vere).
- **Calibrazione [smoke]** contava anche le stime di annulla/rinvia/ritira (~0): corretta in questo step
  (`ConfrontoSmoke.NON_ESEGUITE`), stessi numeri del ricalcolo a mano: strategico 18,7 punti su 124 stime (era 30,1
  su 162), ordini 15,6 su 24 (era 34,3 su 51).

## Tabella delle 56 operazioni annullate

PU riserve = acquisti obbligati del piano attribuiti all'operazione (etichetta `ordini` di `acquisti_piano`); TUV
spostati = unita' mosse verso il raduno (costi standard: fanteria 3, artiglieria 4, mech 4, corazzato 6, caccia 10,
bombardiere tattico 11, bombardiere 12, aaGun 5, trasporto 7); TUV in raccolta = somma per round delle unita' ferme
in raccolta. Stato = stato visto dall'LLM al momento dell'annullamento.

| # | Partita | Tipo | Bersaglio | Nazioni | Avvio | Annullo | Chi | Stato | PU riserve | TUV spostati | TUV in raccolta | Causa | Osc. | Gia' persa | Motivazione LLM (accorciata) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Asse 501 | offensiva | France | Germans>Italians | R1 | R2 | strategico | posizionamento | 14 | 43 | 51 | C1 |  |  | Il bersaglio France è già in mano tedesca (proprietario Germans): l'offensiva non ha più senso e trattiene aerei e PU che servono altrove. |
| 2 | Asse 501 | offensiva | Morocco | Germans>Italians | R2 | R3 | strategico | posizionamento | 14 | 43 | 0 | C1 |  |  | Il Marocco risulta già italiano, quindi non c'è nulla da prendere. L'operazione trattiene aerei tedeschi e PU che servono altrove. |
| 3 | Asse 501 | offensiva | Volgograd | Germans>Italians | R5 | R9 | strategico | posizionamento | 10 | 0 | 0 | C1 |  |  | Volgograd è già tedesca, quindi l'offensiva non ha più senso; è rinviata e le unità italiane in raccolta sono ferme. Libera uno slot. |
| 4 | Asse 501 | rinforzo | France | Italians>Italians | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 | si |  | Tenuta stimata 0% contro 44 TUV americani adiacenti più 20 di acquisti: i rinforzi andrebbero persi. |
| 5 | Asse 501 | rinforzo | Volgograd | Italians>Italians | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Tenuta 0% contro 95 TUV russi in portata; il motore ha già deciso di ritirare. |
| 6 | Asse 501 | offensiva | Egypt | Germans>Italians | R10 | R12 | strategico | preparazione | 7 | 0 | 0 | C4 |  |  | Dopo 2 round non ha raccolto nessuna unità. Le forze servono altrove. |
| 7 | Asse 501 | offensiva | Novgorod | Germans>Italians | R11 | R12 | strategico | preparazione | 0 | 21 | 0 | C6 |  |  | Il fronte Est ha 331 TUV nemici contro 161 nostri, e salita di 110. Novgorod non è tenibile. |
| 8 | Asse 502 | offensiva | Novgorod | Germans>Italians | R4 | R6 | strategico | posizionamento | 28 | 0 | 129 | C1 |  |  | Novgorod è già tedesca (proprietario Germans), quindi l'offensiva non ha più senso. Tenerla bloccata trattiene 3 fighter e 5 tactical_bomber che se... |
| 9 | Asse 502 | offensiva | Russia | Germans>Italians | R7 | R8 | ordini | posizionamento | 7 | 10 | 43 | C3 | si |  | La tappa 2 ha win stimata 0% e presa stimata 0, con rinforzi nemici tra le tappe da 239 TUV (30 fanteria, 16 artiglieria, 6 corazzati) contro 109 T... |
| 10 | Asse 502 | rinforzo | Western Ukraine | Italians>Italians | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Il presidio è un solo artillery contro 21 TUV di aerei e la stima del motore è 0%, quindi trattenere unità italiane non serve più. |
| 11 | Asse 502 | offensiva | Holland Belgium | Germans>Italians | R10 | R11 | strategico | posizionamento | 0 | 16 | 44 | C1 |  |  | Holland Belgium è già tedesca e la minaccia a Ovest è calata (-60 TUV, nemico 103 contro 414 nostri), le riserve servono altrove. |
| 12 | Asse 502 | rinforzo | Rostov | Italians>Italians | R11 | R12 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Rostov vale 2 PU, il motore stima 0% di tenuta e il contrattacco russo massimo è 64 TUV: l'operazione trattiene unità senza utilità. |
| 13 | Asse 503 | offensiva | France | Germans>Italians | R1 | R2 | strategico | posizionamento | 14 | 45 | 65 | C1 |  |  | Il bersaglio France risulta già dei Germans: non c'è nulla da prendere e le riserve trattenute servono altrove. |
| 14 | Asse 503 | offensiva | Algeria | Germans>Italians | R3 | R5 | strategico | tenuta | 21 | 45 | 45 | G  |  |  | Tenuta del motore allo 0% e presidio di 1 mech_infantry; la tenuta è persa e trattiene risorse inutilmente. |
| 15 | Asse 503 | rinforzo | Egypt | Germans>Germans | R4 | R5 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Tenuta allo 0-4% contro 30 TUV nemici in portata, contro 7 TUV di presidio: rinforzare è uno spreco. |
| 16 | Asse 503 | offensiva | Novgorod | Germans>Italians | R5 | R6 | strategico | posizionamento | 7 | 0 | 74 | C1 |  |  | Novgorod è già tedesca (proprietario Germans), quindi l'offensiva non ha più senso e trattiene 3 fighter e 4 tactical_bomber. |
| 17 | Asse 503 | offensiva | Russia | Germans>Italians | R6 | R7 | strategico | posizionamento | 7 | 44 | 0 | C5 | si |  | Il bersaglio 'Russia' non compare più tra i candidati e le riserve restano bloccate a Novgorod; meglio liberarle per Hard. |
| 18 | Asse 503 | offensiva | Volgograd | Germans>Italians | R7 | R8 | strategico | posizionamento | 6 | 42 | 42 | C6 |  |  | Tenuta a 0% con impegno 50%: i 82 TUV russi a Tambov (distanza 1) riprendono subito Volgograd. Storico terra 0.14, ma anche al 25% la tenuta resta ... |
| 19 | Asse 504 | rinforzo | Slovakia Hungary | Italians>Italians | R3 | R4 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Nessun presidio e tenuta 0%, solo 37 TUV in portata e valore di 3 PU: non vale trattenere unita'. |
| 20 | Asse 504 | offensiva | Novgorod | Germans>Italians | R4 | R6 | strategico | posizionamento | 28 | 46 | 35 | C1 |  |  | Novgorod è già tedesca: l'offensiva non ha più senso e trattiene bombardieri che servono altrove. |
| 21 | Asse 504 | offensiva | Rostov | Germans>Italians | R6 | R7 | strategico | posizionamento | 14 | 11 | 46 | C1 |  |  | Rostov risulta già italiana, quindi l'offensiva non ha più un bersaglio nemico da prendere. Le riserve tornano libere per la difesa. |
| 22 | Asse 504 | rinforzo | Rostov | Germans>Germans | R7 | R8 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Rostov vale 2 PU e non è VC; contro 99 TUV a Tambov la tenuta è 0-2%, inutile sprecare unità tedesche. |
| 23 | Asse 504 | offensiva | Caucasus | Germans>Italians | R8 | R9 | ordini | posizionamento | 7 | 11 | 6 | C3 |  |  | Il bersaglio vale solo 2 PU, senza VC, e i rinforzi nemici tra le tappe (89 TUV) portano win_tappa2 a 0 con tenuta 0% a ogni impegno. Rinviare non ... |
| 24 | Asse 504 | rinforzo | Poland | Italians>Italians | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Il presidio è un solo aaGun e la tenuta del motore è 0-1% contro 92 TUV di contrattacco atteso: perdere i 2 fighter sarebbe inutile. |
| 25 | Asse 504 | offensiva | Russia | Germans>Italians | R10 | R11 | ordini | posizionamento | 7 | 12 | 0 | C3 |  |  | Con 2 aerei (23 TUV) contro 18 difensori (65 TUV) la probabilita' di vittoria e' 0 e la perdita attesa e' 23 TUV senza presa possibile. Con rinforz... |
| 26 | Asse 504 | rinforzo | Greater Southern Germany | Italians>Italians | R11 | R12 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Tenuta del motore 0% anche con tutti i rinforzi contro 22-8 di contrattacco; continuare vuol dire sprecare unità italiane. |
| 27 | Asse 505 | offensiva | Normandy Bordeaux | Germans>Italians | R2 | R3 | strategico | posizionamento | 14 | 21 | 51 | C1 |  |  | Normandy Bordeaux è già tedesca, quindi l'offensiva non ha senso. Aerei e unità trattenuti servono meglio altrove, con 220 TUV russi ammassati a Est. |
| 28 | Asse 505 | rinforzo | Iraq | Germans>Germans | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Iraq vale solo 2 PU, non ha presidio e la tenuta stimata è 0% a ogni impegno: l'operazione trattiene unità senza ritorno. |
| 29 | Asse 505 | offensiva | Volgograd | Germans>Italians | R10 | R11 | strategico | posizionamento | 14 | 42 | 0 | C6 | si |  | Volgograd ha 0 difensori ma la tenuta è 0% a ogni impegno, e la presa è già fallita una volta (R8). |
| 30 | Asse 506 | offensiva | France | Germans>Italians | R1 | R2 | strategico | posizionamento | 14 | 43 | 63 | C1 |  |  | France e' gia' dei Germans (proprietario_bersaglio Germans) e la tenuta e' al 100%: l'offensiva non ha piu' nulla da prendere e trattiene aerei e u... |
| 31 | Asse 506 | offensiva | Russia | Germans>Italians | R7 | R10 | strategico | posizionamento | 21 | 124 | 112 | C3 |  |  | Rinviata 2 volte, nessuna unita' in raccolta: sta per annullarsi da sola e trattiene risorse che Hard usa meglio. |
| 32 | Asse 506 | offensiva | Bulgaria | Germans>Italians | R8 | R9 | strategico | posizionamento | 0 | 0 | 4 | C1 |  |  | Bulgaria e' gia' tedesca e non c'e' nulla da conquistare: l'operazione trattiene solo unita' inutilmente. |
| 33 | Asse 506 | rinforzo | France | Italians>Italians | R10 | R11 | strategico | tenuta | 0 | 0 | 0 | G  |  |  | La tenuta della Francia è allo 0-21% contro 102 TUV nemici a distanza 1, quindi altre unità verrebbero solo perse. |
| 34 | Asse 506 | offensiva | Poland | Germans>Italians | R11 | R12 | strategico | posizionamento | 14 | 23 | 26 | C1 |  |  | Poland è già tedesca e non ha minacce rilevate: l'offensiva trattiene aerei senza valore. Meglio liberarli per Egypt e per la difesa. |
| 35 | Alleati 501 | rinforzo | Greece | Russians>Russians | R7 | R8 | strategico | tenuta | 0 | 0 | 0 | C2 |  | si | Greece vale solo 2 PU, non è VC né capitale, e la tenuta stimata è 0% contro 37 TUV di contrattacco con 2 infantry (6 TUV) a difenderla. |
| 36 | Alleati 502 | rinforzo | Egypt | Russians>Russians | R3 | R4 | strategico | tenuta | 0 | 0 | 0 | C2 |  | si | 1 fanteria (3 TUV) contro 32 TUV adiacenti: tenuta 0-12%, il motore stesso propone il ritiro. Non vale la pena trattenere unità. |
| 37 | Alleati 502 | offensiva | Caucasus | Russians>British | R5 | R6 | strategico | posizionamento | 7 | 31 | 13 | C3 |  |  | Caucasus: la tappa 1 ha solo 2 bomber (win 56%) e la tenuta è 15% al 25% di impegno, 0% oltre, con 197 TUV nemici a Rostov. Prendere senza tenere n... |
| 38 | Alleati 502 | rinforzo | Norway | Americans>Americans | R6 | R8 | strategico | tenuta | 0 | 0 | 0 | G  |  |  | Tenuta stimata 0% contro 43 TUV di aerei nemici in portata e ufficiale che ha già deciso di ritirare: l'operazione non ha più senso. |
| 39 | Alleati 502 | sbarco | Southern France | Americans>British | R8 | R10 | strategico | tenuta | 35 | 0 | 36 | G  |  | si | Tenuta stimata allo 0% contro 195 TUV nemici adiacenti, il presidio è già lasciato a Hard e la tenuta è persa. |
| 40 | Alleati 503 | rinforzo | Caucasus | British>British | R5 | R6 | strategico | tenuta | 0 | 0 | 0 | C2 | si |  | Caucasus vale solo 2 PU, non è VC né capitale, e la tenuta stimata dal motore è 0% contro 5 armour e 2 bomber: trattenere unità britanniche è uno s... |
| 41 | Alleati 503 | offensiva | Volgograd | Russians>British | R6 | R7 | strategico | posizionamento | 14 | 21 | 54 | C1 |  |  | Volgograd è un territorio russo, non nemico; il bersaglio non è più valido e la minaccia reale (45 TUV contro 30 difensori, tenuta 16%) richiede un... |
| 42 | Alleati 503 | offensiva | Southern Italy | Russians>British | R8 | R10 | strategico | preparazione | 28 | 0 | 0 | C4 |  |  | Da due round in preparazione senza unita' raccolte; il candidato C3 mostra tappa 1 russa con win 0% e tenuta 0%. |
| 43 | Alleati 503 | rinforzo | Caucasus | Americans>Americans | R9 | R10 | strategico | tenuta | 0 | 0 | 0 | C2 | si |  | Caucasus vale 2 PU e la tenuta stimata e' 0-5%, il rinforzo spreca unita' americane. |
| 44 | Alleati 503 | offensiva | France | Americans>British | R10 | R12 | strategico | posizionamento | 21 | 49 | 98 | C3 |  |  | Già rinviata una volta; il bersaglio ora ha contrattacco massimo di 183 TUV e tenuta 0% a ogni impegno, quindi prendere France senza tenerla non se... |
| 45 | Alleati 503 | rinforzo | Greece | Russians>Russians | R10 | R11 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Un solo fante contro 28 TUV italiani in portata: tenuta 0%, il ritiro è già stato deciso e il blocco è inutile. |
| 46 | Alleati 504 | offensiva | Egypt | Russians>British | R4 | R6 | strategico | posizionamento | 28 | 0 | 12 | C1 |  |  | Egypt non e' piu' in mano nemica (proprietario British) e le riserve sono vuote, l'offensiva non ha piu' senso. |
| 47 | Alleati 504 | rinforzo | Iraq | Russians>Russians | R4 | R5 | strategico | tenuta | 0 | 0 | 0 | G  |  |  | In Iraq la minaccia è solo 12 TUV (1 bomber italiano), quindi il presidio russo serve di più sul fronte Est, dove il nemico ha 499 TUV. |
| 48 | Alleati 505 | tenuta | Western Ukraine | Russians>Russians | R2 | R4 | strategico | tenuta | 0 | 0 | 0 | G  |  | si | Western Ukraine vale 2 PU e non e' VC; il presidio (29 TUV) affronta 192 TUV in portata e il motore stima 0%, quindi conviene ritirare le forze. |
| 49 | Alleati 505 | rinforzo | Egypt | Russians>Russians | R4 | R5 | strategico | tenuta | 0 | 0 | 0 | C2 |  | si | Nessun presidio né rinforzo possibile (tenuta motore 0%), l'Italia ha 38 TUV in portata: la tenuta è impossibile. |
| 50 | Alleati 505 | rinforzo | Volgograd | British>British | R5 | R6 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Tenuta stimata 0%: 9 armour e 12 fanterie a Rostov (110 TUV) contro un presidio di 11 TUV. Non vale trattenere altre unità britanniche. |
| 51 | Alleati 505 | sbarco | Algeria | Americans>British | R6 | R7 | strategico | posizionamento | 21 | 0 | 15 | C4 |  |  | La flotta non e' riunita in 92 Sea Zone (zona non sicura) e l'operazione e' gia' al round obiettivo senza progressi; trattiene unita' e PU che Hard... |
| 52 | Alleati 505 | offensiva | Volgograd | Russians>British | R7 | R8 | strategico | posizionamento | 14 | 21 | 0 | C5 |  |  | Volgograd non e' piu' tra i candidati: il bersaglio risulta superato e le riserve trattengono unita' inutilmente, quindi Hard le userebbe meglio. |
| 53 | Alleati 505 | offensiva | Novgorod | Russians>British | R8 | R10 | ordini | posizionamento | 20 | 31 | 0 | C3 |  |  | Un solo fighter (10 TUV) contro 11 difensori (38 TUV) ha win 0: perdita certa di 10 TUV senza alcuna presa. Anche rinviando, round_max 11 e nessun ... |
| 54 | Alleati 506 | tenuta | Novgorod | Russians>Russians | R3 | R5 | strategico | tenuta | 0 | 0 | 0 | G  |  | si | Novgorod è sotto 270 TUV nemici contro 90 di presidio e la tenuta stimata dal motore è 0%. Trattenere unità lì è uno spreco; meglio difendere Russi... |
| 55 | Alleati 506 | sbarco | Algeria | Americans>British | R7 | R8 | strategico | posizionamento | 14 | 0 | 13 | C4 |  |  | Flotta non riunita in 92 Sea Zone e tenuta 0% in tutte le righe, valore 1 PU: libero unita' e PU per fronti piu' utili. |
| 56 | Alleati 506 | rinforzo | Normandy Bordeaux | Americans>Americans | R8 | R9 | strategico | tenuta | 0 | 0 | 0 | C2 |  |  | Il motore stima 0% e il nemico ha 226 TUV in portata contro 79 di difensori; ci sono solo 2 caccia di rinforzo, quindi la tenuta e' impossibile e m... |
