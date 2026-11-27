import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory

from build import JAR, ROOT, java_tool, run_cli


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ("server", "reserve"):
        print("Usage: python scripts/local.py {server --node ID | reserve ITEM...}", file=sys.stderr)
        return 2
    if not JAR.is_file():
        raise RuntimeError("Run python scripts/build.py to build the JAR.")
    java = java_tool("java")
    runtime = ROOT / "run" / "local"
    config = runtime / "config"
    config.mkdir(parents=True, exist_ok=True)
    profile = ROOT / "config" / "cluster.local.properties"
    with TemporaryDirectory(prefix="config-", dir=config) as directory:
        staged = Path(directory) / "cluster.properties"
        shutil.copyfile(profile, staged)
        staged.replace(config / "cluster.properties")
    command = [java, "-jar", str(JAR), *args]
    if os.name != "nt":
        os.chdir(runtime)
        os.execv(java, command)
    return subprocess.run(command, cwd=runtime).returncode


if __name__ == "__main__":
    sys.exit(run_cli(main))
