"""Run the project's Gradle build using workspace-local tools."""
from pathlib import Path
import ctypes
import json
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
config = json.loads((root / '.tools' / 'build-env.json').read_text(encoding='utf-8'))
env = os.environ.copy()
tasks = sys.argv[1:] or ['testDebugUnitTest', 'assembleDebug', 'lintDebug']
# Some Windows Android/Gradle native subprocesses do not accept non-ASCII paths.
# A temporary drive alias keeps all tools in the workspace without copying files.
mask = ctypes.windll.kernel32.GetLogicalDrives()
drive = next(letter for letter in 'VWXYZ' if not mask & (1 << (ord(letter) - ord('A'))))
alias = Path(drive + ':\\')
subprocess.run(['subst', drive + ':', str(root)], check=True)
local = root / 'local.properties'
original = local.read_bytes() if local.exists() else None
try:
    env['JAVA_HOME'] = str(alias / '.tools' / 'jdk')
    env['ANDROID_HOME'] = str(alias / '.tools' / 'android-sdk')
    env['GRADLE_USER_HOME'] = str(alias / '.tools' / 'gradle-cache')
    env['PATH'] = str(alias / '.tools' / 'jdk' / 'bin') + os.pathsep + env['PATH']
    local.write_text('sdk.dir=' + env['ANDROID_HOME'].replace('\\', '/') + '\n', encoding='utf-8')
    result = subprocess.call([str(alias / '.tools' / 'gradle-8.11.1' / 'bin' / 'gradle.bat'),
        '--no-daemon', '--console=plain', '--max-workers=2', *tasks], cwd=alias, env=env)
finally:
    if original is not None:
        local.write_bytes(original)
    else:
        local.unlink(missing_ok=True)
    subprocess.run(['subst', drive + ':', '/D'], check=True)
raise SystemExit(result)
