# Greeklish Keyboard

Type Greeklish, get Greek. A keyboard for **Android** and a tray app for **Windows**, powered by a tiny AI model (7 MB) that runs entirely on your device: no internet, no permissions, no ads.

`kalimera ti kaneis?` → **καλημέρα τι κάνεις;**

▶ [Watch the 26-second demo](demo/GreeklishKeyboard-demo.mp4)

## Download

Get the latest version from the [Releases page](https://github.com/Plasma3D/greeklish-keyboard/releases/latest):

- **Android**: `GreeklishKeyboard-0.4.2.apk`. Install it, open the app and follow the steps to enable the keyboard.
- **Windows**: `GreeklishKeyboard-Windows-0.2.2.exe`. Just run it; it sits in the tray (ΕΛ / EN) and works in every program.

Features: converts each word when you press Space, keeps English words in English, autocorrects typos, learns your words, Gboard-style layout with long-press symbols, number row, themes, emoji search and clipboard.

## How it works

A character-level transformer (6.7M parameters, CTC) was trained on ~10M Greek sentences turned into synthetic Greeklish in many writing styles, then exported to ONNX and quantized to int8. On Greeklish written by real people it gets 83.5% of sentences exactly right.

To retrain: install PyTorch, download the sources below into `data/`, then run the scripts in `scripts/` (`build_dataset.py` → `prepare.py` → `train.py` → `export_onnx.py`). The training data and checkpoints are not in this repository.

## Training data

Build it with:

```
python -I scripts/build_dataset.py 10000000
```

Output goes to `data/processed/`. Each line is `greeklish<TAB>greek`.

| File | What |
|---|---|
| `train.tsv` / `val.tsv` | Synthetic pairs: real Greek sentences converted to Greeklish by `scripts/greeklish.py` |
| `test_real.tsv` | Greeklish written by real people, with Greek references (AUEB gazzetta + survivorbot) |
| `test_aueb_friends.tsv` | AUEB's artificial Friends test set, for comparison with their paper |

### Sources
- **OpenSubtitles v2024 (OPUS)**: the Greek side of `el-en`, which provides casual dialogue. https://opus.nlpl.eu/OpenSubtitles. If this is published, cite P. Lison and J. Tiedemann (2016), *OpenSubtitles2016*.
- **AUEB Greeklish** (Apache-2.0): https://github.com/nlpaueb/greeklish. We use the Greek text of Europarl, para5 and Friends, plus their test sets. Paper: *Still All Greeklish to Me*, LREC-COLING 2024.

### How the Greeklish is made
Each sentence gets one random "writer style", plus 3% letter-level noise:
- θ → th / 8 / u
- ω → o / w / v
- ξ → ks / x / j / 3
- η → i / h
- ου → ou / u
- αυ → av/af (by sound) / au / ay
- και → kai / k / ke
- `;` → `?`

Accents are always dropped. Casing is lower / sentence / kept, and the Greek target follows the same casing. 20% of samples are cut into 1–6 word fragments, because a keyboard usually sees partial text. Test sentences are never used for training.

## Android app

`android/` contains the keyboard (Java, ONNX Runtime 1.31.1, minSdk 26). It has **no permissions at all**: the manifest removes the INTERNET permission that ONNX Runtime declares.

Build it with JDK 21, Gradle 8.9 and Android SDK 35 (set `ANDROID_HOME`):

```
gradle -p android assembleDebug
```

To update the model: train, then run `scripts/export_onnx.py <checkpoint>` and copy `export/greeklish.int8.onnx` and `export/vocab.json` into `android/src/main/assets/`.

| File | Role |
|---|---|
| `GreeklishIME` | Keyboard service: composing word, commit/undo, fixing the previous word, panels |
| `Converter` | Runs the model and does script-consistent CTC decoding (a port of `scripts/model.py`) |
| `KeysView` | Gboard-style keys: previews, long-press, multi-touch, backspace swipe, space-bar cursor |
| `StripView` | Toolbar / 3-suggestion bar |
| `EmojiPanel`, `ClipboardPanel` | Emoji grid with recents; clipboard history (1 hour, on-device) |
| `SetupActivity` | Enable / switch / try it / settings |

## Windows app

`windows/` is a C# (.NET 10) system-tray app that converts Greeklish to Greek in **any** Windows program. It uses the same `greeklish.int8.onnx` model as the phone.

Build it with the .NET 10 SDK:

```
dotnet publish windows\GreeklishKeyboard.csproj -c Release -o windows\publish
```

The output is one self-contained `GreeklishKeyboard.exe` (~66 MB). Nothing needs installing.

| File | Role |
|---|---|
| `Engine.cs` | Keyboard and mouse hooks. It buffers the typed word, replaces it on Space/Enter, handles Tab (alternative), Esc (keep Latin) and Backspace (undo), and quietly fixes the previous word |
| `Converter.cs` | ONNX model and decoding (a port of the Android `Converter.java`) |
| `Bubble.cs` | Suggestion bubble at the text cursor. It never takes focus and lets clicks through |
| `Accessibility.cs` | UI Automation check that keeps the app out of password boxes |
| `Program.cs`, `SettingsForm.cs`, `AppSettings.cs` | Tray icon (ΕΛ/EN), menu, hotkey, settings (`%APPDATA%\GreeklishKeyboard\settings.json`), start with Windows |

The app ignores only the keys it types itself (tagged with `Native.OwnInputTag`), so it also works with on-screen keyboards and Remote Desktop.

## English detection and autocorrect (phone + Windows)

- **English detection** (`EnglishDetector`): `scripts/build_english.py` compares word frequencies in English subtitles with the Greeklish training text, and writes `english.tsv` (word → ln(P_en/P_greeklish)).
  - Clearly English words stay as typed.
  - Words used in both languages (`to`, `me`, `i`) follow the sentence.
  - A clearly English word turns the previous ambiguous one back to English (`do you`).
  - `scripts/test_english.py` checks the rule.
- **Autocorrect** (`Autocorrect`): `scripts/build_greek_dict.py` builds `greek_words.tsv`, 120k words with counts.
  - If the AI's word isn't in the dictionary, it's replaced with the closest common word, compared by how it sounds in Greeklish (η/ι/υ = i, ο/ω = o).
  - The AI's own version is offered as the alternative.
  - Real words only get a missing accent added (ευχαριστω → ευχαριστώ).
- Both can be turned off in Settings.

## Support

The apps are free. If they help you, you can [donate with PayPal](https://www.paypal.com/donate/?business=gnnsnsts%40gmail.com&currency_code=EUR&item_name=Greeklish%20Keyboard&locale.x=en_US).

## License

Code and model: [MIT](LICENSE). Training data sources keep their own licences (see above).
