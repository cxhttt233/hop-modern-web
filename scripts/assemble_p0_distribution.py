#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.
"""Assemble a P0 production runtime from Hop's official plugin ZIPs.

This preserves the plugin folder, Jandex index and library layout. It never
edits server/pom.xml or adds test-scope dependencies to production.
"""

import hashlib
import importlib.util
import json
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

MAX_ENTRY_SIZE = 512 * 1024 * 1024
MAX_ARCHIVE_SIZE = 2 * 1024 * 1024 * 1024
MAX_DISTRIBUTION_SIZE = 4 * 1024 * 1024 * 1024
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_p0_ids():
    spec = importlib.util.spec_from_file_location(
        "p0_runtime_classpath", Path(__file__).with_name("p0-runtime-classpath.py")
    )
    if spec is None or spec.loader is None:
        raise ValueError("cannot load existing P0 ID contract")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.P0_IDS


def pom_value(path, name):
    value = ET.parse(path).getroot().findtext(f"m:{name}", namespaces=NS)
    if not value or not value.strip():
        raise ValueError(f"missing {name} in {path}")
    return value.strip()


def plugin_artifacts(catalog_path, hop_root):
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    by_id = {row["id"]: row for row in catalog if row.get("id")}
    ids = load_p0_ids()
    if len(ids) != 20 or not ids.issubset(by_id):
        raise ValueError(f"P0 source catalog incomplete: {sorted(ids - by_id.keys())}")
    version = pom_value(hop_root / "pom.xml", "version")
    artifacts = []
    for plugin_id in sorted(ids):
        module = by_id[plugin_id]["module"]
        if not module.startswith("plugins/transforms/") or ".." in Path(module).parts:
            raise ValueError(f"invalid P0 module path: {module}")
        artifact = pom_value(hop_root / module / "pom.xml", "artifactId")
        if not artifact.startswith("hop-transform-"):
            raise ValueError(f"unexpected P0 artifact: {artifact}")
        artifacts.append((plugin_id, module, artifact))
    if len({row[2] for row in artifacts}) != len(artifacts):
        raise ValueError("duplicate P0 artifacts")
    return version, artifacts


def checked_member(info, expected_module):
    name = info.filename
    path = PurePosixPath(name)
    parts = name[:-1].split("/") if info.is_dir() else name.split("/")
    if (not name or name.startswith("/") or "\\" in name or ":" in name
            or any(part in ("", ".", "..") for part in parts)
            or path.is_absolute()):
        raise ValueError(f"unsafe ZIP member: {name!r}")
    mode = (info.external_attr >> 16) & 0xFFFF
    if stat.S_IFMT(mode) == stat.S_IFLNK:
        raise ValueError(f"ZIP symlink forbidden: {name}")
    if info.file_size > MAX_ENTRY_SIZE:
        raise ValueError(f"oversized ZIP member: {name}")
    if info.is_dir():
        return None
    if not (name.startswith(expected_module + "/")
            or name.startswith("lib/core/")
            or name.startswith("lib/jdbc/")
            or name.startswith("config/projects/samples/")):
        raise ValueError(f"unexpected plugin ZIP layout: {name}")
    return path


def assemble(catalog_path, hop_root, maven_repo, output):
    version, artifacts = plugin_artifacts(catalog_path, hop_root)
    if output.exists():
        raise ValueError(f"output already exists, refusing overwrite: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    total_bytes = 0
    manifest = {"schemaVersion": 1, "hopVersion": version,
                "pluginRoot": "plugins", "requiredP0": len(artifacts), "plugins": []}
    with tempfile.TemporaryDirectory(prefix="p0-dist-", dir=output.parent) as temp:
        stage = Path(temp)
        files = {}
        for plugin_id, module, artifact in artifacts:
            archive = (maven_repo / "org/apache/hop" / artifact / version
                       / f"{artifact}-{version}.zip")
            if not archive.is_file():
                raise ValueError(f"official Hop plugin ZIP not installed: {archive}")
            if archive.stat().st_size > MAX_ARCHIVE_SIZE:
                raise ValueError(f"oversized plugin ZIP: {archive}")
            jar_path = f"{module}/{artifact}-{version}.jar"
            seen_jar = False
            with zipfile.ZipFile(archive) as zf:
                if zf.testzip() is not None:
                    raise ValueError(f"corrupt plugin ZIP: {archive}")
                for info in zf.infolist():
                    relative = checked_member(info, module)
                    if relative is None:
                        continue
                    total_bytes += info.file_size
                    if total_bytes > MAX_DISTRIBUTION_SIZE:
                        raise ValueError("distribution exceeds size limit")
                    target = stage.joinpath(*relative.parts)
                    if str(relative) in files:
                        with zf.open(info) as stream:
                            h = hashlib.sha256()
                            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                                h.update(chunk)
                        if h.hexdigest() != files[str(relative)]:
                            raise ValueError(f"conflicting shared ZIP member: {relative}")
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    h = hashlib.sha256()
                    with zf.open(info) as source, target.open("xb") as sink:
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            sink.write(chunk)
                            h.update(chunk)
                    files[str(relative)] = h.hexdigest()
                    if str(relative) == jar_path:
                        seen_jar = True
            if not seen_jar:
                raise ValueError(f"plugin ZIP lacks expected main JAR: {jar_path}")
            with zipfile.ZipFile(stage / jar_path) as jar:
                if "META-INF/jandex.idx" not in jar.namelist():
                    raise ValueError(f"plugin JAR has no Jandex index: {jar_path}")
            manifest["plugins"].append({"id": plugin_id, "artifact": artifact,
                                        "module": module, "zipSha256": digest(archive),
                                        "jarSha256": files[jar_path]})
        manifest["fileCount"] = len(files)
        manifest["totalUncompressedBytes"] = sum(
            p.stat().st_size for p in stage.rglob("*") if p.is_file())
        (stage / "p0-distribution-manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8")
        stage.rename(output)
    print(f"P0_DISTRIBUTION_ARCHIVES={len(artifacts)}/{len(artifacts)}")
    print(f"P0_DISTRIBUTION_FILES={manifest['fileCount']}")
    print(f"P0_DISTRIBUTION_MANIFEST={output / 'p0-distribution-manifest.json'}")
    return manifest


def main(argv):
    if len(argv) != 5:
        raise SystemExit("usage: assemble_p0_distribution.py <source-catalog.json> <hop-root> <m2-repo> <output-dir>")
    assemble(*[Path(x).resolve() for x in argv[1:]])


if __name__ == "__main__":
    main(sys.argv)
