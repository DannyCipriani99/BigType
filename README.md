# BigType

BigType is a modest text editor—I wouldn’t call it a complete one.

It addresses issues that arise with various applications and their text input fields; sometimes, an app might have a small, poorly designed text box that makes typing difficult. Alternatively, if you are writing a post on Reddit and need to make adjustments or change specific terms, this tool will prove useful. It also supports Regex (regular expressions).


When you focus on any text field in any application, a **small floating button** appears next to it. Tapping this button opens a **floating writing panel**, and the "Transfer text to field" button inserts your text into the original field.

## Features
- Search and replace with support for Regex, case sensitivity, whole-word matching, `$1` capture groups, and `\n` for newlines.
- Undo/redo functionality, along with counters for characters, words, and lines.
- Automatic draft saving upon closing; the original content is also preserved during transfer, allowing for easy retrieval.
- Text tools: remove whitespace, empty lines, or duplicate lines; sort, reverse, merge, or number lines; change case (uppercase/lowercase); remove diacritics (tashkeel); normalize Arabic characters (Alif and Ya variants); and convert between Arabic-Indic and Western-Arabic numerals (0123 ⇄ ٠١٢٣).
- "Transfer + Enter" shortcut for search fields (Android 11+).
- Adjustable text direction (auto/right-to-left/left-to-right) and font size.
- Options to exclude specific apps, always ignore password fields, and optionally ignore numeric input fields.

## Getting Started
Enable the service via your device's Accessibility settings. On Android 13+ (if installed manually):
Go to App Info ← ⋮ (menu) ← "Allow restricted settings."
