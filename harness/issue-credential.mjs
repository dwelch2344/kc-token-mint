#!/usr/bin/env node
// End-to-end OID4VCI issuance check against the running mint-demo realm.
//
// Exercises Keycloak's native `oid4vc-vci` feature via the pre-authorized code
// flow (the path the built-in account-console QR can't drive headlessly because
// its SPA omits the `username` param):
//   1. ROPC -> user access token (oid4vci-rest-api confidential client)
//   2. GET credential-offer-uri?...&username=...&pre_authorized=true -> offer
//   3. exchange pre-authorized_code -> access token (+ c_nonce)
//   4. POST /protocol/oid4vc/nonce (if needed) -> c_nonce
//   5. build an ES256 holder key-binding proof JWT
//   6. POST /protocol/oid4vc/credential -> SD-JWT VC
//   7. verify the issuer signature (realm JWKS) + decode disclosures
//
// Pure Node (built-in crypto + global fetch); no npm dependencies.
import crypto from "node:crypto";

const BASE = process.env.KC_BASE || "http://localhost:8888";
const REALM = "mint-demo";
const CLIENT_ID = "oid4vci-rest-api";
const CLIENT_SECRET = "oid4vci-rest-api-secret";
const USERNAME = "demo-user";
const PASSWORD = "password";
const CONFIG_ID = "IdentityCredential";
const REALM_BASE = `${BASE}/realms/${REALM}`;

const b64url = (buf) => Buffer.from(buf).toString("base64url");
const G = "\x1b[32m", R = "\x1b[31m", Y = "\x1b[33m", N = "\x1b[0m";
const pass = (m) => console.log(`${G}✓${N} ${m}`);
const info = (m) => console.log(`${Y}→${N} ${m}`);
const fail = (m) => { console.error(`${R}✗${N} ${m}`); process.exit(1); };

async function form(url, params) {
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(params),
  });
  if (!r.ok) fail(`${url} -> HTTP ${r.status}: ${await r.text()}`);
  return r.json();
}

function decodeJwt(jwt) {
  const [h, p] = jwt.split(".");
  return {
    header: JSON.parse(Buffer.from(h, "base64url")),
    payload: JSON.parse(Buffer.from(p, "base64url")),
  };
}

async function main() {
  // 1. ROPC -> user token
  info(`Requesting user token for ${USERNAME} via ${CLIENT_ID} (ROPC)...`);
  const userTok = (await form(`${REALM_BASE}/protocol/openid-connect/token`, {
    client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
    username: USERNAME, password: PASSWORD, grant_type: "password", scope: "openid",
  })).access_token;
  pass("Got user access token");

  // 2. credential offer (pre-authorized, bound to the target user)
  info(`Creating pre-authorized credential offer for "${CONFIG_ID}"...`);
  const offerUri = new URL(`${REALM_BASE}/protocol/oid4vc/create-credential-offer`);
  offerUri.searchParams.set("credential_configuration_id", CONFIG_ID);
  offerUri.searchParams.set("username", USERNAME);
  offerUri.searchParams.set("pre_authorized", "true");
  const offerRes = await fetch(offerUri, { headers: { Authorization: `Bearer ${userTok}` } });
  if (!offerRes.ok) fail(`credential-offer-uri -> HTTP ${offerRes.status}: ${await offerRes.text()}`);
  const { issuer, nonce } = await offerRes.json();
  const fullOffer = await (await fetch(`${issuer}/${nonce}`)).json();
  const preAuth = fullOffer?.grants?.["urn:ietf:params:oauth:grant-type:pre-authorized_code"]?.["pre-authorized_code"];
  if (!preAuth) fail(`No pre-authorized_code in offer: ${JSON.stringify(fullOffer)}`);
  pass(`Got credential offer (issuer=${fullOffer.credential_issuer})`);

  // 3. exchange pre-authorized code -> access token
  info("Exchanging pre-authorized_code for an access token...");
  const tokenResp = await form(`${REALM_BASE}/protocol/openid-connect/token`, {
    client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
    grant_type: "urn:ietf:params:oauth:grant-type:pre-authorized_code",
    "pre-authorized_code": preAuth,
  });
  pass("Got pre-authorized access token");

  // 4. c_nonce
  let cNonce = tokenResp.c_nonce;
  if (!cNonce) {
    const nr = await fetch(`${REALM_BASE}/protocol/oid4vc/nonce`, { method: "POST" });
    cNonce = (await nr.json()).c_nonce;
  }
  pass(`Have c_nonce for key binding`);

  // 5. ES256 holder key-binding proof JWT
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  const jwk = publicKey.export({ format: "jwk" });
  const header = { alg: "ES256", typ: "openid4vci-proof+jwt", jwk };
  const payload = { nonce: cNonce, aud: fullOffer.credential_issuer, iat: Math.floor(Date.now() / 1000) };
  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
  const sig = crypto.sign("SHA256", Buffer.from(signingInput), { key: privateKey, dsaEncoding: "ieee-p1363" });
  const proofJwt = `${signingInput}.${b64url(sig)}`;
  pass("Built ES256 key-binding proof");

  // 6. request the credential
  info("Requesting credential from the credential endpoint...");
  const credRes = await fetch(`${REALM_BASE}/protocol/oid4vc/credential`, {
    method: "POST",
    headers: { Authorization: `Bearer ${tokenResp.access_token}`, "Content-Type": "application/json" },
    // KC 26.6 requires requesting by credential_identifier (from the token's
    // authorization_details), not format + credential_configuration_id.
    body: JSON.stringify({
      credential_identifier: CONFIG_ID,
      proof: { proof_type: "jwt", jwt: proofJwt },
    }),
  });
  if (!credRes.ok) fail(`credential endpoint -> HTTP ${credRes.status}: ${await credRes.text()}`);
  const credBody = await credRes.json();
  const sdjwt = credBody?.credentials?.[0]?.credential || credBody?.credential;
  if (!sdjwt) fail(`No credential in response: ${JSON.stringify(credBody)}`);
  pass("Received SD-JWT VC from the issuer");

  // 7. decode + verify
  const [issuerJwt, ...disclosures] = sdjwt.split("~");
  const { header: vh, payload: vp } = decodeJwt(issuerJwt);
  console.log(`\n${Y}Issued credential (SD-JWT VC):${N}`);
  console.log(`  header:  ${JSON.stringify({ alg: vh.alg, typ: vh.typ, kid: vh.kid })}`);
  console.log(`  payload: ${JSON.stringify(vp, null, 2).replace(/\n/g, "\n  ")}`);
  const decoded = disclosures.filter(Boolean).map((d) => {
    try { return JSON.parse(Buffer.from(d, "base64url")); } catch { return d; }
  });
  console.log(`  disclosures (selective): ${JSON.stringify(decoded)}`);
  console.log("");

  // verify issuer signature against realm JWKS
  const jwks = await (await fetch(`${REALM_BASE}/protocol/openid-connect/certs`)).json();
  const key = jwks.keys.find((k) => k.kid === vh.kid) || jwks.keys.find((k) => k.kty === "EC");
  if (!key) fail("No matching issuer key in realm JWKS");
  const pub = crypto.createPublicKey({ key, format: "jwk" });
  const [hh, pp, ss] = issuerJwt.split(".");
  const ok = crypto.verify("SHA256", Buffer.from(`${hh}.${pp}`),
    { key: pub, dsaEncoding: "ieee-p1363" }, Buffer.from(ss, "base64url"));
  ok ? pass("Issuer signature verifies against realm JWKS") : fail("Signature verification FAILED");

  // assertions
  vh.typ && vh.typ.includes("sd-jwt") ? pass(`Credential type header: ${vh.typ}`) : info(`header typ: ${vh.typ}`);
  vp.vct ? pass(`vct: ${vp.vct}`) : fail("missing vct");
  (vp.iss === fullOffer.credential_issuer) ? pass(`iss matches issuer`) : info(`iss=${vp.iss}`);
  vp._sd ? pass(`selective-disclosure digests present (_sd: ${vp._sd.length})`) : info("no _sd array");

  console.log(`\n${G}OID4VCI end-to-end issuance succeeded.${N}`);
}

main().catch((e) => fail(e.stack || String(e)));
