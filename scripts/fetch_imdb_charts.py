#!/usr/bin/env python3
"""Fetch the real IMDb Popularity Meter charts (MOVIEmeter, TVmeter) using
Playwright to render the page and read the embedded JSON-LD item list, which
gives us the actual IMDb-ranked order plus each title's IMDb ID.

IMDb's chart pages sit behind an AWS WAF JS challenge that returns a 202
response and rewrites the DOM after a client-side check, so plain HTTP
requests (urllib/requests) get blocked or served an empty shell. A real
browser (Playwright + Chromium) executes the challenge JS like a normal
visitor and the page then renders normally.

TMDB is used to fetch metadata (poster, backdrop, tmdb_id) via the
/find/{imdb_id} endpoint. IMDb rating + vote count come from IMDb's own
non-commercial dataset (title.ratings.tsv.gz) since it's more complete than
the aggregateRating embedded in the chart page's JSON-LD.

Output: JSON written to OUTPUT_PATH.
"""
import gzip
import io
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from urllib.request import Request, urlopen

from playwright.sync_api import sync_playwright

TMDB_API_KEY = os.environ.get("TMDB_API_KEY")
if not TMDB_API_KEY:
    print("Error: TMDB_API_KEY environment variable not set", file=sys.stderr)
    sys.exit(1)

TMDB_BASE = "https://api.themoviedb.org/3"
POSTER_BASE = "https://image.tmdb.org/t/p/w500"
IMDB_RATINGS_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

CHART_URLS = {
    "movie": "https://www.imdb.com/chart/moviemeter/",
    "tv": "https://www.imdb.com/chart/tvmeter/",
}

TARGET_PER_TYPE = 100
MAX_RETRIES = 4
MAX_WORKERS = 20

OUTPUT_PATH = os.environ.get(
    "OUTPUT_PATH",
    "/Users/cuongvuong/Developments/agent-presentation/imdb_charts.json"
    if os.path.exists("/Users/cuongvuong/Developments/agent-presentation")
    else "/tmp/imdb_charts_test.json",
)

TITLE_ID_RE = re.compile(r"/title/(tt\d+)/")


def http_get_json(url: str):
    """GET + parse JSON, retrying with backoff since TMDB intermittently resets
    connections under sustained request volume."""
    time.sleep(0.1)
    last_error = None
    for attempt in range(MAX_RETRIES):
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT})
            with urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8", "ignore"))
        except Exception as e:
            last_error = e
            if attempt < MAX_RETRIES - 1:
                time.sleep(1.5 * (attempt + 1))
    raise last_error


def scrape_chart(page, media_type: str) -> list[dict]:
    """Load a MOVIEmeter/TVmeter chart page and return up to 100 entries in
    rank order, each with imdb_id and title. Tries JSON-LD first, falls back
    to parsing title links out of the rendered DOM."""
    url = CHART_URLS[media_type]
    page.goto(url, wait_until="domcontentloaded", timeout=45000)
    try:
        page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:
        pass
    page.wait_for_timeout(1500)
    html = page.content()

    entries = []
    for script in re.findall(
        r'<script type="application/ld\+json">(.*?)</script>', html, re.S
    ):
        try:
            data = json.loads(script)
        except json.JSONDecodeError:
            continue
        items = data.get("itemListElement")
        if not items:
            continue
        for item in items:
            node = item.get("item") or {}
            m = TITLE_ID_RE.search(node.get("url") or "")
            if not m:
                continue
            entries.append({"imdb_id": m.group(1), "title": node.get("name") or ""})
        if entries:
            return entries[:TARGET_PER_TYPE]

    print(f"No JSON-LD list found for {media_type}, falling back to link parsing", file=sys.stderr)
    seen = set()
    for m in re.finditer(r'href="/title/(tt\d+)/[^"]*"[^>]*>([^<]*)<', html):
        imdb_id, title = m.group(1), m.group(2).strip()
        if imdb_id in seen or not title:
            continue
        seen.add(imdb_id)
        entries.append({"imdb_id": imdb_id, "title": title})
        if len(entries) >= TARGET_PER_TYPE:
            break
    return entries


def fetch_ratings_map(tconsts: set[str]) -> dict[str, dict]:
    """Download IMDb's ratings dataset and return {tconst: {rating, votes}}
    for just the tconsts we care about."""
    req = Request(IMDB_RATINGS_URL, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=120) as resp:
        raw = resp.read()
    text = gzip.decompress(raw).decode("utf-8", "ignore")

    lines = io.StringIO(text)
    header = lines.readline().rstrip("\n").split("\t")
    idx = {name: i for i, name in enumerate(header)}

    ratings = {}
    for line in lines:
        line = line.rstrip("\n")
        if not line:
            continue
        fields = line.split("\t")
        tconst = fields[idx["tconst"]]
        if tconst not in tconsts:
            continue
        ratings[tconst] = {
            "rating": fields[idx["averageRating"]],
            "votes": int(fields[idx["numVotes"]]),
        }
    return ratings


def format_votes(votes: int) -> str:
    if votes >= 1_000_000:
        return f"{votes/1_000_000:.1f}M"
    if votes >= 1_000:
        return f"{votes/1_000:.1f}K"
    return str(votes)


def fetch_tmdb_match(tconst: str):
    """Look up a tconst on TMDB, returning the tmdb item dict or None."""
    url = f"{TMDB_BASE}/find/{tconst}?api_key={TMDB_API_KEY}&external_source=imdb_id"
    try:
        data = http_get_json(url)
    except Exception:
        return None
    for key in ("movie_results", "tv_results"):
        results = data.get(key) or []
        if results:
            return results[0]
    return None


def build_records(entries: list[dict], tmdb_matches: dict[str, dict], ratings: dict[str, dict]) -> list[dict]:
    records = []
    for i, entry in enumerate(entries):
        tconst = entry["imdb_id"]
        tmdb_item = tmdb_matches.get(tconst)
        poster_path = (tmdb_item or {}).get("poster_path") or ""
        rating_info = ratings.get(tconst, {})
        records.append({
            "rank": i + 1,
            "title": (tmdb_item or {}).get("title") or (tmdb_item or {}).get("name") or entry["title"],
            "imdb_id": tconst,
            "tmdb_id": (tmdb_item or {}).get("id"),
            "poster": f"{POSTER_BASE}{poster_path}" if poster_path else "",
            "backdropPath": (tmdb_item or {}).get("backdrop_path") or "",
            "rating": rating_info.get("rating", ""),
            "votes": format_votes(rating_info["votes"]) if "votes" in rating_info else "",
            "source": "IMDb",
        })
    return records


def main():
    print("Launching Playwright Chromium...", file=sys.stderr)
    entries_by_type = {}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(user_agent=USER_AGENT, viewport={"width": 1280, "height": 1600})
        page = context.new_page()
        for media_type in ("movie", "tv"):
            print(f"Scraping {CHART_URLS[media_type]} ...", file=sys.stderr)
            entries_by_type[media_type] = scrape_chart(page, media_type)
            print(f"Got {len(entries_by_type[media_type])} {media_type} entries", file=sys.stderr)
        browser.close()

    movie_entries = entries_by_type["movie"]
    tv_entries = entries_by_type["tv"]

    if not movie_entries or not tv_entries:
        print("ERROR: IMDb blocked the request or returned no chart data (possible CAPTCHA/WAF challenge).", file=sys.stderr)
        data = {
            "updated": datetime.now(timezone.utc).isoformat(),
            "source": "IMDb Popularity Meter",
            "error": "IMDb blocked the scrape (no chart data returned)",
            "movies": [],
            "tv": [],
        }
        os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
        with open(OUTPUT_PATH, "w") as f:
            json.dump(data, f, indent=2)
        sys.exit(1)

    all_entries = movie_entries + tv_entries
    all_tconsts = {e["imdb_id"] for e in all_entries}

    print(f"Fetching TMDB metadata for {len(all_tconsts)} titles...", file=sys.stderr)
    tmdb_matches = {}
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(fetch_tmdb_match, tconst): tconst for tconst in all_tconsts}
        done = 0
        for future in as_completed(futures):
            tconst = futures[future]
            tmdb_matches[tconst] = future.result()
            done += 1
            if done % 50 == 0:
                print(f"Resolved {done}/{len(all_tconsts)}", file=sys.stderr)

    print("Fetching IMDb ratings dataset...", file=sys.stderr)
    ratings = fetch_ratings_map(all_tconsts)
    print(f"Got ratings for {len(ratings)}/{len(all_tconsts)} titles", file=sys.stderr)

    movies = build_records(movie_entries, tmdb_matches, ratings)
    tv = build_records(tv_entries, tmdb_matches, ratings)

    data = {
        "updated": datetime.now(timezone.utc).isoformat(),
        "source": "IMDb Popularity Meter",
        "movies": movies,
        "tv": tv,
    }

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump(data, f, indent=2)

    print(f"Wrote {len(movies)} movies + {len(tv)} TV shows to {OUTPUT_PATH}", file=sys.stderr)


if __name__ == "__main__":
    main()
