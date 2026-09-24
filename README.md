# Shelf (placeholder name)

A personal Android e-book reader in the spirit of ReadEra: a tidy modern bookshelf, series
grouping, 繁→简, read-aloud, translation. Everything runs on the phone; there is no server.

## Features

**Bookshelf**
- Grid / list / compact views; tabs on the main page: 书架 · 最近阅读 · 最近添加 · 作者 · 系列 · 已读完
- Sort by recently read / added, title (pinyin order), author, progress; search by title, author or series
- 继续阅读 card for the book you're in the middle of
- Series grouped automatically (EPUB3 collections, `calibre:series`, and titles like 第二卷 / 卷三 / （三）/
  上中下 / Vol. 2 / Book 3), shown as stacks; long-press a book to edit title / author / series / volume
- Import files or whole folders, "open with" / "share to" from other apps, and online catalogs (OPDS:
  Project Gutenberg built in, add your own Calibre server)

**Formats**: EPUB, TXT (UTF-8/16, GB18030/GBK, Big5; chapters detected automatically)

**Reading**
- Paginated or scrolling; themes (日间 / 护眼 / 夜间), font, size, line height, margins; volume keys turn pages
- Horizontal layout for 繁體 / Japanese books by default (竖排 toggle available)
- 目录 · 书签 · 笔记 (highlights with notes and colors), search inside the book
- 繁→简 per book or for all 繁體 books (OpenCC dictionaries; detected automatically)
- Select text → 查词/翻译 (offline ML Kit translation + any installed dictionary app), 高亮, 笔记, 复制
- 整本翻译: 双语对照 or 仅译文, English/other ↔ 中文, on-device, cached per chapter
- 朗读 with the phone's TTS engine: sentence highlighting, speed, voice choice, keeps going with the
  screen off, lock-screen controls

## Install on your phone

1. Copy `app/build/outputs/apk/release/app-release.apk` to the phone (AirDrop-style apps, WeChat file
   transfer to yourself, USB, or a cloud drive).
2. Open it on the phone; allow "install unknown apps" for the app you opened it from when asked.
3. Or with USB debugging on: `adb install -r app/build/outputs/apk/release/app-release.apk`.

Translation downloads each language model (~30 MB) the first time it's used, and needs Google Play
services (standard on phones sold outside mainland China).

## Build

```bash
cd reader-android
./gradlew assembleRelease          # signed if keystore.properties exists, else debug-signed
./gradlew testDebugUnitTest        # converter, series detection, TXT parsing
python3 tools/make_test_books.py /tmp/books   # sample EPUBs covering the series cases
```

Needs JDK 17 and the Android SDK (platform 37). `gradle.properties` bypasses a local proxy set in
`~/.gradle/gradle.properties`; remove those two lines if you need that proxy.

**Signing key**: `keystore/shelf-release.jks` + `keystore.properties` are gitignored. Back them up:
Android only installs updates signed with the same key (otherwise uninstall first, losing the library).

Quick testing without the file picker: `adb push book.epub /sdcard/Android/data/com.qshlab.shelf/files/inbox/`
and reopen the app; files there are imported on launch.

## Architecture

- Kotlin, Jetpack Compose (Material 3), Room (schema v3 with auto-migrations), DataStore
- [Readium Kotlin toolkit](https://github.com/readium/kotlin-toolkit) 3.4: EPUB parsing, navigator, search,
  TTS, OPDS. TXT is converted to EPUB at import (`data/TxtToEpub.kt`).
- 繁→简 and translation transform chapter HTML as it loads (`TransformingContainer`), never the file
- `reader/ReadAloudService` wraps the TTS player in a Media3 session for background playback

## Not yet

- PDF, MOBI/AZW3, FB2, DOCX, CBZ
- Built-in neural voices (sherpa-onnx) — today it uses the phone's TTS engine
- Reading statistics, cloud sync, backup / restore
