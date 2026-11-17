#!/bin/sh
# Render the nginx site config from the template, then exec the command.
# Only BREAKER_WEB_FE_PORT is substituted; every other $ in the template is
# left for nginx itself.
set -eu
: "${BREAKER_WEB_FE_PORT:?BREAKER_WEB_FE_PORT is required}"
envsubst '${BREAKER_WEB_FE_PORT}' \
    < /etc/nginx/nginx.conf.template \
    > /var/lib/nginx/nginx.conf
exec "$@"
