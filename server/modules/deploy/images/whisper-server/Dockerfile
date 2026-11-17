# whisper-server image recipe.
#
# Build context is the repository root. The host builds the distribution first
# and the image carries the result; nothing is compiled inside the image:
#   ./gradlew :server:modules:whisper-server:installDist
#   docker build -f server/modules/deploy/images/whisper-server/Dockerfile .
#
# Base tag: eclipse-temurin:25-jre. A JRE, not a JDK, because the service runs
# and never compiles. The 25 is the Java version the module targets:
#   server/modules/whisper-server/build.gradle.kts:19-20
#       sourceCompatibility / targetCompatibility = JavaVersion.VERSION_25
#   server/modules/whisper-server/build.gradle.kts:25
#       jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
# Pinned by explicit version, not 'latest' and not a digest; the build host
# resolves the digest when it pins images.
#
# No EXPOSE line: EXPOSE is documentation only, and the listening port is
# published by the compose file (slice 1). The port itself is read from
# BREAKER_WHISPER_PORT at run time; the module deliberately has no default
# (WhisperConfig.kt refuses to start when the variable is unset), so the recipe
# carries no ARG for it either, and the build driver needs no --build-arg.

FROM eclipse-temurin:25-jre

# curl is for the compose health check; installed and cleaned in one layer.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Fixed non-root account (uid/gid 10001 stays clear of the distro users).
RUN groupadd --gid 10001 breaker \
    && useradd --uid 10001 --gid breaker --no-create-home --shell /usr/sbin/nologin breaker

# Data directory, owned before the named volume mounts: Docker copies an image
# directory's ownership into an empty named volume on first mount. The path
# itself comes from BREAKER_WHISPER_DB in the environment; the image only
# creates and owns the directory.
RUN mkdir -p /var/lib/breaker/whisper-server \
    && chown breaker:breaker /var/lib/breaker/whisper-server

# Only the Gradle installDist tree (bin/ and lib/) reaches the image; the
# sibling Dockerfile.dockerignore denies everything else.
COPY server/modules/whisper-server/build/install/whisper-server/ /opt/breaker/whisper-server/

RUN chown -R breaker:breaker /opt/breaker/whisper-server

# USER after every root-requiring instruction, so nothing after it runs as root.
USER breaker
# Exec form: PID 1 is the installDist launcher, which execs the JVM, so SIGTERM
# lands on the service instead of a shell.
ENTRYPOINT ["/opt/breaker/whisper-server/bin/whisper-server"]
