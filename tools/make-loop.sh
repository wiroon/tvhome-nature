#!/usr/bin/env bash
# Turn a video into a clip this launcher likes: 1920x1080, H.264, no audio, and a seamless loop (the last 1.5 s fade into the first).
#
#   tools/make-loop.sh input.mov v01.mp4 [crossfade seconds, default 1.5]
#
# Needs ffmpeg and ffprobe. The result is a little shorter than the input. Keep the file under about 20 MB (about 5 Mbps for 30 s).
set -euo pipefail
IN="${1:?usage: make-loop.sh <input> <output.mp4> [crossfade seconds]}"; OUT="${2:?usage: make-loop.sh <input> <output.mp4>}"
X="${3:-1.5}"
D=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$IN")
python3 - "$D" "$X" <<'PY'
import sys
d, x = float(sys.argv[1]), float(sys.argv[2])
if d < 4 * x: sys.exit(f"the clip is {d:.1f} s; it needs to be at least {4 * x:.1f} s (repeat it first, e.g. ffmpeg -stream_loop 3 -i in.mp4 -c copy long.mp4)")
PY
ffmpeg -v error -y -i "$IN" -an -filter_complex \
 "[0:v]scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,fps=30,split[s1][s2];\
[s1]trim=start=$X,setpts=PTS-STARTPTS[a];[s2]trim=end=$X,setpts=PTS-STARTPTS[b];\
[a][b]xfade=transition=fade:duration=$X:offset=$(python3 -c "print(max(0,$D-2*$X))"),format=yuv420p" \
 -c:v libx264 -profile:v high -level 4.1 -crf 23 -maxrate 5M -bufsize 10M -movflags +faststart "$OUT"
echo "wrote $OUT: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT") s, $(wc -c < "$OUT" | tr -d ' ') bytes"
