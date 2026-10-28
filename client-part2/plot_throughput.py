"""Usage: python3 plot_throughput.py <result-directory>.
Always writes a standalone SVG using the Python standard library.
Also writes PNG/PDF when matplotlib is installed.
"""
import csv
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 plot_throughput.py <result-directory>")
folder = Path(sys.argv[1])
with (folder / "throughput.csv").open(newline="") as source:
    rows = list(csv.DictReader(source))
if not rows:
    raise SystemExit("No throughput samples found")
x = [(float(r["startSeconds"]) + float(r["endSeconds"])) / 2 for r in rows]
y = [float(r["swipesPerSecond"]) for r in rows]
end = max(float(r["endSeconds"]) for r in rows)
xmax, ymax = max(end, 1), max(max(y) * 1.15, 1)
left, top, width, height = 100, 65, 820, 360
px = lambda value: left + value / xmax * width
py = lambda value: top + height - value / ymax * height
svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="560" viewBox="0 0 1000 560">',
       '<rect width="1000" height="560" fill="white"/>',
       '<g font-family="Arial,sans-serif" fill="#1f2937">',
       '<text x="500" y="32" text-anchor="middle" font-size="21">SkiTrak ingest throughput</text>']
for i in range(6):
    xv, yv = xmax * i / 5, ymax * i / 5
    xx, yy = px(xv), py(yv)
    svg.extend([
        f'<line x1="{left}" x2="{left+width}" y1="{yy}" y2="{yy}" stroke="#e5e7eb"/>',
        f'<text x="{left-12}" y="{yy+5}" text-anchor="end" font-size="13">{yv:,.0f}</text>',
        f'<text x="{xx}" y="{top+height+25}" text-anchor="middle" font-size="13">{xv:.1f}</text>'])
points = ' '.join(f'{px(a):.2f},{py(b):.2f}' for a, b in zip(x, y))
svg.extend([
    f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+height}" stroke="#374151"/>',
    f'<line x1="{left}" y1="{top+height}" x2="{left+width}" y2="{top+height}" stroke="#374151"/>',
    f'<polyline points="{points}" fill="none" stroke="#2563eb" stroke-width="2.5"/>'])
for a, b in zip(x, y):
    svg.append(f'<circle cx="{px(a):.2f}" cy="{py(b):.2f}" r="4" fill="#2563eb"/>')
svg.extend([
    '<text x="510" y="485" text-anchor="middle" font-size="15">Seconds since warmup started</text>',
    '<text transform="translate(25,245) rotate(-90)" text-anchor="middle" font-size="15">Successful swipes per second</text>',
    '<text x="100" y="525" font-size="13">10-second buckets; final partial bucket uses its actual duration. Warmup included.</text>',
    '</g></svg>'])
(folder / 'throughput.svg').write_text('\n'.join(svg))
print('Saved:', folder / 'throughput.svg')
try:
    import matplotlib
except ImportError:
    print('SVG is ready: open it in a browser. Optional: install matplotlib for PNG/PDF output.')
    raise SystemExit(0)
matplotlib.use('Agg')
import matplotlib.pyplot as plt
fig, ax = plt.subplots(figsize=(10, 5))
ax.plot(x, y, marker='o', linewidth=1.5)
ax.set(xlabel='Seconds since warmup started', ylabel='Successful swipes per second',
       title='SkiTrak ingest throughput (10-second buckets)', ylim=(0, None))
ax.grid(alpha=0.25)
ax.text(0.01, -0.19, 'Final partial bucket uses its actual duration; warmup is included.',
        transform=ax.transAxes, fontsize=9)
fig.tight_layout()
fig.savefig(folder / 'throughput.png', dpi=180, bbox_inches='tight')
fig.savefig(folder / 'throughput.pdf', bbox_inches='tight')
plt.close(fig)
print('Also saved PNG and PDF.')
