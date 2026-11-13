#!/usr/bin/env python3
"""Extract a timestamped lossless contact sheet and indexed still frames from an Android screen recording.
It does not equate optical refraction with a rigid translation or claim frame ownership.
Dependencies: pip install opencv-python pillow
Usage: python lg_visual_rebound_contact_sheet.py input.mp4 --output visual_frames --fps 8
"""
import argparse, pathlib, math, sys, csv
import cv2
from PIL import Image, ImageDraw, ImageFont

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('video', type=pathlib.Path)
    p.add_argument('--output', type=pathlib.Path, default=pathlib.Path('LG_visual_frames'))
    p.add_argument('--fps', type=float, default=8.0, help='Extracted frames per second (not recording FPS).')
    p.add_argument('--width', type=int, default=370, help='Contact sheet thumbnail width')
    p.add_argument('--columns', type=int, default=4)
    p.add_argument('--max-frames', type=int, default=160)
    a = p.parse_args()
    if a.fps <= 0 or a.columns < 1 or a.width < 100 or a.max_frames < 1:
        p.error('Invalid fps/columns/width/max-frames')
    if not a.video.is_file():
        p.error('Video not found')
    cap = cv2.VideoCapture(str(a.video))
    if not cap.isOpened():
        p.error('Cannot open video')
    native_fps = cap.get(cv2.CAP_PROP_FPS)
    native_frames = cap.get(cv2.CAP_PROP_FRAME_COUNT)
    if native_fps <= 0 or native_frames <= 0:
        p.error('Video has no valid FPS/frame count; inspect the MP4 first')
    duration = native_frames / native_fps
    a.output.mkdir(parents=True, exist_ok=True)
    rows, thumbs = [], []
    num = min(a.max_frames, math.floor(duration * a.fps)+1)
    for i in range(num):
        t = i / a.fps
        if t >= duration: break
        frame_idx = min(int(round(t*native_fps)), int(native_frames)-1)
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
        ok, frame = cap.read()
        if not ok: continue
        actual_t = frame_idx / native_fps
        path = a.output / f'frame_{i:04d}_{actual_t:08.3f}s.png'
        cv2.imwrite(str(path),frame)
        rows.append([i,frame_idx,round(actual_t,6),path.name])
        rgb = cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)
        im = Image.fromarray(rgb)
        tw=a.width; th=max(1,round(im.height*tw/im.width))
        im=im.resize((tw,th),Image.Resampling.LANCZOS)
        thumbs.append((f'{actual_t:.3f}s   frame {frame_idx}',im))
    cap.release()
    if not thumbs:
        p.error('Could not extract any frames')
    with (a.output/'frames.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.writer(f); w.writerow(['index','original_frame','seconds','png']);w.writerows(rows)
    tile_h=max(im.height for _,im in thumbs)+28; tile_w=a.width
    sheet=Image.new('RGB',(tile_w*a.columns,tile_h*math.ceil(len(thumbs)/a.columns)),'#f5f5f5')
    drawer=ImageDraw.Draw(sheet)
    for i,(label,im) in enumerate(thumbs):
        x=(i%a.columns)*tile_w;y=(i//a.columns)*tile_h
        sheet.paste(im,(x,y+26));drawer.text((x+5,y+5),label,fill='#151515')
    output=a.output/'contact_sheet.jpg'
    sheet.save(output,quality=94,subsampling=0)
    print(f'Input: {a.video} | Video FPS: {native_fps:.3f} | Duration: {duration:.3f}s')
    print(f'Extracted {len(thumbs)} full-resolution PNG frames and {output}')
    print('Contact sheet is for locating motion phases; use original PNGs for pixel comparisons.')
    print('A recording cannot by itself prove a specific GraphicsLayer-to-DisplayFrame ownership.')
if __name__=='__main__':main()
