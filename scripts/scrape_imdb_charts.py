#!/usr/bin/env python3
"""Fetch IMDb Most Popular Movies and TV Shows using TMDB API (reliable, no browser needed).
Outputs top-100 movies and top-100 TV shows with IMDb IDs, TMDB ratings, and posters.
Output: JSON to stdout.

Replaces the old Playwright-based scraper which was blocked by IMDb on GH Actions IPs.
"""
import json
import sys
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

TMDB_API_KEY = "1f54bd990f1cdfb230adb312546d765d"
TMDB_BASE = "https://api.themoviedb.org/3"
POSTER_BASE = "https://image.tmdb.org/t/p/w500"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"


def http_get_json(url: str):
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


def get_popular(media_type: str, pages: int = 5) -> list[dict]:
    """Fetch up to pages*20 popular items from TMDB."""
    items = []
    for page in range(1, pages + 1):
        url = f"{TMDB_BASE}/{media_type}/popular?api_key={TMDB_API_KEY}&language=en-US&page={page}"
        try:
            data = http_get_json(url)
            items.extend(data.get("results", []))
        except Exception as e:
            print(f"Warning: page {page} failed: {e}", file=sys.stderr)
    return items


def get_external_ids(media_type: str, tmdb_id: int) -> str | None:
    """Get IMDb ID for a TMDB item."""
    url = f"{TMDB_BASE}/{media_type}/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}"
    try:
        data = http_get_json(url)
        return data.get("imdb_id") or None
    except Exception:
        return None


def build_items(tmdb_items: list[dict], media_type: str, limit: int = 100) -> list[dict]:
    results = []
    for i, item in enumerate(tmdb_items[:limit]):
        tmdb_id = item.get("id")
        title = item.get("title") or item.get("name") or ""
        poster_path = item.get("poster_path") or ""
        poster = f"{POSTER_BASE}{poster_path}" if poster_path else ""
        rating = str(round(item.get("vote_average", 0.0), 1))
        votes_raw = item.get("vote_count", 0)
        # Format votes like IMDb: 1.2K, 3.4M etc.
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
            "rating": rating,
            "votes": votes,
            "source": "TMDB",
        })
    return results


movies = get_popular("movie", pages=5)
tv = get_popular("tv", pages=5)

data = {
    "updated": datetime.now(timezone.utc).isoformat(),
    "source": "TMDB Popular",
    "movies": build_items(movies, "movie", 100),
    "tv": build_items(tv, "tv", 100),
}

print(json.dumps(data, indent=2))
