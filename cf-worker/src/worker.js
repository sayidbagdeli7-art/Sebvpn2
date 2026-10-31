// Cloudflare Worker - simple web proxy
// Paste this whole file into the Cloudflare Workers editor and deploy.
//
// Usage: open https://<your-worker>.workers.dev/  and type a URL in the box,
// or go directly to https://<your-worker>.workers.dev/?url=https://example.com

export default {
  async fetch(request) {
    const requestUrl = new URL(request.url);
    const targetParam = requestUrl.searchParams.get('url');

    if (!targetParam) {
      return new Response(renderHomePage(), {
        headers: { 'content-type': 'text/html; charset=utf-8' }
      });
    }

    let targetUrl;
    try {
      targetUrl = new URL(targetParam);
    } catch (e) {
      return new Response('آدرس نامعتبر', { status: 400 });
    }

    const proxyReq = new Request(targetUrl.toString(), {
      method: request.method,
      headers: stripHeaders(request.headers),
      body: (request.method === 'GET' || request.method === 'HEAD') ? undefined : request.body,
      redirect: 'follow'
    });

    let resp;
    try {
      resp = await fetch(proxyReq);
    } catch (e) {
      return new Response('خطا در دریافتِ سایتِ مقصد: ' + e.message, { status: 502 });
    }

    const contentType = resp.headers.get('content-type') || '';
    const newHeaders = new Headers(resp.headers);
    newHeaders.delete('content-security-policy');
    newHeaders.delete('x-frame-options');
    newHeaders.set('access-control-allow-origin', '*');

    if (contentType.includes('text/html')) {
      let body = await resp.text();
      body = rewriteHtml(body, targetUrl, requestUrl);
      return new Response(body, { status: resp.status, headers: newHeaders });
    }

    return new Response(resp.body, { status: resp.status, headers: newHeaders });
  }
};

function stripHeaders(headers) {
  const h = new Headers(headers);
  h.delete('cookie'); // kept stateless/simple on purpose
  return h;
}

function toProxied(absoluteUrl, workerOrigin) {
  return workerOrigin + '/?url=' + encodeURIComponent(absoluteUrl);
}

function rewriteHtml(html, targetUrl, requestUrl) {
  const workerOrigin = requestUrl.origin;

  function resolve(link) {
    try {
      return new URL(link, targetUrl).toString();
    } catch (e) {
      return null;
    }
  }

  return html.replace(/(href|src|action)=["']([^"']+)["']/gi, (match, attr, link) => {
    if (link.startsWith('data:') || link.startsWith('javascript:') || link.startsWith('#')) return match;
    const abs = resolve(link);
    if (!abs) return match;
    return `${attr}="${toProxied(abs, workerOrigin)}"`;
  });
}

function renderHomePage() {
  return `<!doctype html><html><head><meta charset="utf-8">
  <title>پروکسی وب</title></head>
  <body style="font-family:sans-serif;padding:20px;direction:rtl">
  <h2>پروکسیِ وب</h2>
  <form onsubmit="location.href='/?url='+encodeURIComponent(document.getElementById('u').value);return false;">
    <input id="u" type="text" placeholder="https://example.com" style="width:80%;padding:8px;font-size:16px" />
    <button type="submit" style="padding:8px 16px;font-size:16px">برو</button>
  </form>
  </body></html>`;
}
