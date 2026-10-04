# Manga Downloader

**App Android** per cercare manga su più siti insieme, leggerli online o scaricarli come `.cbz`, con reader integrato, preferiti con notifiche dei nuovi capitoli, integrazione AniList e aggiornamento automatico. Vive in [android-app](./android-app).

## Cosa fa

- **Ricerca aggregata** su 7 fonti, filtrabili per lingua e disattivabili dalle impostazioni:
  - italiano: **MangaWorld**, **Hasta Team**
  - inglese: **Mangapill**, **Asura Scans**, **DemonicScans**, **TCB Scans**, **VyManga**
- **Lettura online o download**: dalla lista capitoli un tocco apre il capitolo in streaming, l'icona accanto lo scarica. I capitoli scaricati diventano file `.cbz` in `Android/data/com.lorenzo.mangadownloader/files/Download/MangaDownloader/<manga>/`.
- **Download in background** con `WorkManager` e notifica di avanzamento. I capitoli già salvati vengono saltati.
- **Reader** verticale o a pagine, con gestione delle pagine doppie (adatta, dividi, ruota) e dei capitoli molto lunghi.
- **Preferiti** con controllo periodico dei nuovi capitoli, notifiche e feed "Aggiornamenti", filtri per stato di lettura e scaffali con nomi scelti dall'utente. Se una fonte non risponde, l'app ripiega su un'altra fonte collegata alla stessa serie.
- **AniList**: tracciamento dei progressi e sincronizzazione dei preferiti nei due sensi; i preferiti AniList che nessuna fonte ha restano visibili nel gruppo "Senza scan".
- **Home** con "Riprendi", "Scopri" e consigliati; cronologia e statistiche di lettura; widget 4×1 per la schermata Home del telefono con l'ultima lettura, da aggiungere anche dalle impostazioni.
- **Controllo genitori**: PIN per la ricerca (con blocco dopo troppi tentativi), filtro dei manga per adulti, vetrine AniList nascoste. Il filtro per adulti si può usare anche da solo.
- **Backup/ripristino**, schermata "Segnala un problema", aggiornamento automatico dalle GitHub Release (anche preview, se attivate).

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
./gradlew testDebugUnitTest :shared:testAndroidHostTest assembleRelease
```

### Segnalazioni in-app (facoltativo)

"Segnala un problema" invia un'email via SMTP da un account dedicato. In locale le credenziali vanno in `android-app/local.properties` (non versionato): `smtpHost`, `smtpPort`, `smtpUser`, `smtpPassword`, `reportToEmail`. Se mancano, le segnalazioni restano disattivate e il build funziona lo stesso. Attenzione: user e password finiscono nell'APK e sono estraibili, per questo si usa un account usa-e-getta.

## Struttura del codice

Il progetto Gradle in `android-app/` ha tre moduli:

| Modulo | Contenuto |
| --- | --- |
| `shared/` | modulo **Kotlin Multiplatform** (Android + iOS) con quasi tutto il codice: modelli, fonti, rete (Ktor), libreria su disco (okio), preferenze, AniList, `MangaViewModel` e l'intera UI Compose (`App()` è la radice). Sorgenti in `shared/src/commonMain/kotlin/com/lorenzo/mangadownloader/`; le implementazioni di piattaforma in `androidMain/` e `iosMain/` |
| `app/` | l'app Android vera e propria, sottile: `MainActivity` (ospita `App()`), `MangaApplication`, i worker di WorkManager, widget, segnalazioni via email, aggiornamento dell'APK e il container dei servizi Android (`app/AndroidAppContainer.kt`) |
| `benchmark/` | Macrobenchmark delle prestazioni UI |

Dentro `shared/src/commonMain/` il codice è diviso per area:

| Package | Contenuto |
| --- | --- |
| `app/` | `MangaViewModel`, `AppContainer` (ciò che la piattaforma fornisce), navigazione (`Screen`), messaggi d'errore |
| `data/` | modelli, rete, fonti (`sources/`), libreria su disco, download (`download/`), AniList, persistenza (`store/`), backup |
| `domain/` | logica pura: identità delle serie, progressi di lettura, blocchi della Home, controllo dei nuovi capitoli |
| `ui/` | tema, componenti condivisi e una cartella per schermata; `PlatformHost` per ciò che tocca il sistema operativo |
| `platform/` | piccole primitive per piattaforma (`expect`/`actual`): file system, immagini, numeri casuali sicuri |

**Le classi nella radice di `app/src/main/java/com/lorenzo/mangadownloader/` non vanno spostate né rinominate.** Android e WorkManager le identificano per nome completo della classe: spostarle romperebbe le icone e i widget già in Home, i controlli periodici dei preferiti (registrati con `ExistingPeriodicWorkPolicy.KEEP`) e i download in coda sui telefoni che hanno già l'app.

I test stanno nello stesso package della classe che verificano: la logica comune in `shared/src/commonTest/` (`kotlin.test`, girano anche su iOS), i confronti con le API della JVM in `shared/src/androidHostTest/`, e in `app/src/test/` i test Robolectric (ViewModel col container Android, WorkManager, widget) e Compose UI.

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
