# Keycloak 26.7.3 with the Phase Two providers layered in.
#
# Phase Two publishes `quay.io/phasetwo/phasetwo-keycloak:<keycloak-version>`, but that image
# trails upstream Keycloak by a minor or two (newest tag at the time of writing: 26.6.6). The
# image is nothing more than an official Keycloak base with a set of provider jars dropped into
# /opt/keycloak/providers, so we reproduce it here on top of the Keycloak version we actually
# target. That gives us keycloak-orgs, keycloak-events, the admin portal and the bundled
# `keycloak-rest-provider` (used by the slow-migration flow) running on a genuine 26.7.3 server.
#
# Once Phase Two ships a 26.7.x tag this file can collapse back to a plain `FROM` of that image.

ARG PHASETWO_IMAGE=quay.io/phasetwo/phasetwo-keycloak
ARG PHASETWO_TAG=26.6.6
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak
ARG KEYCLOAK_TAG=26.7.3

FROM ${PHASETWO_IMAGE}:${PHASETWO_TAG} AS phasetwo

FROM ${KEYCLOAK_IMAGE}:${KEYCLOAK_TAG}
COPY --from=phasetwo /opt/keycloak/providers/ /opt/keycloak/providers/
