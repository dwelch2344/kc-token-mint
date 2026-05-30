// Injects a "Credentials" item into the Account Console nav that renders the
// token-mint UI (/realms/{realm}/mint/ui) in an overlay iframe. Loaded via the
// account theme's `scripts=` property. Runs in the account console page context.
(function () {
  "use strict";

  var realm = (location.pathname.match(/\/realms\/([^/]+)\/account/) || [])[1];
  if (!realm) return;
  var MINT_URL = location.origin + "/realms/" + realm + "/mint/ui?embed=1";

  var overlay = null;
  var iframe = null;
  var rafId = 0;

  function ensureOverlay() {
    if (overlay) return;
    overlay = document.createElement("div");
    overlay.id = "mint-credentials-overlay";
    // z-index must clear PatternFly's .pf-c-page__main (z-index:100).
    overlay.style.cssText =
      "position:fixed;z-index:1000;background:var(--pf-v5-global--BackgroundColor--100);display:none;overflow:hidden;";
    iframe = document.createElement("iframe");
    iframe.title = "Credentials";
    iframe.style.cssText = "width:100%;height:100%;border:0;display:block;";
    iframe.src = MINT_URL;
    overlay.appendChild(iframe);
    document.body.appendChild(overlay);
  }

  function position() {
    var main = document.querySelector("main") || document.getElementById("main-content");
    if (!main || !overlay) return;
    var r = main.getBoundingClientRect();
    overlay.style.top = r.top + "px";
    overlay.style.left = r.left + "px";
    overlay.style.width = r.width + "px";
    overlay.style.height = r.height + "px";
  }

  // PatternFly marks the active nav item with the `pf-m-current` class (plus
  // aria-current). Toggle both together.
  var CURRENT_CLASS = "pf-m-current";

  function setCurrent(link, on) {
    if (!link) return;
    link.classList.toggle(CURRENT_CLASS, on);
    if (on) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }

  function navLinks() {
    var nav = document.querySelector("nav");
    return nav ? Array.prototype.slice.call(nav.querySelectorAll("a")) : [];
  }

  // Re-apply the active marker to the nav item matching the real route (used
  // when leaving the Credentials view).
  function restoreRouteCurrent() {
    navLinks().forEach(function (a) {
      if (a.id === "mint-credentials-link") return;
      var path;
      try { path = new URL(a.href, location.origin).pathname; } catch (e) { path = ""; }
      setCurrent(a, path === location.pathname);
    });
  }

  function show() {
    ensureOverlay();
    overlay.style.display = "block";
    cancelAnimationFrame(rafId);
    (function loop() {
      position();
      if (overlay.style.display === "block") rafId = requestAnimationFrame(loop);
    })();
    // Only the Credentials item should look active while its panel is open.
    navLinks().forEach(function (a) { setCurrent(a, false); });
    setCurrent(document.getElementById("mint-credentials-link"), true);
  }

  function hide() {
    if (overlay) overlay.style.display = "none";
    cancelAnimationFrame(rafId);
    setCurrent(document.getElementById("mint-credentials-link"), false);
    restoreRouteCurrent();
  }

  function injectNav() {
    if (document.getElementById("mint-credentials-link")) return;
    var nav = document.querySelector("nav");
    if (!nav) return;
    var sample = nav.querySelector("a");
    if (!sample) return;
    var li = sample.closest("li");
    if (!li || !li.parentElement) return;

    var newLi = li.cloneNode(true);
    var a = newLi.querySelector("a");
    if (!a) return;
    a.id = "mint-credentials-link";
    a.setAttribute("href", "#credentials");
    a.textContent = "Credentials";
    // The cloned node may have come from the active item — clear its marker so
    // Credentials isn't permanently shown as selected.
    newLi.classList.remove(CURRENT_CLASS);
    setCurrent(a, false);
    a.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      show();
    });
    li.parentElement.appendChild(newLi);
  }

  // Hide the overlay when any other nav link is clicked (delegated so it
  // survives React re-renders that replace the nav DOM).
  document.addEventListener(
    "click",
    function (e) {
      var a = e.target && e.target.closest ? e.target.closest("a") : null;
      if (!a || a.id === "mint-credentials-link") return;
      if (a.closest("nav")) hide();
    },
    true
  );

  // The account console mounts asynchronously and re-renders the nav, so keep
  // re-injecting whenever the DOM changes.
  new MutationObserver(injectNav).observe(document.body, {
    childList: true,
    subtree: true,
  });
  injectNav();
})();

// ── OID4VCI "Verifiable Credentials" page fix ────────────────────────────────
// The native account console generates its offer QR via an <img> pointing at
// .../create-credential-offer?...&type=qr-code. In this Keycloak version the
// account-ui sends credential_configuration_id=undefined (issuer metadata
// returns id:null) AND the qr-code endpoint is gone — so the QR is always
// broken. We recreate the offer correctly and render a copyable textual offer,
// plus a working client-side QR in an "Experimental" rollup.
(function () {
  "use strict";

  var realm = (location.pathname.match(/\/realms\/([^/]+)\/account/) || [])[1];
  if (!realm) return;
  var REALM_BASE = location.origin + "/realms/" + realm;

  // Load the QR generator as a CLASSIC script so its UMD wrapper exposes the
  // window.qrcode global (loaded as an ES module via scripts= it does not).
  (function loadQrLib() {
    if (window.qrcode || document.getElementById("mint-qrcode-lib")) return;
    var me = document.querySelector('script[src*="mint-account/js/mint-credentials.js"]');
    if (!me) return;
    var s = document.createElement("script");
    s.id = "mint-qrcode-lib";
    s.src = me.src.replace("mint-credentials.js", "qrcode.js");
    document.head.appendChild(s);
  })();

  var token = null;
  var origFetch = window.fetch.bind(window);

  function decodeJwt(t) {
    try { return JSON.parse(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))); }
    catch (e) { return null; }
  }

  // Capture the account console's access token: primarily from the token
  // endpoint response (always fetched on load/refresh), and from any Bearer
  // header as a backup.
  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    try {
      var h = (init && init.headers) || (input && input.headers);
      var a = h ? (typeof h.get === "function" ? h.get("Authorization") : h.Authorization || h.authorization) : null;
      if (a && a.indexOf("Bearer ") === 0) token = a.slice(7);
    } catch (e) {}
    var p = origFetch(input, init);
    if (url.indexOf("/protocol/openid-connect/token") >= 0) {
      p.then(function (res) {
        res.clone().json().then(function (d) { if (d && d.access_token) token = d.access_token; }).catch(function () {});
      }).catch(function () {});
    }
    return p;
  };

  function el(tag, props, css) {
    var e = document.createElement(tag);
    if (props) Object.assign(e, props);
    if (css) e.style.cssText = css;
    return e;
  }
  function labeled(text, node) {
    var w = el("div", null, "margin-top:12px;");
    w.appendChild(el("div", { textContent: text }, "font-size:12px;color:var(--pf-v5-global--Color--200);margin-bottom:4px;"));
    w.appendChild(node);
    return w;
  }
  var TA_CSS = "width:100%;font-family:'RedHatMono',monospace;font-size:12px;line-height:1.5;padding:8px 12px;" +
    "background:var(--pf-v5-global--BackgroundColor--200);color:var(--pf-v5-global--Color--100);" +
    "border:1px solid var(--pf-v5-global--BorderColor--100);border-radius:4px;resize:vertical;";

  var building = false;
  async function buildOffer(qrImg) {
    if (building) return;
    building = true;
    try {
      var meta = await (await origFetch(REALM_BASE + "/.well-known/openid-credential-issuer")).json();
      var configs = Object.keys(meta.credential_configurations_supported || {});
      if (!configs.length) throw new Error("No credential configurations advertised by the issuer.");
      // Prefer the config whose name matches the dropdown's current selection.
      var cfgId = configs[0];
      var btn = Array.prototype.find.call(document.querySelectorAll("button"), function (b) {
        return configs.indexOf((b.textContent || "").trim()) >= 0;
      });
      if (btn) cfgId = (btn.textContent || "").trim();

      if (!token) throw new Error("No account session token captured yet — reselect the credential.");
      var username = (decodeJwt(token) || {}).preferred_username;
      if (!username) throw new Error("Could not determine the current username from the session token.");

      var offerRes = await origFetch(
        REALM_BASE + "/protocol/oid4vc/create-credential-offer?credential_configuration_id=" +
        encodeURIComponent(cfgId) + "&username=" + encodeURIComponent(username) + "&pre_authorized=true",
        { headers: { Authorization: "Bearer " + token } }
      );
      if (!offerRes.ok) throw new Error("create-credential-offer → HTTP " + offerRes.status + ": " + (await offerRes.text()));
      var on = await offerRes.json(); // { issuer, nonce }
      var offerUri = on.issuer + "/" + on.nonce;
      var walletLink = "openid-credential-offer://?credential_offer_uri=" + encodeURIComponent(offerUri);
      var offerJson = await (await origFetch(offerUri, { headers: { Authorization: "Bearer " + token } })).json();

      render(qrImg, cfgId, walletLink, offerJson);
    } catch (e) {
      render(qrImg, null, null, null, e.message);
    } finally {
      building = false;
    }
  }

  function render(qrImg, cfgId, walletLink, offerJson, errMsg) {
    var main = document.querySelector("main") || document.body;
    var prev = document.getElementById("mint-vc-offer");
    if (prev) prev.remove();
    if (qrImg) qrImg.style.display = "none";

    var box = el("div", { id: "mint-vc-offer" }, "max-width:760px;margin:8px 0 24px;");

    if (errMsg) {
      box.appendChild(el("pre", { textContent: "Could not build the credential offer:\n" + errMsg },
        "white-space:pre-wrap;color:var(--pf-v5-global--danger-color--100);background:var(--pf-v5-global--BackgroundColor--200);border:1px solid var(--pf-v5-global--danger-color--100);border-radius:4px;padding:12px;font-size:12px;"));
      main.appendChild(box);
      return;
    }

    box.appendChild(el("div", { textContent: "Pre-authorized credential offer — " + cfgId },
      "font-weight:600;font-size:15px;margin-bottom:4px;"));
    box.appendChild(el("div", {
      textContent: "Scan the QR with a wallet, or copy the offer below."
    }, "font-size:13px;color:var(--pf-v5-global--Color--200);"));

    // Wallet offer link + copy
    var linkTa = el("textarea", { readOnly: true, rows: 3, value: walletLink }, TA_CSS);
    box.appendChild(labeled("Wallet offer link", linkTa));
    var copyBtn = el("button", { type: "button", textContent: "Copy offer link" },
      "margin-top:8px;background:var(--pf-v5-global--primary-color--100);color:#fff;border:0;border-radius:4px;padding:6px 16px;font-size:14px;cursor:pointer;");
    copyBtn.addEventListener("click", function () {
      (navigator.clipboard ? navigator.clipboard.writeText(walletLink) : Promise.reject()).then(
        function () { copyBtn.textContent = "Copied!"; },
        function () { linkTa.select(); document.execCommand && document.execCommand("copy"); copyBtn.textContent = "Copied!"; }
      ).then(function () { setTimeout(function () { copyBtn.textContent = "Copy offer link"; }, 1500); });
    });
    box.appendChild(copyBtn);

    // Offer JSON
    var jsonTa = el("textarea", { readOnly: true, rows: 12, value: JSON.stringify(offerJson, null, 2) },
      TA_CSS + "margin-top:0;");
    box.appendChild(labeled("Credential offer (JSON)", jsonTa));

    // Demo wallet: complete the pre-authorized flow in-browser and show the
    // actual issued SD-JWT VC (issuer JWT ~ disclosures).
    var issueBtn = el("button", { type: "button", textContent: "Issue to this browser (demo wallet)" },
      "margin-top:16px;background:var(--pf-v5-global--success-color--100);color:#fff;border:0;border-radius:4px;padding:6px 16px;font-size:14px;cursor:pointer;");
    var credMount = el("div", { id: "mint-vc-credential" });
    issueBtn.addEventListener("click", function () { issueCredential(issueBtn, credMount, offerJson); });
    box.appendChild(issueBtn);
    box.appendChild(credMount);

    // Experimental: working client-side QR of the wallet link
    var det = el("details", null, "margin-top:14px;");
    det.appendChild(el("summary", { textContent: "Experimental — QR code" }, "cursor:pointer;color:var(--pf-v5-global--Color--200);font-size:13px;"));
    var qrWrap = el("div", null, "margin-top:10px;");
    try {
      if (typeof window.qrcode === "function") {
        var qr = window.qrcode(0, "M");
        qr.addData(walletLink);
        qr.make();
        var pad = el("div", null, "display:inline-block;background:#fff;padding:12px;border-radius:6px;");
        pad.appendChild(el("img", { src: qr.createDataURL(5, 0), alt: "Credential offer QR" }, "display:block;"));
        qrWrap.appendChild(pad);
      } else {
        qrWrap.appendChild(el("div", { textContent: "QR library not loaded." }, "color:var(--pf-v5-global--Color--200);"));
      }
    } catch (e) {
      qrWrap.appendChild(el("div", { textContent: "QR render error: " + e.message }, "color:var(--pf-v5-global--danger-color--100);"));
    }
    det.appendChild(qrWrap);
    box.appendChild(det);

    main.appendChild(box);
  }

  // ── Demo wallet (in-browser issuance) ──────────────────────────────────────
  function b64uBytes(buf) {
    return btoa(String.fromCharCode.apply(null, new Uint8Array(buf)))
      .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function b64uStr(s) { return b64uBytes(new TextEncoder().encode(s)); }
  function b64uDecode(s) { try { return atob(s.replace(/-/g, "+").replace(/_/g, "/")); } catch (e) { return ""; } }

  async function issueCredential(btn, mount, offerJson) {
    btn.disabled = true;
    var orig = btn.textContent;
    btn.textContent = "Issuing…";
    mount.innerHTML = "";
    try {
      var issuer = offerJson.credential_issuer;
      var preAuth = offerJson.grants["urn:ietf:params:oauth:grant-type:pre-authorized_code"]["pre-authorized_code"];

      // 1) Exchange the pre-authorized code as the public account-console client.
      var tokRes = await origFetch(REALM_BASE + "/protocol/openid-connect/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: "account-console",
          grant_type: "urn:ietf:params:oauth:grant-type:pre-authorized_code",
          "pre-authorized_code": preAuth,
        }),
      });
      if (!tokRes.ok) throw new Error("pre-authorized_code exchange → HTTP " + tokRes.status + ": " + (await tokRes.text()));
      var tok = await tokRes.json();

      // 2) c_nonce for the key-binding proof.
      var cNonce = tok.c_nonce ||
        (await (await origFetch(REALM_BASE + "/protocol/oid4vc/nonce", { method: "POST" })).json()).c_nonce;

      // 3) Generate a holder key and build an ES256 key-binding proof JWT.
      var kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"]);
      var jwk = await crypto.subtle.exportKey("jwk", kp.publicKey);
      var header = { alg: "ES256", typ: "openid4vci-proof+jwt", jwk: { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y } };
      var payload = { nonce: cNonce, aud: issuer, iat: Math.floor(Date.now() / 1000) };
      var signingInput = b64uStr(JSON.stringify(header)) + "." + b64uStr(JSON.stringify(payload));
      var sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, kp.privateKey, new TextEncoder().encode(signingInput));
      var proof = signingInput + "." + b64uBytes(sig);

      // 4) Request the credential.
      var credRes = await origFetch(REALM_BASE + "/protocol/oid4vc/credential", {
        method: "POST",
        headers: { Authorization: "Bearer " + tok.access_token, "Content-Type": "application/json" },
        body: JSON.stringify({ credential_identifier: "IdentityCredential", proof: { proof_type: "jwt", jwt: proof } }),
      });
      if (!credRes.ok) throw new Error("credential endpoint → HTTP " + credRes.status + ": " + (await credRes.text()));
      var cred = await credRes.json();
      var sdjwt = (cred.credentials && cred.credentials[0] && cred.credentials[0].credential) || cred.credential;
      if (!sdjwt) throw new Error("No credential in response: " + JSON.stringify(cred));

      renderCredential(mount, sdjwt);
    } catch (e) {
      mount.appendChild(el("pre", { textContent: "Issuance failed:\n" + e.message },
        "white-space:pre-wrap;color:var(--pf-v5-global--danger-color--100);background:var(--pf-v5-global--BackgroundColor--200);border:1px solid var(--pf-v5-global--danger-color--100);border-radius:4px;padding:12px;font-size:12px;margin-top:12px;"));
    } finally {
      btn.disabled = false;
      btn.textContent = orig;
    }
  }

  function renderCredential(mount, sdjwt) {
    var parts = sdjwt.split("~");
    var jwt = parts[0].split(".");
    function decSeg(seg) { try { return JSON.parse(b64uDecode(seg)); } catch (e) { return {}; } }
    var disclosures = parts.slice(1).filter(Boolean).map(function (d) {
      try { return JSON.parse(b64uDecode(d)); } catch (e) { return d; }
    });

    mount.appendChild(el("div", { textContent: "Issued credential (SD-JWT VC)" },
      "font-weight:600;font-size:15px;margin:20px 0 4px;"));
    mount.appendChild(el("div", {
      textContent: "Format: dc+sd-jwt — issuer-signed JWT, then one ~disclosure~ per selectively-disclosable claim."
    }, "font-size:13px;color:var(--pf-v5-global--Color--200);"));

    var raw = el("textarea", { readOnly: true, rows: 7, value: sdjwt }, TA_CSS);
    mount.appendChild(labeled("Raw credential (paste into a SD-JWT / jwt.io debugger)", raw));
    var copyBtn = el("button", { type: "button", textContent: "Copy credential" },
      "margin-top:8px;background:var(--pf-v5-global--primary-color--100);color:#fff;border:0;border-radius:4px;padding:6px 16px;font-size:14px;cursor:pointer;");
    copyBtn.addEventListener("click", function () {
      (navigator.clipboard ? navigator.clipboard.writeText(sdjwt) : Promise.reject()).then(
        function () { copyBtn.textContent = "Copied!"; },
        function () { raw.select(); document.execCommand && document.execCommand("copy"); copyBtn.textContent = "Copied!"; }
      ).then(function () { setTimeout(function () { copyBtn.textContent = "Copy credential"; }, 1500); });
    });
    mount.appendChild(copyBtn);

    var decoded = { header: decSeg(jwt[0]), payload: decSeg(jwt[1]), disclosures: disclosures };
    var pre = el("textarea", { readOnly: true, rows: 20, value: JSON.stringify(decoded, null, 2) }, TA_CSS + "margin-top:0;");
    mount.appendChild(labeled("Decoded — header, payload (_sd digests, vct, cnf), and disclosed claims", pre));
  }

  // The native (broken) QR <img> appears when a credential is selected — that's
  // our trigger to recreate the offer correctly.
  new MutationObserver(function () {
    if (!/\/account\/oid4vci/.test(location.pathname)) return;
    var img = document.querySelector('img[data-testid="qr-code"]');
    if (img && !img.dataset.mintHandled) {
      img.dataset.mintHandled = "1";
      buildOffer(img);
    }
  }).observe(document.body, { childList: true, subtree: true });
})();
