# SA-MP Mobile (on Winlator Ludashi)

Play **desktop GTA San Andreas + SA-MP** on an Android phone, with a one-tap SA-MP launcher.
Built on [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) (open source, MIT),
which brings current Wine/Proton, Box64/FEX and **newest Turnip drivers through Adrenotools**,
including Adreno 8xx (Snapdragon 8 Elite family).

The previous version, built on Winlator 7.1, is on the `winlator-7.1` branch. Its Turnip
(Mesa 24.1) doesn't support Adreno 8xx.

## What it adds to Ludashi

- **SA-MP launcher as the home screen**: GTA folder, nickname, server list (IP:port), Connect.
  Favourites on first run: Valrise RPG and Vice City Roleplay.
- **Direct boot, no launcher**: a small built-in starter (`overlay/tools/sampinject`, compiled
  by the workflow with MinGW) runs `gta_sa.exe -c -n <nick> -h <host> -p <port>` and loads
  `samp.dll` from GTA's own main thread at its entry point. Only `gta_sa.exe` and `samp.dll`
  are needed; a modpack's replaced `samp.exe` is never run.
- SA-MP client version manager (0.3.7 R1-R5, 0.3.DL-R1), import other client files.
- SA-MP touch controls profile and a keyboard button for chat.
- **Crash logs** (SYSTEM > Crash logs, `Download/SA-MP-Mobile/logs`): every Connect writes a
  log with phone/GPU, container settings, gta_sa.exe size, the GTA folder listing, all Wine
  output, the game windows, how it ended, and the end of SA-MP's and mods' own logs.
  App crashes are logged too.
- "Advanced settings" opens Ludashi's full UI (containers, drivers, Adrenotools, controls).

## First start

The first Connect sends you through Ludashi's setup (Windows system, Wine/Proton, drivers).
Then the launcher creates an **SA-MP** container with Ludashi's recommended settings and your
GTA folder as drive D:.

The package id stays `com.winlator.cmod` (the Wine image is built for it), so this app can't be
installed next to Winlator Ludashi or Cmod. It can sit next to official Winlator.

## How the repository works

- `LUDASHI_COMMIT`: the Ludashi commit to build on.
- `overlay/`: new files copied into the Ludashi tree (`com.winlator.cmod.samp.*`, the
  controls profile, the starter's source).
- `patches/ludashi.patch`: the small changes to Ludashi's own files (launcher activity in the
  manifest, crash log and keyboard button hooks in `XServerDisplayActivity`).
- `.github/workflows/build.yml`: checks out Ludashi at that commit, adds the overlay, applies
  the patches, builds `sampinject.exe` and the APK, and publishes both as a pre-release.

To move to a newer Ludashi, change `LUDASHI_COMMIT` and fix the patch if it no longer applies.

`sampinject.exe` is also published on its own, to use with any Winlator version:
`sampinject.exe "<GTA folder>\gta_sa.exe" "<GTA folder>\samp.dll" -c -n Nick -h host -p port`.
