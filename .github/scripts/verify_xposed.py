#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
EXPECTED_ENTRY = "com.mt8163.tools.xposed.XposedEntry"
errors = []

def fail(message):
    errors.append(message)

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
gradle_path = ROOT / "app/build.gradle"
entry_path = ROOT / "app/src/main/java/com/mt8163/tools/xposed/XposedEntry.java"
init_path = ROOT / "app/src/main/assets/xposed_init"
docs_path = ROOT / "docs/PROJECT_STRUCTURE.md"

try:
    manifest = ET.parse(manifest_path).getroot()
    app = manifest.find("application")
    metadata = {
        item.get(ANDROID_NS + "name"): item
        for item in app.findall("meta-data")
    } if app is not None else {}

    module = metadata.get("xposedmodule")
    if module is None or module.get(ANDROID_NS + "value") != "true":
        fail("Manifest must declare xposedmodule=true.")

    min_version = metadata.get("xposedminversion")
    if min_version is None or min_version.get(ANDROID_NS + "value") != "82":
        fail("Manifest xposedminversion must be exactly 82.")

    scope = metadata.get("xposedscope")
    scope_value = "" if scope is None else scope.get(ANDROID_NS + "value", "")
    scope_targets = {item.strip() for item in scope_value.split(";") if item.strip()}
    required_targets = {"android", "com.android.systemui"}
    if not required_targets.issubset(scope_targets):
        fail("Manifest xposedscope must include android and com.android.systemui, separated by semicolons.")
except (ET.ParseError, OSError, AttributeError) as error:
    fail(f"Cannot validate AndroidManifest.xml: {error}")

try:
    gradle = gradle_path.read_text(encoding="utf-8")
    if not any("compileOnly" in line and "de.robv.android.xposed:api:82" in line
               for line in gradle.splitlines()):
        fail("Legacy Xposed API 82 must remain a compileOnly dependency.")
    if re.search(r"""(?m)^\s*(implementation|api|runtimeOnly)\s+['"].*de\.robv\.android\.xposed""", gradle):
        fail("XposedBridge API must not be packaged as an application/runtime dependency.")
except OSError as error:
    fail(f"Cannot read app/build.gradle: {error}")

try:
    if init_path.read_text(encoding="utf-8").strip() != EXPECTED_ENTRY:
        fail(f"assets/xposed_init must contain only {EXPECTED_ENTRY}.")
except OSError as error:
    fail(f"Cannot read assets/xposed_init: {error}")

try:
    entry_source = entry_path.read_text(encoding="utf-8")
    if "IXposedHookLoadPackage" not in entry_source:
        fail("XposedEntry must implement the Legacy IXposedHookLoadPackage entry interface.")
except OSError as error:
    fail(f"Cannot read XposedEntry.java: {error}")

try:
    docs = docs_path.read_text(encoding="utf-8")
    if "xposedminversion=82" not in docs:
        fail("Project documentation must state xposedminversion=82.")
    if "de.robv.android.xposed:api:82" not in docs:
        fail("Project documentation must explain the Legacy API 82 compile artifact.")
except OSError as error:
    fail(f"Cannot read PROJECT_STRUCTURE.md: {error}")

source_root = ROOT / "app/src/main"
for source in source_root.rglob("*"):
    if source.suffix not in {".java", ".kt"}:
        continue
    try:
        source_text = source.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"Cannot read {source.relative_to(ROOT)}: {error}")
        continue
    if "io.github.libxposed" in source_text:
        fail(f"Modern libxposed reference found in {source.relative_to(ROOT)}; this project uses Legacy XposedBridge.")

if errors:
    print("Xposed configuration validation failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Xposed configuration is consistent: Legacy XposedBridge API 82, scopes android and com.android.systemui.")
