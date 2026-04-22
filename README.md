# Manga Downloader

Scarica un capitolo da **mangaworld.mx** o **mangapill.com** e lo salva in un unico **PDF** o in una singola immagine **JPG**.

Il repo contiene anche un'app Android in [android-app](./android-app): cerchi il manga per nome direttamente dentro l'app, scegli il capitolo da cui partire e il download continua in background tramite `WorkManager`.

## Installazione

```bash
pip install -r requirements.txt
```

## App Android

L'app Android si trova in `android-app/` e offre un flusso completamente nativo:

1. apri l'app, digita il nome del manga nella barra di ricerca
2. tocca uno dei risultati per vedere copertina, titolo e lista capitoli
3. tocca il capitolo da cui vuoi partire e conferma: il download continua in background

Dettagli:

- supporto: solo **Mangapill**
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

- `versionCode`
- `versionName`

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

## Uso

```bash
# PDF (default), nome dedotto dall'URL
python manga_downloader.py "https://www.mangaworld.mx/manga/2726/20th-century-boys/read/62633081bc201e40421ea7b7/1?style=list"

# JPG lungo (tutte le pagine impilate in verticale)
python manga_downloader.py "https://www.mangaworld.mx/manga/2726/20th-century-boys/read/62633081bc201e40421ea7b7/1" -f jpg

# Mangapill
python manga_downloader.py "https://mangapill.com/chapters/11-10121000/20-seiki-shounen-chapter-121"

# Range di capitoli Mangapill in una cartella
python manga_downloader.py "https://mangapill.com/manga/11" --range 121-249

# Range in parallelo con 4 job
python manga_downloader.py "https://mangapill.com/manga/11" --range 121-249 --jobs 4 -o 20_seiki_shounen_121_249

# Percorso di output personalizzato
python manga_downloader.py "<URL>" -o 20th_century_boys_vol01.pdf
```

Per MangaWorld lo script forza automaticamente `?style=list` nell'URL per ottenere tutte le pagine del capitolo in una sola richiesta HTML. Per Mangapill legge invece le immagini presenti nei blocchi `chapter-page` (`img.js-page` con `data-src` o `src`). In entrambi i casi scarica tutte le pagine e le unisce nel formato scelto.

Se usi `--range START-END` con un URL Mangapill del manga o di un capitolo, lo script non prova a costruire gli URL dei capitoli: apre la pagina del manga, legge i link reali disponibili e scarica solo quelli nel range richiesto, salvando ogni capitolo in un file separato dentro una directory.

Con `--jobs N` puoi scaricare pi&ugrave; capitoli in parallelo. I file vengono scritti prima come `*.part` e rinominati solo a download concluso: se interrompi lo script, i capitoli gi&agrave; completati restano validi e al riavvio vengono saltati automaticamente, evitando duplicati.

## Note

- `-f pdf` &rarr; un PDF multipagina (una pagina manga per pagina PDF).
- `-f jpg` &rarr; una sola immagine JPEG verticale con tutte le pagine impilate (tutte portate alla stessa larghezza della pagina pi&ugrave; larga).
- `--range 121-249` &rarr; modalità batch per Mangapill: crea una cartella e salva un file per ogni capitolo del range.
- `--jobs 4` &rarr; scarica fino a 4 capitoli contemporaneamente nella modalità batch.
