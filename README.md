# Keycloak Token Minting

Description tbd.

# Notes

```bash
# get a token
ACCESS_TOKEN=$(curl -sf -X POST http://localhost:8888/realms/mint-demo/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=mint-client" \
  -d "client_secret=mint-client-secret" \
  -d "scope=mint:capability" \
  | jq -r '.access_token') && echo "Got token"

# now mint something (schema matters!)
curl -s -X POST http://localhost:8888/realms/mint-demo/mint/token \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -d '{
    "type": "capability",
    "payload": {
      "resource": "orders",
      "operations": ["read", "write"]
    },
    "ttl_seconds": 300,
    "audience": "test-service"
  }' | jq -r .token
```
