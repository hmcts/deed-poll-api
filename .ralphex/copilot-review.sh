#!/usr/bin/env bash
#
# ralphex phase-3 external review, via the GitHub Copilot CLI.
#
# We have no Codex licence, so this replaces ralphex's default codex reviewer. The contract
# (https://github.com/umputun/ralphex#custom-external-review) is:
#
#   in   — one argument: path to a file containing the review prompt. ralphex has already
#          substituted {{DIFF_INSTRUCTION}}, which on the first iteration asks for
#          `git diff main...HEAD` and on later ones for `git diff` (uncommitted fixes only).
#   out  — findings on stdout, one per line as `file:line - description`, or the literal
#          string `NO ISSUES FOUND`. ralphex hands stdout to Claude to evaluate and fix.
#
# Anything on stderr is diagnostic only and is not part of the contract.

set -euo pipefail

prompt_file="${1:?usage: copilot-review.sh <prompt-file>}"

if [[ ! -r "$prompt_file" ]]; then
  echo "copilot-review.sh: cannot read prompt file '$prompt_file'" >&2
  exit 1
fi

if ! command -v copilot >/dev/null 2>&1; then
  echo "copilot-review.sh: the copilot CLI is not on PATH (expected @github/copilot)" >&2
  exit 1
fi

# The reviewer reports; Claude fixes. Denying `write` keeps those roles apart — if copilot
# edited files here, ralphex's next iteration would diff its edits as if they were our fixes
# and the loop would chase its own tail. `shell(git push)` is denied because nothing in a
# review should ever reach a remote.
#
# --allow-all-tools is required for non-interactive mode and is what lets copilot run the
# git diff the prompt asks for. --silent restricts stdout to the agent response alone.
#
# --model is load-bearing and must not be dropped. Without it the CLI picks its own default,
# which on 2026-09-07 was `claude-sonnet-5` — so the "external" review was Claude reviewing
# Claude's own work, since the task and internal review phases run through Claude Code. That
# run found nothing in 9,391 lines, including two issues an earlier Claude review had already
# flagged, which is what correlated blind spots look like. The whole point of ralphex's
# external phase is a second opinion from a different model; codex defaults to GPT for exactly
# this reason. Verified by reading ~/.copilot/session-state/<id>/events.jsonl, not by asking
# the model what it was.
#
# `copilot help config` lists the valid identifiers.
exec copilot \
  --model gpt-5.6-sol \
  --prompt "$(cat "$prompt_file")

Output rules, which override any conflicting instruction above:
- Report each finding on its own line, formatted exactly as: file:line - description
- If you find no issues, output exactly: NO ISSUES FOUND
- Do not modify, create or delete any file. Report only.
- Do not print a preamble, a summary, or anything other than the findings." \
  --silent \
  --no-color \
  --log-level none \
  --allow-all-tools \
  --allow-all-paths \
  --deny-tool 'write' \
  --deny-tool 'shell(git push)'
