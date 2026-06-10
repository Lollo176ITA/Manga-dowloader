# Manga Downloader

**App Android** per cercare, leggere e scaricare manga offline come `.cbz`, con reader integrato, download in background (`WorkManager`) e auto-aggiornamento. Vive in [android-app](./android-app).

## App Android

L'app Android si trova in `android-app/` e offre un flusso completamente nativo:

1. apri l'app, digita il nome del manga nella barra di ricerca
2. tocca uno dei risultati per vedere copertina, titolo e lista capitoli
3. tocca il capitolo da cui vuoi partire e conferma: il download continua in background

Dettagli:

- fonti supportate: **Mangapill**, **MangaWorld**, **HastaTeam**
- ricerca, browsing e download interamente dentro l'app (nessun URL da copiare)
- output: un file `.cbz` per capitolo in `Android/data/com.lorenzo.mangadownloader/files/Download/MangaDownloader/<manga>/`
- resume: i capitoli gi&agrave; salvati vengono saltati al riavvio
- background: `WorkManager` + notifica foreground

### Build locale dell'APK

Serve una **JDK 17 o 21** e l'**Android SDK** (platform 35, build-tools 35). In Android Studio Ã¨ sufficiente aprire la cartella `android-app/` e far fare all'IDE il resto. Da riga di comando:

```bash
cd android-app
./gradlew assembleDebug
```

L'APK firmato con la chiave di debug di Android finisce in `android-app/app/build/outputs/apk/debug/app-debug.apk`. Per installarlo sul telefono:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Oppure trasferisci l'APK sul telefono e aprilo (ricordati di abilitare "Installa da sorgenti sconosciute").

### Build automatica via GitHub Actions

Il repo include `.github/workflows/android.yml`: ad ogni push su `main` o sui branch `claude/**` GitHub costruisce l'APK release firmato e lo carica come artifact chiamato `manga-downloader-release`.

La versione Android ora vive in `android-app/version.properties`. Per pubblicare una nuova release e renderla visibile all'auto-update dell'app, aggiorna almeno:

- `versionName`
- `releaseNotes` se vuoi mostrare le novità nel popup di aggiornamento dell'app

Il `versionCode` viene calcolato automaticamente da `versionName` con schema `major * 1_000_000 + minor * 1_000 + patch`, quindi per `1.7.1` diventa `1007001`.

Il workflow GitHub genera automaticamente il tag release come `android-v<versionName>`.

Secrets richiesti nel repository GitHub:

- `ANDROID_KEYSTORE_BASE64`: contenuto della keystore `.jks` codificato in Base64
- `ANDROID_KEYSTORE_PASSWORD`: password della keystore
- `ANDROID_KEY_ALIAS`: alias della chiave di release
- `ANDROID_KEY_PASSWORD`: password della chiave di release

Genera `ANDROID_KEYSTORE_BASE64` senza newline extra. Esempi:

```bash
# macOS
base64 -i android-app/release-keystore.jks | tr -d '\n'

# Linux
base64 -w 0 android-app/release-keystore.jks
```

Se il secret contiene un valore troncato o codificato male, il workflow fallisce in fase di firma con errori come `KeytoolException` o `EOFException`.

Per scaricarlo:

1. apri la tab **Actions** del repo su GitHub
2. seleziona l'ultimo run "Android release APK"
3. scarica l'artifact `manga-downloader-release` (contiene `app-release.apk`)

Su `main`, lo stesso workflow crea o aggiorna anche una GitHub Release con l'asset `app-release.apk`: l'app Android usa quel file per controllare e installare gli aggiornamenti.
