# Monet: design system

**Concept: ink on paper, with one seal.** A calm reading surface (warm paper, ink-black text) and a single accent: **vermilion 朱红**, the color of a Chinese seal stamp. Color only appears where it carries meaning: an expression to explore, a tone, an action.

## Anti-slop rules (hard requirements)
- No gradients, no glassmorphism, no glow, no drop shadows heavier than `elevation.1`.
- No ✨ sparkles or "AI" iconography, no emoji in the UI, no stock illustrations or mascots.
- No more than **one** accent color on a screen (tone colors in pinyin are the only exception: they are data, not decoration).
- Copy is short and human: "Translating…", not "Our AI is working its magic ✨". Never say "AI" in the UI.
- Every screen must work in light, dark, 200% font scale and TalkBack.
- Source text stays visually primary; annotations are secondary in size and weight.

## Color tokens
| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | `#F7F4EE` | `#151412` | App background |
| `surface` | `#FFFDF9` | `#1E1C19` | Cards, sheets |
| `surfaceSunken` | `#EFEBE3` | `#0F0E0C` | Input fields, code-like areas |
| `ink` | `#1B1A17` | `#ECE8E1` | Primary text |
| `inkMuted` | `#5E5A52` | `#A39E94` | Secondary text, glosses |
| `inkFaint` | `#9A958B` | `#6B665D` | Hints, disabled, read lines |
| `hairline` | `#1B1A17` @ 10% | `#ECE8E1` @ 12% | Dividers, borders (1dp) |
| `seal` | `#C8402B` | `#E06A52` | **The accent.** Primary actions, meme/wordplay underline, selection |
| `sealSoft` | `#C8402B` @ 10% | `#E06A52` @ 16% | Selection fill, chips |
| `indigo` | `#2F4A7A` | `#8CA6D8` | 成语/idiom/proverb underline (semantic, not an accent) |
| `success` | `#3C7A4E` | `#7DBF8E` | Document ready |
| `danger` | `#B3261E` | `#F2B8B5` | Errors |

**Pinyin tone colors** (optional style; muted so they read as ink, not as a rainbow):
`t1 #C8402B` · `t2 #B9822E` · `t3 #3C7A4E` · `t4 #2F4A7A` · `t5 (neutral) inkFaint`. Dark mode uses the lighter variants of the same hues.

**Overlay chips** (over other apps): the background is **sampled from the frame** behind the line (median of the bbox border pixels); the text color is `ink` or `paper`, whichever reaches ≥ 4.5:1 contrast. 1dp hairline border, radius 4. This keeps translations looking like part of the page.

## Typography
Fonts are loaded via the Google Fonts downloadable-font provider (no bundled CJK fonts, to keep the APK small); the system fonts are the fallback.

| Role | Font | Size / line height | Weight |
|---|---|---|---|
| `display` | Source Serif 4 | 28 / 34 | 600 |
| `title` | IBM Plex Sans | 20 / 26 | 600 |
| `body` | IBM Plex Sans | 16 / 24 | 400 |
| `label` | IBM Plex Sans | 13 / 18 | 500, +0.2 tracking |
| `reading` (translations, document text) | Source Serif 4 | 18 / 28 | 400 |
| `hanzi` (source zh in Detail sheet) | Noto Serif SC (TC for Traditional) | 26 / 40 | 500 |
| `ruby` (pinyin over hanzi) | IBM Plex Sans | 11 / 14 | 500 |
| `ipa` | IBM Plex Sans | 12 / 16 | 400 italic |

## Space, shape, motion
- Spacing on a 4dp grid: `4, 8, 12, 16, 24, 32, 48`. Screen gutters are 20dp.
- Radius: `xs 4` (chips) · `sm 8` (buttons, inputs) · `md 12` (cards) · `lg 20` (sheet top corners).
- Elevation: `0` by default, `1` for floating bubble/cards over other apps (2dp, 12% shadow). Separate with hairlines, not shadows.
- Motion: `short 120ms` (taps), `medium 200ms` (chips appear, band moves), `long 280ms` (sheet). Easing: standard decelerate. Respect *Remove animations*.
- Haptics: light tick when the reading band advances; none elsewhere.

## Components

**Bubble.** 48dp circle, `surface` with hairline. The glyph is a small seal-red square "印" mark (custom vector, not a stock icon). It snaps to the screen edge, and idles at 60% opacity after 3 s. Long-press = Off.

**Mode pill.** A segmented control `Auto | Select | Off` plus a lines-per-step stepper (`– 1 +`).

**Reading band.** A full-width translucent `sealSoft` band behind the active lines, a 2dp `seal` rule on its left edge, and a ⌄ handle bottom-right. Read lines fade to `inkFaint` over 200 ms.

**Translation chip** (overlay). It is positioned over or just below its source line, uses the `reading` font scaled to fit the line height (min 12sp), and expands on tap.

**Expression underline.** 1.5dp: `indigo` dotted for 成语/idiom/proverb/classical; `seal` wavy for meme/slang/wordplay.

**Detail sheet.** Three sections divided by hairlines and titled with small-caps `label`s: MEANING · BREAKDOWN · WHY IT SAYS THAT.
- Breakdown tokens are laid out as a flow row; each token is ruby + hanzi + gloss, center-aligned.
- Expression cards: a kind tag (outlined, `label`), then the **literal → meaning** line in `reading`, then origin, tone and equivalent as definition rows.
- Confidence: `✓ dictionary` vs `likely`, in `inkMuted`.

**Library row.** File-type glyph, title, pages, a status line ("Processing · 42%" with a 2dp progress rule) and an overflow menu.

**Empty states.** One line of text plus one action. No illustrations.

## Iconography
Material Symbols *Outlined*, weight 300, grade 0, 24dp. A custom glyph is used only for the bubble seal.
