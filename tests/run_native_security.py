#!/usr/bin/env python3
"""Run the real native HTTP/RTSP handlers under ASan/UBSan (macOS/Linux, OpenSSL required)."""
from pathlib import Path
import os, re, subprocess, tempfile
repo = Path(__file__).resolve().parents[1]
cpp = repo / 'app/src/main/cpp'
lib = cpp / 'third_party/UxPlay/lib'
plist = cpp / 'third_party/libplist'
openssl = Path(os.environ.get('OPENSSL_PREFIX', '/opt/homebrew/opt/openssl@3'))
core = ['airplay_video', 'byteutils', 'compat', 'crypto', 'dnssd', 'fairplay_playfair',
        'http_request', 'http_response', 'httpd', 'logger', 'mirror_buffer', 'netutils',
        'pairing', 'raop_buffer', 'raop_ntp', 'raop_rtp', 'raop_rtp_mirror', 'srp', 'utils']
plist_src = ['base64', 'bplist', 'bytearray', 'hashtable', 'jplist', 'jsmn', 'oplist',
             'out-default', 'out-limd', 'out-plutil', 'plist', 'ptrarray', 'time64', 'xplist']
sources = [repo/'tests/native_security.c', cpp/'android_dnssd_shim.c'] + [lib/(s+'.c') for s in core]
sources += list((lib/'llhttp').glob('*.c')) + list((lib/'playfair').glob('*.c'))
sources += [plist/'src'/(s+'.c') for s in plist_src] + [plist/'libcnary/node.c', plist/'libcnary/node_list.c']
includes = [cpp, lib, lib/'llhttp', lib/'playfair', plist/'include', plist/'src', plist/'libcnary/include', openssl/'include']
with tempfile.TemporaryDirectory(prefix='native-security-', dir=repo.parent) as build:
    exe = Path(build)/'native_security'
    # UxPlay retains its matching SRP client behind #if 0. Enable only the client
    # struct/functions in a temporary test copy; production server code is unchanged.
    client_c = Path(build)/'srp.c'
    client_h = Path(build)/'srp.h'
    client_c.write_text(re.sub(r'#if 0(\s*/\*\s*begin removed section [34])', r'#if 1\1', (lib/'srp.c').read_text()))
    client_h.write_text(re.sub(r'#if 0(\s*/\*\s*begin removed section 4)', r'#if 1\1', (lib/'srp.h').read_text()))
    sources[sources.index(lib/'srp.c')] = client_c
    includes.insert(0, Path(build))
    cmd = ['clang', '-g', '-O1', '-std=gnu17', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
           '-D_GNU_SOURCE', '-DHAVE_STRNDUP', '-DPACKAGE_VERSION="2.6.0"', '-DPLIST_210', '-DTARGET_RT_LITTLE_ENDIAN=1',
           '-Wno-deprecated-declarations']
    cmd += ['-I'+str(p) for p in includes] + [str(p) for p in sources]
    cmd += ['-L'+str(openssl/'lib'), '-lcrypto', '-lpthread', '-lm', '-o', str(exe)]
    subprocess.run(cmd, check=True)
    subprocess.run([str(exe)], env={**os.environ, 'ASAN_OPTIONS':'detect_leaks=0'}, check=True)
