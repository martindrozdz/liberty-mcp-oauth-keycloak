#!/usr/bin/env bash
# Fetch an access token from Keycloak using the password grant (demo/testing only).
# Usage: ./get-token.sh            -> prints the full token response (JSON)
#        ./get-token.sh -a         -> prints only the access_token
set -euo pipefail

cd "$(dirname "$0")"

KEYCLOAK_URL="${KEYCLOAK_URL:-https://localhost:8443}"
REALM="${REALM:-liberty-demo}"
CLIENT_ID="${CLIENT_ID:-liberty-mcp-server}"
USERNAME="${KC_USERNAME:-testadmin}"
PASSWORD="${KC_PASSWORD:-testadmin123}"
SCOPE="${SCOPE:-mcp-demo-server}"

# Client secret: env var wins, otherwise read it from server.env
if [[ -z "${CLIENT_SECRET:-}" ]]; then
  CLIENT_SECRET="$(grep '^OAUTH_CLIENT_SECRET=' src/main/liberty/config/server.env | cut -d= -f2-)"
fi

RESPONSE="$(curl -k -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -d client_id="${CLIENT_ID}" \
  -d client_secret="${CLIENT_SECRET}" \
  -d username="${USERNAME}" \
  -d password="${PASSWORD}" \
  -d scope="${SCOPE}" \
  -d grant_type=password)"

if [[ "${1:-}" == "-a" ]]; then
  echo "${RESPONSE}" | jq -r '.access_token'
else
  echo "${RESPONSE}" | jq .
fi
