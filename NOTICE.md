# Credits and third-party notices

Clicker reimplements published protocols. No code was copied from these projects, but their
work made this possible:

- **[pyatv](https://github.com/postlund/pyatv)** (MIT): the reference for Apple's Companion
  protocol, OPACK, and HAP pair-setup / pair-verify. Unit-test vectors in
  `app/src/test/.../ReferenceVectors.kt` were generated with pyatv and srptools
  (`scripts/generate_vectors.py`).
- **[aiowebostv](https://github.com/home-assistant-libs/aiowebostv)** (Apache-2.0): the reference
  for LG's webOS SSAP API. `app/src/main/assets/webos_registration.json` is the registration
  manifest published in aiowebostv's `handshake.py`; the manifest itself is LG's.
- **[Flipper Zero firmware](https://github.com/flipperdevices/flipperzero-firmware)** (GPL-3.0):
  the Kaseikyo address/command layout used by Flipper-IRDB files. Clicker's encoder is an
  independent implementation of that layout.
- **[Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB)**: the Denon soundbar codes.

Online services used by the optional features:

- **[TVmaze](https://www.tvmaze.com/api)**: show data and air dates, under CC BY-SA. Credited on
  the Shows pages.
- **[TMDB](https://www.themoviedb.org)**: trending shows. This product uses the TMDB API but is
  not endorsed or certified by TMDB. Streaming availability is
  **[JustWatch](https://www.justwatch.com)** data, provided through TMDB.
- **[Pushover](https://pushover.net)**: help alerts.

Libraries shipped in the app: AndroidX / Jetpack Compose and Room (Apache-2.0), Kotlin
coroutines (Apache-2.0), OkHttp (Apache-2.0), Bouncy Castle (MIT-style Bouncy Castle licence).

Apple TV, tvOS, LG, webOS, Denon, Haptique and the streaming services named in the app are
trademarks of their owners. Clicker is not
affiliated with or endorsed by any of them.
