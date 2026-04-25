# CinePhantom

Dark-mode Android home screen widget + search app for IMDb movies and TV shows.

## What it does
- Home screen widget styled like a compact search bar
- Tap widget → opens app search screen
- Search IMDb titles using IMDb's public suggestion endpoint
- Shows result list with title, type, year, and cast
- Tap a result → opens the official IMDb title page/app

## Free IMDb search research

### Recommended MVP source
Use IMDb's public suggestion endpoint:

```text
https://v2.sg.media-imdb.com/suggestion/<first-letter>/<query>.json
```

Examples:
- `https://v2.sg.media-imdb.com/suggestion/i/inception.json`
- `https://v2.sg.media-imdb.com/suggestion/b/breaking%20bad.json`

### Why this is the best free path for MVP
- No API key required
- Returns IMDb title IDs (`tt...`), title name, type, year/range, cast, and poster metadata
- Fast and lightweight for autosuggest/search-style UX
- Lets the app deeplink directly to official IMDb pages

### Caveat
This is a public endpoint, not a formal developer API with stability guarantees. Good for a personal-use tool, but if IMDb changes it later, the app should be updated or switched to another provider.

### Alternatives considered
- **IMDb web scraping**: brittle, more likely to break, and heavier than needed
- **OMDb API**: easy, but free tier requires API key and is not official IMDb data access
- **TMDb API**: excellent API, but not IMDb-native and still requires API key
- **RapidAPI IMDb wrappers**: often limited, paid, or unreliable for long-term free use

## Stack
- Kotlin
- Android Views + RecyclerView
- AppWidgetProvider + RemoteViews
- OkHttp
- JSON parsing via org.json
- Custom Tabs / browser deeplink to official IMDb URLs

## Package
- `com.cvuong233.cinephantom`

## Build
Open in Android Studio and let it install the missing SDK/Gradle components.

Then run the `app` configuration on your device.

## Next good upgrades
- Inline suggestions directly from the widget via pinned shortcuts or search config flow
- Result thumbnails
- Voice search
- Recent searches / favorites
- Open directly in IMDb app if installed
- Filter chips: Movies / TV / People

## Notes
This repo was scaffolded in an environment without Android SDK / Gradle tooling installed, so the project structure and source were created directly but not fully built in-place yet.
