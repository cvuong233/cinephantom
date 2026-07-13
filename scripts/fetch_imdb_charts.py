#!/usr/bin/env python3
"""Fetch top Movies and TV Shows ranked by popularity (IMDb vote count) from
IMDb's official non-commercial dataset (title.ratings.tsv.gz). TMDB is used
only to classify each title as movie/TV and fetch metadata (poster, backdrop,
title) via the /find/{imdb_id} endpoint. Output: JSON written to OUTPUT_PATH.

IMDb's own chart pages (www.imdb.com/chart/...) sit behind an AWS WAF JS
challenge and can't be scraped with plain HTTP requests, even from non-CI
IPs, so ranking comes from the raw dataset instead.
"""
import gzip
import io
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from urllib.request import Request, urlopen

TMDB_API_KEY = os.environ.get("TMDB_API_KEY")
if not TMDB_API_KEY:
    print("Error: TMDB_API_KEY environment variable not set", file=sys.stderr)
    sys.exit(1)

TMDB_BASE = "https://api.themoviedb.org/3"
POSTER_BASE = "https://image.tmdb.org/t/p/w500"
IMDB_RATINGS_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"

MIN_VOTES = 10_000
CANDIDATE_POOL = 1000
TARGET_PER_TYPE = 100
MAX_RETRIES = 4
MAX_WORKERS = 20

OUTPUT_PATH = os.environ.get(
    "OUTPUT_PATH",
    "/Users/cuongvuong/Developments/agent-presentation/imdb_charts.json"
    if os.path.exists("/Users/cuongvuong/Developments/agent-presentation")
    else "/tmp/imdb_charts_test.json",
)


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


def fetch_ratings_candidates() -> list[dict]:
    """Download IMDb's ratings dataset, filter to non-obscure titles, and
    return the top CANDIDATE_POOL entries sorted by vote count desc."""
    req = Request(IMDB_RATINGS_URL, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=120) as resp:
        raw = resp.read()
    text = gzip.decompress(raw).decode("utf-8", "ignore")

    lines = io.StringIO(text)
    header = lines.readline().rstrip("\n").split("\t")
    idx = {name: i for i, name in enumerate(header)}

    candidates = []
    for line in lines:
        line = line.rstrip("\n")
        if not line:
            continue
        fields = line.split("\t")
        num_votes = int(fields[idx["numVotes"]])
        if num_votes < MIN_VOTES:
            continue
        candidates.append({
            "tconst": fields[idx["tconst"]],
            "rating": fields[idx["averageRating"]],
            "votes": num_votes,
        })

    candidates.sort(key=lambda c: c["votes"], reverse=True)
    return candidates[:CANDIDATE_POOL]


def format_votes(votes: int) -> str:
    if votes >= 1_000_000:
        return f"{votes/1_000_000:.1f}M"
    if votes >= 1_000:
        return f"{votes/1_000:.1f}K"
    return str(votes)


def classify_and_fetch(tconst: str):
    """Look up a tconst on TMDB, returning (media_type, tmdb_item) or None
    if TMDB has no movie or TV match for it."""
    url = f"{TMDB_BASE}/find/{tconst}?api_key={TMDB_API_KEY}&external_source=imdb_id"
    try:
        data = http_get_json(url)
    except Exception:
        return None
    movie_results = data.get("movie_results") or []
    if movie_results:
        return "movie", movie_results[0]
    tv_results = data.get("tv_results") or []
    if tv_results:
        return "tv", tv_results[0]
    return None


def finalize(records: list[dict]) -> list[dict]:
    result = []
    for i, r in enumerate(records):
        result.append({
            "rank": i + 1,
            "title": r["title"],
            "imdb_id": r["imdb_id"],
            "tmdb_id": r["tmdb_id"],
            "poster": r["poster"],
            "backdropPath": r["backdropPath"],
            "rating": r["rating"],
            "votes": format_votes(r["votes_raw"]),
            "source": "IMDb",
        })
    return result


print("Fetching IMDb ratings dataset...", file=sys.stderr)
candidates = fetch_ratings_candidates()
print(f"Got {len(candidates)} candidates (>= {MIN_VOTES} votes)", file=sys.stderr)

print(f"Classifying + fetching TMDB metadata for {len(candidates)} titles...", file=sys.stderr)
matches_by_tconst = {}
with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
    futures = {executor.submit(classify_and_fetch, c["tconst"]): c["tconst"] for c in candidates}
    done = 0
    for future in as_completed(futures):
        tconst = futures[future]
        matches_by_tconst[tconst] = future.result()
        done += 1
        if done % 100 == 0:
            print(f"Resolved {done}/{len(candidates)}", file=sys.stderr)

movies = []
tv = []
for c in candidates:
    match = matches_by_tconst.get(c["tconst"])
    if not match:
        continue
    media_type, item = match
    poster_path = item.get("poster_path") or ""
    poster = f"{POSTER_BASE}{poster_path}" if poster_path else ""
    record = {
        "title": item.get("title") or item.get("name") or "",
        "imdb_id": c["tconst"],
        "tmdb_id": item.get("id"),
        "poster": poster,
        "backdropPath": item.get("backdrop_path") or "",
        "rating": c["rating"],
        "votes_raw": c["votes"],
    }
    (movies if media_type == "movie" else tv).append(record)

movies.sort(key=lambda r: r["votes_raw"], reverse=True)
tv.sort(key=lambda r: r["votes_raw"], reverse=True)
movies = finalize(movies[:TARGET_PER_TYPE])
tv = finalize(tv[:TARGET_PER_TYPE])

data = {
    "updated": datetime.now(timezone.utc).isoformat(),
    "source": "IMDb Dataset (popularity)",
    "movies": movies,
    "tv": tv,
}

os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
with open(OUTPUT_PATH, "w") as f:
    json.dump(data, f, indent=2)

print(f"Wrote {len(movies)} movies + {len(tv)} TV shows to {OUTPUT_PATH}", file=sys.stderr)
