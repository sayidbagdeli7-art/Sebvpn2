addEventListener("fetch", event => {
  event.respondWith(handle(event.request));
});

async function handle(request) {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/video\/([\w-]{6,20})\.json$/);
  if (!match) {
    return new Response("Usage: /video/VIDEO_ID.json", { status: 200 });
  }
  const vid = match[1];
  try {
    const info = await getVideoInfo(vid);
    return new Response(JSON.stringify(info, null, 2), {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "access-control-allow-origin": "*"
      }
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: e.message || String(e) }), {
      status: 200,
      headers: { "content-type": "application/json; charset=utf-8" }
    });
  }
}

async function getVideoInfo(vid) {
  const body = {
    videoId: vid,
    context: { client: { clientName: "ANDROID", clientVersion: "16.13.35" } }
  };
  const res = await fetch(
    "https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    }
  );
  const rawText = await res.text();
  let data;
  try {
    data = JSON.parse(rawText);
  } catch (e) {
    throw new Error("non-JSON response, HTTP " + res.status + ": " + rawText.slice(0, 300));
  }

  const status = data.playabilityStatus && data.playabilityStatus.status;
  if (status !== "OK") {
    // DEBUG: include the raw response so we can see exactly what YouTube sent back
    // instead of just "unknown" - remove this once it's working.
    throw new Error(
      "playability not OK. HTTP " + res.status + ". Raw (first 500 chars): " + rawText.slice(0, 500)
    );
  }

  const details = data.videoDetails || {};
  const streamingData = data.streamingData || {};
  const formats = (streamingData.formats || []).concat(streamingData.adaptiveFormats || []);

  // Only formats with a direct "url" are included - some formats instead come with
  // a "signatureCipher" that needs YouTube's obfuscated per-page JS to decode, which
  // is what made the original script huge and fragile. Skipping those keeps this
  // small; it just means a few of the highest-quality formats may be missing.
  const streams = formats
    .filter(f => f.url)
    .map(f => ({
      itag: f.itag,
      quality: f.qualityLabel || f.quality,
      type: f.mimeType,
      url: f.url
    }));

  return {
    id: details.videoId,
    title: details.title,
    author: details.author,
    duration: details.lengthSeconds,
    streamCount: streams.length,
    streams
  };
}
