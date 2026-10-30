#!/usr/bin/env python3
"""Landscape -> 9:16 portrait with the original video untouched in the centre on a solid colour.

    python landscape_to_portrait.py input.mp4 output.mp4
    python landscape_to_portrait.py input.mp4 output.mp4 --color "#1a1a2e"
    python landscape_to_portrait.py input.mp4 output.mp4 --canvas-width 1080     # classic 1080x1920
    python landscape_to_portrait.py input.mp4 output.mp4 --encoder libx264       # force CPU

Notes on "stream copy":
  * Audio is stream-copied (bit-exact, instant).
  * Video CANNOT be stream-copied: adding a background changes every frame, so it must be re-encoded once.
    To keep that fast and lossless-looking the script uses a GPU encoder (NVENC / QuickSync / AMF) if one
    works on this PC, otherwise x264 at CRF 14 (visually transparent). The clip is never scaled or cropped.
Requires ffmpeg + ffprobe on PATH.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys

# name -> (ffmpeg args for near-transparent quality)
GPU_ENCODERS = {
    "h264_nvenc": ["-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "15", "-b:v", "0"],
    "h264_qsv": ["-c:v", "h264_qsv", "-global_quality", "15", "-preset", "medium"],
    "h264_amf": ["-c:v", "h264_amf", "-quality", "quality", "-rc", "cqp", "-qp_i", "15", "-qp_p", "15"],
}
CPU_ENCODER = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "14"]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def probe(path):
    r = run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", path])
    if r.returncode:
        sys.exit(f"ffprobe failed: {r.stderr.strip()}")
    info = json.loads(r.stdout)
    video = next((s for s in info["streams"] if s["codec_type"] == "video"), None)
    if not video:
        sys.exit("No video stream found.")
    w, h = int(video["width"]), int(video["height"])
    rot = 0
    for sd in video.get("side_data_list", []):
        if "rotation" in sd:
            rot = int(sd["rotation"])
    rot = int(video.get("tags", {}).get("rotate", rot))
    if abs(rot) in (90, 270):
        w, h = h, w
    has_audio = any(s["codec_type"] == "audio" for s in info["streams"])
    return w, h, has_audio


def gpu_works(name):
    """Real 1-frame test encode; presence in `ffmpeg -encoders` alone does not mean the hardware exists."""
    args = GPU_ENCODERS[name]
    r = run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=640x360:d=0.2", "-frames:v", "2",
             *args, "-pix_fmt", "yuv420p", "-f", "null", "-"])
    return r.returncode == 0


def pick_encoder(forced):
    if forced == "libx264":
        return "libx264", CPU_ENCODER
    if forced:
        if forced not in GPU_ENCODERS:
            sys.exit(f"Unknown encoder {forced}. Use one of: libx264, {', '.join(GPU_ENCODERS)}")
        return forced, GPU_ENCODERS[forced]
    for name in GPU_ENCODERS:
        if gpu_works(name):
            return name, GPU_ENCODERS[name]
    return "libx264", CPU_ENCODER


def even(n):
    return int(n) // 2 * 2


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--color", default="#000000", help="background colour as #RRGGBB (default black)")
    ap.add_argument("--canvas-width", type=int, default=0,
                    help="output width; height = width*16/9. Default: same width as the source (no downscale). "
                         "Must be >= source width so the clip is never scaled.")
    ap.add_argument("--encoder", help="libx264, h264_nvenc, h264_qsv or h264_amf (default: auto-detect GPU)")
    ap.add_argument("-y", action="store_true", help="overwrite output")
    a = ap.parse_args()

    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            sys.exit(f"{tool} not found on PATH.")
    if not re.fullmatch(r"#?[0-9a-fA-F]{6}", a.color):
        sys.exit("--color must look like #RRGGBB")
    color = "0x" + a.color.lstrip("#")

    w, h, has_audio = probe(a.input)
    if w < h:
        sys.exit(f"Input is {w}x{h} (portrait); this script is for landscape/square sources.")

    cw = even(a.canvas_width or w)
    if cw < w:
        sys.exit(f"--canvas-width {cw} is smaller than the source width {w}; that would crop/scale the video. "
                 f"Use >= {w}, or omit it.")
    ch = even(cw * 16 / 9)
    if ch < h:  # extremely wide-but-tall sources
        ch = even(h)

    name, venc = pick_encoder(a.encoder)
    vf = f"pad={cw}:{ch}:(ow-iw)/2:(oh-ih)/2:color={color},setsar=1"
    cmd = ["ffmpeg", "-hide_banner", "-stats", "-loglevel", "error", "-y" if a.y else "-n",
           "-i", a.input, "-map", "0:v:0", *(["-map", "0:a?"] if has_audio else []),
           "-vf", vf, *venc, "-pix_fmt", "yuv420p",
           "-c:a", "copy", "-movflags", "+faststart", a.output]
    print(f"Source {w}x{h} -> canvas {cw}x{ch}, bg {a.color}, encoder {name}, audio copy")
    r = subprocess.run(cmd)
    if r.returncode and name != "libx264" and not a.encoder:
        print(f"{name} failed; retrying on CPU (libx264)...")
        cmd[cmd.index("-vf") + 2:cmd.index("-pix_fmt")] = CPU_ENCODER
        r = subprocess.run(cmd)
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
