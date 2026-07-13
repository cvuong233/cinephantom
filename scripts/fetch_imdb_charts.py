#!/usr/bin/env python3
"""Fetch top-rated Movies and TV Shows, ranked by TMDB's top_rated lists, with real
IMDb ratings and vote counts cross-referenced from IMDb's official non-commercial
dataset (title.ratings.tsv.gz). Outputs top-100 movies and top-100 TV shows.
Output: JSON to stdout.

IMDb's own chart pages (www.imdb.com/chart/...) sit behind an AWS WAF JS challenge
and can't be scraped with plain HTTP requests, even from non-CI IPs. Ranking instead
comes from TMDB's top_rated endpoint, and the imdb_id per title (via TMDB's
external_ids) is used to look up the real rating/vote count in IMDb's own dataset.
"""
import csv
import gzip
import io
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from urllib.request import Request, urlopen

TMDB_API_KEY = "1f54bd990f1cdfb230adb312546d765d"
TMDB_BASE = "https://api.themoviedb.org/3"
POSTER_BASE = "https://image.tmdb.org/t/p/w500"
IMDB_RATINGS_URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"
TARGET_COUNT = 100
MAX_PAGES = 10  # buffer beyond 5 pages (100 items) for titles missing an imdb_id/rating
MAX_RETRIES = 4
MAX_WORKERS = 10


def http_get_json(url: str):
    """GET + parse JSON, retrying with backoff since TMDB intermittently resets
    connections under sustained request volume. A small pacing delay before every
    call (not just retries) keeps request bursts from tripping rate limits."""
    time.sleep(0.25)
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


def fetch_imdb_ratings() -> dict:
    """Download IMDb's official ratings dataset and index by tconst (imdb_id)."""
    req = Request(IMDB_RATINGS_URL, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=60) as resp:
        raw = resp.read()
    text = gzip.decompress(raw).decode("utf-8")
    ratings = {}
    reader = csv.DictReader(io.StringIO(text), delimiter="\t")
    for row in reader:
        ratings[row["tconst"]] = {
            "rating": row["averageRating"],
            "votes": int(row["numVotes"]),
        }
    return ratings


def get_imdb_id(media_type: str, tmdb_id: int) -> str | None:
    url = f"{TMDB_BASE}/{media_type}/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}"
    try:
        data = http_get_json(url)
        return data.get("imdb_id") or None
    except Exception:
        return None


def get_top_rated(media_type: str, max_pages: int) -> list[dict]:
    items = []
    for page in range(1, max_pages + 1):
        url = f"{TMDB_BASE}/{media_type}/top_rated?api_key={TMDB_API_KEY}&language=en-US&page={page}"
        try:
            data = http_get_json(url)
            items.extend(data.get("results", []))
        except Exception as e:
            print(f"Warning: {media_type} top_rated page {page} failed: {e}", file=sys.stderr)
    return items


def format_votes(votes: int) -> str:
    if votes >= 1_000_000:
        return f"{votes/1_000_000:.1f}M"
    if votes >= 1_000:
        return f"{votes/1_000:.1f}K"
    return str(votes)


def build_chart(media_type: str, imdb_ratings: dict, target: int, max_pages: int) -> list[dict]:
    candidates = get_top_rated(media_type, max_pages)
    print(f"{media_type}: got {len(candidates)} candidates, resolving imdb ids...", file=sys.stderr)

    imdb_ids_by_index = {}
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(get_imdb_id, media_type, item.get("id")): idx
            for idx, item in enumerate(candidates)
        }
        done = 0
        for future in as_completed(futures):
            idx = futures[future]
            imdb_ids_by_index[idx] = future.result()
            done += 1
            if done % 20 == 0:
                print(f"{media_type}: resolved {done}/{len(candidates)} imdb ids", file=sys.stderr)

    results = []
    for idx, item in enumerate(candidates):
        if len(results) >= target:
            break
        imdb_id = imdb_ids_by_index.get(idx)
        if not imdb_id:
            continue
        rating_info = imdb_ratings.get(imdb_id)
        if not rating_info:
            continue

        title = item.get("title") or item.get("name") or ""
        poster_path = item.get("poster_path") or ""
        poster = f"{POSTER_BASE}{poster_path}" if poster_path else ""
        backdrop_path = item.get("backdrop_path") or ""

        results.append({
            "rank": len(results) + 1,
            "title": title,
            "imdb_id": imdb_id,
            "tmdb_id": item.get("id"),
            "poster": poster,
            "backdropPath": backdrop_path,
            "rating": rating_info["rating"],
            "votes": format_votes(rating_info["votes"]),
            "source": "IMDb",
        })
    return results


imdb_ratings = fetch_imdb_ratings()
movies = build_chart("movie", imdb_ratings, TARGET_COUNT, MAX_PAGES)
tv = build_chart("tv", imdb_ratings, TARGET_COUNT, MAX_PAGES)

data = {
    "updated": datetime.now(timezone.utc).isoformat(),
    "source": "TMDB Top Rated + IMDb Dataset",
    "movies": movies,
    "tv": tv,
}

print(json.dumps(data, indent=2))
