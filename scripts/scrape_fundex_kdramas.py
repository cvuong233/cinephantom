#!/usr/bin/env python3
"""Fetch FUNdex popular K-dramas and enrich them with stronger IMDb matching via TMDB.
Outputs JSON to stdout.
"""
import json
import re
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, quote_plus
from urllib.request import Request, urlopen

FUNDEX_HOME_URL = "https://www.fundex.co.kr/"
FUNDEX_API_URL = "https://www.fundex.co.kr/select/nowplay.getOqlistNew.do"
FUNDEX_DETAIL_BASE_URL = "https://www.fundex.co.kr/fundex/funprogram.do?pjseq="
FUNDEX_POSTER_BASE_URL = "https://www.fundex.co.kr/pjmeta/pjimg/pj_"
TMDB_API_KEY = "1f54bd990f1cdfb230adb312546d765d"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36"
OVERRIDES_PATH = Path(__file__).with_name("fundex_kdrama_overrides.json")


def http_get_text(url: str) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "ignore")


def http_get_json(url: str):
    return json.loads(http_get_text(url))


def http_post_form_json(url: str, form: dict):
    from urllib.parse import urlencode
    body = urlencode(form).encode()
    req = Request(
        url,
        data=body,
        headers={
            "User-Agent": USER_AGENT,
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        },
    )
    with urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8", "ignore"))


def get_wcd() -> str:
    html = http_get_text(FUNDEX_HOME_URL)
    m = re.search(r"var\s+__WCD\s*=\s*'([^']+)'", html)
    if not m:
        raise RuntimeError("FUNdex week code not found")
    return m.group(1)


def load_overrides() -> dict:
    if not OVERRIDES_PATH.exists():
        return {}
    return json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    value = unicodedata.normalize("NFKC", value)
    value = value.lower().strip()
    value = re.sub(r"[’'`:!?,.&()\-_/]", " ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def title_variants(title: str | None):
    if not title:
        return []
    value = title.strip()
    variants = [value]
    variants.append(re.sub(r"\s+", " ", value))
    variants.append(value.replace(" ", ""))

    stripped = re.sub(r"\bseason\s*\d+\b", "", value, flags=re.I).strip()
    stripped = re.sub(r"\bpart\s*\d+\b", "", stripped, flags=re.I).strip()
    stripped = re.sub(r"\s+\d+$", "", stripped).strip()
    stripped = re.sub(r"(?<=[^\W\d_])\d+$", "", stripped, flags=re.UNICODE).strip()

    base_variants = [stripped]
    if stripped:
        base_variants.append(stripped.replace(" ", ""))
        spaced_digits = re.sub(r"(?<=[^\W\d_])(\d+)$", r" \1", value, flags=re.UNICODE).strip()
        if spaced_digits != value:
            base_variants.append(spaced_digits)
            no_digit = re.sub(r"\s+\d+$", "", spaced_digits).strip()
            if no_digit:
                base_variants.append(no_digit)
                base_variants.append(no_digit.replace(" ", ""))

    for candidate in base_variants:
        if candidate and candidate not in variants:
            variants.append(candidate)

    seen = set()
    out = []
    for item in variants:
        item = item.strip()
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out


def score_candidate(item: dict, english_title: str | None, korean_title: str, year: str | None, query: str):
    score = float(item.get("popularity", 0.0) or 0.0)
    name = (item.get("name") or "").strip()
    original_name = (item.get("original_name") or "").strip()
    first_air = (item.get("first_air_date") or "").strip()
    origin_countries = item.get("origin_country") or []
    original_language = item.get("original_language") or ""

    norm_query = normalize_text(query)
    norm_name = normalize_text(name)
    norm_original = normalize_text(original_name)
    norm_english = normalize_text(english_title)
    norm_korean = normalize_text(korean_title)

    if year and first_air.startswith(year):
        score += 1000
    if "KR" in origin_countries:
        score += 200
    if original_language == "ko":
        score += 120
    if norm_query and norm_query == norm_name:
        score += 500
    if norm_query and norm_query == norm_original:
        score += 650
    if norm_english and norm_english == norm_name:
        score += 350
    if norm_english and norm_english == norm_original:
        score += 420
    if norm_korean and norm_korean == norm_name:
        score += 550
    if norm_korean and norm_korean == norm_original:
        score += 700
    if norm_korean and norm_korean.replace(" ", "") == norm_original.replace(" ", ""):
        score += 200
    if re.search(r"season\s*\d+|\d+$", query, re.I) and not re.search(r"season\s*\d+|\d+$", name, re.I):
        score -= 40
    return score


def fetch_external_ids(tmdb_id: int):
    return http_get_json(f"https://api.themoviedb.org/3/tv/{tmdb_id}/external_ids?api_key={TMDB_API_KEY}")


def fetch_imdb_suggestions(query: str):
    if not query:
        return []
    encoded = quote(query)
    first = query[0].lower()
    url = f"https://v2.sg.media-imdb.com/suggestion/{first}/{encoded}.json"
    try:
        data = http_get_json(url)
    except Exception:
        return []
    return data.get("d") or []


def score_imdb_candidate(item: dict, english_title: str | None, korean_title: str, year: str | None, query: str):
    score = 0.0
    label = (item.get("l") or "").strip()
    kind = (item.get("q") or "").strip().lower()
    item_year = str(item.get("y") or "").strip()
    norm_label = normalize_text(label)
    norm_query = normalize_text(query)
    norm_english = normalize_text(english_title)
    norm_korean = normalize_text(korean_title)

    if item_year and year and item_year == year:
        score += 1000
    if "tv" in kind or "series" in kind:
        score += 180
    if norm_query and norm_query == norm_label:
        score += 700
    if norm_english and norm_english == norm_label:
        score += 500
    if norm_korean and norm_korean == norm_label:
        score += 500
    if norm_query and norm_query in norm_label:
        score += 120
    if norm_label and norm_label in norm_query:
        score += 80
    return score


def resolve_imdb_via_imdb_search(english_title: str | None, korean_title: str, year: str | None):
    candidates = []
    seen = set()
    queries = []
    for source in [english_title, korean_title]:
        for variant in title_variants(source):
            if variant not in queries:
                queries.append(variant)

    for query in queries:
        for item in fetch_imdb_suggestions(query):
            imdb_id = (item.get("id") or "").strip()
            if not imdb_id or not imdb_id.startswith("tt") or imdb_id in seen:
                continue
            seen.add(imdb_id)
            score = score_imdb_candidate(item, english_title, korean_title, year, query)
            candidates.append((score, query, item))

    if not candidates:
        return None

    candidates.sort(key=lambda row: row[0], reverse=True)
    score, query, item = candidates[0]
    confidence = "high" if score >= 1500 else "medium" if score >= 900 else "low"
    return {
        "imdb_id": (item.get("id") or "").strip(),
        "tmdb_id": None,
        "tmdb_name": (item.get("l") or "").strip() or None,
        "original_name": None,
        "first_air_date": str(item.get("y") or "").strip() or None,
        "backdrop_path": None,
        "match_confidence": confidence,
        "match_method": "imdb_suggestion_fallback",
        "match_notes": f"query={query} score={score:.1f}",
    }


def resolve_imdb(english_title: str | None, korean_title: str, year: str | None, overrides: dict):
    override_key = f"{korean_title}|{year or ''}"
    override = overrides.get(override_key)
    if override is not None:
        return {
            "imdb_id": override.get("imdb_id"),
            "tmdb_id": override.get("tmdb_id"),
            "tmdb_name": override.get("tmdb_name"),
            "original_name": override.get("original_name"),
            "first_air_date": override.get("first_air_date"),
            "backdrop_path": override.get("backdrop_path"),
            "match_confidence": override.get("match_confidence", "manual"),
            "match_method": override.get("match_method", "override"),
            "match_notes": override.get("match_notes"),
        }

    candidates = []
    seen_ids = set()
    queries = []
    for source in [english_title, korean_title]:
        for variant in title_variants(source):
            if variant not in queries:
                queries.append(variant)

    for query in queries:
        encoded = quote_plus(query)
        url = f"https://api.themoviedb.org/3/search/tv?api_key={TMDB_API_KEY}&query={encoded}&include_adult=false"
        if year:
            url += f"&first_air_date_year={year}"
        try:
            data = http_get_json(url)
        except Exception:
            continue
        for item in data.get("results") or []:
            tmdb_id = item.get("id")
            if not tmdb_id or tmdb_id in seen_ids:
                continue
            seen_ids.add(tmdb_id)
            item["__query"] = query
            item["__score"] = score_candidate(item, english_title, korean_title, year, query)
            candidates.append(item)

    if not candidates:
        return None

    candidates.sort(key=lambda item: item.get("__score", 0.0), reverse=True)
    best_tmdb = candidates[0] if candidates else None
    for item in candidates[:8]:
        tmdb_id = item.get("id")
        try:
            external = fetch_external_ids(tmdb_id)
        except Exception:
            continue
        imdb_id = (external.get("imdb_id") or "").strip() or None
        if not imdb_id:
            continue
        score = float(item.get("__score", 0.0) or 0.0)
        confidence = "high" if score >= 1500 else "medium" if score >= 900 else "low"
        return {
            "imdb_id": imdb_id,
            "tmdb_id": tmdb_id,
            "tmdb_name": (item.get("name") or "").strip() or None,
            "original_name": (item.get("original_name") or "").strip() or None,
            "first_air_date": (item.get("first_air_date") or "").strip() or None,
            "backdrop_path": item.get("backdrop_path") or None,
            "match_confidence": confidence,
            "match_method": "tmdb_scored_search",
            "match_notes": f"query={item.get('__query')} score={score:.1f}",
        }

    imdb_fallback = resolve_imdb_via_imdb_search(english_title, korean_title, year)
    if imdb_fallback:
        if best_tmdb is not None:
            imdb_fallback["tmdb_id"] = best_tmdb.get("id")
            imdb_fallback["original_name"] = (best_tmdb.get("original_name") or "").strip() or None
            imdb_fallback["first_air_date"] = (best_tmdb.get("first_air_date") or "").strip() or imdb_fallback.get("first_air_date")
            imdb_fallback["backdrop_path"] = best_tmdb.get("backdrop_path") or None
        return imdb_fallback
    return None


def main():
    overrides = load_overrides()
    wcd = get_wcd()
    params = {
        "perPage": 20,
        "pageoff": 0,
        "clsid": "",
        "is_ott": "ott",
        "order": "oqpoint",
        "wcd": wcd,
        "wtype": "week",
        "table": "tvr_week_rank_nor_ott",
        "cat_id": "cat001",
    }
    items = http_post_form_json(FUNDEX_API_URL, {"param": json.dumps(params, ensure_ascii=False)})

    kdramas = []
    for idx, item in enumerate(items[:20], start=1):
        pj_seq = item.get("pj_seq")
        korean_title = (item.get("pjname") or "").strip()
        if not pj_seq or not korean_title:
            continue
        english_title = ((item.get("epjname") or "").strip() or None)
        title = english_title or korean_title
        startday = ((item.get("startday") or "").strip() or None)
        year = startday[:4] if startday and len(startday) >= 4 else None
        genre = ((item.get("genre_list") or "").strip() or None)
        sharepoint = item.get("cat_sharepoint")
        try:
            fundex_score = f"{float(sharepoint):.2f}%"
        except Exception:
            fundex_score = None
        match = resolve_imdb(english_title, korean_title, year, overrides)
        kdramas.append({
            "rank": idx,
            "title": title,
            "english_title": english_title,
            "korean_title": korean_title,
            "year": year,
            "genre": genre,
            "fundex_score": fundex_score,
            "source": "FUNdex",
            "source_url": f"{FUNDEX_DETAIL_BASE_URL}{pj_seq}",
            "poster": f"{FUNDEX_POSTER_BASE_URL}{pj_seq}.jpg",
            "imdb_id": (match or {}).get("imdb_id"),
            "tmdb_id": (match or {}).get("tmdb_id"),
            "tmdb_name": (match or {}).get("tmdb_name"),
            "backdropPath": (match or {}).get("backdrop_path"),
            "original_title_ko": (match or {}).get("original_name"),
            "first_air_date": (match or {}).get("first_air_date"),
            "match_confidence": (match or {}).get("match_confidence"),
            "match_method": (match or {}).get("match_method"),
            "match_notes": (match or {}).get("match_notes"),
        })

    payload = {
        "updated": datetime.now(timezone.utc).isoformat(),
        "source": "FUNdex",
        "mode": "weekly_tv_ott_drama_buzzworthiness",
        "count": len(kdramas),
        "kdramas": kdramas,
    }
    print(json.dumps(payload, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
