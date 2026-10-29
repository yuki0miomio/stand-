"""Start the unmodified official V3 relay; use Ctrl+C to stop."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
bun = root / '.tools' / 'bun-windows-x64-baseline' / 'bun.exe'
source = root / 'relay' / 'upstream' / 'v3-server.ts'
if not bun.exists() or not source.exists():
    subprocess.run([sys.executable, str(root / 'tools' / 'prepare_relay.py')], check=True)
env = os.environ.copy()
env['PORT'] = '9999'
env['LOG_LEVEL'] = 'info'
raise SystemExit(subprocess.call([str(bun), str(source)], cwd=source.parent, env=env))
