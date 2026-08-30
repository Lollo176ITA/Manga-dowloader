# Changelog

<!-- markdownlint-disable MD024 -->

> Voci completate, distillate da [MIGLIORIE.md](MIGLIORIE.md) dall'agente `changelog-writer` (Sonnet). Raggruppate per giorno (dal più recente) e per tipo.

## 2026-08-30

### Migliorato

- La lista dei capitoli ora offre entrambe le azioni insieme: toccare la riga apre il capitolo, l'icona a destra lo scarica. Prima bisognava scegliere una volta per tutte fra le due cose con un interruttore nelle impostazioni, che di fatto teneva nascosta la lettura online a chi non andava a cercarlo.
- Toccare un capitolo già scaricato apre la copia in libreria invece di riscaricarne le pagine dalla rete.
- Leggendo in streaming, il capitolo successivo viene preparato mentre si finiscono le ultime pagine di quello corrente: premendo "avanti" si parte quasi subito, invece di aspettare a schermo vuoto che l'app scarichi l'elenco delle pagine e poi la prima immagine.

### Rimosso

- L'impostazione "Leggi senza scaricare" non serve più: la lettura online è sempre disponibile dalla lista dei capitoli.

## 2026-08-26

### Aggiunto

- La modalità delle pagine doppie (adatta allo schermo, dividi in due, ruota) si può cambiare anche per la singola serie dal menu del lettore, oltre che come impostazione generale.
- La data di uscita del capitolo compare ora nella lista dei capitoli, per le fonti che la forniscono.

### Migliorato

- I contenuti della Home (Scopri, Consigliati) si aggiornano una volta al giorno invece che a ogni apertura dell'app; restano comunque aggiornabili subito con "tira per aggiornare".

### Corretto

- Le pagine doppie ruotate seguono ora il verso di lettura scelto (manga o occidentale), invece di girare sempre nello stesso senso.

## 2026-08-25

### Aggiunto

- Pagine doppie nel lettore: le tavole affiancate possono restare intere e rimpicciolite, dividersi in due pagine separate o ruotare per leggerle girando il telefono (scelta in Impostazioni).
- Nuovo interruttore "Sincronizza preferiti" per AniList: unisce nei due sensi i preferiti dell'app e quelli dell'account AniList, senza mai rimuovere nulla.
- Quando la fonte di un preferito multi-fonte smette di rispondere, l'app prova automaticamente un'altra fonte agganciata alla stessa serie; un'icona sulla copertina segnala il cambio di fonte o l'irraggiungibilità, coi dettagli visibili aprendo le azioni del preferito.

### Corretto

- I preferiti letti da una fonte diversa da quella originale ora riconoscono correttamente la stella dei preferiti e lo stato di lettura, invece di sembrare "non preferiti" o "da iniziare" per errore.
- L'account AniList non si disconnette più da solo per un ritardo di rete o per la riapertura dell'app dopo molto tempo.
- Toccare due volte di seguito lo stesso titolo consigliato dalla Home non lascia più la ricerca bloccata sullo spinner di caricamento.
- La ricerca aggregata e il collegamento automatico ad AniList non raggruppano più per errore titoli diversi che condividono lo stesso sinonimo su AniList.

## 2026-07-28

### Corretto

- Su Mangapill i capitoli con lo stesso numero pubblicati da gruppi di scanlation diversi restano distinti (con l'indicazione del gruppo), invece di sovrascriversi a vicenda.
- Se tutte le fonti di una lingua vengono disattivate dalle impostazioni, la ricerca non le interroga più comunque: i filtri di lingua mostrano solo le lingue con almeno una fonte attiva.

## 2026-07-27

### Migliorato

- Il dialog di aggiornamento non si chiude più per un tocco accidentale fuori dalla finestra, specialmente durante l'installazione.

## 2026-07-25

### Aggiunto

- Nuova fonte: TCB Scans.

## 2026-07-20

### Aggiunto

- Nuove fonti: Asura Scans e Demonic Scans.

### Corretto

- I titoli di Mangapill nei risultati di ricerca non compaiono più raddoppiati (es. "One Piece One Piece").

## 2026-07-19

### Aggiunto

- I preferiti seguono ora la serie e non più una singola fonte: nel dettaglio di un manga si può scegliere tra le fonti che pubblicano la stessa serie, e la ricerca raggruppa in un'unica scheda i risultati della stessa serie provenienti da fonti diverse.

## 2026-07-12

### Corretto

- I capitoli webtoon con pagine "a striscia" molto alte (es. Solo Max-Level Newbie su MangaWorld, pagine da 18.000 pixel) ora si leggono normalmente: prima ogni striscia superava i limiti grafici di Android e restava per sempre "Pagina non caricata", anche riprovando. Ora la pagina viene spezzata in blocchi e mostrata intera.
- Le pagine "Pagina non caricata" ora si possono davvero recuperare: se una pagina di un capitolo streaming in cache è rotta o sparita dal disco, "Tocca per riprovare" la riscarica dal sito invece di rileggere all'infinito lo stesso file rotto.
- La cache dei capitoli streaming e delle pagine estratte dai capitoli scaricati ora scarta i file vuoti o troncati: alla riapertura il capitolo si riscarica (streaming) o si ri-estrae dal file in libreria (download), invece di mostrare per sempre pagine irrecuperabili.
- Un'estrazione interrotta a metà (per esempio app chiusa di colpo) non lascia più una cache parziale che alla riapertura passava per un capitolo completo, con pagine mancanti in silenzio.

## 2026-07-10

### Migliorato

- Restyling della Home in stile Material 3 Expressive: header con saluto e titolo grande "La tua lettura"; la card "Riprendi" diventa il pezzo forte della pagina, con colore d'accento, barra di progresso a onda e indicazione "pagina X di Y".
- Le novità dai preferiti ora scorrono in orizzontale come chip compatte (mini-copertina + capitolo + pallino "non letto") invece di una lista verticale che spingeva in basso il resto della Home.
- Preferiti recenti e Scopri mostrano le copertine come protagoniste: poster arrotondati senza cornice-card, col titolo sotto.
- La modalità modifica della Home è una vista dedicata: titolo "Modifica Home", istruzioni in testa e un elenco di card (icona + nome + descrizione del blocco) con frecce e occhio; i blocchi nascosti restano in lista, attenuati, per riattivarli al volo.
- Card di benvenuto "Per iniziare" e stato iniziale ridisegnati: icona in un cerchio d'accento e spiegazione di cosa apparirà nella Home mentre leggi.
## 2026-07-04

### Migliorato

- La ricerca ora si filtra per lingua: chip "Tutte · Italiano · English" al posto delle sigle dei server (MP, MW…), che non dicevano nulla a chi non li conosce. Scegliere una lingua cerca su tutte le fonti in quella lingua; il badge sulla copertina distingue le edizioni quando lo stesso titolo arriva da più fonti.
- Per chi conosce i server: nuova opzione "Mostra fonti singole" nelle Impostazioni (sezione Ricerca) che aggiunge le chip delle singole fonti, con i nomi completi spiegati nella descrizione.
- Il menu a 3 puntini in alto a destra è stato sostituito dall'icona Impostazioni diretta: la voce "Server" era ridondante con le chip della ricerca (e il dialog di selezione è stato rimosso).
- La lingua di ricerca predefinita ora è Italiano (prima il server predefinito era in inglese).
- Nella ricerca su più fonti i risultati ora si alternano (il primo di ogni fonte, poi i secondi, …) invece di comparire a blocchi: prima tutta una fonte e poi l'altra. Il miglior risultato di ogni fonte è subito in cima — utile coi titoli alternativi: cercando "demon slayer", MangaWorld e Mangapill lo trovano ma lo mostrano col titolo originale "Kimetsu no Yaiba", che prima finiva sepolto in fondo alla lista.

### Corretto

- Gli avvisi in basso con un pulsante ("Download aggiunto in coda", "Rimosso dai preferiti", errori con "Riprova"…) non restavano più a schermo per sempre: ora spariscono da soli dopo 8 secondi se non li tocchi.

### Rimosso

- Voce "Rivedi il tutorial" dalle Impostazioni: un tap accidentale fuori dal benvenuto già non brucia più il tour (si ripropone al riavvio), quindi la voce serviva solo a chi lo saltava di proposito.

## 2026-06-11

### Aggiunto

- Nuova voce "Segnala un problema" nelle Impostazioni: consente di inviare una segnalazione scegliendo il tipo (bug, richiesta di funzionalità o altro), descrivendo il problema, allegando immagini o un messaggio vocale registrato nell'app e indicando facoltativamente la propria email per essere ricontattati.
- Dopo un crash, il dialog mostra un pulsante per inviare la segnalazione direttamente.
- Integrazione AniList: puoi collegare il tuo account dalle impostazioni (accesso nel browser, nessuna password nell'app).
- Nel dettaglio di un manga puoi collegare la serie al titolo corrispondente su AniList tramite una ricerca con conferma.
- Dal dettaglio puoi modificare manualmente stato di lettura, capitoli letti e voto sulla tua lista AniList.
- A fine capitolo l'app aggiorna automaticamente il progresso su AniList; all'ultimo capitolo la serie viene segnata come completata. La sincronizzazione automatica si può disattivare dalle impostazioni.

### Migliorato

- Navigando tra le schermate (dettaglio manga, serie scaricata, ecc.) e tornando indietro, la posizione di scroll viene ripristinata dove si era lasciata.

## 2026-06-10

### Aggiunto

- Sezione "Informazioni" nelle impostazioni con la versione dell'app e una schermata "Novità" che mostra il changelog.
- Azione "Elimina capitoli letti" in Libreria e Gestione memoria (tiene il capitolo in corso e i non letti).
- Modalità di lettura "Manga": reader a pagine da destra a sinistra.

### Migliorato

- Lo stop dei download ora chiede conferma e indica quante serie ferma, invece di cancellare tutto a un tap cieco.
- La notifica di download si tocca per aprire l'app sulla Libreria, e a fine download arriva una notifica di completamento (o di errore).
- Il dialog di download mostra quanti capitoli scaricherà e quanti sono già presenti; la conferma diventa "Scarica N".
- Lo snackbar "Download in coda" ha l'azione "Libreria" per vedere subito progresso e coda.
- Righe della Gestione memoria tappabili (aprono la serie); label "letti · % dello spazio" più chiara.
- La lista di una serie scaricata atterra sul punto di lettura (auto-scroll), non più sul capitolo 1.

### Corretto

- I download falliti ora mostrano l'errore con un'azione "Riprova", invece di sparire in silenzio.

## 2026-06-09

### Migliorato

- Il permesso notifiche viene chiesto all'avvio di un download lungo, così il sistema non lo interrompe a metà.

### Corretto

- Il badge "nuovi capitoli" non risorge più dopo "segna come visto" (risolta la race tra worker e app).
- L'evento del feed viene salvato prima della notifica: niente doppie notifiche dopo un crash.

### Interno

- Aggiornamenti di stato uniformati su `updateState`.

## 2026-05-26

### Aggiunto

- Licenza PolyForm Noncommercial.

### Migliorato

- Retry con backoff sugli errori di rete (ricerca, dettaglio, download).

### Corretto

- Referer corretto per-immagine: niente più immagini rotte (403) leggendo da MangaWorld/HastaTeam.
- Un CBZ corrotto non lascia più un capitolo mostrato a metà (cache ripulita su errore).
- Spazio disco insufficiente gestito con un messaggio chiaro, senza retry infiniti.

### Sicurezza

- Verifica della firma dell'APK di auto-update prima dell'installazione (fail-closed).

### Interno

- Test eseguiti in CI su PR/`dev` e prima della release.
- README riallineato all'app Android come progetto principale.
- Parsing Mangapill estratto in funzioni pure testabili (+ test).
- Test sui rami d'errore di download e sul CrashReporter.
- Persistenza estratta dal `MangaViewModel` in store dedicati.
- Navigazione modellata con un `sealed Screen` + back centralizzato.
- Matching libreria estratto in `LibraryMatching` (puro, testato).
- Meno allocazioni in lettura (progresso reader applicato senza ricostruire la libreria).
- `LibraryRepository`/`MangaSourceRegistry` condivisi come singleton.
- Favoriti/recenti serializzati in modo tipizzato (kotlinx.serialization).
- Rimosso codice morto; `*.log` in `.gitignore`.
