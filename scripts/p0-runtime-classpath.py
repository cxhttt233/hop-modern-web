#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

P0_IDS = {
    "CheckSum", "ConcatFields", "DataGrid", "ExecSql", "FilterRows", "GroupBy", "Http",
    "InsertUpdate", "JsonInput", "MergeJoin", "ReplaceString", "Rest", "ScriptValueMod",
    "SelectValues", "SetVariable", "StreamLookup", "StringCut", "TableInput", "TableOutput",
    "UniqueRowsByHashSet",
}

def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: p0-runtime-classpath.py <source-catalog.json> <hop-root> <server-pom>")
    catalog = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    hop_root = Path(sys.argv[2]).resolve()
    server_pom = Path(sys.argv[3]).resolve()
    by_id = {row["id"]: row for row in catalog if row.get("id")}
    missing = sorted(P0_IDS - set(by_id))
    if missing:
        raise SystemExit("P0 ids absent from source catalog: " + ", ".join(missing))
    modules = sorted({by_id[plugin_id]["module"] for plugin_id in P0_IDS})
    artifacts = []
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module in modules:
        pom = hop_root / module / "pom.xml"
        if not pom.is_file():
            raise SystemExit(f"P0 module pom missing: {module}")
        root = ET.parse(pom).getroot()
        artifact = root.findtext("m:artifactId", namespaces=ns)
        if not artifact:
            raise SystemExit(f"P0 module artifactId missing: {module}")
        artifacts.append((module, artifact))
    text = server_pom.read_text(encoding="utf-8")
    marker = "  </dependencies>"
    if text.count(marker) != 1:
        raise SystemExit("server pom dependencies marker is not unique")
    existing_root = ET.fromstring(text)
    existing_dependencies = existing_root.find("m:dependencies", ns)
    if existing_dependencies is None:
        raise SystemExit("server pom has no dependencies element")
    existing_hop_artifacts = {
        dependency.findtext("m:artifactId", namespaces=ns)
        for dependency in existing_dependencies.findall("m:dependency", ns)
        if dependency.findtext("m:groupId", namespaces=ns) == "org.apache.hop"
    }
    deps = []
    for _, artifact in artifacts:
        if artifact in existing_hop_artifacts:
            continue
        deps.append(
            "    <dependency>\n"
            "      <groupId>org.apache.hop</groupId>\n"
            f"      <artifactId>{artifact}</artifactId>\n"
            "      <version>" + "$" + "{hop.version}</version>\n"
            "      <scope>test</scope>\n"
            "    </dependency>"
        )
    text = text.replace(marker, "\n".join(deps) + "\n" + marker)
    server_pom.write_text(text, encoding="utf-8")
    print("P0_SOURCE=20/20")
    print("P0_MODULE_COUNT=" + str(len(modules)))
    print("P0_MODULES=" + ",".join(modules))
    print("P0_ARTIFACTS=" + ",".join(a for _, a in artifacts))
    print("P0_MAVEN_PL=" + ",".join(modules))

if __name__ == "__main__":
    main()
