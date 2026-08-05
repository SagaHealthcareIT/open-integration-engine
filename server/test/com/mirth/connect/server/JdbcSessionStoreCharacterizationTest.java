package com.mirth.connect.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.ee8.nested.SessionHandler;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.DatabaseAdaptor;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.JDBCSessionDataStore.SessionTableSchema;
import org.eclipse.jetty.session.JDBCSessionDataStoreFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionDataStore;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Jetty 12 migration Stage 0 characterization harness (see
 * docs/Marketplace/Jetty12-Migration-Plan.md in the project-mdds-llm workspace repo, section 4).
 *
 * Replicates MirthWebServer.createSessionDataStoreFactory's wiring — JDBCSessionDataStoreFactory +
 * custom-named SessionTableSchema + the SQL Server image-blob DatabaseAdaptor override — against a
 * real Postgres, and pins the behavior existing installs rely on: a session written through one
 * server lands in the store's table and is readable by a NEW server instance pointed at the same
 * table (i.e., sessions survive an engine restart). In Stage 3 this same test must pass with the
 * phase-1 rows written by Jetty 9.4 and read by Jetty 12's org.eclipse.jetty.session store.
 *
 * Unlike the other Stage 0 tests, this one necessarily uses Jetty session classes, so the
 * migration WILL touch this file: Stage 1's import moves, and in Stage 3 the wiring helpers mirror
 * the production changes (factory calls become SessionManager-mediated, and the servlet context
 * attaches to the server through the ee8/core bridge). The ASSERTIONS must stay frozen — only
 * imports and wiring may change, in lockstep with MirthWebServer's own wiring.
 *
 * Requires a real Postgres (workspace rule: SQL behavior is validated against a real database, not
 * an in-memory stand-in). Skipped unless these environment variables are set:
 *
 *   MIRTH_TEST_POSTGRES_URL       e.g. jdbc:postgresql://127.0.0.1:5432/jetty12_char
 *   MIRTH_TEST_POSTGRES_USER
 *   MIRTH_TEST_POSTGRES_PASSWORD
 *
 * Use a scratch database — the test creates and drops its session table.
 */
public class JdbcSessionStoreCharacterizationTest {

    private static final String TABLE_NAME = "jetty12charsessions";

    private String url;
    private String user;
    private String password;
    private Server server;
    private HttpClient httpClient = HttpClient.newHttpClient();

    @Before
    public void checkDatabaseConfigured() {
        url = System.getenv("MIRTH_TEST_POSTGRES_URL");
        user = System.getenv("MIRTH_TEST_POSTGRES_USER");
        password = System.getenv("MIRTH_TEST_POSTGRES_PASSWORD");
        Assume.assumeTrue("MIRTH_TEST_POSTGRES_* not set; skipping JDBC session store characterization", StringUtils.isNoneBlank(url, user, password));
    }

    @After
    public void cleanup() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (url != null) {
            try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            }
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /** The exact wiring from MirthWebServer.createSessionDataStoreFactory. */
    private JDBCSessionDataStoreFactory createSessionDataStoreFactory() throws Exception {
        JDBCSessionDataStoreFactory jdbcSDSFactory = new JDBCSessionDataStoreFactory();
        SessionTableSchema schema = new SessionTableSchema();
        schema.setTableName(TABLE_NAME);
        jdbcSDSFactory.setSessionTableSchema(schema);

        DatabaseAdaptor dbAdapter = new DatabaseAdaptor() {
            @Override
            public String getBlobType() {
                if (_blobType == null && StringUtils.containsIgnoreCase(getDBName(), "sql server")) {
                    setBlobType("image");
                }
                return super.getBlobType();
            }
        };

        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection()) {
            dbAdapter.adaptTo(connection.getMetaData());
            dbAdapter.setDatasource(dataSource);
        }
        jdbcSDSFactory.setDatabaseAdaptor(dbAdapter);

        return jdbcSDSFactory;
    }

    /**
     * Mirrors MirthWebServer's per-context session wiring (cache factory + data store factory).
     * Production selects NullSessionCacheFactory when server.api.sessioncache=none and
     * DefaultSessionCacheFactory otherwise (MirthWebServer lines 184-194) — both modes are
     * characterized.
     */
    private int startServer(boolean nullSessionCache) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setMaxInactiveInterval(3600);

        // Jetty 12: the session factories take the SessionManager the handler wraps
        SessionCache sessionCache;
        if (nullSessionCache) {
            sessionCache = new NullSessionCacheFactory().getSessionCache(sessionHandler.getSessionManager());
        } else {
            sessionCache = new DefaultSessionCacheFactory().getSessionCache(sessionHandler.getSessionManager());
        }
        SessionDataStore sessionDataStore = createSessionDataStoreFactory().getSessionDataStore(sessionHandler.getSessionManager());
        sessionCache.setSessionDataStore(sessionDataStore);
        sessionHandler.setSessionCache(sessionCache);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setSessionHandler(sessionHandler);
        context.addServlet(new ServletHolder(new SessionServlet()), "/session/*");
        // ee8 context attaches to the core server through its CoreContextHandler bridge
        context.setServer(server);
        server.setHandler(context.getCoreContextHandler());

        server.start();
        return connector.getLocalPort();
    }

    public static class SessionServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            if (request.getPathInfo() != null && request.getPathInfo().startsWith("/set")) {
                HttpSession session = request.getSession(true);
                session.setAttribute("characterization", "SESSION-VALUE");
                response.getWriter().print(session.getId());
            } else {
                HttpSession session = request.getSession(false);
                Object value = session == null ? null : session.getAttribute("characterization");
                response.getWriter().print(value == null ? "NO-SESSION" : value);
            }
        }
    }

    private HttpResponse<String> get(int port, String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void sessionPersistsToTableAndSurvivesServerRestart() throws Exception {
        runSessionLifecycle(false);
    }

    @Test
    public void sessionPersistsWithNullSessionCache() throws Exception {
        // The write-through mode (server.api.sessioncache=none) — different flush timing
        runSessionLifecycle(true);
    }

    private void runSessionLifecycle(boolean nullSessionCache) throws Exception {
        int port = startServer(nullSessionCache);

        // Create a session and capture the cookie
        HttpResponse<String> setResponse = get(port, "/session/set", null);
        assertEquals(200, setResponse.statusCode());
        String sessionId = setResponse.body();
        String cookie = setResponse.headers().firstValue("Set-Cookie").orElse(null);
        assertNotNull("no session cookie issued", cookie);
        cookie = cookie.split(";")[0];

        // Same server instance can read it back
        assertEquals("SESSION-VALUE", get(port, "/session/get", cookie).body());

        // The row lands in the JDBC store's table under the bare session id (no worker suffix)
        String bareSessionId = StringUtils.substringBefore(sessionId, ".");
        long deadline = System.currentTimeMillis() + 10000;
        int rows = 0;
        while (rows == 0 && System.currentTimeMillis() < deadline) {
            rows = countRows(bareSessionId);
            if (rows == 0) {
                Thread.sleep(250);
            }
        }
        assertEquals("session row not written to " + TABLE_NAME, 1, rows);

        // A brand-new server on the same table — the engine-restart scenario — reads the session
        server.stop();
        int newPort = startServer(nullSessionCache);
        assertNotEquals(port, newPort);
        assertEquals("SESSION-VALUE", get(newPort, "/session/get", cookie).body());

        // Sanity: an unknown cookie does not resolve to a session
        assertEquals("NO-SESSION", get(newPort, "/session/get", "JSESSIONID=bogus.node0").body());
    }

    private int countRows(String sessionId) throws Exception {
        try (Connection connection = dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + TABLE_NAME + " WHERE sessionid = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
