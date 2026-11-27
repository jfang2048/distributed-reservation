import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory

from build import JAR, ROOT, build, java_tool, run_cli


def main():
    java, javac = java_tool("java"), java_tool("javac")
    build()
    # Compile unit tests against the packaged classes without including the tests in the JAR.
    with TemporaryDirectory(prefix="reservation-test-") as directory:
        classes = Path(directory)
        unit_sources = sorted((ROOT / "tests" / "unit").rglob("*.java"))
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        "-cp", JAR, "-d", classes, *unit_sources], check=True)
        classpath = os.pathsep.join((str(JAR), str(classes)))
        subprocess.run([java, "-cp", classpath, "reservation.UnitTests"], check=True)
    # Give the integration runner the exact JVM and JAR used by the unit tests.
    env = os.environ.copy()
    env.update(RESERVATION_JAVA=java, RESERVATION_JAR=str(JAR))
    subprocess.run([sys.executable, "-B", "-m", "unittest", "discover",
                    "-s", ROOT / "tests" / "integration", "-v"], cwd=ROOT, env=env, check=True)


if __name__ == "__main__":
    sys.exit(run_cli(main))
