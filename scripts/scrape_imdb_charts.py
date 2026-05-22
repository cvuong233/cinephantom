#!/usr/bin/env python3
"""Scrape IMDb Most Popular Movies and TV Shows charts (top 100 each).
Captures title, poster, IMDb rating, vote count, ranking, and IMDb ID.
Posters are upscaled to UX500 for good quality on mobile widgets.
Output: JSON to stdout.
"""
import asyncio, json, re, sys
from datetime import datetime, timezone
from playwright.async_api import async_playwright

def upscale_poster(url: str) -> str:
    """Transform IMDb CDN poster URL from tiny UX90 thumbnail to UX500 full poster.
    Strips the CR (crop) parameters so the full poster is shown, not a zoomed-in crop."""
    # Remove crop region and quality params, keep only the image ID and UX500
    url = re.sub(r'@\._.*', '@._V1_UX500_.jpg', url)
    url = re.sub(r'\._V1_.*?\.jpg$', '._V1_UX500_.jpg', url)
    return url

async def scrape():
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=True,
            args=['--disable-blink-features=AutomationControlled', '--no-sandbox']
        )
        context = await browser.new_context(
            user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147.0.0.0 Safari/537.36',
            viewport={'width': 1920, 'height': 1080},
            locale='en-US',
        )
        page = await context.new_page()
        await page.add_init_script("""
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
        """)
        
        all_data = {"updated": "", "movies": [], "tv": []}
        
        for chart_type, url in [
            ("movies", "https://www.imdb.com/chart/moviemeter/"),
            ("tv", "https://www.imdb.com/chart/tvmeter/")
        ]:
            await page.goto(url, timeout=30000, wait_until="networkidle")
            await page.wait_for_timeout(5000)
            
            items = await page.evaluate(r"""() => {
                const results = [];
                document.querySelectorAll('ul.ipc-metadata-list > li').forEach((li, i) => {
                    if (i >= 100) return;
                    const titleEl = li.querySelector('h3.ipc-title__text');
                    const title = titleEl ? titleEl.textContent.replace(/^\\d+\\.\\s*/, '').trim() : '';
                    const linkEl = li.querySelector('a[href*="/title/tt"]');
                    const href = linkEl?.getAttribute('href') || linkEl?.href || '';
                    const imdbId = href ? (href.match(/tt\d+/)?.[0] || '') : '';
                    const posterEl = li.querySelector('img');

                    const allText = Array.from(li.querySelectorAll('span'))
                      .map(el => (el.textContent || '').trim())
                      .filter(Boolean);
                    const ratingNode = li.querySelector('[aria-label*="IMDb rating"]');
                    const ratingText = ratingNode ? (ratingNode.textContent || '').replace(/\u00A0/g, ' ').trim() : '';
                    let rating = '';
                    let votes = '';
                    const ratingMatch = ratingText.match(/(\d\.\d)/);
                    const votesMatch = ratingText.match(/\(([^)]+)\)/);
                    if (ratingMatch) rating = ratingMatch[1];
                    if (votesMatch) votes = votesMatch[1];
                    if (!rating) {
                      rating = allText.find(t => /^\d\.\d$/.test(t)) || '';
                    }
                    if (!votes) {
                      votes = allText.find(t => /^\d+(?:\.\d+)?[KMB]$/.test(t.replace(/,/g, ''))) || '';
                    }
                    if (title) results.push({rank: i+1, title, imdb_id: imdbId, poster: posterEl?.src || '', rating, votes});
                });
                return results;
            }""")
            
            # Upscale poster URLs
            for item in items:
                item["poster"] = upscale_poster(item["poster"])
            
            all_data[chart_type] = items
        
        await browser.close()
        return all_data

data = asyncio.run(scrape())
data["updated"] = datetime.now(timezone.utc).isoformat()
print(json.dumps(data, indent=2))
