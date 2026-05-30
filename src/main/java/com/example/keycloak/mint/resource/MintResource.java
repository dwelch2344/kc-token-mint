package com.example.keycloak.mint.resource;

import com.example.keycloak.mint.audit.MintEventLogger;
import com.example.keycloak.mint.schema.FetchedSchema;
import com.example.keycloak.mint.schema.SchemaFetchException;
import com.example.keycloak.mint.schema.SchemaFetcher;
import com.example.keycloak.mint.token.TokenBuilder;
import com.example.keycloak.mint.token.TypeConfig;
import com.example.keycloak.mint.token.TypeConfigLoader;
import com.example.keycloak.mint.validation.PayloadValidator;
import com.example.keycloak.mint.validation.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class MintResource {

    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private static final Set<String> RESERVED_TYPES = Set.of(
            "iss", "sub", "aud", "exp", "iat", "nbf", "jti", "azp", "act", "scope", "type"
    );

    private final KeycloakSession session;
    private final BearerAuthenticator authenticator;
    private final MintEventLogger eventLogger;
    private final SchemaFetcher schemaFetcher;
    private final List<PayloadValidator> validators;
    private final TypeConfigLoader typeConfigLoader;
    private final TokenBuilder tokenBuilder;
    private final int maxPayloadBytes;
    private final int maxPayloadDepth;

    public MintResource(
            KeycloakSession session,
            BearerAuthenticator authenticator,
            MintEventLogger eventLogger,
            SchemaFetcher schemaFetcher,
            List<PayloadValidator> validators,
            TypeConfigLoader typeConfigLoader,
            TokenBuilder tokenBuilder,
            int maxPayloadBytes,
            int maxPayloadDepth) {
        this.session = session;
        this.authenticator = authenticator;
        this.eventLogger = eventLogger;
        this.schemaFetcher = schemaFetcher;
        this.validators = validators;
        this.typeConfigLoader = typeConfigLoader;
        this.tokenBuilder = tokenBuilder;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxPayloadDepth = maxPayloadDepth;
    }

    @GET
    @Path("ui")
    @Produces(MediaType.TEXT_HTML)
    public Response ui() {
        String realm = session.getContext().getRealm().getName();
        String html = loadHtml().replace("{{REALM}}", realm);
        return Response.ok(html, MediaType.TEXT_HTML + ";charset=UTF-8").build();
    }

    private String loadHtml() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mint-ui.html")) {
            if (is == null) throw new RuntimeException("mint-ui.html not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load mint-ui.html", e);
        }
    }

    @POST
    @Path("token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mint(MintRequest request) {
        RealmModel realm = session.getContext().getRealm();

        // Step 1: authenticate bearer token
        AuthenticationManager.AuthResult authResult = authenticator.authenticate();
        if (authResult == null) {
            return error(Response.Status.UNAUTHORIZED, MintErrorResponse.unauthorized());
        }
        AccessToken callerToken = authResult.getToken();

        // Step 2: role gate
        AccessToken.Access realmAccess = callerToken.getRealmAccess();
        if (realmAccess == null || !realmAccess.getRoles().contains("token-minter")) {
            return error(Response.Status.FORBIDDEN,
                    MintErrorResponse.forbidden("Caller does not have the 'token-minter' realm role"));
        }

        // Step 3: type field present and valid format
        String type = request.getType();
        if (type == null || type.isBlank()) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("'type' field is required"));
        }
        if (!TYPE_PATTERN.matcher(type).matches()) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("'type' must match ^[a-z][a-z0-9_-]*$"));
        }

        // Step 4: type must not be reserved
        if (RESERVED_TYPES.contains(type)) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("'type' value '" + type + "' is reserved"));
        }

        // Step 5: scope gate — caller must have mint:<type>
        String scopeClaim = callerToken.getScope();
        boolean hasMintScope = scopeClaim != null &&
                Arrays.asList(scopeClaim.split(" ")).contains("mint:" + type);
        if (!hasMintScope) {
            return error(Response.Status.FORBIDDEN,
                    MintErrorResponse.forbidden("Caller does not have scope 'mint:" + type + "'"));
        }

        String callerClientId = callerToken.getIssuedFor() != null
                ? callerToken.getIssuedFor()
                : (authResult.getClient() != null ? authResult.getClient().getClientId() : "unknown");

        // Step 6: load type configuration
        Optional<TypeConfig> typeConfigOpt = typeConfigLoader.load(session, realm, type);
        if (typeConfigOpt.isEmpty()) {
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    MintErrorResponse.internalError("No configuration found for type '" + type + "'"));
        }
        TypeConfig typeConfig = typeConfigOpt.get();

        // Step 7: TTL validation
        long ttlSeconds = request.getTtlSeconds();
        if (ttlSeconds <= 0 || ttlSeconds > typeConfig.maxTtl().getSeconds()) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest(
                            "'ttl_seconds' must be between 1 and " + typeConfig.maxTtl().getSeconds()));
        }

        // Step 8: audience validation
        String audience = request.getAudience();
        if (audience == null || !typeConfig.allowedAudiences().contains(audience)) {
            return error(Response.Status.FORBIDDEN,
                    MintErrorResponse.forbidden("Audience '" + audience + "' is not permitted for type '" + type + "'"));
        }

        // Step 9: payload size and depth
        JsonNode payload = request.getPayload();
        if (payload == null) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("'payload' field is required"));
        }
        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (payloadBytes.length > maxPayloadBytes) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("Payload exceeds maximum size of " + maxPayloadBytes + " bytes"));
        }
        if (depth(payload) > maxPayloadDepth) {
            return error(Response.Status.BAD_REQUEST,
                    MintErrorResponse.badRequest("Payload nesting depth exceeds maximum of " + maxPayloadDepth));
        }

        // Step 10: fetch schema
        FetchedSchema fetchedSchema;
        try {
            fetchedSchema = schemaFetcher.fetch(typeConfig.schemaUri(), typeConfig.schemaContentTypeOverride());
        } catch (SchemaFetchException e) {
            eventLogger.logFailure(callerClientId, type, "schema_fetch_failed");
            return error(Response.Status.SERVICE_UNAVAILABLE, MintErrorResponse.schemaFetchFailed());
        }

        // Step 11: validate payload against schema
        PayloadValidator validator = validators.stream()
                .filter(v -> v.supports(fetchedSchema.contentType()))
                .findFirst()
                .orElse(null);
        if (validator == null) {
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    MintErrorResponse.internalError("No validator for content type: " + fetchedSchema.contentType()));
        }
        ValidationResult validationResult = validator.validate(payload, fetchedSchema);
        if (!validationResult.valid()) {
            eventLogger.logFailure(callerClientId, type, "payload_validation_failed");
            return Response.status(422).entity(
                    MintErrorResponse.payloadInvalid(String.join("; ", validationResult.errors()))).build();
        }

        // Step 12: build JWT, audit, respond
        try {
            String signedToken = tokenBuilder.build(
                    realm, session, callerClientId, type, payload, audience, Duration.ofSeconds(ttlSeconds));

            long expiresAt = System.currentTimeMillis() / 1000 + ttlSeconds;
            String jti = extractJti(signedToken);

            eventLogger.logSuccess(callerClientId, type, audience, ttlSeconds, jti);
            return Response.ok(new MintResponse(signedToken, expiresAt, jti)).build();
        } catch (Exception e) {
            eventLogger.logFailure(callerClientId, type, "token_build_failed");
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    MintErrorResponse.internalError("Token signing failed"));
        }
    }

    private Response error(Response.Status status, MintErrorResponse body) {
        return Response.status(status).entity(body).build();
    }

    private int depth(JsonNode node) {
        if (node == null || node.isValueNode()) return 0;
        int max = 0;
        var iter = node.elements();
        while (iter.hasNext()) {
            int d = depth(iter.next());
            if (d > max) max = d;
        }
        return max + 1;
    }

    private String extractJti(String signedJwt) {
        try {
            String[] parts = signedJwt.split("\\.");
            if (parts.length < 2) return "";
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode claims = mapper.readTree(decoded);
            JsonNode jtiNode = claims.get("jti");
            return jtiNode != null ? jtiNode.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
