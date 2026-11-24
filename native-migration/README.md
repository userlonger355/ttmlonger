# Native migration — TTMLonger 0.0.1a (dev buld)

This branch contains a reproducible import recipe, not the complete upstream tree. The full prepared source bundle is attached to the conversation; the original user-provided `MargyT-main (2).zip` is required to restore bundled font assets and reproduce the complete tree. The prior LSPatch module and main branch are unchanged.

Source ZIP SHA-256: `ff3872ec999be2ccabbf98739d3ae94d4e47041148a3de8f91c5ec1f65702ac9`. Original version: 2.3.0. Native anchors include TikTok 47.2.41. Do not assume 47.9.3 compatibility.

```sh
python3 -m pip install Pillow
python3 native-migration/prepare_native_fork.py 'MargyT-main (2).zip' native-work
cd native-work/margyt
python3 -m margyt /path/to/official.apk -o build/TTMLonger-native.apk
```

The import retains the upstream GPL-3.0 license and additional terms, author and support links, original native patcher, API, features and UI. Only five upstream files are modified for branding, attribution and disabling the upstream APK update source; the distinct default icon is generated locally. The upstream internal version and package names remain for compatibility. Do not use --test. Network-dependent upstream services are not migrated.

Original host tests passed locally: 17 (ad filter 1, date overlay 1, offline native 3, plugin audit 1, glass video 4, comment selection 3, streak repository 1, tab count 1, brightness 2). These are Java/Python fixtures, not live TikTok or Motion Blur MTP tests. Native APK has not been built or installed. Source snapshot has 681 unchanged original files and five modified originals. Fork maintainer: @Ueod5; upstream author: @narezany. Modified work, not an official MargyT build.
