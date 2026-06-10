# Wizdier-AniRepo

A personal Aniyomi/AniZen extension repository.

## 📲 Add this repo to your app

Add this URL in **Settings → Browse → Extension repos** (AniZen, Aniyomi, Animetail, …):

```
https://raw.githubusercontent.com/Wizdier/Wizdier-AniRepo/repo/index.min.json
```

## 📦 Extensions

| Extension | Language | Version | NSFW | Highlights |
| --- | --- | --- | --- | --- |
| Miruro.tv | en | 14.4 | Yes | AniList + ani.zip episode metadata (titles, thumbnails, descriptions), country-of-origin filter (JP/CN/KR/TW), filler marking |
| Cineby | en | 14.1 | Yes | Movies & TV with **TMDB metadata integration** — bring your own TMDB API key, per-episode thumbnails/descriptions/ratings, 20 metadata languages |

## 🎬 Cineby: TMDB metadata integration

Cineby is TMDB-backed by design. This build adds a full **bring-your-own-key** TMDB integration on top:

- **Personal TMDB API key** (extension settings → *TMDB API Key*):
  - All metadata (browse, search, details, episodes) is fetched **directly from `api.themoviedb.org`** with your key — no proxy rate limits, always-fresh data.
  - The **same v3 API key** you use for AniZen's built-in TMDB tracker works here. Get one free at [themoviedb.org → Settings → API](https://www.themoviedb.org/settings/api).
  - Keys are validated as you type (32-char hex). If your key is ever invalid/revoked/rate-limited, the extension **automatically falls back** to Cineby's keyless metadata proxy — the source never breaks.
  - No key set? Everything still works through the built-in proxy.
- **TMDB Metadata Language** — choose from 20 languages (English, Spanish, Portuguese, French, German, Japanese, Bengali, Hindi, Arabic, Chinese, …) for titles, overviews and episode data when using your key.
- **TMDB Episode Metadata** (on by default) — episode lists show TMDB **thumbnails, descriptions, ratings (★) and runtimes** on apps that support it (AniZen, Animetail, current Aniyomi). Gracefully no-ops on older apps.

## 🛠️ How it works

- Extension sources live under `src/<lang>/<name>/`; shared libraries under `lib/`.
- Pushing to `main`/`master` (or manually running the **Build & Publish Repo** workflow) builds all extensions, generates the repo index with the [aniyomi-extensions-inspector](https://github.com/komikku-app/aniyomi-extensions-inspector), writes `repo.json` with the signing-key fingerprint, and force-pushes the result (`apk/`, `icon/`, `index.min.json`, `repo.json`) to the `repo` branch.
- APKs are signed with the repo's permanent keystore (via the secrets below), so installed extensions are correctly attributed (`@Wizdier`) and never get stuck in update loops.

### Release signing secrets

| Secret | Description |
| --- | --- |
| `SIGNING_KEY` | Base64 of the `.jks` keystore (`base64 -w0 key.jks`) |
| `ALIAS` | Keystore alias |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

## 🧑‍💻 Build locally

Requires JDK 17 and the Android SDK.

```bash
./gradlew :src:en:miruro:assembleRelease
./gradlew :src:en:cineby:assembleRelease
```

APKs are output to `src/en/<name>/build/outputs/apk/release/`.

## Credits

- Extension sources: [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions)
- Build system: [keiyoushi](https://github.com/keiyoushi/extensions-source) / yuzono
- Metadata: [TMDB](https://www.themoviedb.org/) — this product uses the TMDB API but is not endorsed or certified by TMDB.

## License

Apache-2.0 — see [LICENSE](LICENSE).
