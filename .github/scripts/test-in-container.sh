#!/bin/sh
# Runs the Java tests, compiled on the runner, on the JVM of the container, with the engine
# library that the loader picks for the container. Expects the checkout at /src and the local
# Maven repository of the runner at /m2, both read-only, and java and mvn on the PATH. Maven
# may download what the repository of the runner lacks.
set -eu
cp -r /src /work
cp -r /m2 /root/m2
cd /work
rm -rf target/surefire-reports
ldd --version 2>&1 | head -1
java -version
mvn -B -ntp -Dmaven.repo.local=/root/m2 -Dengine.skip=true surefire:test
