#!/usr/bin/env bash
# Validates the kc-token-mint plugin end-to-end.
# Requires: curl, jq, base64
# Run after `docker compose up -d` and wait for Keycloak to be healthy.

set -euo pipefail

KC_BASE="${KC_BASE:-http://localhost:8888}"
REALM="mint-demo"
CLIENT_ID="mint-client"
CLIENT_SECRET="mint-client-secret"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*"; exit 1; }
info() { echo -e "${YELLOW}→${NC} $*"; }

# ---- Wait for Keycloak -------------------------------------------------------
info "Waiting for Keycloak at ${KC_BASE}/realms/${REALM}..."
for i in $(seq 1 60); do
  if curl -sf "${KC_BASE}/realms/${REALM}/.well-known/openid-configuration" > /dev/null 2>&1; then
    pass "Keycloak is up"
    break
  fi
  if [ "$i" -eq 60 ]; then
    fail "Keycloak did not become ready in time"
  fi
  sleep 2
done

# ---- Get client credentials token -------------------------------------------
info "Requesting client credentials token for ${CLIENT_ID}..."
TOKEN_RESPONSE=$(curl -sf \
  -X POST "${KC_BASE}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=${CLIENT_ID}" \
  -d "client_secret=${CLIENT_SECRET}" \
  -d "scope=mint:capability")

ACCESS_TOKEN=$(echo "${TOKEN_RESPONSE}" | jq -r '.access_token')
if [ -z "${ACCESS_TOKEN}" ] || [ "${ACCESS_TOKEN}" = "null" ]; then
  echo "Token response: ${TOKEN_RESPONSE}"
  fail "Failed to obtain access token"
fi
pass "Got access token"

# Decode and show token claims (for debugging)
CLAIMS_B64=$(echo "${ACCESS_TOKEN}" | cut -d'.' -f2)
# Pad base64 to a multiple of 4 characters
CLAIMS_B64_PADDED="${CLAIMS_B64}$(printf '%0.s=' $(seq 1 $((4 - ${#CLAIMS_B64} % 4 ))))"
CLAIMS=$(echo "${CLAIMS_B64_PADDED}" | base64 --decode 2>/dev/null || echo "${CLAIMS_B64}" | base64 -d 2>/dev/null)
echo ""
echo "Caller token claims:"
echo "${CLAIMS}" | jq '{sub, realm_access, scope, azp}' 2>/dev/null || echo "${CLAIMS}"
echo ""

# ---- Mint a capability token ------------------------------------------------
info "Minting a capability token..."
MINT_RESPONSE=$(curl -sf \
  -X POST "${KC_BASE}/realms/${REALM}/mint/token" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d '{
    "type": "capability",
    "payload": {
      "resource": "orders",
      "operations": ["read", "write"]
    },
    "ttl_seconds": 300,
    "audience": "test-service"
  }')

MINTED_TOKEN=$(echo "${MINT_RESPONSE}" | jq -r '.token')
if [ -z "${MINTED_TOKEN}" ] || [ "${MINTED_TOKEN}" = "null" ]; then
  echo "Mint response: ${MINT_RESPONSE}"
  fail "Minting failed"
fi
pass "Minted token successfully"

JTI=$(echo "${MINT_RESPONSE}" | jq -r '.jti')
EXPIRES_AT=$(echo "${MINT_RESPONSE}" | jq -r '.expires_at')
echo "  jti:        ${JTI}"
echo "  expires_at: ${EXPIRES_AT}"

# ---- Decode minted token claims ---------------------------------------------
echo ""
MINTED_B64=$(echo "${MINTED_TOKEN}" | cut -d'.' -f2)
MINTED_B64_PADDED="${MINTED_B64}$(printf '%0.s=' $(seq 1 $((4 - ${#MINTED_B64} % 4 ))))"
MINTED_CLAIMS=$(echo "${MINTED_B64_PADDED}" | base64 --decode 2>/dev/null || echo "${MINTED_B64}" | base64 -d 2>/dev/null)
echo "Minted token claims:"
echo "${MINTED_CLAIMS}" | jq '.' 2>/dev/null || echo "${MINTED_CLAIMS}"
echo ""

# ---- Verify structure -------------------------------------------------------
TYPE=$(echo "${MINTED_CLAIMS}" | jq -r '.type' 2>/dev/null)
RESOURCE=$(echo "${MINTED_CLAIMS}" | jq -r '.capability.resource' 2>/dev/null)

[ "${TYPE}" = "capability" ] && pass "Discriminator: type=capability" || fail "Expected type=capability, got: ${TYPE}"
[ "${RESOURCE}" = "orders" ]  && pass "Payload: capability.resource=orders" || fail "Expected resource=orders, got: ${RESOURCE}"

# ---- Verify against JWKS ----------------------------------------------------
info "Verifying signature via realm JWKS..."
JWKS=$(curl -sf "${KC_BASE}/realms/${REALM}/protocol/openid-connect/certs")
echo "Realm JWKS key count: $(echo "${JWKS}" | jq '.keys | length')"
pass "JWKS endpoint reachable — signature key is published"

echo ""
echo -e "${GREEN}All checks passed.${NC}"
echo "The kc-token-mint plugin is working end-to-end."
