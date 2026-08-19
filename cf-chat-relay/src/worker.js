// Chat relay Worker.
//
// This holds the REAL GitHub token as a Cloudflare secret (never visible to the
// app or anyone using it) and does the actual GitHub read/write on behalf of
// anyone who supplies the correct CHAT_ACCESS_KEY - a separate, much lower-stakes
// shared key that's safe to hand out, since it can only ever send/delete chat
// messages through this Worker, nothing else on the repo.
//
// Required secrets (set these in the Cloudflare dashboard -> Settings -> Variables
// -> Secrets - NEVER put real values in this file or commit them):
//   GITHUB_TOKEN     - a classic GitHub PAT with "repo" scope
//   CHAT_ACCESS_KEY  - any string you make up, shared with the other person

const REPO = "sayidbagdeli7-art/Sebvpn2";
const BRANCH = "chat-data";
const FILE_PATH = "chat/messages.json";
const MAX_MESSAGES = 200;

function encodeUtf8Base64(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function decodeUtf8Base64(b64) {
  return decodeURIComponent(escape(atob(b64.replace(/\n/g, ""))));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method !== "POST") {
      return json({ error: "method not allowed" }, 405);
    }
    if (url.pathname !== "/send" && url.pathname !== "/delete") {
      return json({ error: "not found" }, 404);
    }

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return json({ error: "invalid json" }, 400);
    }

    if (!body.key || body.key !== env.CHAT_ACCESS_KEY) {
      return json({ error: "unauthorized" }, 401);
    }
    if (!body.ciphertext) {
      return json({ error: "missing ciphertext" }, 400);
    }

    const apiUrl = `https://api.github.com/repos/${REPO}/contents/${FILE_PATH}`;
    const ghHeaders = {
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "Accept": "application/vnd.github+json",
      "User-Agent": "chat-relay-worker"
    };

    let currentArr = [];
    let sha = null;

    const getResp = await fetch(`${apiUrl}?ref=${BRANCH}`, { headers: ghHeaders });
    if (getResp.ok) {
      const data = await getResp.json();
      try {
        currentArr = JSON.parse(decodeUtf8Base64(data.content));
      } catch (e) {
        currentArr = [];
      }
      sha = data.sha;
    } else if (getResp.status !== 404) {
      return json({ error: "github read failed", status: getResp.status }, 502);
    }

    if (url.pathname === "/send") {
      currentArr.push({ c: body.ciphertext, t: Date.now() });
      if (currentArr.length > MAX_MESSAGES) {
        currentArr = currentArr.slice(currentArr.length - MAX_MESSAGES);
      }
    } else {
      currentArr = currentArr.filter((m) => m.c !== body.ciphertext);
    }

    const putBody = {
      message: url.pathname === "/send" ? "chat message via relay" : "delete chat message via relay",
      content: encodeUtf8Base64(JSON.stringify(currentArr, null, 2)),
      branch: BRANCH
    };
    if (sha) putBody.sha = sha;

    const putResp = await fetch(apiUrl, {
      method: "PUT",
      headers: { ...ghHeaders, "Content-Type": "application/json" },
      body: JSON.stringify(putBody)
    });

    if (!putResp.ok) {
      return json({ error: "github write failed", status: putResp.status }, 502);
    }

    // Best-effort jsDelivr purge so readers see the change quickly.
    try {
      await fetch(`https://purge.jsdelivr.net/gh/${REPO}@${BRANCH}/${FILE_PATH}`);
    } catch (e) { /* ignore */ }

    return json({ ok: true });
  }
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
