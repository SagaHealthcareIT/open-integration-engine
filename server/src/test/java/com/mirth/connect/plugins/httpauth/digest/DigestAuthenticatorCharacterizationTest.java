package com.mirth.connect.plugins.httpauth.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.Connector;
import com.mirth.connect.donkey.util.MessageMaps;
import com.mirth.connect.plugins.httpauth.AuthenticationResult;
import com.mirth.connect.plugins.httpauth.AuthenticationResult.AuthStatus;
import com.mirth.connect.plugins.httpauth.RequestInfo;
import com.mirth.connect.plugins.httpauth.digest.DigestHttpAuthProperties.Algorithm;
import com.mirth.connect.plugins.httpauth.digest.DigestHttpAuthProperties.QOPMode;

/**
 * Jetty 12 migration Stage 0 characterization harness (see
 * docs/Marketplace/Jetty12-Migration-Plan.md in the project-mdds-llm workspace repo, section 4).
 *
 * DigestAuthenticator parses the Authorization header with Jetty's QuotedStringTokenizer — a class
 * Jetty 12 reshapes into a builder-based interface — and builds the WWW-Authenticate challenge
 * itself. These tests pin the observable protocol behavior before that rewrite:
 *
 * - The challenge advertises ALL configured algorithms comma-joined in ONE quoted directive
 *   (BridgeLink's Jetty 12 port regressed to advertising only the first — plan section 3.7 says we
 *   measure and decide, not silently inherit).
 * - Every challenge directive value is quoted, including algorithm and qop.
 * - Quoted directive values containing commas and spaces parse correctly (the tokenizer's job).
 * - A failed authentication re-challenges (CHALLENGED with a fresh nonce), it does not return
 *   FAILURE.
 * - RFC 2617 qop=auth, RFC 2069 legacy (no qop), and MD5-sess handshakes all validate, and a nonce
 *   is reusable with an incremented nonce count.
 */
public class DigestAuthenticatorCharacterizationTest {

    private static final String REALM = "Test Realm";
    private static final String OPAQUE = "test-opaque-value";
    private static final String USERNAME = "user";
    private static final String PASSWORD = "password";

    private DigestHttpAuthProperties properties;
    private DigestAuthenticator authenticator;

    @Before
    public void setup() {
        properties = new DigestHttpAuthProperties();
        properties.setRealm(REALM);
        properties.setOpaque(OPAQUE);
        Map<String, String> credentials = new LinkedHashMap<String, String>();
        credentials.put(USERNAME, PASSWORD);
        properties.setCredentialsMap(credentials);
        rebuildAuthenticator();
    }

    private void rebuildAuthenticator() {
        Channel channel = mock(Channel.class);
        doReturn("Digest Characterization Channel").when(channel).getName();
        Connector connector = mock(Connector.class);
        doReturn("digestchannelid").when(connector).getChannelId();
        doReturn(channel).when(connector).getChannel();

        DigestAuthenticatorProvider provider = new DigestAuthenticatorProvider(connector, properties);
        authenticator = new DigestAuthenticator(provider, new MessageMaps());
    }

    private RequestInfo request(String method, String uri, String authHeader) {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        if (authHeader != null) {
            headers.put("Authorization", Arrays.asList(authHeader));
        }
        return new RequestInfo("127.0.0.1", 12345, "127.0.0.1", 8080, "HTTP/1.1", method, uri, headers, new LinkedHashMap<String, List<String>>(), () -> new byte[0]);
    }

    private String challengeHeader(AuthenticationResult result) {
        assertEquals(AuthStatus.CHALLENGED, result.getStatus());
        List<String> values = result.getResponseHeaders().get("WWW-Authenticate");
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static Map<String, String> parseDirectives(String challenge) {
        assertTrue(challenge.startsWith("Digest "));
        Map<String, String> directives = new LinkedHashMap<String, String>();
        Matcher matcher = Pattern.compile("([a-zA-Z]+)=\"([^\"]*)\"").matcher(challenge);
        while (matcher.find()) {
            directives.put(matcher.group(1), matcher.group(2));
        }
        return directives;
    }

    private static String md5Hex(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------------
    // Challenge advertisement
    // ---------------------------------------------------------------------

    @Test
    public void challengeAdvertisesAllAlgorithmsInOneQuotedDirective() {
        // Default properties: algorithms [MD5, MD5-sess], qop [auth, auth-int] (LinkedHashSet order)
        String challenge = challengeHeader(authenticator.authenticate(request("GET", "/", null)));

        // The exact baseline form: all values quoted, all algorithms and qop modes comma-joined
        assertTrue(challenge, challenge.contains("algorithm=\"MD5,MD5-sess\""));
        assertTrue(challenge, challenge.contains("qop=\"auth,auth-int\""));
        assertTrue(challenge, challenge.contains("realm=\"" + REALM + "\""));
        assertTrue(challenge, challenge.contains("opaque=\"" + OPAQUE + "\""));
        assertTrue(challenge, challenge.contains("domain=\"/\""));

        Map<String, String> directives = parseDirectives(challenge);
        assertTrue(directives.containsKey("nonce"));
        assertTrue(directives.get("nonce").length() > 0);
    }

    @Test
    public void challengeWithSingleAlgorithmAndQop() {
        properties.setAlgorithms(new LinkedHashSet<Algorithm>(Arrays.asList(Algorithm.MD5)));
        properties.setQopModes(new LinkedHashSet<QOPMode>(Arrays.asList(QOPMode.AUTH)));
        rebuildAuthenticator();

        String challenge = challengeHeader(authenticator.authenticate(request("GET", "/", null)));

        assertTrue(challenge, challenge.contains("algorithm=\"MD5\""));
        assertTrue(challenge, challenge.contains("qop=\"auth\""));
    }

    // ---------------------------------------------------------------------
    // Handshakes
    // ---------------------------------------------------------------------

    private String obtainNonce(String uri) {
        String challenge = challengeHeader(authenticator.authenticate(request("GET", uri, null)));
        return parseDirectives(challenge).get("nonce");
    }

    private String qopAuthHeader(String username, String password, String uri, String nonce, String nc, String cnonce) throws Exception {
        String ha1 = md5Hex(username + ":" + REALM + ":" + password);
        String ha2 = md5Hex("GET:" + uri);
        String response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        return "Digest username=\"" + username + "\", realm=\"" + REALM + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\", response=\"" + response + "\", opaque=\"" + OPAQUE + "\", algorithm=MD5";
    }

    @Test
    public void qopAuthHandshakeSucceeds() throws Exception {
        String nonce = obtainNonce("/path");

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, PASSWORD, "/path", nonce, "00000001", "abc123")));

        assertEquals(AuthStatus.SUCCESS, result.getStatus());
        assertEquals(USERNAME, result.getUsername());
    }

    @Test
    public void nonceReusableWithIncrementedNonceCount() throws Exception {
        String nonce = obtainNonce("/path");

        assertEquals(AuthStatus.SUCCESS, authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, PASSWORD, "/path", nonce, "00000001", "abc123"))).getStatus());
        assertEquals(AuthStatus.SUCCESS, authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, PASSWORD, "/path", nonce, "00000002", "def456"))).getStatus());
    }

    @Test
    public void legacyRfc2069HandshakeWithoutQopSucceeds() throws Exception {
        String nonce = obtainNonce("/path");
        String ha1 = md5Hex(USERNAME + ":" + REALM + ":" + PASSWORD);
        String ha2 = md5Hex("GET:/path");
        String response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        String header = "Digest username=\"" + USERNAME + "\", realm=\"" + REALM + "\", nonce=\"" + nonce + "\", uri=\"/path\", response=\"" + response + "\", opaque=\"" + OPAQUE + "\"";

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", header));

        assertEquals(AuthStatus.SUCCESS, result.getStatus());
    }

    @Test
    public void md5SessHandshakeSucceeds() throws Exception {
        String nonce = obtainNonce("/path");
        String cnonce = "abc123";
        String credentialsDigest = md5Hex(USERNAME + ":" + REALM + ":" + PASSWORD);
        String ha1 = md5Hex(credentialsDigest + ":" + nonce + ":" + cnonce);
        String ha2 = md5Hex("GET:/path");
        String response = md5Hex(ha1 + ":" + nonce + ":00000001:" + cnonce + ":auth:" + ha2);
        String header = "Digest username=\"" + USERNAME + "\", realm=\"" + REALM + "\", nonce=\"" + nonce + "\", uri=\"/path\", qop=auth, nc=00000001, cnonce=\"" + cnonce + "\", response=\"" + response + "\", opaque=\"" + OPAQUE + "\", algorithm=\"MD5-sess\"";

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", header));

        assertEquals(AuthStatus.SUCCESS, result.getStatus());
    }

    // ---------------------------------------------------------------------
    // Quoted-string parsing — QuotedStringTokenizer territory
    // ---------------------------------------------------------------------

    @Test
    public void quotedUsernameWithCommaAndSpaceParses() throws Exception {
        String trickyUser = "test user, 1";
        properties.getCredentialsMap().put(trickyUser, PASSWORD);
        rebuildAuthenticator();
        String nonce = obtainNonce("/path");

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", qopAuthHeader(trickyUser, PASSWORD, "/path", nonce, "00000001", "abc123")));

        assertEquals(AuthStatus.SUCCESS, result.getStatus());
        assertEquals(trickyUser, result.getUsername());
    }

    // ---------------------------------------------------------------------
    // Failure behavior
    // ---------------------------------------------------------------------

    @Test
    public void wrongPasswordRechallengesWithFreshNonce() throws Exception {
        String nonce = obtainNonce("/path");

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, "wrong", "/path", nonce, "00000001", "abc123")));

        // A bad digest yields a NEW challenge (not AuthStatus.FAILURE) with a different nonce
        String rechallenge = challengeHeader(result);
        assertNotEquals(nonce, parseDirectives(rechallenge).get("nonce"));
    }

    /*
     * Malformed Authorization headers must yield a clean re-challenge, never a thrown exception:
     * an exception propagates to the security handler as a 500 + ErrorEvent that any
     * unauthenticated client could trigger. The 9.4 tokenizer parsed these leniently; Jetty 12's
     * RFC 9110 tokenizer THROWS on them, which is why the rewrite must use the legacy() builder.
     */
    @Test
    public void malformedHeaderUnterminatedQuoteRechallenges() {
        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", "Digest username=\"jo"));
        assertEquals(AuthStatus.CHALLENGED, result.getStatus());
    }

    @Test
    public void malformedHeaderCharactersAfterEndQuoteRechallenges() {
        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", "Digest username=\"jo\"x, realm=\"bogus\""));
        assertEquals(AuthStatus.CHALLENGED, result.getStatus());
    }

    @Test
    public void singleQuotedUsernameParsesAsQuoted() throws Exception {
        // 9.4's tokenizer treated single quotes as quoting characters; legacy() preserves that
        String trickyUser = "single quoted, user";
        properties.getCredentialsMap().put(trickyUser, PASSWORD);
        rebuildAuthenticator();
        String nonce = obtainNonce("/path");

        String ha1 = md5Hex(trickyUser + ":" + REALM + ":" + PASSWORD);
        String ha2 = md5Hex("GET:/path");
        String response = md5Hex(ha1 + ":" + nonce + ":00000001:abc123:auth:" + ha2);
        String header = "Digest username='" + trickyUser + "', realm=\"" + REALM + "\", nonce=\"" + nonce + "\", uri=\"/path\", qop=auth, nc=00000001, cnonce=\"abc123\", response=\"" + response + "\", opaque=\"" + OPAQUE + "\", algorithm=MD5";

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", header));

        assertEquals(AuthStatus.SUCCESS, result.getStatus());
        assertEquals(trickyUser, result.getUsername());
    }

    @Test
    public void mismatchedUriRechallenges() throws Exception {
        String nonce = obtainNonce("/path");

        AuthenticationResult result = authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, PASSWORD, "/other", nonce, "00000001", "abc123")));

        assertEquals(AuthStatus.CHALLENGED, result.getStatus());
    }

    @Test
    public void unknownNonceStaleBehaviorDependsOnOpaque() throws Exception {
        /*
         * An unknown/expired nonce has no stored opaque, so the expected opaque is "". A client
         * that echoes OUR configured opaque therefore fails the opaque comparison and gets a plain
         * re-challenge with NO stale directive; only a client sending an empty/absent opaque gets
         * stale="true". Subtle, but it's the shipped 9.4 behavior — pin it.
         */
        AuthenticationResult withOpaque = authenticator.authenticate(request("GET", "/path", qopAuthHeader(USERNAME, PASSWORD, "/path", "bogusnonce", "00000001", "abc123")));
        String plainRechallenge = challengeHeader(withOpaque);
        assertTrue(plainRechallenge, !plainRechallenge.contains("stale="));

        String ha1 = md5Hex(USERNAME + ":" + REALM + ":" + PASSWORD);
        String ha2 = md5Hex("GET:/path");
        String response = md5Hex(ha1 + ":bogusnonce:00000001:abc123:auth:" + ha2);
        String headerWithoutOpaque = "Digest username=\"" + USERNAME + "\", realm=\"" + REALM + "\", nonce=\"bogusnonce\", uri=\"/path\", qop=auth, nc=00000001, cnonce=\"abc123\", response=\"" + response + "\", algorithm=MD5";

        AuthenticationResult withoutOpaque = authenticator.authenticate(request("GET", "/path", headerWithoutOpaque));
        String staleRechallenge = challengeHeader(withoutOpaque);
        assertTrue(staleRechallenge, staleRechallenge.contains("stale=\"true\""));
    }
}
