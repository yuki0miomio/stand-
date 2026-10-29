"""Workspace-local Windows build tools; no system settings are changed."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import os
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)


def read_url(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'CoyoteVision-build'})
    return urllib.request.urlopen(request, timeout=90)


def download(name, url, checksum, algorithm='sha256'):
    target = TOOLS / name
    if not target.exists() or hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        print(f'Downloading {name}', flush=True)
        partial = target.with_suffix('.part')
        digest = hashlib.new(algorithm)
        with read_url(url) as response, partial.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                digest.update(chunk)
        if digest.hexdigest() != checksum:
            raise RuntimeError(f'Checksum mismatch: {name}')
        partial.replace(target)
    print(f'Verified {name}', flush=True)
    return target


assets = json.load(read_url('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse'))
package = assets[0]['binary']['package']
repo = ET.fromstring(read_url('https://dl.google.com/android/repository/repository2-1.xml').read())
commandline = next(p for p in repo if p.tag.endswith('remotePackage') and p.attrib['path'] == 'cmdline-tools;19.0')
archive = next(a for a in commandline.find('archives') if a.findtext('host-os') == 'windows')
complete = archive.find('complete')
gradle_hash = read_url('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').read().decode().strip()
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    jobs = [
        pool.submit(download, 'jdk.zip', package['link'], package['checksum']),
        pool.submit(download, 'cmdline19.zip', 'https://dl.google.com/android/repository/' + complete.findtext('url'), complete.findtext('checksum'), 'sha1'),
        pool.submit(download, 'gradle.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', gradle_hash),
    ]
    jdk_zip, sdk_zip, gradle_zip = [job.result() for job in jobs]

if not (TOOLS / 'jdk').exists():
    with zipfile.ZipFile(jdk_zip) as archive:
        top = archive.namelist()[0].split('/')[0]
        archive.extractall(TOOLS / 'jdk-extract')
    (TOOLS / 'jdk-extract' / top).rename(TOOLS / 'jdk')
sdk = TOOLS / 'android-sdk'
if not (sdk / 'cmdline-tools' / '19.0').exists():
    with zipfile.ZipFile(sdk_zip) as archive:
        archive.extractall(TOOLS / 'sdk19-extract')
    (sdk / 'cmdline-tools').mkdir(parents=True, exist_ok=True)
    (TOOLS / 'sdk19-extract' / 'cmdline-tools').rename(sdk / 'cmdline-tools' / '19.0')
if not (TOOLS / 'gradle-8.11.1').exists():
    with zipfile.ZipFile(gradle_zip) as archive:
        archive.extractall(TOOLS)

env = os.environ.copy()
env['JAVA_HOME'] = str(TOOLS / 'jdk')
env['ANDROID_HOME'] = str(sdk)
env['PATH'] = str(TOOLS / 'jdk' / 'bin') + os.pathsep + env['PATH']
env['GRADLE_USER_HOME'] = str(TOOLS / 'gradle-cache')
manager = sdk / 'cmdline-tools' / '19.0' / 'bin' / 'sdkmanager.bat'
print('Preparing Android SDK packages', flush=True)
subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'], input='y\n' * 100, text=True, env=env, check=True, stdout=subprocess.DEVNULL)
subprocess.run([str(manager), '--sdk_root=' + str(sdk), 'platforms;android-35', 'build-tools;35.0.0', 'platform-tools'], env=env, check=True)
(ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk).replace('\\', '/') + '\n', encoding='utf-8')
(TOOLS / 'build-env.json').write_text(json.dumps({'java': env['JAVA_HOME'], 'sdk': str(sdk), 'gradle': str(TOOLS / 'gradle-8.11.1' / 'bin' / 'gradle.bat'), 'cache': env['GRADLE_USER_HOME']}), encoding='utf-8')
print('Build environment ready', flush=True)
