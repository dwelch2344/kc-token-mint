FROM quay.io/keycloak/keycloak:26.5.0 AS builder

COPY target/kc-token-mint-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/

# Pre-build the augmented KC distribution with the provider compiled in
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true

FROM quay.io/keycloak/keycloak:26.5.0

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
