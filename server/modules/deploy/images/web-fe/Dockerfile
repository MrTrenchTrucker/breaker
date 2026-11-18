# web-fe image recipe.
#
# Build context is the repository root. The host builds the static tree first
# and the image carries the result; nothing is compiled inside the image:
#   ./gradlew :server:modules:web-fe:assemble
#   docker build -f server/modules/deploy/images/web-fe/Dockerfile .
# assemble depends on copyWebLibs (web-fe/build.gradle.kts:64-65), which writes
# the vendored libraries to build/web/js/vendor/ (:41-50).
#
# Base tag: debian:trixie-slim. Decision D14 says only "Web FE = Debian
# container" (docs/05-decisions-and-risks.md:73) without naming a release, so
# the stable Debian slim is pinned explicitly; not 'latest'.
#
# No EXPOSE line: EXPOSE is documentation only, and the listening port is
# published by the compose file (slice 1). The port itself is read from
# BREAKER_WEB_FE_PORT at run time; the recipe carries no ARG for it, so
# nothing dead is left behind.

FROM debian:trixie-slim

# nginx-light from Debian's own archive, plus gettext-base for envsubst at
# container start; installed and cleaned in one layer.
RUN apt-get update \
    && apt-get install -y --no-install-recommends nginx-light gettext-base \
    && rm -rf /var/lib/apt/lists/*

# Fixed non-root account and the writable runtime directories nginx needs when
# its master process runs unprivileged.
RUN groupadd --gid 10001 breaker \
    && useradd --uid 10001 --gid breaker --no-create-home --shell /usr/sbin/nologin breaker \
    && mkdir -p /var/lib/nginx/body \
    && chown -R breaker:breaker /var/lib/nginx

# Static site: the served resources, the vendored library copy, and the design
# tokens the page links. The tokens file is shared, not part of the web-fe
# module; the sibling Dockerfile.dockerignore allows exactly that one file.
COPY server/modules/web-fe/src/main/resources/web/ /usr/share/nginx/html/
COPY server/modules/web-fe/build/web/js/vendor/ /usr/share/nginx/html/js/vendor/
COPY shared/modules/ui-tokens/src/main/resources/tokens.css /usr/share/nginx/html/css/tokens.css

# Recipe assets: the site config template and the entrypoint that renders it.
COPY server/modules/deploy/images/web-fe/nginx.conf.template /etc/nginx/nginx.conf.template
COPY server/modules/deploy/images/web-fe/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
# The host checkout may carry a restrictive umask's 0600 modes; make the
# copied trees world-readable/executable so the non-root account can always
# read them (the entrypoint already needed +x, the template needed +r).
RUN chmod -R a+rX /usr/share/nginx/html \
    && chmod a+rx /etc/nginx/nginx.conf.template /usr/local/bin/docker-entrypoint.sh

# USER after every root-requiring instruction, so nothing after it runs as root.
USER breaker
# The entrypoint renders the config and execs nginx in the foreground, so PID 1
# is nginx and receives SIGTERM.
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["nginx", "-c", "/var/lib/nginx/nginx.conf", "-g", "daemon off;"]
