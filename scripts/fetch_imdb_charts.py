#!/usr/bin/env python3
"""Fetch top-rated Movies and TV Shows, ranked directly from IMDb's official
non-commercial dataset (title.ratings.tsv.gz + title.basics.tsv.gz).
TMDB is used only for metadata lookup (poster, backdrop, title) via the
/find/{imdb_id} endpoint. Output: JSON written to OUTPUT_PATH.

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
IMDB_BASICS_URL = "https://datasets.imdbws.com/title.basics.tsv.gz"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"

MIN_VOTES = 50_000
CANDIDATE_POOL = 500
TARGET_PER_TYPE = 100
MAX_RETRIES = 4
MAX_WORKERS = 10

MOVIE_TYPES = {"movie"}
TV_TYPES = {"tvSeries", "tvMiniSeries"}

OUTPUT_PATH = os.environ.get(
    "OUTPUT_PATH",
    "/Users/cuongvuong/Developments/agent-presentation/imdb_charts.json"
    if os.path.exists("/Users/cuongvuong/Developments/agent-presentation")
    else "/tmp/imdb_charts_test.json",
)


def http_get_json(url: str):
    """GET + parse JSON, retrying with backoff since TMDB intermittently resets
    connections under sustained request volume."""
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


def fetch_top_rated_ids() -> dict:
    """Download IMDb's ratings dataset, filter to popular titles, and return
    the top CANDIDATE_POOL tconst -> {rating, votes} sorted by rating desc."""
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

    candidates.sort(key=lambda c: float(c["rating"]), reverse=True)
    top = candidates[:CANDIDATE_POOL]
    return {c["tconst"]: c for c in top}


def fetch_basics_for(wanted: dict) -> list[dict]:
    """Stream-decompress IMDb's basics dataset and pull only rows matching
    the wanted tconst set, merging in the rating/votes already known."""
    req = Request(IMDB_BASICS_URL, headers={"User-Agent": USER_AGENT})
    remaining = set(wanted.keys())
    records = []
    with urlopen(req, timeout=300) as resp:
        with gzip.GzipFile(fileobj=resp) as gz:
            header = gz.readline().decode("utf-8", "ignore").rstrip("\n").split("\t")
            idx = {name: i for i, name in enumerate(header)}
            for raw_line in gz:
                if not remaining:
                    break
                line = raw_line.decode("utf-8", "ignore").rstrip("\n")
                if not line:
                    continue
                fields = line.split("\t")
                tconst = fields[idx["tconst"]]
                if tconst not in remaining:
                    continue
                remaining.discard(tconst)
                title_type = fields[idx["titleType"]]
                if title_type not in MOVIE_TYPES and title_type not in TV_TYPES:
                    continue
                info = wanted[tconst]
                records.append({
                    "tconst": tconst,
                    "titleType": title_type,
                    "primaryTitle": fields[idx["primaryTitle"]],
                    "startYear": fields[idx["startYear"]],
                    "rating": info["rating"],
                    "votes": info["votes"],
                })
    return records


def format_votes(votes: int) -> str:
    if votes >= 1_000_000:
        return f"{votes/1_000_000:.1f}M"
    if votes >= 1_000:
        return f"{votes/1_000:.1f}K"
    return str(votes)


def find_tmdb_match(tconst: str, is_movie: bool):
    url = f"{TMDB_BASE}/find/{tconst}?api_key={TMDB_API_KEY}&external_source=imdb_id"
    try:
        data = http_get_json(url)
    except Exception:
        return None
    results = data.get("movie_results" if is_movie else "tv_results") or []
    return results[0] if results else None


def build_chart(records: list[dict], is_movie: bool) -> list[dict]:
    matches_by_tconst = {}
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(find_tmdb_match, rec["tconst"], is_movie): rec["tconst"]
            for rec in records
        }
        for future in as_completed(futures):
            tconst = futures[future]
            matches_by_tconst[tconst] = future.result()

    results = []
    for rec in records:
        match = matches_by_tconst.get(rec["tconst"])
        if not match:
            continue
        poster_path = match.get("poster_path") or ""
        poster = f"{POSTER_BASE}{poster_path}" if poster_path else ""
        results.append({
            "rank": len(results) + 1,
            "title": match.get("title") or match.get("name") or rec["primaryTitle"],
            "imdb_id": rec["tconst"],
            "tmdb_id": match.get("id"),
            "poster": poster,
            "backdropPath": match.get("backdrop_path") or "",
            "rating": rec["rating"],
            "votes": format_votes(rec["votes"]),
            "source": "IMDb",
        })
    return results


print("Fetching IMDb ratings dataset...", file=sys.stderr)
top_candidates = fetch_top_rated_ids()
print(f"Got {len(top_candidates)} candidate titles (>= {MIN_VOTES} votes)", file=sys.stderr)

print("Streaming IMDb basics dataset...", file=sys.stderr)
basics_records = fetch_basics_for(top_candidates)
print(f"Matched {len(basics_records)} titles in basics dataset", file=sys.stderr)

movie_records = sorted(
    (r for r in basics_records if r["titleType"] in MOVIE_TYPES),
    key=lambda r: float(r["rating"]), reverse=True,
)[:TARGET_PER_TYPE]
tv_records = sorted(
    (r for r in basics_records if r["titleType"] in TV_TYPES),
    key=lambda r: float(r["rating"]), reverse=True,
)[:TARGET_PER_TYPE]

print(f"Resolving TMDB metadata for {len(movie_records)} movies + {len(tv_records)} TV shows...", file=sys.stderr)
movies = build_chart(movie_records, is_movie=True)
tv = build_chart(tv_records, is_movie=False)

data = {
    "updated": datetime.now(timezone.utc).isoformat(),
    "source": "IMDb Dataset",
    "movies": movies,
    "tv": tv,
}

os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
with open(OUTPUT_PATH, "w") as f:
    json.dump(data, f, indent=2)

print(f"Wrote {len(movies)} movies + {len(tv)} TV shows to {OUTPUT_PATH}", file=sys.stderr)
