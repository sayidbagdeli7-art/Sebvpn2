#!/usr/bin/env python3
"""
Fetches the latest messages from public Telegram channels using their
public web preview (https://t.me/s/<channel>) - no bot token or API key
needed since it's just the same public HTML page anyone can view in a
browser. Writes one JSON file per channel into news/<channel_lower>.json.

Each JSON file is an array of objects, newest first:
  { "id": 348463, "text": "...", "time": "12:21", "date": "2026-07-19T12:21:00+00:00", "link": "https://t.me/IranintlTV/348463" }

The app later reads these JSON files through the jsDelivr GitHub mirror
(cdn.jsdelivr.net/gh/...), which works even where raw.githubusercontent.com
or t.me itself might be filtered.
"""
import json
import os
import re
import sys
import urllib.request

from html.parser import HTMLParser

MAX_MESSAGES = 30
CHANNELS_FILE = os.path.join(os.path.dirname(__file__), "channels.txt")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "news")

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
}


class MessageParser(HTMLParser):
    """Minimal HTML parser (no external deps) that pulls out each
    tgme_widget_message block: its post id/link, text and time.

    HTML order per message is: [optional photo] -> message text div ->
    footer with the date/permalink link. So we buffer the text first and
    only emit a finished message once we hit the date/link that follows it.
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.messages = []
        self._in_text = False
        self._text_depth = 0
        self._current_text_parts = []
        self._pending_text = None
        self._current_time = None

    def handle_starttag(self, tag, attrs):
        attrs_dict = dict(attrs)
        classes = attrs_dict.get("class", "")

        if tag == "div" and "tgme_widget_message_text" in classes.split():
            self._in_text = True
            self._text_depth = 1
            self._current_text_parts = []
            return

        if self._in_text and tag == "div":
            self._text_depth += 1

        if self._in_text and tag == "br":
            self._current_text_parts.append("\n")

        if tag == "time":
            self._current_time = attrs_dict.get("datetime")

        if tag == "a" and "tgme_widget_message_date" in classes.split():
            link = attrs_dict.get("href")
            post_id = None
            if link:
                m = re.search(r"/(\d+)$", link)
                if m:
                    post_id = int(m.group(1))
            if post_id is not None:
                self.messages.append({
                    "id": post_id,
                    "text": self._pending_text or "",
                    "date": self._current_time,
                    "link": link,
                })
            # Reset for the next message block
            self._pending_text = None
            self._current_time = None

    def handle_endtag(self, tag):
        if self._in_text and tag == "div":
            self._text_depth -= 1
            if self._text_depth == 0:
                self._in_text = False
                text = "".join(self._current_text_parts).strip()
                text = re.sub(r"\n{3,}", "\n\n", text)
                self._pending_text = text

    def handle_data(self, data):
        if self._in_text:
            self._current_text_parts.append(data)


def fetch_channel(channel: str):
    url = f"https://t.me/s/{channel}"
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20) as resp:
        html = resp.read().decode("utf-8", errors="replace")

    parser = MessageParser()
    parser.feed(html)

    msgs = [m for m in parser.messages if m["text"]]
    # Keep only the ones that actually resolved to a post id, sort newest first
    msgs = [m for m in msgs if m["id"] is not None]
    msgs.sort(key=lambda m: m["id"], reverse=True)
    return msgs[:MAX_MESSAGES]


def main():
    if not os.path.exists(CHANNELS_FILE):
        print(f"No channels file at {CHANNELS_FILE}")
        sys.exit(1)

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    with open(CHANNELS_FILE, "r", encoding="utf-8") as f:
        channels = [line.strip() for line in f if line.strip() and not line.startswith("#")]

    any_error = False
    for channel in channels:
        try:
            msgs = fetch_channel(channel)
            out_path = os.path.join(OUTPUT_DIR, f"{channel.lower()}.json")
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump({
                    "channel": channel,
                    "messages": msgs,
                }, f, ensure_ascii=False, indent=2)
            print(f"{channel}: wrote {len(msgs)} messages")
        except Exception as e:
            any_error = True
            print(f"{channel}: FAILED - {e}")

    sys.exit(1 if any_error else 0)


if __name__ == "__main__":
    main()
