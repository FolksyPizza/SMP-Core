#!/usr/bin/env python3
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
"""Check current source licensing and optionally built plugin metadata."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile


APACHE_SHA256 = 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'
MARIADB_SHA256 = 'c8cfa082f122323774057c2808eb7e796703bde9459fa04ff6f2c9cc25f74eff'
REPLACED_TERMS = re.compile(r'SMP-Core Source License|all rights reserved|\brevocable permission\b', re.I)
TEXT_SUFFIXES = {'.java', '.md', '.sh', '.py', '.kts', '.yml', '.yaml', '.json', '.properties', '.toml'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check(root, jars=None):
    license_bytes = (root / 'LICENSE').read_bytes()
    normalized = re.sub(r'(?m)^   Copyright [^\n]*$',
                        '   Copyright [yyyy] [name of copyright owner]', license_bytes.decode('utf-8'))
    require(hashlib.sha256(normalized.encode('utf-8')).hexdigest() == APACHE_SHA256,
            'LICENSE must contain the approved Apache 2.0 text')
    notice = (root / 'NOTICE').read_bytes()
    require(b'Apache License, Version 2.0' in notice, 'NOTICE must identify Apache 2.0')
    require(b'MariaDB Connector/J' in notice and b'LGPL-2.1' in notice,
            'NOTICE must preserve the bundled driver attribution')
    driver_license = (root / 'licenses/mariadb-connector-j-LICENSE').read_bytes()
    require(hashlib.sha256(driver_license).hexdigest() == MARIADB_SHA256,
            'the third-party MariaDB license must remain unchanged')
    require('MIT License' in (root / 'README.md').read_text(encoding='utf-8'),
            'README must retain the historical MIT release boundary')
    require('Apache License, Version 2.0' in (root / 'CONTRIBUTING.md').read_text(encoding='utf-8'),
            'CONTRIBUTING must identify the current contribution license')
    paths = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z']).split(b'\0')
    for raw in filter(None, paths):
        path = Path(raw.decode('utf-8'))
        # Upstream licenses retain their terms; this checker's rejection patterns are test inputs.
        if path.parts[0] == 'licenses' or path.as_posix() == 'tools/ci/check-licenses.py':
            continue
        if path.suffix in TEXT_SUFFIXES:
            require(not REPLACED_TERMS.search((root / path).read_text(encoding='utf-8')),
                    f'{path}: obsolete first-party licensing notice')
    if jars is not None:
        expected = {p.name + '.jar' for p in root.glob('Pizza*')
                    if p.is_dir() and (p / 'src').is_dir() and p.name != 'PizzaCommon'}
        actual = {p.name for p in jars.glob('*.jar')}
        require(actual == expected, 'built plugin jars do not match the source modules')
        for name in sorted(expected):
            with ZipFile(jars / name) as jar:
                require(jar.read('META-INF/LICENSE') == license_bytes, f'{name}: LICENSE mismatch')
                require(jar.read('META-INF/NOTICE') == notice, f'{name}: NOTICE mismatch')
                if name == 'PizzaProxyGuard.jar':
                    require(jar.read('META-INF/mariadb-connector-j-LICENSE') == driver_license,
                            f'{name}: missing or changed driver license')
                else:
                    banner = jar.read('dev/pizzasmp/common/SuiteBanner.class')
                    require(b'Apache-2.0' in banner and b'SMP-Core Source License' not in banner,
                            f'{name}: stale startup license label')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--jars', type=Path, help='built plugin directory, relative to the repository root')
    args = parser.parse_args()
    root = args.root.resolve()
    jars = (root / args.jars).resolve() if args.jars is not None else None
    try:
        check(root, jars)
    except (ValueError, OSError, KeyError, UnicodeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'License check failed: {error}\n')
    print('License check passed: Apache 2.0 source; historical MIT boundary and third-party terms preserved.'
          + (' Built plugin metadata verified.' if jars is not None else ''))


if __name__ == '__main__':
    main()
