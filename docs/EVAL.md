# Golden-set evaluation

- Date: 2026-10-11 · Model: `gemini-3.5-flash` · 43 cases · 263 s
- **Cases passed: 43/43 (100%)**
- Translation returned: 43/43
- Expression recall: 43/43 (100%)
- False positives rejected: 3/3
- Translation keyword check (no calques): 43/43

Regenerate with `uv run python scripts/eval_golden.py` (needs an LLM key in `.env`).

| Case | OK | Translation | Notes |
|---|---|---|---|
| zh-meme-work-complaint | ✓ | Guys, I can't even—my boss is making empty promises again. |  |
| zh-homophone-bengbu | ✓ | I literally can't hold it together. |  |
| zh-number-slang-price | ✓ | This sells for 666 yuan, it's too expensive. |  |
| zh-number-slang-praise | ✓ | Awesome, that move was so impressive. |  |
| zh-number-price-total | ✓ | That's 666 yuan total, I can't go any lower. |  |
| zh-abbrev-yyds | ✓ | This streamer is the GOAT! |  |
| zh-chengyu-duiniu | ✓ | Trying to reason with him is like casting pearls before swine. |  |
| zh-chengyu-huashe | ✓ | Making those changes will only be counterproductive. |  |
| zh-chengyu-yiju | ✓ | Este plan mata dos pájaros de un tiro y todos están satisfechos. |  |
| zh-single-char-trap | ✓ | The company's profits are very high. |  |
| zh-emo | ✓ | I'm feeling a bit down today. |  |
| zh-social | ✓ | He is a social butterfly, but I am socially anxious. |  |
| zh-anti-proverb | ✓ | Listening to you is like listening to you. |  |
| zh-thank-you-pun | ✓ | My boss wants me to work this weekend again, thanks for nothing. |  |
| zh-number-520 | ✓ | Today is May 20th, I love you. |  |
| zh-xiehouyu | ✓ | He does this every single day; it's the same old story. |  |
| zh-literary-allusion | ✓ | He refuses to lower his pride, still clinging to the elitism of his degree. |  |
| zh-triple-trend | ✓ | Los jóvenes están optando por no esforzarse y dejarse llevar, pero todavía hay quienes siguen atrapados en la competencia feroz. |  |
| zh-teasing-es | ✓ | Es un payaso, siempre llamando la atención. |  |
| zh-worker | ✓ | Wage slave, wage slave soul, good morning. |  |
| zh-plain-sentence | ✓ | Excuse me, how do I get to the nearest subway station? |  |
| es-idiom-nubes | ✓ | He is daydreaming and has no idea what is going on. |  |
| es-idiom-pelos | ✓ | My boss doesn't mince words. |  |
| es-idiom-pelo | ✓ | Don't pull my leg, I already know. |  |
| es-slang-fuerte | ✓ | That's crazy, I can't believe it! |  |
| es-slang-guay | ✓ | Dude, this is really cool. |  |
| es-idiom-pan-comido | ✓ | The exam was a piece of cake. |  |
| es-idiom-calabazas | ✓ | He was rejected at the party. |  |
| es-slang-mola | ✓ | This is really cool. |  |
| es-idiom-pegar-ojo | ✓ | I didn't sleep a wink last night. |  |
| es-idiom-ojo-cara | ✓ | 那块手表贵得离谱。 |  |
| es-plain | ✓ | 火车站怎么走？ |  |
| en-slang-tea-cap | ✓ | Ella soltó el chisme, sin mentiras. |  |
| en-meme-grass | ✓ | Hermano, sal a que te dé el aire. |  |
| en-slang-giving | ✓ | Tiene vibras de protagonista. |  |
| en-slang-rizz | ✓ | Tiene muchísimo carisma. |  |
| en-idiom-break-leg | ✓ | ¡Mucha suerte esta noche! |  |
| en-idiom-arm-leg | ✓ | 那辆车贵得离谱。 |  |
| en-idiom-bush | ✓ | Deja de andarte con rodeos y dímelo. |  |
| en-slang-lowkey | ✓ | 我有点厌倦这一切了。 |  |
| en-slang-mid | ✓ | Sinceramente, esa película fue mediocre. |  |
| en-slang-ghosting | ✓ | 他好几周都没理我，玩失踪。 |  |
| en-plain | ✓ | La reunión comienza mañana a las nueve. |  |
