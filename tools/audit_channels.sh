#!/bin/zsh
set -u

# long: 按应用实际频道顺序逐台切换；每台等到网页播放器出现 custom view 后再额外停留 5 秒，
# 用截图亮度和日志共同判断是否真的出画，避免只把网页加载完成误判成直播可播。
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/artifacts/channel_audit_api31_20260824_yangshipin"
SERIAL="emulator-5554"
PKG="com.example.tv"
FFMPEG="$(command -v ffmpeg || true)"
[[ -z "$FFMPEG" ]] && FFMPEG="/Users/long/00/m3u8DL/ffmpeg"
mkdir -p "$OUT"
export OUT ROOT SERIAL PKG

python3 - <<'PY'
import json, os
root=os.environ['ROOT']
rows=[(g['groupName'],c['name'],c['urls'][0]) for g in json.load(open(root+'/app/src/main/assets/sources/base/sourceLive.plain.json')) for c in g.get('channelList',[]) if c.get('urls')]
with open(os.environ['OUT']+'/channels.tsv','w') as f:
    for i,(group,name,url) in enumerate(rows,1):
        f.write(f'{i}\t{group}\t{name}\t{url}\n')
PY

adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell monkey -p "$PKG" 1 >/dev/null
sleep 8

printf 'index\tgroup\tname\turl\tstatus\twait_sec\tyavg\tcustom_view\n' > "$OUT/results.tsv"
total=$(wc -l < "$OUT/channels.tsv" | tr -d ' ')
current=0
exec 3< "$OUT/channels.tsv"
while IFS=$'\t' read -r index group name url <&3; do
  current=$((current+1))
  if [[ "$index" -gt 1 ]]; then adb -s "$SERIAL" shell input keyevent KEYCODE_DPAD_DOWN >/dev/null; fi
  if [[ "$index" -gt 1 ]]; then adb -s "$SERIAL" logcat -c; fi
  started=$(date +%s)
  custom=0
  waited=0
  while [[ "$waited" -lt 15 ]]; do
    if adb -s "$SERIAL" logcat -d -v brief | rg -q 'custom view show'; then custom=1; break; fi
    sleep 1
    waited=$(( $(date +%s) - started ))
  done
  # custom view 只代表网页进入视频层；央视频可能还在缓冲，继续轮询截图直到真实画面出现。
  first_frame_wait="$waited"
  if [[ "$custom" -eq 1 ]]; then
    frame_ready=0
    extra=0
    while [[ "$extra" -lt 12 ]]; do
      adb -s "$SERIAL" exec-out screencap -p > "$OUT/$(printf '%02d' "$index")_frame.png"
      probe="$OUT/$(printf '%02d' "$index")_probe.txt"
      "$FFMPEG" -hide_banner -loglevel error -i "$OUT/$(printf '%02d' "$index")_frame.png" -vf signalstats,metadata=print:file="$probe" -f null - >/dev/null 2>&1 || true
      probe_yavg=""
      [[ -f "$probe" ]] && probe_yavg=$(rg -o 'lavfi.signalstats.YAVG=[0-9.]+|YAVG:[ ]*[0-9.]+' "$probe" | tail -1 | rg -o '[0-9.]+$' || true)
      if [[ -n "$probe_yavg" ]] && awk "BEGIN{exit !($probe_yavg > 28)}"; then frame_ready=1; break; fi
      sleep 1
      extra=$((extra+1))
    done
    waited=$((waited+extra))
    if [[ "$frame_ready" -eq 1 ]]; then sleep 5; fi
  else
    adb -s "$SERIAL" exec-out screencap -p > "$OUT/$(printf '%02d' "$index")_frame.png"
  fi
  adb -s "$SERIAL" logcat -d -v threadtime > "$OUT/$(printf '%02d' "$index").log"
  stats="$OUT/$(printf '%02d' "$index")_stats.txt"
  "$FFMPEG" -hide_banner -loglevel error -i "$OUT/$(printf '%02d' "$index")_frame.png" -vf signalstats,metadata=print:file="$stats" -f null - >/dev/null 2>&1 || true
  yavg=""
  if [[ -f "$stats" ]]; then
    yavg=$(rg -o 'lavfi.signalstats.YAVG=[0-9.]+|YAVG:[ ]*[0-9.]+' "$stats" | tail -1 | rg -o '[0-9.]+$' || true)
  fi
  [[ -z "$yavg" ]] && yavg=""
  # 画面正常出画时中心画面通常明显高于纯黑背景；custom view 只是进入视频层，不等于已有首帧。
  result_status=no_frame
  if [[ "$custom" -eq 1 && -n "$yavg" ]] && awk "BEGIN{exit !($yavg > 28)}"; then result_status=playable; fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$index" "$group" "$name" "$url" "$result_status" "$waited" "$yavg" "$custom" >> "$OUT/results.tsv"
  printf '%02d/%02d %s %s wait=%ss yavg=%s\n' "$index" "$total" "$result_status" "$name" "$waited" "${yavg:-?}"
done
exec 3<&-

python3 - <<'PY'
import csv,json,os,datetime
out=os.environ['OUT']
with open(out+'/results.tsv') as f: rows=list(csv.DictReader(f,delimiter='\t'))
for r in rows:
    r['index']=int(r['index'])
summary={'generated_at':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),'device':os.environ['SERIAL'],'avd':'TVLive_Common_API31_1080p','channels_total':len(rows),'playable':sum(r['status']=='playable' for r in rows),'no_frame':sum(r['status']=='no_frame' for r in rows),'rows':rows}
with open(out+'/summary.json','w') as f: json.dump(summary,f,ensure_ascii=False,indent=2)
with open(out+'/results.csv','w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
print(json.dumps({k:summary[k] for k in ('generated_at','channels_total','playable','no_frame')},ensure_ascii=False))
PY
