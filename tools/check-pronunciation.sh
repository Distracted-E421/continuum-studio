#!/usr/bin/env bash
# Check pronunciation dictionary entries against the live EasyVoice API.
#
# Why this exists: the dictionary is the one place a change can look correct in
# the code and still sound wrong, and there is no way to hear the result from a
# dev machine without playing audio. This synthesizes each phrase and transcribes
# it locally so entries can be judged on evidence.
#
# ## Reading the output
#
# Whisper is a language model and cannot spell invented names: it renders
# "Synapsix" as "Synapse 6" regardless of the audio, because "Synapse" is a real
# word and a trailing sibilant becomes "6". Control words transcribe exactly, so
# the useful signals are:
#
#   1. Does the respelled clip differ from the plain one? (md5)
#   2. Is it longer? (more syllables => more time)
#   3. Does a real-word control still transcribe exactly? (sanity)
#
# ## Usage
#
#   ./tools/check-pronunciation.sh                 # default phrases
#   ./tools/check-pronunciation.sh "my-word" ...   # extra phrases to try
#
# Requirements: EASYVOICE_API_KEY (read from android/local.properties if unset),
# curl, ffprobe, and whisper-cli with an English model.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPS="$REPO_ROOT/android/local.properties"
OUT_DIR="${TMPDIR:-/tmp}/pronounce-check"
mkdir -p "$OUT_DIR"

KEY="${EASYVOICE_API_KEY:-}"
if [[ -z "$KEY" && -f "$LOCAL_PROPS" ]]; then
    KEY="$(grep -oE 'EASYVOICE_API_KEY=.*' "$LOCAL_PROPS" | cut -d= -f2-)"
fi
if [[ -z "$KEY" ]]; then
    echo "error: no EASYVOICE_API_KEY (env or android/local.properties)" >&2
    exit 1
fi

WHISPER="${WHISPER_CLI:-whisper-cli}"
MODEL="${WHISPER_MODEL:-$HOME/.cache/whisper/ggml-base.en.bin}"
if ! command -v "$WHISPER" >/dev/null 2>&1; then
    echo "error: whisper-cli not found (set WHISPER_CLI)" >&2
    exit 1
fi
if [[ ! -f "$MODEL" ]]; then
    echo "error: whisper model not found at $MODEL (set WHISPER_MODEL)" >&2
    exit 1
fi

VOICE="${EASYVOICE_VOICE:-af_heart}"

synth() { # $1=text $2=outfile
    curl -s -m 60 -X POST https://easyvoice.ae/api/v1/audio/speech \
        -H "Authorization: Bearer $KEY" \
        -H 'Content-Type: application/json' \
        -d "$(printf '{"input":%s,"voice":"%s","response_format":"mp3","speed":1.0}' "$1" "$VOICE")" \
        -o "$2"
}

transcribe() { # $1=file
    "$WHISPER" -m "$MODEL" -f "$1" -nt -np 2>/dev/null | tr -d '\n' | sed 's/  */ /g'
}

dur() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1" 2>/dev/null; }

# Each pair is: json-quoted respelling  |  label
run_pair() { # $1=respelled-json $2=plain-json $3=label
    local r="$OUT_DIR/r.mp3" p="$OUT_DIR/p.mp3"
    synth "$1" "$r"; synth "$2" "$p"
    local rd pd
    rd="$(dur "$r")"; pd="$(dur "$p")"
    printf '%-16s respelled %5.2fs [%s] -> %s\n' "$3" "$rd" "$(md5sum <"$r" | cut -c1-8)" "$(transcribe "$r")"
    printf '%-16s plain     %5.2fs [%s] -> %s\n' "" "$pd" "$(md5sum <"$p" | cut -c1-8)" "$(transcribe "$p")"
    if [[ "$(md5sum <"$r")" == "$(md5sum <"$p")" ]]; then
        printf '%-16s ! identical audio: respelling had no effect\n' ""
    fi
    echo
}

echo "== controls (real words must transcribe exactly) =="
synth '"refrigerator"' "$OUT_DIR/c1.mp3"; printf 'refrigerator -> %s\n' "$(transcribe "$OUT_DIR/c1.mp3")"
synth '"obsidian"' "$OUT_DIR/c2.mp3"; printf 'obsidian     -> %s\n' "$(transcribe "$OUT_DIR/c2.mp3")"
echo

echo "== project names: respelled vs plain =="
run_pair '"sin-app-sicks"' '"Synapsix"' "synapsix"
run_pair '"con-tin-yoo-um"' '"Continuum"' "continuum"
run_pair '"nicks-oh-ess"' '"NixOS"' "nixos"
run_pair '"for-jay-oh"' '"Forgejo"' "forgejo"

for phrase in "$@"; do
    echo "== custom: $phrase =="
    synth "\"$phrase\"" "$OUT_DIR/x.mp3"
    printf '  %5.2fs -> %s\n\n' "$(dur "$OUT_DIR/x.mp3")" "$(transcribe "$OUT_DIR/x.mp3")"
done
