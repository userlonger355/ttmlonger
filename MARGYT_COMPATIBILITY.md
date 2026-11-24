# Compatibility notes — MargyT reference

Reference: user-provided MargyT archive, GPL-3.0-or-later with ADDITIONAL-TERMS.md. Its README identifies narezany as author and explains the project is a set of patches rather than a TikTok APK. Source project: https://github.com/narezany/MargyT. This change independently adapts integration points to TTMLonger's existing Xposed implementation; it is not a wholesale import of MargyT source or a claim of equivalence.

- Feed.java / dexpatch.py: list getter, direct items access and ingress; SearchMixFeedList.mItems. Runtime constructor, setter and known-page Gson results supplement existing hooks. Direct iget rewriting is not available through these hooks; actual live-ad coverage needs verification.
- Stickers.java: typed StickerTemplate click entry points, including sheet/corner callbacks. Keep original native handler; do not reuse stale URL when current model is unreadable.
- PhotoModeImageUrlModel: prefer the available native fields and getter alternatives for each image. Keep existing download options.
- NativeStreaks.java / Streaks.java: known typed service accessor and bounded read-only verification. A submission receipt is not proof of extension. Only an observed transition from grey to active counts; no retry after an unconfirmed submission and no server status override.
- Obfuscated names in MargyT are documented for older TikTok versions; verify against actual 47.9.3. Current Android 17 device remains untested.
