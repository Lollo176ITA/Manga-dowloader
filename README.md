# Manga Downloader

Scarica un capitolo da **mangaworld.mx** e lo salva in un unico **PDF** o in una singola immagine **JPG**.

## Installazione

```bash
pip install -r requirements.txt
```

## Uso

```bash
# PDF (default), nome dedotto dall'URL
python manga_downloader.py "https://www.mangaworld.mx/manga/2726/20th-century-boys/read/62633081bc201e40421ea7b7/1?style=list"

# JPG lungo (tutte le pagine impilate in verticale)
python manga_downloader.py "https://www.mangaworld.mx/manga/2726/20th-century-boys/read/62633081bc201e40421ea7b7/1" -f jpg

# Percorso di output personalizzato
python manga_downloader.py "<URL>" -o 20th_century_boys_vol01.pdf
```

Lo script forza automaticamente `?style=list` nell'URL per ottenere tutte le pagine del capitolo in una sola richiesta HTML, poi scarica ogni immagine (`img.page-image`) e le unisce nel formato scelto.

## Note

- `-f pdf` &rarr; un PDF multipagina (una pagina manga per pagina PDF).
- `-f jpg` &rarr; una sola immagine JPEG verticale con tutte le pagine impilate (tutte portate alla stessa larghezza della pagina pi&ugrave; larga).
