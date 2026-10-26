# report-relay

Cloudflare Worker che riceve le segnalazioni di MangApp (form "Segnala un problema" e crash) e le
trasforma in card sulla board Trello. Sostituisce l'invio via SMTP: le credenziali Trello stanno
solo nel Worker, l'APK conosce solo l'URL.

In produzione: `https://mangapp-report-relay.lollo176ita.workers.dev/report` (account Cloudflare
lollo176ita@gmail.com, sottodominio `lollo176ita`; `mangapp.workers.dev` è di qualcun altro).

## Contratto

`POST /report`, `multipart/form-data`:

| Campo | Obbligatorio | Note |
|---|---|---|
| `title` | sì | titolo della card, tagliato a 200 caratteri |
| `body` | sì | descrizione; oltre 16000 caratteri il testo completo va in `segnalazione.txt` |
| `label` | sì | `bug`, `feature`, `altro` o `crash` |
| `attachment` | no | ripetibile, max 6 file da 8 MB, 20 MB in totale; jpeg/png/webp/gif/heic/heif/avif o audio m4a |

Risposte: `201` inviata, `400`/`413`/`415` richiesta non valida, `429` troppe richieste
(3 al minuto per IP), `502` Trello ha rifiutato. Il corpo è sempre `{ ok, message }`.

## Primo deploy

Servono Node 18+ e `npx` (Wrangler non va installato).

1. **Chiave e token Trello**
   - Su <https://trello.com/apps/admin> crea una Power-Up (basta un nome, l'URL iframe può
     restare vuoto), apri la scheda **Trello Auth** e genera una nuova API key.
   - Accanto alla chiave clicca **Token** e premi **Consenti**: copia il token. Concede lettura e
     scrittura su tutte le board del tuo account, quindi va trattato come una password.
2. **Id della lista** in cui creare le card: con la chiave e il token,
   `https://api.trello.com/1/boards/BASFSpoO/lists?key=CHIAVE&token=TOKEN`. Copia l'`id` della
   lista e mettilo in `TRELLO_LIST_ID` in `wrangler.jsonc`.
3. **Etichette (facoltativo)**: da `https://api.trello.com/1/boards/BASFSpoO/labels?key=CHIAVE&token=TOKEN`
   copia gli id in `TRELLO_LABELS`, ad esempio `"{\"bug\":\"…\",\"feature\":\"…\",\"crash\":\"…\"}"`.
4. **Login e secret** (dalla cartella `report-relay/`):
   ```
   npx wrangler@4 login
   npx wrangler@4 secret put TRELLO_KEY
   npx wrangler@4 secret put TRELLO_TOKEN
   ```
   Se il Worker non esiste ancora, `secret put` chiede di crearlo: rispondi sì.
5. **Deploy**: `npx wrangler@4 deploy`. Alla fine stampa l'URL,
   `https://mangapp-report-relay.<sottodominio>.workers.dev`. Se l'account non ha ancora un
   sottodominio `workers.dev`, Wrangler chiede di sceglierne uno.
6. **Prova**:
   ```
   curl -F "title=[Prova] relay" -F "body=funziona" -F "label=altro" https://…workers.dev/report
   ```
   Deve rispondere `201` e la card deve comparire in cima alla lista.

Il limite di richieste (`ratelimits` in `wrangler.jsonc`) si configura solo da qui: la dashboard
Cloudflare non lo mostra.

## Prova in locale

Crea `.dev.vars` (escluso da git) con `TRELLO_KEY=…` e `TRELLO_TOKEN=…`, poi
`npx wrangler@4 dev` e manda le richieste a `http://127.0.0.1:8787/report`.
