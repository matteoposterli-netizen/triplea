# REPORT notte 10-11 novembre 2026: R0 (diagnosi) e R1 (correzioni) dello Stratega LLM

Lavoro autonomo, nessuna domanda a Matteo. Branch `proplus` soltanto, 3 push (R0 codice, R1 codice, questo report).

## In breve
1. **Da dove vengono i VC persi dall'Asse Stratega: dalle tenute.** R0 (50 partite, semi 1101-1110): Stratega LLM − Hard = −1,70 VC
   (IC 95% [−3,24; −0,16]); lo stesso Stratega con le tenute spente = +1,50 VC rispetto allo Stratega completo (IC [0,18; 2,82]) ed e'
   alla pari con Hard (−0,20). Lo Stratega senza chiave e' Hard (−0,20, 0 differenze nel controllo di 449.743 risposte). Le liste
   irrinunciabili/sacrificabili non fanno una differenza misurabile (+0,70, include lo 0).
2. **Meccanismo**: l'LLM sceglie quasi solo tenute (33 su 37 operazioni in 10 partite) e ogni tenuta toglieva agli attacchi di Hard
   tutto il presidio e i rinforzi: 2.144 unita' tenute ferme in 99 turni (~22 per turno). Reddito finale dell'Asse 35,8 contro 66,1.
3. **R1 (correzioni A1-A8 + B5-B8 + D6)**: le unita' ferme scendono a 638 in 75 turni, ma l'Asse resta sotto Hard: −1,20 VC
   (IC [−2,26; −0,14]). Gli Alleati Stratega: −1,00 (IC [−1,98; −0,02]). Una regola semplice (linea di base) fa come l'LLM
   (−0,40 rispetto all'LLM, include lo 0).
4. **Conclusione onesta**: oggi lo Stratega LLM peggiora Hard su entrambi i lati; la configurazione migliore misurata e' "senza tenute"
   (R0). Non ho cambiato i default (regola del prompt): **decide Matteo** se spegnere le tenute di default.

## Fase R0 — diagnosi
- Interruttori nuovi (solo Stratega, default = prima): `liste`, `tenute` (`StrategaInterruttori`, proprieta' `Pro+ stratega llm
  spento: <alleanza>`, autoplay `--stratega-spento`). Modalita' `[diagnosi]`, sezione "Diagnosi Stratega (R0)".
- Run 42915599025 (commit 9d73dd4): 50/50 completate, 0 eccezioni, 7,99 $. Cancello R0 superato.

| Configurazione (Asse) | VC R12 | Δ da Hard (IC 95%) | Reddito | Capitali cadute (10 partite) |
|---|---|---|---|---|
| a Hard | 5,70 ± 1,64 | - | 66,1 | 1 |
| b Stratega senza chiave | 5,50 ± 1,43 | −0,20 [−1,65; 1,25] | 62,0 | 3 |
| c Stratega LLM | 4,00 ± 1,63 | **−1,70 [−3,24; −0,16]** | 35,8 | 5 |
| d senza liste | 4,70 ± 1,83 | −1,00 [−2,63; 0,63] | 50,0 | 7 |
| e senza tenute | 5,50 ± 1,08 | −0,20 [−1,52; 1,12] | 55,6 | 3 |

d − c +0,70 [−0,93; 2,33] include lo 0; **e − c +1,50 [0,18; 2,82] esclude lo 0**. Con 10 semi si distinguono solo ~1,2 VC.
Regola "b distinguibile da a" NON scattata ⇒ nessuna correzione "Stratega senza chiave = Hard" in R1 (non serviva).

## Fase R1 — correzioni (tutte verificate nel codice prima di toccarle; nessun punto smentito)
Gruppo A: A1 piano di Hard ricalcolato (hook protetto in `AbstractProAi`, mai chiamato da Hard) + tappa 2 stimata con le unita'
dell'esecuzione; A2 insieme eseguibile unico; A3 impegno per tipo di bersaglio; A4 nemico per alleanza e guerra per ogni nazione;
A5 guardia fissata al lancio; A6 paracadute = pausa; A7 chiamata riprovata una volta; A8 tenute filtrate e trattenuta minima.
Gruppo B fatto: B5, B6, B7, B8, D6. Non fatto: D5 (tempo). Facoltativo fatto: linea di base a regole (`StrategaRegole`).
Dettagli in CLAUDE.md "Correzioni dello Stratega (step R1, gruppo A)".

Run 42921405264 (commit cebbd59): 40/40, 0 eccezioni, 4,90 $.

| Configurazione | VC R12 | Δ da Hard (IC 95%) |
|---|---|---|
| a Hard (Asse / Alleati) | 5,60 / 5,40 | - |
| c Asse Stratega LLM | 4,40 ± 0,97 | **−1,20 [−2,26; −0,14]** |
| f Alleati Stratega LLM | 4,40 ± 0,70 | **−1,00 [−1,98; −0,02]** |
| g Asse a regole | 4,00 ± 1,49 | −1,60 [−2,90; −0,30] |

Menu dei candidati vuoto: 30/120 round (c), 56/120 (f), 32/120 (g). In una partita locale di 4 round (seme 1101, Stratega simulato)
il menu era vuoto in tutti i round: offensive scartate per raccolta oltre l'orizzonte (18), "Hard lo prende da solo" (20), successo
< 30% (13, D6); tenute scartate da A8 "Hard lo tiene da solo".

## Scelte fatte da solo (da rivedere)
- **R2a-OMBRA non fatta**: il prompt la elenca ma non ne da' la specifica (il testo finisce con le run di R1). Inventare un
  pianificatore di campagna senza specifica violava "decidi seguendo questo prompt" e "non fare altro". Da specificare.
- Push rimandati finche' la run precedente non aveva pubblicato `autoplay-latest` (gli hook di sessione chiedevano il push prima).
- "Nessuna chiave" non viene riprovata ne' ripetuta nel log a ogni round (una riga per partita); errori e risposte non valide si'.
- Paracadute oltre `round_max` o sul bersaglio finale: fine normale (rinvio), non pausa.
- A2 vale per il round corrente della nazione di turno (le opzioni d'attacco sono le sue); per i round futuri resta il conteggio C1-ter.
- A8: "tenuta di Hard da solo" = difensori presenti senza rinforzi contro lo stesso contrattacco realistico; se nemmeno tutte le unita'
  arrivano alla soglia si trattengono tutte; il non combattimento continua a tenere il presidio intero (le unita' non attaccano li').
- A4: la guerra "possibile" = in guerra o dichiarabile ora per le regole della mappa (`MinacciaReale`), in entrambe le direzioni;
  le tappe si eseguono solo con la guerra reale (altrimenti rinvio).
- I "rinforzi prioritari" (pesi dell'ammassamento) non sono toccati dall'interruttore `tenute`.

## Prossimi passi proposti (non fatti)
1. Decisione di Matteo: tenute spente di default per lo Stratega? (R0: unica configurazione alla pari con Hard.)
2. Se si tengono: una [diagnosi] con R1 e `--stratega-spento Axis=tenute` per vedere se A8 basta (stessi semi).
3. Capire perche' le offensive non arrivano mai a esecuzione (5/5 annullate in c R1) prima di nuovi candidati.

Costo API della notte: 7,99 + 4,90 = **12,89 $** (tetto 45). Nessuna chiave in codice, log o file.

## Seguito: step R2a-OMBRA (2026-11-11)
Tenute spente di default (decisione di Matteo dopo R0/R1) + pianificatore di campagna in ombra. Run 42976494562 (f9b7eae), stessi semi:
Asse Stratega 5,70 contro Hard 6,10 (−0,40 [−1,55; 0,75]), contro R1 +1,30 [0,20; 2,40]; Alleati 4,90 contro 4,90 (0,00), contro R1
+0,50 [−0,49; 1,49]. Ombra: difensori futuri sovrastimati nel ~90% dei casi, perdite previste pessimiste (reale <= prevista 98-100% a
ogni margine), regola 2.6 caso a) affidabile. Dettagli in CLAUDE.md, step R2a-OMBRA.
