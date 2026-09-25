# Shelf (placeholder name)

A personal Android e-book reader in the spirit of ReadEra: a tidy modern bookshelf, series
grouping, 繁→简, read-aloud, translation. Everything runs on the phone; there is no server.

## Features

**Bookshelf**
- Grid / list / compact views; organize 按系列 (default) / 按书 / 按作者; sort by recently read / added,
  title (pinyin order), author, progress; filter by 在读 / 想读 / 未读 / 已读完, language, format; search
- 继续阅读 card for the book you're in the middle of
- Series grouped automatically (EPUB3 collections, `calibre:series`, and titles like 第二卷 / 卷三 / （三）/
  上中下 / Vol. 2 / Book 3), shown as stacks
- 整理系列: title-based suggestions for unlabeled books ("星海", "星海Ⅱ：暗潮" → 星海), confirmed by you
- Long-press to select several books: 设为系列 (with volume order), 想读, 已读完, delete; a series page
  has 整理 to rename / reorder / dissolve it
- 扫描手机: finds EPUB / TXT in shared storage (formats and TXT minimum size configurable), tick what to
  import; also multi-file / folder import, "open with" / "share to", and online catalogs (OPDS: Project
  Gutenberg built in with 中文书 / 日文书 shortcuts and 简→繁 search, add your own Calibre server)
- App settings (gear icon): AI voices, scan formats, reading defaults, storage use

**Formats**: EPUB, TXT (UTF-8/16, GB18030/GBK, Big5; chapters detected automatically)

**Reading**
- Paginated or scrolling; themes (日间 / 护眼 / 夜间), 黑体 / 宋体 / the book's font, size, line height,
  margins; volume keys turn pages (except while reading aloud)
- Books look alike: bold body text, odd sizes, letter spacing and heading fonts from the book's CSS are
  evened out (unless 使用书籍自带排版 is on)
- Settings open as a side panel, so the page reflows next to it while you adjust
- Per book: 横排, or 竖排 · 日式 (right-to-left); Japanese books published vertical open that way
- 目录 · 书签 · 笔记 (highlights with notes and colors), search inside the book
- 繁→简 per book or for all 繁體 books (OpenCC dictionaries; detected automatically)
- Select text → 查词/翻译 (offline ML Kit translation + any installed dictionary app), 高亮, 笔记, 复制
- 整本翻译: 双语对照 or 仅译文, English/other ↔ 中文, on-device, cached per chapter
- 朗读 with on-device AI voices (sherpa-onnx): Kokoro 中英文 (100 voices, ~195 MB download from Hugging
  Face, GitHub fallback), or light Piper voices (小雅 中文, Amy English); falls back to the phone's TTS
  engine for other languages. Sentence highlighting, speed, voice choice with preview, keeps going with
  the screen off, lock-screen controls

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
python3 tools/make_test_books.py /tmp/books   # sample EPUBs: series cases, Japanese vertical, heavy CSS
```

Needs JDK 17 and the Android SDK (platform 37). `gradle.properties` bypasses a local proxy set in
`~/.gradle/gradle.properties`; remove those two lines if you need that proxy.

**Signing key**: `keystore/shelf-release.jks` + `keystore.properties` are gitignored. Back them up:
Android only installs updates signed with the same key (otherwise uninstall first, losing the library).

Quick testing without the file picker: `adb push book.epub /sdcard/Android/data/com.qshlab.shelf/files/inbox/`
and reopen the app; files there are imported on launch.

## Architecture

- Kotlin, Jetpack Compose (Material 3), Room (schema v4 with auto-migrations), DataStore
- [Readium Kotlin toolkit](https://github.com/readium/kotlin-toolkit) 3.4: EPUB parsing, navigator, search,
  TTS, OPDS. TXT is converted to EPUB at import (`data/TxtToEpub.kt`).
- 繁→简 and translation transform chapter HTML as it loads (`TransformingContainer`), never the file
- `reader/ReadAloudService` wraps the TTS player in a Media3 session for background playback
- `tts/AiTtsEngine` plugs sherpa-onnx into Readium's TTS navigator (clauses are synthesized while the
  previous one plays); `app/libs/sherpa-onnx-*-arm64.aar` is the official AAR trimmed to arm64
- `data/SeriesSuggester` (title-based series guesses) and `data/BookScanner` (phone scan)

## Not yet

- PDF, MOBI/AZW3, FB2, DOCX, CBZ
- AI voices for Japanese and other languages (they use the phone's TTS engine)
- Reading statistics, cloud sync, backup / restore
