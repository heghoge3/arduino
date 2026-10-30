#!/usr/bin/env python3
"""Build a release with the owner's persistent key; never fall back to a debug key."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, help="Private JSON file, outside the repository")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    raw = args.config.read_text() if args.config else os.environ.get("ANDROID_SIGNING_BUNDLE")
    if not raw:
        raise SystemExit("Configure ANDROID_SIGNING_BUNDLE in GitHub Secrets; see docs/android-signing.md")
    try:
        config = json.loads(raw)
        for field in ("keystoreBase64", "storePassword", "keyPassword", "keyAlias"):
            if not isinstance(config.get(field), str) or not config[field]:
                raise ValueError()
        key = base64.b64decode(config["keystoreBase64"], validate=True)
    except (ValueError, TypeError, AttributeError):
        raise SystemExit("Invalid private signing configuration") from None

    expected = (root / "signing-certificate.sha256").read_text().strip().lower()
    env = os.environ.copy()
    env.pop("ANDROID_SIGNING_BUNDLE", None)
    env.update(VABUGEODE_STORE_PASSWORD=config["storePassword"],
               VABUGEODE_KEY_PASSWORD=config["keyPassword"],
               VABUGEODE_KEY_ALIAS=config["keyAlias"])
    with tempfile.TemporaryDirectory(prefix="vabugeode-signing-") as directory:
        keystore = Path(directory) / "release.p12"
        keystore.write_bytes(key)
        keystore.chmod(0o600)
        env["VABUGEODE_KEYSTORE_PATH"] = str(keystore)
        result = subprocess.run([
            "keytool", "-exportcert", "-keystore", str(keystore), "-storetype", "PKCS12",
            "-alias", config["keyAlias"], "-storepass:env", "VABUGEODE_STORE_PASSWORD",
        ], env=env, capture_output=True)
        if result.returncode:
            raise SystemExit("Cannot open signing key with the supplied configuration")
        if hashlib.sha256(result.stdout).hexdigest() != expected:
            raise SystemExit("Wrong signing certificate: refusing to build an incompatible update")
        wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        subprocess.run([str(wrapper), "--no-daemon", "--max-workers=4", "assembleRelease"],
                       cwd=root, env=env, check=True)
        sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise SystemExit("ANDROID_HOME is required to verify the final APK")
        executable = "apksigner.bat" if os.name == "nt" else "apksigner"
        candidates = list((Path(sdk) / "build-tools").glob(f"*/{executable}"))
        if not candidates:
            raise SystemExit("Android SDK apksigner is missing")
        apksigner = sorted(candidates)[-1]
        apk = root / "app/build/outputs/apk/release/app-release.apk"
        result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                                env=env, capture_output=True, text=True)
        if result.returncode or f"certificate SHA-256 digest: {expected}" not in result.stdout:
            raise SystemExit("Final APK signature verification failed")
    print(f"Signed APK verified: {apk}\nCertificate SHA-256: {expected}")


if __name__ == "__main__":
    main()
