#!/usr/bin/env python3
"""Build and sign either independent Android 9 package outside its source tree.

Requires official Android platform 28 r06, Build Tools 35.0.1, and JDK 17+.
No dependency download, installation, device launch, or functional test is run.
All generated Java/classes/dex/APKs are kept in the explicitly supplied work dir.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def run(args, env):
    """Run a build tool with structured arguments, never exposing signing secrets."""
    subprocess.run([str(arg) for arg in args], env=env, check=True)


def main():
    """Compile the selected module plus shared code, convert dex, align, sign, and verify its APK."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True, type=Path, help="Directory containing Android 28 android.jar")
    parser.add_argument("--module", required=True, choices=["app", "engine"], help="Settings UI or independent background engine")
    parser.add_argument("--tools", required=True, type=Path, help="Android Build Tools 35.0.1 directory")
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path, help="Build directory outside this repository")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--password-file", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    work = args.work.resolve()
    if work == root or root in work.parents:
        raise SystemExit("Build intermediates must remain outside the source repository")
    work.mkdir(parents=True, exist_ok=True)
    for name in ["generated", "classes", "dex"]:
        directory = work / name
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    java_home = args.java_home.resolve()
    tools = args.tools.resolve()
    platform = args.platform.resolve() / "android.jar"
    env = dict(os.environ, JAVA_HOME=str(java_home))
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    source = root / args.module / "src/main"
    common = root / "common/src/main"
    resources = []
    for name, resource_source in [("shared", common / "res"), ("module", source / "res")]:
        if resource_source.exists() and any(resource_source.rglob("*.xml")):
            compiled = work / (name + "-resources.zip")
            run([tools / "aapt2", "compile", "--dir", resource_source, "-o", compiled], env)
            resources.append(compiled)
    run([tools / "aapt2", "link", "-o", work / "resources.apk", "-I", platform,
         "--manifest", source / "AndroidManifest.xml", "--java", work / "generated",
         "--min-sdk-version", "28", "--target-sdk-version", "28", *resources], env)
    java = sorted((source / "java").rglob("*.java")) + sorted((common / "java").rglob("*.java")) + sorted((work / "generated").rglob("*.java"))
    run([java_home / "bin/javac", "--release", "8", "-encoding", "UTF-8", "-Xlint:all",
         "-classpath", platform, "-d", work / "classes", *java], env)
    run([tools / "d8", "--release", "--min-api", "28", "--lib", platform,
         "--output", work / "dex", *sorted((work / "classes").rglob("*.class"))], env)
    unsigned = work / "unsigned.apk"
    shutil.copyfile(work / "resources.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
        for dex in sorted((work / "dex").glob("*.dex")):
            apk.write(dex, dex.name)
    aligned = work / "aligned.apk"
    run([tools / "zipalign", "-f", "4", unsigned, aligned], env)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run([tools / "apksigner", "sign", "--ks", args.keystore.resolve(), "--ks-key-alias", "autobrightness",
         "--ks-pass", "file:" + str(args.password_file.resolve()), "--out", output, aligned], env)
    run([tools / "apksigner", "verify", "--verbose", output], env)
    run([tools / "aapt2", "dump", "badging", output], env)
    print(f"Built {args.module}: {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
