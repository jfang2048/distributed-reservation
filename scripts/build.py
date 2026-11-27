import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "build" / "reservation.jar"


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    executable = name + ".exe" if os.name == "nt" else name
    command = Path(home) / "bin" / executable if home else executable
    path = shutil.which(str(command))
    if path is None:
        raise RuntimeError(f"Install JDK 17 or newer. Set JAVA_HOME or add {name} to PATH.")
    return str(Path(path).absolute())


def build():
    sources = sorted((ROOT / "src").rglob("*.java"))
    if not sources:
        raise RuntimeError("Add Java sources under src/ before building.")
    javac, jar = java_tool("javac"), java_tool("jar")
    JAR.parent.mkdir(exist_ok=True)
    with TemporaryDirectory(prefix="compile-", dir=JAR.parent) as directory:
        work = Path(directory)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8",
                        "-d", classes, *sources], check=True)
        if not (classes / "reservation" / "Main.class").is_file():
            raise RuntimeError("Add the reservation.Main entry point to src/.")
        output = work / "reservation.jar"
        subprocess.run([jar, "--create", "--file", output, "--main-class",
                        "reservation.Main", "-C", classes, "."], check=True)
        output.replace(JAR)
    print(JAR.relative_to(ROOT), flush=True)


def run_cli(main):
    try:
        return main()
    except subprocess.CalledProcessError as error:
        return error.returncode
    except (OSError, RuntimeError) as error:
        print(error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(run_cli(build))
