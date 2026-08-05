package com.mirth.connect.connectors.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mirth.connect.connectors.http.HttpStaticResource.ResourceType;
import com.mirth.connect.donkey.model.channel.ConnectorPluginProperties;
import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.CannedDispatchChannel;
import com.mirth.connect.donkey.server.channel.StorageSettings;
import com.mirth.connect.donkey.server.data.DonkeyDao;
import com.mirth.connect.donkey.server.data.DonkeyDaoFactory;
import com.mirth.connect.donkey.util.MessageMaps;
import com.mirth.connect.plugins.httpauth.basic.BasicHttpAuthProperties;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

/**
 * Jetty 12 migration Stage 0 characterization harness (see
 * docs/Marketplace/Jetty12-Migration-Plan.md in the project-mdds-llm workspace repo, section 4).
 *
 * Boots the REAL HttpReceiver — its Jetty server, handler collection, static resource contexts, and
 * (for the auth tests) the ConstraintSecurityHandler wrapper — with the donkey channel plumbing
 * mocked, then talks to it over real HTTP.
 *
 * These tests pin the Jetty 9.4 handler-fallthrough semantics that customer channels depend on:
 * StaticResourceHandler deliberately leaves a request unhandled (non-GET method, context-path
 * mismatch, query-parameter mismatch, missing file in a DIRECTORY resource) so that the next
 * handler in the collection — ultimately the channel's RequestHandler — serves it. Jetty 12's
 * ContextHandlerCollection selects a single context with no fallthrough, which is exactly how
 * BridgeLink's port silently broke static resources for five weeks. Every scenario here must pass
 * unchanged before AND after the migration.
 *
 * Deliberately contains NO org.eclipse.jetty imports so the migration cannot force edits to it.
 */
public class HttpListenerCharacterizationTest {

    private static final String CHANNEL_RESPONSE = "CHANNEL-RESPONSE";

    private static Field factoryField;
    private static ControllerFactory originalFactory;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpReceiver receiver;
    private HttpClient httpClient = HttpClient.newHttpClient();

    /*
     * HttpReceiver, DefaultHttpConfiguration, and MirthMessageMaps all pull controllers from the
     * ControllerFactory singleton at construction time. Install a fully mocked factory for the
     * duration of this class so nothing touches a database, and restore whatever was there before
     * (the Ant test target runs every suite in one JVM).
     */
    @BeforeClass
    public static void installMockControllerFactory() throws Exception {
        factoryField = ControllerFactory.class.getDeclaredField("factory");
        factoryField.setAccessible(true);
        originalFactory = (ControllerFactory) factoryField.get(null);

        ControllerFactory factory = mock(ControllerFactory.class);
        doReturn(mock(ConfigurationController.class)).when(factory).createConfigurationController();
        doReturn(mock(EventController.class)).when(factory).createEventController();
        factoryField.set(null, factory);
    }

    @AfterClass
    public static void restoreControllerFactory() throws Exception {
        if (factoryField != null) {
            factoryField.set(null, originalFactory);
        }
    }

    @After
    public void stopReceiver() throws Exception {
        if (receiver != null) {
            receiver.onStop();
            receiver = null;
        }
    }

    private int bootReceiver(HttpReceiverProperties props) throws Exception {
        CannedDispatchChannel channel = new CannedDispatchChannel();
        channel.setChannelId("charchannelid");
        channel.setName("Characterization Channel");
        channel.setMessageMaps(new MessageMaps());

        DonkeyDaoFactory daoFactory = mock(DonkeyDaoFactory.class);
        doReturn(mock(DonkeyDao.class)).when(daoFactory).getDao();
        channel.setDaoFactory(daoFactory);

        StorageSettings storageSettings = new StorageSettings();
        storageSettings.setStoreSentResponse(false);
        storageSettings.setStoreMergedResponseMap(false);
        channel.setStorageSettings(storageSettings);

        // A fresh DispatchResult per request — sendResponse mutates it (setAttemptedResponse)
        channel.setDispatcher((RawMessage rawMessage) -> {
            Message message = new Message();
            message.setMessageId(1L);
            ConnectorMessage connectorMessage = new ConnectorMessage();
            message.getConnectorMessages().put(0, connectorMessage);
            Response response = new Response(Status.SENT, CHANNEL_RESPONSE);
            return CannedDispatchChannel.createDispatchResult(1L, message, response);
        });

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        props.getListenerConnectorProperties().setHost("127.0.0.1");
        props.getListenerConnectorProperties().setPort(String.valueOf(port));

        receiver = new HttpReceiver();
        receiver.setChannel(channel);
        receiver.setChannelId("charchannelid");
        receiver.setMetaDataId(0);
        receiver.setConnectorProperties(props);
        receiver.onDeploy();
        receiver.setCurrentState(DeployedState.STARTED);
        receiver.onStart();

        return port;
    }

    private HttpResponse<String> get(int port, String pathAndQuery) throws IOException, InterruptedException {
        return send(port, pathAndQuery, "GET", null, null);
    }

    private HttpResponse<String> send(int port, String pathAndQuery, String method, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery));
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------------
    // Main request handler
    // ---------------------------------------------------------------------

    @Test
    public void mainHandlerDispatchesToChannel() throws Exception {
        int port = bootReceiver(new HttpReceiverProperties());

        HttpResponse<String> response = send(port, "/anything", "POST", "MSH|test-message", null);

        assertEquals(200, response.statusCode());
        assertEquals(CHANNEL_RESPONSE, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    }

    @Test
    public void serverAndXPoweredByHeadersSuppressed() throws Exception {
        // DefaultHttpConfiguration sets sendServerVersion=false and sendXPoweredBy=false
        int port = bootReceiver(new HttpReceiverProperties());

        HttpResponse<String> response = get(port, "/");

        assertEquals(200, response.statusCode());
        assertEquals(Optional.empty(), response.headers().firstValue("Server"));
        assertEquals(Optional.empty(), response.headers().firstValue("X-Powered-By"));
    }

    // ---------------------------------------------------------------------
    // Static resource fallthrough — the BridgeLink canary
    // ---------------------------------------------------------------------

    private HttpReceiverProperties propsWithCustomResource(String contextPath, String value) {
        HttpReceiverProperties props = new HttpReceiverProperties();
        List<HttpStaticResource> resources = new ArrayList<HttpStaticResource>();
        resources.add(new HttpStaticResource(contextPath, ResourceType.CUSTOM, value, "text/plain"));
        props.setStaticResources(resources);
        return props;
    }

    @Test
    public void customStaticResourceServed() throws Exception {
        int port = bootReceiver(propsWithCustomResource("/static", "STATIC-CONTENT"));

        HttpResponse<String> response = get(port, "/static");

        assertEquals(200, response.statusCode());
        assertEquals("STATIC-CONTENT", response.body());
    }

    @Test
    public void staticContextPathMismatchFallsThroughToChannel() throws Exception {
        // Non-DIRECTORY resource + sub-path request: StaticResourceHandler returns unhandled,
        // the request must reach the channel's RequestHandler at the root context.
        int port = bootReceiver(propsWithCustomResource("/static", "STATIC-CONTENT"));

        HttpResponse<String> response = get(port, "/static/sub");

        assertEquals(200, response.statusCode());
        assertEquals(CHANNEL_RESPONSE, response.body());
    }

    @Test
    public void nonGetMethodFallsThroughToChannel() throws Exception {
        int port = bootReceiver(propsWithCustomResource("/static", "STATIC-CONTENT"));

        HttpResponse<String> response = send(port, "/static", "POST", "payload", null);

        assertEquals(200, response.statusCode());
        assertEquals(CHANNEL_RESPONSE, response.body());
    }

    @Test
    public void queryParameterMatchAndMismatch() throws Exception {
        // Query parameters embedded in the resource's context path become match conditions
        int port = bootReceiver(propsWithCustomResource("/static?foo=bar", "STATIC-CONTENT"));

        assertEquals("STATIC-CONTENT", get(port, "/static?foo=bar").body());
        assertEquals(CHANNEL_RESPONSE, get(port, "/static?foo=baz").body());
        assertEquals(CHANNEL_RESPONSE, get(port, "/static").body());
        // Matching is bidirectional set equality: an EXTRA parameter also falls through
        assertEquals(CHANNEL_RESPONSE, get(port, "/static?foo=bar&x=1").body());
    }

    @Test
    public void unrelatedPathStillDispatchesToChannelWhenStaticResourcesExist() throws Exception {
        int port = bootReceiver(propsWithCustomResource("/static", "STATIC-CONTENT"));

        assertEquals(CHANNEL_RESPONSE, get(port, "/other").body());
    }

    @Test
    public void caseMismatchFallsThroughToChannel() throws Exception {
        // 9.4's per-resource ContextHandler matched case-SENSITIVELY: /STATIC went to the channel
        int port = bootReceiver(propsWithCustomResource("/static", "STATIC-CONTENT"));

        assertEquals(CHANNEL_RESPONSE, get(port, "/STATIC").body());
        assertEquals(CHANNEL_RESPONSE, get(port, "/Static").body());
    }

    @Test
    public void directoryMatchIsSegmentBounded() throws Exception {
        // /dir must never match /dirx.txt — 9.4's ContextHandler matching was segment-aware
        File dir = tempFolder.newFolder("bounded");
        Files.write(new File(dir, "x.txt").toPath(), "BOUNDED-FILE".getBytes(StandardCharsets.UTF_8));

        HttpReceiverProperties props = new HttpReceiverProperties();
        List<HttpStaticResource> resources = new ArrayList<HttpStaticResource>();
        resources.add(new HttpStaticResource("/dir", ResourceType.DIRECTORY, dir.getAbsolutePath(), "text/plain"));
        props.setStaticResources(resources);
        int port = bootReceiver(props);

        assertEquals("BOUNDED-FILE", get(port, "/dir/x.txt").body());
        // No segment boundary: the channel gets it, the directory resource must not serve x.txt
        assertEquals(CHANNEL_RESPONSE, get(port, "/dirx.txt").body());
    }

    @Test
    public void directoryResourceServesFilesAndFallsThroughOtherwise() throws Exception {
        File dir = tempFolder.newFolder("staticdir");
        Files.write(new File(dir, "exists.txt").toPath(), "FILE-CONTENT".getBytes(StandardCharsets.UTF_8));
        new File(dir, "sub").mkdir();
        Files.write(new File(dir, "sub/nested.txt").toPath(), "NESTED".getBytes(StandardCharsets.UTF_8));

        HttpReceiverProperties props = new HttpReceiverProperties();
        List<HttpStaticResource> resources = new ArrayList<HttpStaticResource>();
        resources.add(new HttpStaticResource("/dir", ResourceType.DIRECTORY, dir.getAbsolutePath(), "text/plain"));
        props.setStaticResources(resources);
        int port = bootReceiver(props);

        // Existing file in the directory is served directly
        assertEquals("FILE-CONTENT", get(port, "/dir/exists.txt").body());
        // Missing file falls through to the channel
        assertEquals(CHANNEL_RESPONSE, get(port, "/dir/missing.txt").body());
        // Subdirectory paths fall through to the channel
        assertEquals(CHANNEL_RESPONSE, get(port, "/dir/sub/nested.txt").body());
        // The directory itself falls through to the channel
        assertEquals(CHANNEL_RESPONSE, get(port, "/dir").body());
    }

    @Test
    public void moreSpecificStaticContextTakesPrecedence() throws Exception {
        /*
         * Ordering only becomes observable when BOTH handlers would serve the same request: a
         * DIRECTORY resource at /a containing a file "b", overlapped by a CUSTOM resource at /a/b.
         * On 9.4 the TreeMap-descending sort puts /a/b first, so it wins; if precedence were lost
         * (or replaced by a selection model that picks the /a context) the DIRECTORY handler would
         * serve the file instead. This is exactly where Jetty 12's ContextHandlerCollection
         * longest-prefix selection differs from 9.4's ordered HandlerCollection.
         */
        File dir = tempFolder.newFolder("outerdir");
        Files.write(new File(dir, "b").toPath(), "OUTER-FILE".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "c").toPath(), "OUTER-C".getBytes(StandardCharsets.UTF_8));

        HttpReceiverProperties props = new HttpReceiverProperties();
        List<HttpStaticResource> resources = new ArrayList<HttpStaticResource>();
        resources.add(new HttpStaticResource("/a", ResourceType.DIRECTORY, dir.getAbsolutePath(), "text/plain"));
        resources.add(new HttpStaticResource("/a/b", ResourceType.CUSTOM, "INNER", "text/plain"));
        props.setStaticResources(resources);
        int port = bootReceiver(props);

        // The more specific CUSTOM context wins over the DIRECTORY that could also serve /a/b
        assertEquals("INNER", get(port, "/a/b").body());
        // The DIRECTORY genuinely serves (proves the losing handler was live, not broken)
        assertEquals("OUTER-C", get(port, "/a/c").body());
        // Missing file and the directory itself fall through
        assertEquals(CHANNEL_RESPONSE, get(port, "/a/missing").body());
        assertEquals(CHANNEL_RESPONSE, get(port, "/a").body());
    }

    // ---------------------------------------------------------------------
    // Basic auth through the ConstraintSecurityHandler wrapper
    // ---------------------------------------------------------------------

    private HttpReceiverProperties propsWithBasicAuth(String realm, String username, String password) {
        HttpReceiverProperties props = new HttpReceiverProperties();
        BasicHttpAuthProperties authProps = new BasicHttpAuthProperties();
        authProps.setRealm(realm);
        Map<String, String> credentials = new LinkedHashMap<String, String>();
        credentials.put(username, password);
        authProps.setCredentialsMap(credentials);
        Set<ConnectorPluginProperties> pluginProps = new HashSet<ConnectorPluginProperties>();
        pluginProps.add(authProps);
        props.setPluginProperties(pluginProps);
        return props;
    }

    private static String basicAuthHeader(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void basicAuthMissingCredentialsChallenges() throws Exception {
        int port = bootReceiver(propsWithBasicAuth("Test Realm", "user", "password"));

        HttpResponse<String> response = get(port, "/");

        assertEquals(401, response.statusCode());
        // Exact challenge format produced by BasicAuthenticator and relayed by the security handler
        assertEquals("Basic realm=\"Test Realm\"", response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    public void basicAuthValidCredentialsDispatchesToChannel() throws Exception {
        int port = bootReceiver(propsWithBasicAuth("Test Realm", "user", "password"));

        HttpResponse<String> response = send(port, "/", "POST", "payload", Map.of("Authorization", basicAuthHeader("user", "password")));

        assertEquals(200, response.statusCode());
        assertEquals(CHANNEL_RESPONSE, response.body());
    }

    @Test
    public void basicAuthWrongPasswordChallenges() throws Exception {
        int port = bootReceiver(propsWithBasicAuth("Test Realm", "user", "password"));

        HttpResponse<String> response = send(port, "/", "GET", null, Map.of("Authorization", basicAuthHeader("user", "wrong")));

        assertEquals(401, response.statusCode());
        assertEquals("Basic realm=\"Test Realm\"", response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    public void basicAuthProtectsStaticResourcesToo() throws Exception {
        // The security handler wraps the whole handler collection, static resources included
        HttpReceiverProperties props = propsWithBasicAuth("Test Realm", "user", "password");
        List<HttpStaticResource> resources = new ArrayList<HttpStaticResource>();
        resources.add(new HttpStaticResource("/static", ResourceType.CUSTOM, "STATIC-CONTENT", "text/plain"));
        props.setStaticResources(resources);
        int port = bootReceiver(props);

        assertEquals(401, get(port, "/static").statusCode());

        HttpResponse<String> authed = send(port, "/static", "GET", null, Map.of("Authorization", basicAuthHeader("user", "password")));
        assertEquals(200, authed.statusCode());
        assertEquals("STATIC-CONTENT", authed.body());
    }
}
