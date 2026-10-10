# Monet: product spec

> Read anything on your screen and actually *get* it: idioms, memes, wordplay and tone included, not only the words.

Monet is an Android app that reads text on screen (any app), or in a PDF/DOCX you upload, and shows a **culturally-aware translation**: what the text means, how it sounds, and *why* it says what it says. Launch languages: **Chinese (Simplified + Traditional), Spanish, English**, in any direction. Chinese gets the deepest treatment (成语, 歇后语, 网络梗, 谐音梗, classical quotations).

"Translation" in this doc always means the full package: natural translation + phonetics + cultural explanation.

---

## 1. Principles

1. **Meaning over words.** A literal translation of 绝绝子 or "no cap" is a bug.
2. **Honest uncertainty.** If Monet is unsure whether something is a meme, it says *"possibly a meme"* rather than inventing a story.
3. **Private by design.** Screen captures and OCR stay **on the device**. Only the recognized text leaves the phone, and only when a translation is needed.
4. **Calm UI.** The source text stays primary. Monet annotates the page; it does not cover it in chrome.
5. **Fast first, deep on demand.** The overlay shows a fast translation (target < 1.5 s, cached < 150 ms). The full breakdown loads when the user asks for it.

## 2. Surfaces

### 2.1 Lens (overlay over any app)
A small floating **bubble** (draggable, snaps to the screen edge) starts and controls the Lens. Tapping the bubble opens a compact **mode pill**: `Auto · Select · Off`.

**Auto mode**
- Monet captures the screen when it changes (scroll settles for ~300 ms, or the frame diff passes a threshold) and runs OCR on the device.
- Translations are drawn **in place**: each source line gets a translation chip aligned to its bounding box. The chip color is sampled from the background so the result looks like the page itself, not a sticker.
- **Reading band.** The user sets *lines per step* (1, 2, 3, 5, 10 or All) in Settings or from the pill.
  - Only the lines inside the band are translated and shown. Lines already read are dimmed slightly; upcoming lines are untouched.
  - **Next step**: tap the band's ⌄ handle, swipe up on the band, or tap the bubble. The band jumps to the next N lines and skips the ones already read. **Previous step**: swipe down.
  - When the band reaches the bottom and the user scrolls, it re-anchors on the first unread line.
- Expressions are underlined: **dotted** for 成语/idioms/proverbs, **wavy** for memes/slang/wordplay. One tap opens the Detail sheet focused on that expression.

**Select mode**
- The frame freezes (slightly dimmed) and the user **drags to draw a selection**: a rectangle, or a highlighter stroke across lines. Only the selected text is translated.
- The result appears as a card anchored to the selection.
- Tap outside to dismiss and unfreeze.

**In every mode:** a long-press on any recognized text opens the **Detail sheet** for that line or selection.

### 2.2 Detail sheet (the heart of Monet)
A bottom sheet with three layers, shown in this order:

1. **Meaning**: the natural translation in the user's target language. ▶ plays the source audio, and a second tap plays it at 0.7×.
2. **Breakdown**: the source text token by token.
   - Chinese: hanzi with **ruby pinyin** (tone marks, numbers or tone colors, per Settings).
   - English/Spanish: IPA under each word.
   - Each token: short gloss; tap a token to hear it alone.
3. **Why it says that**: one card per detected expression:
   - kind tag (成语 · meme · slang · wordplay · proverb · classical),
   - literal reading → actual meaning,
   - origin (story, source text, year or platform for memes),
   - tone/register (ironic, affectionate, vulgar, formal, dated…),
   - a **closest equivalent** in the target language, when one exists,
   - confidence (dictionary-backed ✓ vs model-inferred "likely").

Actions: **Save to Notebook**, **Copy**, **Share**, **Report a bad translation** (sends the text and an optional comment; feeds the quality loop).

### 2.3 Library (documents)
- Upload **PDF, DOCX, TXT** (later: EPUB, images). Upload from the Android share sheet or the file picker.
- Each item shows its state: `Uploading → Processing (n%) → Ready` or `Failed (retry)`. Processing runs on the server, so the user can leave the app.
- **Reader**: tap a Ready document.
  - PDF pages render natively.
  - DOCX/TXT render as clean reflowable text.
  - Same `Auto / Select` modes and reading band as the Lens.
  - Long-press opens the Detail sheet.
  - The reading position is remembered.
- Translations are precomputed for the whole document in the user's target language at processing time, so navigating feels instant. Changing the target language triggers background re-processing; the user sees the old language until the new one is ready.

### 2.4 Notebook
Saved expressions and lines with their full Detail data, which work offline once saved. A later version adds spaced-repetition review (daily, 5–10 cards).

### 2.5 Settings
- Target language; source language (Auto | zh | es | en); Chinese script preference.
- Pinyin style: tone marks / numbers / tone colors / hidden.
- Lines per step (1, 2, 3, 5, 10, All); overlay opacity; chip text size.
- TTS speed; voice per language (from the system TTS engines).
- Excluded apps (the Lens never captures them; banking apps are excluded by default).
- Theme: system / light / dark.

## 3. Onboarding (≤ 4 screens, skippable)
1. Pick your target language.
2. Explain the Lens with a looping 3-second animation, then ask for the *display over other apps* permission.
3. Ask for screen capture only when the user first turns the Lens on: Android shows its own system dialog, and Monet explains it right before.
4. "Try it": a sample screen full of memes for the user to play with.

## 4. Quality bar for "understanding"
Monet must handle these correctly; they form the golden set in `services/api/tests/golden/`.
- 成语 used literally vs figuratively (e.g. 对牛弹琴, 画蛇添足).
- Internet slang: yyds, 绝绝子, 破防, 躺平, 内卷, 摆烂, 社死, 凡尔赛, 栓Q, 芭比Q了, 蚌埠住了, i人/e人.
- Homophone puns (谐音梗): 蚌埠住了 = 绷不住了, 夺笋 = 多损, 栓Q = thank you.
- Number slang: 666, 520, 886, 233.
- Spanish/English idioms and slang: "estar en las nubes", "no tener pelos en la lengua", "flipar"; "no cap", "spill the tea", "touch grass", "it's giving".
- Context dependency: 666 in a gaming chat vs. a price.

## 5. Non-goals (v1)
- iOS. The OS does not allow overlays or continuous capture over other apps. A future iOS version could offer documents and share-sheet translation only.
- Real-time video subtitles.
- Handwriting.

## 6. Milestones
| # | Milestone | User-visible result |
|---|---|---|
| M0 | Foundations | Repo, CI, contract, design system, toolchain |
| M1 | Brain | `/v1/annotate` returns pinyin, translation and expression explanations; a web playground to test quality |
| M2 | App shell + Detail sheet | Type, paste or share text into Monet → full Detail sheet with audio |
| M3 | Lens: Select | Bubble + screen capture + on-device OCR + drag-to-select over any app |
| M4 | Lens: Auto + Reading band | In-place translations, N lines per step |
| M5 | Library | Upload PDF/DOCX → processed → Reader with both modes |
| M6 | Accounts, Notebook, deploy | Sign-in, synced notebook, backend deployed on a free tier |
