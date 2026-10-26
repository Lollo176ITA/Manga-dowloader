// Relay delle segnalazioni di MangApp verso Trello. L'app manda un POST multipart a /report e il
// Worker crea la card con l'API REST di Trello: key e token stanno solo qui (secret del Worker),
// mai nell'APK. Chi conosce l'URL può solo creare card, con limiti di frequenza e dimensione.

const MAX_TITLE_CHARS = 200;
// Trello accetta descrizioni fino a 16384 caratteri: oltre, il testo completo va in allegato.
const MAX_DESC_CHARS = 16000;
const MAX_FILES = 6;
const MAX_FILE_BYTES = 8 * 1024 * 1024;
const MAX_TOTAL_BYTES = 20 * 1024 * 1024;
const LABELS = new Set(["bug", "feature", "altro", "crash"]);
// Il selettore foto di Android può restituire anche GIF, HEIC/HEIF e AVIF: rifiutarli farebbe
// perdere l'intera segnalazione per un solo allegato.
const ALLOWED_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
  "image/heic",
  "image/heif",
  "image/avif",
  "audio/mp4",
  "audio/m4a",
  "audio/aac",
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/report") return reply(404, "Not found");
    if (request.method !== "POST") return reply(405, "Usa POST");

    const ip = request.headers.get("CF-Connecting-IP") ?? "sconosciuto";
    const { success } = await env.REPORT_LIMITER.limit({ key: ip });
    if (!success) return reply(429, "Troppe segnalazioni, riprova tra un minuto");

    const declaredLength = Number(request.headers.get("Content-Length") ?? 0);
    if (declaredLength > MAX_TOTAL_BYTES) return reply(413, "Segnalazione troppo grande");

    let form;
    try {
      form = await request.formData();
    } catch {
      return reply(400, "Richiesta non valida");
    }

    const title = text(form, "title").slice(0, MAX_TITLE_CHARS);
    const body = text(form, "body");
    const label = text(form, "label");
    if (!title || !body || !LABELS.has(label)) return reply(400, "Campi mancanti o non validi");

    const files = form.getAll("attachment").filter((value) => typeof value !== "string");
    if (files.length > MAX_FILES) return reply(400, "Troppi allegati");
    let totalBytes = 0;
    for (const file of files) {
      if (!ALLOWED_TYPES.has(file.type)) return reply(415, `Tipo di allegato non ammesso: ${file.type}`);
      if (file.size > MAX_FILE_BYTES) return reply(413, "Allegato troppo grande");
      totalBytes += file.size;
    }
    if (totalBytes > MAX_TOTAL_BYTES) return reply(413, "Segnalazione troppo grande");

    const descriptionFits = body.length <= MAX_DESC_CHARS;
    const card = await trello(env, "/1/cards", {
      idList: env.TRELLO_LIST_ID,
      name: title,
      desc: descriptionFits ? body : `${body.slice(0, MAX_DESC_CHARS)}\n\n[…testo completo in allegato]`,
      pos: "top",
      idLabels: labelId(env, label) ?? "",
    });
    if (!card.ok) return reply(502, "Trello non ha accettato la segnalazione");
    const { id } = await card.json();

    // La card esiste già: un allegato che fallisce non deve far ripetere l'invio all'app,
    // altrimenti si creano card doppie. Si segnala solo nel conteggio.
    const attachments = descriptionFits
      ? files
      : [new File([body], "segnalazione.txt", { type: "text/plain" }), ...files];
    let failedAttachments = 0;
    for (const file of attachments) {
      const upload = new FormData();
      upload.append("file", file, file.name || "allegato");
      upload.append("name", file.name || "allegato");
      const response = await trello(env, `/1/cards/${id}/attachments`, upload);
      if (!response.ok) failedAttachments++;
    }

    return reply(201, "Segnalazione inviata", { failedAttachments });
  },
};

function text(form, name) {
  const value = form.get(name);
  return typeof value === "string" ? value.trim() : "";
}

// Mappa facoltativa etichetta → id Trello, come JSON nella variabile TRELLO_LABELS.
function labelId(env, label) {
  try {
    return JSON.parse(env.TRELLO_LABELS || "{}")[label];
  } catch {
    return undefined;
  }
}

// Key e token vanno nell'header Authorization e non nella query string, così non finiscono
// nei log degli URL.
function trello(env, path, payload) {
  const isMultipart = payload instanceof FormData;
  return fetch(`https://api.trello.com${path}`, {
    method: "POST",
    headers: {
      Authorization: `OAuth oauth_consumer_key="${env.TRELLO_KEY}", oauth_token="${env.TRELLO_TOKEN}"`,
      ...(isMultipart ? {} : { "Content-Type": "application/json" }),
    },
    body: isMultipart ? payload : JSON.stringify(payload),
  });
}

function reply(status, message, extra = {}) {
  return Response.json({ ok: status < 300, message, ...extra }, { status });
}
