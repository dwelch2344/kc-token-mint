FROM quay.io/keycloak/keycloak:26.6.2 AS builder

COPY target/kc-token-mint-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/

# Pre-build the augmented KC distribution with the provider compiled in.
# oid4vc-vci enables Keycloak's native OpenID for Verifiable Credential Issuance;
# oid4vc-vci-preauth-code enables the pre-authorized code grant (a separate flag
# as of KC 26.6).
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true \
    --features=oid4vc-vci,oid4vc-vci-preauth-code

FROM quay.io/keycloak/keycloak:26.6.2

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
