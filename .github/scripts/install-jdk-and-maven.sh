#!/bin/sh
# Installs Temurin 21 to /opt/jdk and Maven to /opt/maven, for distributions whose packages
# carry neither. Expects curl, tar and gzip, and the JDK architecture in JDK_ARCH (x64 or
# aarch64).
set -eu
maven_version=3.9.16
mkdir -p /opt/jdk /opt/maven
curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse" | tar -xz -C /opt/jdk --strip-components 1
curl -fsSL -o /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${maven_version}/binaries/apache-maven-${maven_version}-bin.tar.gz"
curl -fsSL -o /tmp/maven.tar.gz.sha512 "https://archive.apache.org/dist/maven/maven-3/${maven_version}/binaries/apache-maven-${maven_version}-bin.tar.gz.sha512"
echo "$(cut -d' ' -f1 /tmp/maven.tar.gz.sha512)  /tmp/maven.tar.gz" | sha512sum -c -
tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components 1
