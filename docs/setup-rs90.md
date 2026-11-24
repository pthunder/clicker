# Setting up Clicker on a Haptique RS90

This guide takes an RS90 from the box to Clicker running as its home screen. It takes about
20 minutes. Most of it also applies to any Android 8+ phone or tablet; only the IR parts need a
device with an IR emitter.

Clicker sits beside the stock software. It doesn't replace Android or touch the bootloader (the
RS90's is locked). Uninstalling Clicker returns the remote to its stock launcher; the show-list
backup in `Documents/Clicker` stays until you delete it.

## What you need

- The RS90, charged, on the same Wi-Fi as your TV and Apple TV.
- A computer with [Android platform tools](https://developer.android.com/tools/releases/platform-tools)
  (`adb`) and a USB-C cable.
- The Clicker APK: from this repository's Releases page when one is published, from the
  `clicker-debug-apk` artifact of the latest *Build* run under Actions, or built yourself (see
  the README).

## 1. Turn on USB debugging

1. Open **Android Settings** on the remote. If the stock launcher hides it, connect over USB
   after step 2 and run `adb shell am start -a android.settings.SETTINGS`.
2. **About phone** (or *About device*) **→ Build number**: tap it seven times until it says you're a developer.
3. **System → Developer options → USB debugging**: on.
4. Plug the remote into the computer and accept the "Allow USB debugging?" prompt on the remote.

Check the connection:

```bash
adb devices
```

## 2. (Optional) Keep a copy of the stock app

The RS90's software can't be backed up as a whole, but you can save the stock app before
experimenting:

```bash
adb shell pm path com.cantata.remote
```

It prints a line like `package:/data/app/~~abc==/com.cantata.remote-xyz==/base.apk`. Copy the
path after `package:` and pull it:

```bash
adb pull <that path> haptique-stock.apk
```

## 3. Install Clicker

```bash
adb install -r clicker.apk
adb shell am start -n dev.clicker.remote/.MainActivity
```

**The network prompt (important).** The RS90 runs MediaTek's network permission control. The
first time Clicker goes online, and again after **every** update or reinstall, it shows
*"Clicker needs to access the network…"*. Tap **Allow** (or **Wi-Fi only**). Until then every connection silently times out: no
artwork, devices show offline, show search finds nothing.

If the prompt was missed or Clicker still can't connect although *Settings → Network & internet
→ Network data controller* lists it as allowed, **restart the remote**. That clears it. Avoid
force-stopping Clicker while the prompt is on screen.

## 4. Make Clicker the home screen (optional)

So the remote wakes up straight into Clicker:

```bash
adb shell cmd package set-home-activity dev.clicker.remote/.MainActivity
```

To go back, choose the stock launcher under *Android Settings → Apps → Default apps → Home app*,
or uninstall Clicker. Clicker's **Settings → Other apps** has **Haptique app** and **Android
settings** buttons, so neither is ever out of reach.

## 5. Connect your devices

All of this is in Clicker's **Settings** (the gear on the Apps page).

- **Apple TV** (tvOS 15+): *Find*, pick it, *Pair*. Type the 4-digit code from the TV **within
  about 30 seconds**; tvOS gives up after that and the pairing fails with "not set up".
- **LG webOS TV**: *Find* (or type its address), *Connect*, then accept the prompt on the TV. To
  let **On** wake the TV over Wi-Fi, turn on the TV's *Turn on via Wi-Fi* (or *Mobile TV On*).
- **After On, switch the TV to the Apple TV's input**: pick the HDMI port the Apple TV uses.
  After **On**, Clicker switches the TV to it once the TV's network control is up.
- **Volume**: Apple TV (HDMI-CEC, best when the TV and soundbar follow CEC), the LG TV, or a
  soundbar over IR. **Mute** uses the soundbar's IR code, because tvOS has no mute command.

Addresses can also be set from the computer:

```bash
adb shell am start -n dev.clicker.remote/.MainActivity --es atv_host 192.168.1.20 --es lg_host 192.168.1.21
```

## 6. The RS90's buttons

| Button | Does |
|---|---|
| Power (reports `F1`) | everything on or off |
| D-pad, OK | navigate the Apple TV or TV |
| Back | Back (Menu on the Apple TV) |
| Menu | Home |
| Volume +/− (hold to repeat) | the volume target chosen in Settings |
| Mute | mute |
| Play/Pause | play/pause |
| Page / Channel up and down | up and down |

Other keys show up in **Settings → Hardware key tester**, with their Android key code and scan
code, if you want to map them.

## 7. Shows (optional)

- Search and follow shows on the **Shows** page. Air dates come from TVmaze and need no setup.
- **Trending** needs a free TMDB account: themoviedb.org → *Settings → API* → request a key
  (personal use), then paste the **API Read Access Token** under Clicker's Settings → Show list.
- A long list is quicker to prepare on a computer. See *Show tracking* in the README for the
  file format and the `adb push` command.

## 8. Help alerts (optional)

The **Help** page sends big-button requests ("Water please") to a phone as
[Pushover](https://pushover.net) emergency alerts, which repeat every 30 seconds until someone
taps *Acknowledge*. The remote then shows when it was seen.

1. Install Pushover on the phone that should be alerted and sign up (free trial, then a
   one-time purchase per platform).
2. On pushover.net, copy your **User Key**, then *Create an Application/API Token* and copy the
   **API Token**.
3. Enter them under Clicker's **Settings → Help alerts** as *Application API token* and *User
   key (whose phone)*, with the names for *From* and *To*.
   Edit the buttons there too; a line starting with `!` makes a request urgent (red button,
   siren, longer repeats).
4. On an iPhone: *Settings → Notifications → Pushover* → allow **Critical Alerts** and **Time
   Sensitive** notifications, and add Pushover to any Focus modes you use. Then test it with the
   phone on silent.

Help alerts depend on Wi-Fi, the internet and Pushover. They're for everyday requests, not a
replacement for a medical alert system.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Nothing loads, devices show red dots, artwork missing | The network prompt wasn't allowed. See step 3; restart the remote if needed. |
| Apple TV pairing says "not set up" | The code was entered after tvOS's ~30-second window. Pair again and type it straight away. |
| Apple TV connects slowly after waking | Normal for the first press after sleep; Clicker reconnects as soon as the remote wakes. |
| **On** doesn't turn the LG TV on | Turn on *Turn on via Wi-Fi* on the TV, or keep the remote pointed at it so the IR power code reaches it. |
| An update won't install ("signatures don't match") | Builds must be signed with the same key as the installed copy. See *Contributing* in the README. |
