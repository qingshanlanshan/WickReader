// wick.qshlab.com: static files from public/, plus POST /api/feedback, which emails the message to
// the author through Cloudflare Email Routing (the send_email binding). Nothing is stored.
import { EmailMessage } from "cloudflare:email";

const MAX_MESSAGE = 4000;
/** Apps that send feedback here, by the `product` field (WickReader when missing). */
const PRODUCTS = { wickreader: "WickReader", heqie: "何切道场 Heqie" };
const MAX_PER_HOUR = 6;

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "access-control-allow-origin": "*" },
  });

/** UTF-8 → base64, for the email body and subject. */
function b64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

function mime({ fromName, from, to, replyTo, subject, text }) {
  const body = b64(text).replace(/.{76}/g, "$&\r\n");
  return [
    `From: =?UTF-8?B?${b64(fromName)}?= <${from}>`,
    `To: <${to}>`,
    replyTo ? `Reply-To: <${replyTo}>` : null,
    `Subject: =?UTF-8?B?${b64(subject)}?=`,
    `Date: ${new Date().toUTCString()}`,
    `Message-ID: <${crypto.randomUUID()}@qshlab.com>`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: base64",
    "",
    body,
  ].filter((l) => l !== null).join("\r\n");
}

/** A few messages per hour per address, kept in the edge cache (best effort). */
async function allowed(ip) {
  const cache = caches.default;
  const key = new Request(`https://ratelimit.invalid/feedback/${encodeURIComponent(ip)}`);
  const hit = await cache.match(key);
  const count = hit ? parseInt(await hit.text(), 10) || 0 : 0;
  if (count >= MAX_PER_HOUR) return false;
  await cache.put(key, new Response(String(count + 1), { headers: { "cache-control": "max-age=3600" } }));
  return true;
}

async function feedback(request, env) {
  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "POST",
        "access-control-allow-headers": "content-type",
      },
    });
  }
  if (request.method !== "POST") return json({ error: "method" }, 405);

  let data;
  try {
    data = await request.json();
  } catch {
    return json({ error: "format" }, 400);
  }
  // Honeypot field that people never see; bots fill it in.
  if (data.website) return json({ ok: true });

  const message = String(data.message ?? "").trim();
  if (message.length < 2 || message.length > MAX_MESSAGE) return json({ error: "length" }, 400);
  const contact = String(data.contact ?? "").trim().slice(0, 200);
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (!(await allowed(ip))) return json({ error: "rate" }, 429);

  const info = [
    ["Contact", contact || "(none)"],
    ["App", data.app],
    ["Android", data.android],
    ["Device", data.device],
    ["Language", data.language],
    ["From", data.source || "app"],
    ["Country", request.cf?.country],
  ].filter(([, v]) => v).map(([k, v]) => `${k}: ${String(v).slice(0, 200)}`);

  const product = PRODUCTS[data.product] || PRODUCTS.wickreader;
  const replyTo = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact) ? contact : null;
  const firstLine = message.split("\n")[0].slice(0, 60);
  const raw = mime({
    fromName: product,
    from: env.FEEDBACK_FROM,
    to: env.FEEDBACK_TO,
    replyTo,
    subject: `[${product}] ${firstLine}`,
    text: `${message}\n\n———\n${info.join("\n")}\n`,
  });
  try {
    await env.FEEDBACK.send(new EmailMessage(env.FEEDBACK_FROM, env.FEEDBACK_TO, raw));
  } catch (e) {
    return json({ error: "send" }, 502);
  }
  return json({ ok: true });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/api/feedback") return feedback(request, env);
    return env.ASSETS.fetch(request);
  },
};
