# Zune Organizer

**Browse and tidy the music that is already on a Zune, from an Android phone over USB OTG.**

A companion to [Zune Android](../README.md) (same libraries, same Zune look). Zune Android sends music *to* a Zune;
Zune Organizer edits what is *on* it, in place: nothing is downloaded or re-uploaded, and play counts and ratings are kept.

## What it does

- Reads the Zune's library and lists it by **artists, albums, genres** or as a flat **songs** list (search box, `no:album`,
  `no:artist`, `no:genre`, `no:year` filters; long-press to select, ALL / NONE).
- **Tag editor** (one or many songs): title, artist, album, album artist, genre, year, track number. Every field has a
  drop-down of the values already in the selection (MediaMonkey style) or you type a new one. Only fields you change are
  written; untouched fields are never sent back.
- **Rename / merge** whole artists, albums or genres to one name picked from the selection or typed.
- **Songs without an album** can be put into one: the album is created on the Zune (or an existing album with the same name
  and artist is joined) and the songs are linked to it, the same steps the upload path uses for a new album.
- **Suggest from MusicBrainz** (single song): looks the song up by title, artist, album and duration, lists the
  matching releases (a song is often on both a soundtrack and the artist's own album), and lets you tick which of
  artist, album, year, track number and genre to take. It only fills the editor; you still press Apply.
- **Auto-fill** (many songs): the same lookup for a whole selection. When a song is found, that release's full track list is
  downloaded once and the other songs are matched against it by length and title, so an album costs about two requests
  instead of one per song. Only confident matches (length agrees to 1.5 s, artist agrees) are offered, in one review list.
- Shows the Zune's "unknown artist" GUID (`{09316835-3188-481c-a359-0f8d2a937ac8}`) as *Unknown artist* and converts it back on write.
- Cancel button, per-transfer USB timeouts and a watchdog, because a Zune can stop answering for a while after some edits.

## Status

Tested on one **Zune 30** (USB `045e:0710`, "Keel", Classic mode) from a POCO F7, library of about 2,200 songs.

- Verified: reading the library; changing genre, artist, album artist, album name, year, track number; creating an album for
  songs that had none and adding more songs to it; reading back what the Zune stores after each write.
- **Not verified / not supported:** the Zune HD (album and artist edits are refused there), converting or deleting songs,
  anything other than the Classic-family layout.
- Known quirk: after artist or album edits the Zune can ignore a new USB session for a minute or more (probably while it
  updates its library). The app waits 30 s after an edit and retries for several minutes; Cancel is always available.
- Known quirk: the library database parser in XuneSyncLibrary skips artist records created by an edit, so the app reads each
  song's own artist/genre from the device for songs whose database value is blank or a GUID.
- **Risk:** this writes metadata the Zune's firmware also manages. Try it on a library you can afford to reset.

## MTPZ credentials

Like Zune Android, the Zune only accepts writes from a client that authenticates with an MTPZ key file. **This repository
does not contain that file.** Unlike Zune Android, Zune Organizer has no setup screen for it: put your own `mtpz-data`
at `organizer/app/src/main/assets/mtpz-data` before building (it is git-ignored). Without it the app can read but edits fail.

## Building

Same toolchain and third-party sources as Zune Android (see the main README: `XuneSyncLibrary/`, `deps/`, patches
`0001`-`0003`; patch `0003` is needed here and adds USB timeouts). OpenSSL's `libcrypto.so` and the library built from
`organizer/native/` go into `organizer/app/src/main/jniLibs/<abi>/`; TagLib is not needed.

```
cmake -S organizer/native -B build-arm64-v8a -G Ninja -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake ^
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
cmake --build build-arm64-v8a            # produces libzuneorganizer.so
cd organizer && gradle :app:assembleDebug -PphoneOnly
```

`native/CMakeLists.txt` looks for `XuneSyncLibrary/`, `deps/` and Zune Android's `native/` (for `shim/` and `version_stub.cpp`)
in the repo root; pass `-DZUNE_ROOT=<folder>` if they live elsewhere. App log lines also go to logcat under the tag `ZuneOrganizer`.

## Network use and credits

Only the MusicBrainz lookups use the network, and only for the song you ask about: title, artist, album and duration are sent to
`musicbrainz.org` (the app identifies itself by this repository's address, as MusicBrainz asks, and keeps to about one request per
second). Song data comes from [MusicBrainz](https://musicbrainz.org). The Zune protocol work is
[XuneSyncLibrary](https://github.com/xune-software/XuneSyncLibrary) by the xune-software team (LGPL-2.1), the same as Zune Android.
