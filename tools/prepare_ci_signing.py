"""Create ephemeral CI signing files from repository secrets; never log their values."""
import base64
import os
from pathlib import Path

names = ("AIRPLAY_KEYSTORE_B64", "AIRPLAY_STORE_PASSWORD", "AIRPLAY_KEY_ALIAS", "AIRPLAY_KEY_PASSWORD")
if not all(os.environ.get(name) for name in names):
    raise SystemExit("Configure all four AIRPLAY signing secrets before publishing a release.")

directory = Path(os.environ["RUNNER_TEMP"]) / "airplay-signing"
directory.mkdir(mode=0o700, exist_ok=True)
keystore = directory / "release.p12"
keystore.write_bytes(base64.b64decode(os.environ["AIRPLAY_KEYSTORE_B64"], validate=True))
keystore.chmod(0o600)

def escape(value):
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

properties = directory / "signing.properties"
properties.write_text("\n".join((
    "storeFile=" + escape(str(keystore)),
    "storePassword=" + escape(os.environ["AIRPLAY_STORE_PASSWORD"]),
    "keyAlias=" + escape(os.environ["AIRPLAY_KEY_ALIAS"]),
    "keyPassword=" + escape(os.environ["AIRPLAY_KEY_PASSWORD"]),
)) + "\n")
properties.chmod(0o600)
with open(os.environ["GITHUB_ENV"], "a") as env_file:
    env_file.write("AIRPLAY_SIGNING_PROPERTIES=" + str(properties) + "\n")
