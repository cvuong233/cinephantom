#!/usr/bin/env python3
"""Fetch trending English-language Movies and TV Shows using TMDB Weekly Trending API.
Outputs top-100 movies and top-100 TV shows with IMDb IDs, TMDB ratings, and posters.
Output: JSON to stdout.

Uses /trending/{type}/week (time-windowed, similar to IMDb MovieMeter) and filters
client-side to original_language == "en". The with_original_language param is silently
ignored by TMDB's trending endpoint, so we fetch extra pages and filter manually.

Direct IMDb scraping is not possible from GitHub Actions IPs (blocked by AWS WAF).
"""
import json
import sys
from datetime import datetime, timezone
from urllib.request import Request, urlopen

TMDB_API_KEY = "1f54bd990f1cdfb230adb312546d765d"
TMDB_BASE = "https://api.themoviedb.org/3"
POSTER_BASE = "https://image.tmdb.org/t/p/w500"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"


def http_get_json(url: str):
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


def get_trending_english(media_type: str, limit: int = 100, max_pages: int = 12) -> list[dict]:
    """Fetch weekly trending items filtered to English original language.
    Trending endpoint ignores with_original_language, so we filter client-side
    and fetch enough pages to guarantee `limit` English results."""
    items = []
    for page in range(1, max_pages + 1):
        url = f"{TMDB_BASE}/trending/{media_type}/week?api_key={TMDB_API_KEY}&language=en-US&page={page}"
        try:
            data = http_get_json(url)
            results = data.get("results", [])
            en_results = [r for r in results if r.get("original_language") == "en"]
            items.extend(en_results)
            if len(items) >= limit:
                break
        except Exception as e:
            print(f"Warning: {media_type} trending page {page} failed: {e}", file=sys.stderr)
    return items[:limit]


def get_external_ids(media_type: str, tmdb_id: int) -> str | None:
    """Get IMDb ID for a TMDB item."""
    url = f"{TMDB_BASE}/{media_type}/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}"
    try:
        data = http_get_json(url)
        return data.get("imdb_id") or None
    except Exception:
        return None


def build_items(tmdb_items: list[dict], media_type: str) -> list[dict]:
    results = []
    for i, item in enumerate(tmdb_items):
        tmdb_id = item.get("id")
        title = item.get("title") or item.get("name") or ""
        poster_path = item.get("poster_path") or ""
        poster = f"{POSTER_BASE}{poster_path}" if poster_path else ""
        backdrop_path = item.get("backdrop_path") or ""
        rating = str(round(item.get("vote_average", 0.0), 1))
        votes_raw = item.get("vote_count", 0)
        if votes_raw >= 1_000_000:
            votes = f"{votes_raw/1_000_000:.1f}M"
        elif votes_raw >= 1_000:
            votes = f"{votes_raw/1_000:.1f}K"
        else:
            votes = str(votes_raw)

        imdb_id = get_external_ids(media_type, tmdb_id)

        results.append({
            "rank": i + 1,
            "title": title,
            "imdb_id": imdb_id or "",
            "tmdb_id": tmdb_id,
            "poster": poster,
            "backdropPath": backdrop_path,
            "rating": rating,
            "votes": votes,
            "source": "TMDB",
        })
    return results


movies = get_trending_english("movie", limit=100)
tv     = get_trending_english("tv",    limit=100)

data = {
    "updated": datetime.now(timezone.utc).isoformat(),
    "source": "TMDB Weekly Trending (English)",
    "movies": build_items(movies, "movie"),
    "tv":     build_items(tv,     "tv"),
}

print(json.dumps(data, indent=2))
