#!/usr/bin/env python3
"""Synchronize exact TEST download locations from the operator-managed release catalogs."""
import hashlib
import os
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

ORIGIN = "https://api-test.uvel.ai"
ARTIFACT_ROOT = Path("/srv/uvel-app/artifacts")
TARGET = Path("/etc/nginx/uvel-app-updates-test.conf")
VHOST = Path("/etc/nginx/conf.d/uvel-api-test.conf")
MEDIA_TYPES = {"apk": "application/vnd.android.package-archive", "wgt": "application/octet-stream"}

def locations():
    config = dict(line.split("=", 1) for line in Path("/srv/uvel-release/backend.env").read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
    def setting(name):
        return config["NEXION_DB_" + name].strip().strip('"').strip("'")

    database = urlsplit(setting("URL").removeprefix("jdbc:"))
    query = """
        SELECT platform,sha256,'apk',download_url FROM nx_app_update_artifact WHERE platform='android' AND available=1
        UNION ALL
        SELECT platform,sha256,'wgt',download_url FROM nx_app_update_resource WHERE available=1 ORDER BY 1,2,3;
    """
    records = subprocess.run(["mysql", "-NB", "--protocol=tcp", "-h", database.hostname, "-P", str(database.port or 3306),
                              "-u", setting("USERNAME"), database.path.lstrip("/")], input=query, text=True, check=True,
                             capture_output=True, env={**os.environ, "MYSQL_PWD": setting("PASSWORD")})
    entries = []
    for line in records.stdout.splitlines():
        platform, digest, extension, url = line.split("\t")
        if platform not in ("android", "ios") or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Invalid platform or digest in the TEST release catalog")
        uri = f"/app-updates/{platform}/{digest}.{extension}"
        if url != ORIGIN + uri:
            raise ValueError(f"Unexpected TEST download origin or path for {digest}")
        source = ARTIFACT_ROOT / uri.lstrip("/")
        if source.resolve() != source or not source.is_file():
            raise ValueError(f"Missing immutable TEST file for {digest}")
        checksum = hashlib.sha256()
        with source.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                checksum.update(chunk)
        if checksum.hexdigest() != digest:
            raise ValueError(f"TEST file digest mismatch for {digest}")
        entries.append((f"= {uri}", extension, True))
    # Unregistered files retain the original TEST vhost network restrictions.
    entries.extend([(r'~ "^/app-updates/android/[a-f0-9]{64}[.]apk$"', "apk", False),
                    (r'~ "^/app-updates/(android|ios)/[a-f0-9]{64}[.]wgt$"', "wgt", False)])
    sections = []
    for match, extension, public in entries:
        access = "    allow all;\n" if public else ""
        sections.append(f"""location {match} {{
    root {ARTIFACT_ROOT};
{access}    limit_except GET {{ deny all; }}
    default_type {MEDIA_TYPES[extension]};
    add_header X-Content-Type-Options nosniff;
    add_header Cache-Control "private, no-store" always;
    try_files $uri =404;
}}
""")
    return "\n".join(sections)

def replace(text):
    with tempfile.NamedTemporaryFile(dir=TARGET.parent, mode="w", delete=False) as f:
        f.write(text)
        os.fchmod(f.fileno(), 0o644)
    os.replace(f.name, TARGET)

def main():
    vhost = VHOST.read_text()
    if "server_name api-test.uvel.ai;" not in vhost or f"include {TARGET};" not in vhost:
        raise RuntimeError("The TEST download include is not installed on this host")
    old = TARGET.read_text()
    new = locations()
    if old == new:
        print("TEST download locations are current")
        return
    replace(new)
    try:
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
    except subprocess.CalledProcessError:
        replace(old)
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
        raise
    print("Updated TEST download locations")

if __name__ == "__main__":
    main()
