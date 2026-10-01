# Handoff: Kotlin/JVM prototype — detect terminal light/dark theme

## Goal (ONLY this, nothing more)
A tiny Kotlin/JVM CLI that, when run in a terminal, **queries the terminal once** and prints whether
it is in **light** or **dark** mode (and which method answered). No live-change subscription yet (no mode 2031 listener) — out of scope.

## Background (from investigation of how `herdr` does it)
herdr does not call any OS API. It asks the terminal over the TTY; the terminal emulator (e.g. Ghostty)
watches macOS appearance itself. Relevant escape sequences:

| Sequence | Meaning |
|---|---|
| `CSI ? 996 n`  (`ESC [ ? 996 n`) | Query color scheme. Reply: `ESC [ ? 997 ; 1 n` = dark, `ESC [ ? 997 ; 2 n` = light |
| `OSC 11 ; ? ST` (`ESC ] 11 ; ? ESC \` or BEL) | Query background color. Reply `ESC ] 11 ; rgb:RRRR/GGGG/BBBB` (ST or BEL terminated; 1-4 hex digits per channel) |
| `CSI c` (DA1) | Device attributes. EVERY terminal answers `ESC [ ? ... c`. Use it as a **sentinel**: send it last; when its reply arrives, all earlier replies that will ever come have arrived, so we never hang on terminals that don't support 996/OSC 11 |

(The Mordant library author said this is "impossible" for a library — reasons: async reply on stdin, need raw mode,
patchy terminal support, tmux/ssh passthrough. The DA1 sentinel trick is how to avoid hangs.)

## Required behavior
1. Open `/dev/tty` for read/write (not System.in, so piping stdout still works). If no tty -> print `unknown (no tty)` and exit 2.
2. Put tty into raw, no-echo mode. On JVM there's no termios: shell out to `stty` with the tty attached,
   e.g. `ProcessBuilder("sh","-c","stty raw -echo < /dev/tty")`; save the original with `stty -g < /dev/tty` and ALWAYS restore in `finally` (+ shutdown hook).
3. Write in one go: `ESC[?996n` `ESC]11;?ESC\` `ESC[c`. Flush.
4. Read bytes from /dev/tty with an overall timeout (~500 ms; use a reader thread / coroutine + timeout, since FileInputStream.read blocks) until the DA1 reply (`ESC [ ? … c`) is seen or timeout hits.
5. Parse replies out of the byte stream (they may be interleaved/fragmented):
   - `ESC[?997;1n` -> dark, `ESC[?997;2n` -> light  (method = `csi-996`)
   - OSC 11 `rgb:R/G/B` -> normalize each channel by its digit count to 0..1, compute relative luminance
     (e.g. 0.2126R+0.7152G+0.0722B, threshold 0.5) -> light/dark (method = `osc-11`, also print the color)
   - Preference: 996 answer > OSC 11 luminance > `COLORFGBG` env fallback (`fg;bg`, bg 0-6 or 8 => dark, 7 or 9-15 => light; method = `colorfgbg`) > `unknown`.
6. Output a single line like: `dark (via csi-996)` / `light (via osc-11, bg=#fafafa)` / `unknown`. Optionally `--verbose` prints every raw reply (escaped) and timing.
7. Exit code 0 on detected, 1 on unknown, 2 on no tty.

## Structure / constraints
- Separate pure parsing/luminance logic (unit-testable, no IO) from the tty IO. Write a few meaningful unit tests for the parser
  (996 dark/light, OSC 11 with 2-digit and 4-digit channels, BEL vs ST terminator, fragmented input, DA1 sentinel detection). TDD preferred.
- Build tool: the Kotlin Toolchain (formerly Amper) — load skill `/Users/Anton.Prokhorov/.agents/skills/kotlin-tooling-kotlin-toolchain/SKILL.md` and use it to scaffold (project.yaml / module.yaml, `./kotlin` wrapper). No Gradle/Maven.
- Idiomatic Kotlin, no external deps unless truly needed (no Mordant, no JLine — we are demonstrating the raw mechanism).
- Do NOT implement live updates (mode 2031), a library API, or anything beyond the one-shot query.

## Verification
- Unit tests pass.
- Run the app in this very pane (it's a herdr pane -> real PTY) and report the output. Also try `... | cat` (stdout piped) to confirm /dev/tty is used, and `< /dev/null` still works.
- Report in your final message: what terminal answered what (which of 996 / OSC 11 worked here), and the exact run command.
- Note: herdr itself sits between the app and the real terminal, so also mention whether replies came through herdr correctly.
- Commit the result in this repo (git is initialized). Do not push.
