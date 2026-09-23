# Manga Downloader

**App Android** per cercare manga su più siti insieme, leggerli online o scaricarli come `.cbz`, con reader integrato, preferiti con notifiche dei nuovi capitoli, integrazione AniList e aggiornamento automatico. Vive in [android-app](./android-app).

## Cosa fa

- **Ricerca aggregata** su 7 fonti, filtrabili per lingua e disattivabili dalle impostazioni:
  - italiano: **MangaWorld**, **Hasta Team**
  - inglese: **Mangapill**, **Asura Scans**, **DemonicScans**, **TCB Scans**, **VyManga**
- **Lettura online o download**: dalla lista capitoli un tocco apre il capitolo in streaming, l'icona accanto lo scarica. I capitoli scaricati diventano file `.cbz` in `Android/data/com.lorenzo.mangadownloader/files/Download/MangaDownloader/<manga>/`.
- **Download in background** con `WorkManager` e notifica di avanzamento. I capitoli già salvati vengono saltati.
- **Reader** verticale o a pagine, con gestione delle pagine doppie (adatta, dividi, ruota) e dei capitoli molto lunghi.
- **Preferiti** con controllo periodico dei nuovi capitoli, notifiche e feed "Aggiornamenti". Se una fonte non risponde, l'app ripiega su un'altra fonte collegata alla stessa serie.
- **AniList**: tracciamento dei progressi e sincronizzazione dei preferiti nei due sensi.
- **Home** con "Riprendi", "Scopri" e consigliati; cronologia e statistiche di lettura.
- **Backup/ripristino**, parental control, schermata "Segnala un problema", aggiornamento automatico dalle GitHub Release (anche preview, se attivate).

Le novità di ogni versione sono in [CHANGELOG.md](CHANGELOG.md), che l'app mostra anche nella schermata "Novità".

## Build locale dell'APK

Serve l'**Android SDK** con la platform 37. La JDK 17 la usa Gradle: se non è installata la scarica da solo (vedi `gradle/gradle-daemon-jvm.properties`). In Android Studio basta aprire la cartella `android-app/`. Da riga di comando:

```bash
cd android-app
./gradlew assembleDebug
```

L'APK firmato con la chiave di debug finisce in `android-app/app/build/outputs/apk/debug/app-debug.apk`. Per installarlo:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Oppure trasferisci l'APK sul telefono e aprilo (abilitando "Installa da sorgenti sconosciute").

Test e verifica del build di release (la minificazione R8 è attiva solo in release):

```bash
cd android-app
./gradlew testDebugUnitTest assembleRelease
```

### Segnalazioni in-app (facoltativo)

"Segnala un problema" invia un'email via SMTP da un account dedicato. In locale le credenziali vanno in `android-app/local.properties` (non versionato): `smtpHost`, `smtpPort`, `smtpUser`, `smtpPassword`, `reportToEmail`. Se mancano, le segnalazioni restano disattivate e il build funziona lo stesso. Attenzione: user e password finiscono nell'APK e sono estraibili, per questo si usa un account usa-e-getta.

## Struttura del codice

Sorgenti in `android-app/app/src/main/java/com/lorenzo/mangadownloader/`, divisi per area:

| Package | Contenuto |
| --- | --- |
| *(radice)* | `MainActivity`, `MangaApplication`, `DownloadWorker`, `FavoriteUpdatesWorker` |
| `app/` | `MangaViewModel`, navigazione (`Screen`), effetti di sistema, messaggi d'errore |
| `data/` | modelli, rete, fonti (`sources/`), libreria su disco, AniList, persistenza (`store/`), backup, segnalazioni, aggiornamenti |
| `domain/` | logica pura senza Android né Compose: identità delle serie, progressi di lettura, blocchi della Home |
| `ui/` | tema, componenti condivisi e una cartella per schermata |

**Le quattro classi nella radice non vanno spostate né rinominate.** Android e WorkManager le identificano per nome completo della classe: spostarle romperebbe le icone già in Home, i controlli periodici dei preferiti (registrati con `ExistingPeriodicWorkPolicy.KEEP`) e i download in coda sui telefoni che hanno già l'app.

I test (`src/test/...`) stanno nello stesso package della classe che verificano; quelli che coprono più aree, come i test del `MangaViewModel`, stanno in `app/`.

## Build e release con GitHub Actions

| Workflow | Quando parte | Cosa fa |
| --- | --- | --- |
| `android-tests.yml` | pull request che toccano `android-app/` | test unitari |
| `android-preview.yml` | push su `dev` che toccano `android-app/` | test + APK firmato, pubblicato come pre-release `android-preview-v<versione>-preview.<n>` |
| `android.yml` | push su `main` che cambiano `android-app/version.properties` (o avvio manuale) | test + APK firmato, pubblicato nella release `android-v<versionName>` e come artifact `manga-downloader-release` |

Per pubblicare una versione stabile basta aggiornare `versionName` in `android-app/version.properties` su `main`. Il `versionCode` si calcola da solo come `major * 1_000_000 + minor * 1_000 + patch` (`1.15.3` → `1015003`). Il popup di aggiornamento dell'app mostra come note il messaggio del commit a cui punta il tag della release.

L'app controlla gli aggiornamenti sulle GitHub Release del repo e scarica l'asset `app-release.apk`. Le preview compaiono solo a chi le ha attivate nelle impostazioni.

Secret richiesti nel repository GitHub:

- `ANDROID_KEYSTORE_BASE64`: la keystore `.jks` codificata in Base64
- `ANDROID_KEYSTORE_PASSWORD`: password della keystore
- `ANDROID_KEY_ALIAS`: alias della chiave di release
- `ANDROID_KEY_PASSWORD`: password della chiave di release
- facoltativi, per le segnalazioni: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `REPORT_TO_EMAIL`

Genera `ANDROID_KEYSTORE_BASE64` senza a capo extra:

```bash
# macOS
base64 -i android-app/release-keystore.jks | tr -d '\n'

# Linux
base64 -w 0 android-app/release-keystore.jks
```

Se il secret è troncato o codificato male, il workflow fallisce in fase di firma con errori come `KeytoolException` o `EOFException`.
