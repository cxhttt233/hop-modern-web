#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.
"""Stdlib tests for production plugin ZIP distribution safety."""

import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import assemble_p0_distribution as builder


class P0DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.hop = self.root / "hop"
        self.repo = self.root / "m2"
        self.catalog = self.root / "catalog.json"
        self.output = self.root / "dist"
        self.version = "2.20.0-SNAPSHOT"
        self._pom(self.hop / "pom.xml", "hop", self.version)
        rows = []
        self.artifacts = {}
        for plugin_id in sorted(builder.load_p0_ids()):
            slug = plugin_id.lower()
            module = f"plugins/transforms/{slug}"
            artifact = f"hop-transform-{slug}"
            self._pom(self.hop / module / "pom.xml", artifact)
            rows.append({"id": plugin_id, "module": module})
            self.artifacts[plugin_id] = (module, artifact)
            self._zip(module, artifact)
        self.catalog.write_text(json.dumps(rows), encoding="utf-8")

    @staticmethod
    def _pom(path, artifact, version=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        version_tag = f"<version>{version}</version>" if version else ""
        path.write_text(
            f'<project xmlns="http://maven.apache.org/POM/4.0.0">'
            f'<artifactId>{artifact}</artifactId>{version_tag}</project>', encoding="utf-8")

    def _zip(self, module, artifact, extras=None, jandex=True):
        jar_name = f"{artifact}-{self.version}.jar"
        jar_bytes = io.BytesIO()
        with zipfile.ZipFile(jar_bytes, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            if jandex:
                jar.writestr("META-INF/jandex.idx", b"test-index")
        archive = self.repo / "org/apache/hop" / artifact / self.version / f"{artifact}-{self.version}.zip"
        archive.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr(f"{module}/{jar_name}", jar_bytes.getvalue())
            zf.writestr(f"{module}/version.xml", "<version>test</version>")
            for path, content in (extras or {}).items():
                zf.writestr(path, content)

    def test_assembles_all_official_zips_without_pom_mutation(self):
        before = {p: p.read_bytes() for p in self.hop.rglob("pom.xml")}
        result = builder.assemble(self.catalog, self.hop, self.repo, self.output)
        self.assertEqual(20, result["requiredP0"])
        self.assertEqual(20, len(result["plugins"]))
        self.assertEqual(before, {p: p.read_bytes() for p in self.hop.rglob("pom.xml")})
        self.assertEqual(20, len(list((self.output / "plugins/transforms").glob("*/*.jar"))))
        self.assertTrue((self.output / "p0-distribution-manifest.json").is_file())
        with self.assertRaisesRegex(ValueError, "refusing overwrite"):
            builder.assemble(self.catalog, self.hop, self.repo, self.output)

    def test_rejects_zip_traversal_and_leaves_no_distribution(self):
        module, artifact = self.artifacts["TableInput"]
        self._zip(module, artifact, {"../../unsafe.txt": "bad"})
        with self.assertRaisesRegex(ValueError, "unsafe ZIP member"):
            builder.assemble(self.catalog, self.hop, self.repo, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse((self.root / "unsafe.txt").exists())

    def test_rejects_missing_jandex(self):
        module, artifact = self.artifacts["TableInput"]
        self._zip(module, artifact, jandex=False)
        with self.assertRaisesRegex(ValueError, "no Jandex index"):
            builder.assemble(self.catalog, self.hop, self.repo, self.output)
        self.assertFalse(self.output.exists())

    def test_rejects_conflicting_shared_dependency(self):
        a, b = self.artifacts["TableInput"], self.artifacts["Rest"]
        self._zip(*a, extras={"lib/core/common.jar": b"one"})
        self._zip(*b, extras={"lib/core/common.jar": b"two"})
        with self.assertRaisesRegex(ValueError, "conflicting shared ZIP member"):
            builder.assemble(self.catalog, self.hop, self.repo, self.output)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_official_zip(self):
        _, artifact = self.artifacts["TableInput"]
        archive = self.repo / "org/apache/hop" / artifact / self.version / f"{artifact}-{self.version}.zip"
        archive.unlink()
        with self.assertRaisesRegex(ValueError, "official Hop plugin ZIP not installed"):
            builder.assemble(self.catalog, self.hop, self.repo, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
