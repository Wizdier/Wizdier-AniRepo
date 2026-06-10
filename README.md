# Wizdier-AniRepo

A personal Aniyomi extension repository containing the **Miruro.tv** extension.

## 📲 Add this repo to Aniyomi

Once the GitHub Actions workflow has run at least once (it publishes to the `repo` branch), add this URL in **Aniyomi → Settings → Browse → Extension repos**:

```
https://raw.githubusercontent.com/Wizdier/Wizdier-AniRepo/repo/index.min.json
```

## 📦 Extensions

| Extension | Language | Version | NSFW |
| --- | --- | --- | --- |
| Miruro.tv | en | 14.2 | Yes |

## 🛠️ How it works

- Extension source lives under `src/en/miruro/`.
- Pushing to `main`/`master` (or manually running the **Build & Publish Repo** workflow) builds all extensions, generates the repo index with the [aniyomi-extensions-inspector](https://github.com/komikku-app/aniyomi-extensions-inspector), and force-pushes the result (`apk/`, `icon/`, `index.min.json`) to the `repo` branch.
- If no signing key is configured, APKs are signed with the Android debug key — this works fine for a personal repo, but updates require the same key, so keep it consistent.

### Optional: release signing

Create a keystore and add these repository secrets to sign with your own key:

| Secret | Description |
| --- | --- |
| `SIGNING_KEY` | Base64 of your `.jks` keystore (`base64 -w0 key.jks`) |
| `ALIAS` | Keystore alias |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

## 🧑‍💻 Build locally

Requires JDK 17 and the Android SDK.

```bash
./gradlew :src:en:miruro:assembleRelease
```

The APK is output to `src/en/miruro/build/outputs/apk/release/`.

## Credits

- Extension source: [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions)
- Build system: [keiyoushi](https://github.com/keiyoushi/extensions-source) / yuzono

## License

Apache-2.0 — see [LICENSE](LICENSE).
