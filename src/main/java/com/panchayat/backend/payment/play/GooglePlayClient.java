package com.panchayat.backend.payment.play;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * The real Google Play check: a service account (set in the server's settings) asks the Play Developer API about a purchase token.
 *   APP_GOOGLEPLAY_PACKAGE_NAME            the app's package (default world.panchayat.app)
 *   APP_GOOGLEPLAY_SERVICE_ACCOUNT_JSON    the service account key: the JSON text, or the path of the file
 */
@Component
@Profile("!playtest")
public class GooglePlayClient implements PlayVerifier {

    private static final Logger log = LoggerFactory.getLogger(GooglePlayClient.class);
    private static final String API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";

    private final String packageName;
    private final String accountSetting;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    private volatile String accessToken;
    private volatile long accessUntil;

    public GooglePlayClient(@Value("${app.googleplay.package-name:world.panchayat.app}") String packageName,
                            @Value("${app.googleplay.service-account-json:}") String accountSetting) {
        this.packageName = packageName;
        this.accountSetting = accountSetting == null ? "" : accountSetting.strip();
    }

    private static ResponseStatusException unavailable(String m) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, m);
    }

    private JsonNode account() throws Exception {
        if (accountSetting.isEmpty()) throw unavailable("Google Play purchases aren't set up on the server yet.");
        String text = accountSetting.startsWith("{") ? accountSetting : Files.readString(Path.of(accountSetting));
        return json.readTree(text);
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private synchronized String token() throws Exception {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessUntil - 60_000) return accessToken;
        JsonNode acc = account();
        String email = acc.path("client_email").asText("");
        String pem = acc.path("private_key").asText("");
        String uri = acc.path("token_uri").asText("https://oauth2.googleapis.com/token");
        if (email.isEmpty() || pem.isEmpty()) throw unavailable("The Google Play service account key is not valid.");
        byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        long iat = now / 1000;
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(json.writeValueAsBytes(java.util.Map.of("iss", email, "scope", "https://www.googleapis.com/auth/androidpublisher", "aud", uri, "iat", iat, "exp", iat + 3600)));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        String assertion = header + "." + claims + "." + b64(sig.sign());
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw unavailable("Google didn't accept the server's key.");
        JsonNode t = json.readTree(res.body());
        accessToken = t.path("access_token").asText();
        accessUntil = now + t.path("expires_in").asLong(3000) * 1000;
        return accessToken;
    }

    private static String seg(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public PlayPurchase verify(String playProductId, String purchaseToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + seg(packageName) + "/purchases/products/" + seg(playProductId) + "/tokens/" + seg(purchaseToken)))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404 || res.statusCode() == 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google doesn't know this purchase.");
            if (res.statusCode() / 100 != 2) {
                log.warn("Google Play answered {} for a purchase check", res.statusCode());
                throw unavailable("Google Play couldn't be reached. Nothing was charged twice; try again in a minute.");
            }
            JsonNode p = json.readTree(res.body());
            return new PlayPurchase(p.path("purchaseState").asInt(1), p.path("orderId").asText(""), p.path("acknowledgementState").asInt(0), p.path("consumptionState").asInt(0));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google Play check failed: {}", e.getMessage());
            throw unavailable("Google Play couldn't be reached. Try again in a minute.");
        }
    }

    @Override
    public void acknowledge(String playProductId, String purchaseToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + seg(packageName) + "/purchases/products/" + seg(playProductId) + "/tokens/" + seg(purchaseToken) + ":acknowledge"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Couldn't acknowledge a Google Play purchase: {}", e.getMessage());
        }
    }
}
