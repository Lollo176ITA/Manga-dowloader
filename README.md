# Manga Downloader

Scarica un capitolo da **mangaworld.mx** o **mangapill.com** e lo salva in un unico **PDF** o in una singola immagine **JPG**.

Il repo contiene anche un MVP Android in [android-app](./android-app) con una singola schermata: incolli il primo URL capitolo di Mangapill, avvii il download e il job continua in background tramite `WorkManager`.

## Installazione

```bash
pip install -r requirements.txt
```

## App Android

L'app Android si trova in `android-app/` ed &egrave; pensata per questo flusso:

1. incolli il primo chapter URL, per esempio `https://mangapill.com/chapters/11-10001000/20-seiki-shounen-chapter-1`
2. premi `Avvia download`
3. l'app ricava la pagina manga, trova tutti i capitoli dal primo in poi e li scarica in background

Dettagli del MVP Android:

- supporto iniziale: solo **Mangapill**
- output: un file `.cbz` per capitolo
- resume: i capitoli gi&agrave; salvati vengono saltati al riavvio
- background: `WorkManager` + notifica foreground

Per importarla in Android Studio:

```bash
cd android-app
./gradlew wrapper
```

Note build Android:

- serve una **JDK 17 o 21** per Gradle/Android Studio
- nel mio ambiente era disponibile solo Java `25.0.2`, quindi non ho potuto completare una build Android locale
- il progetto Gradle e il wrapper sono comunque gi&agrave; presenti nel repo

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
