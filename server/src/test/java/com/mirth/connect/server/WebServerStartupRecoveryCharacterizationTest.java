package com.mirth.connect.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.After;
import org.junit.Test;

/**
 * Jetty 12 migration Stage 0 characterization (see docs/Marketplace/Jetty12-Migration-Plan.md in
 * the project-mdds-llm workspace repo, section 4).
 *
 * MirthWebServer's ONLY post-assembly handler-list mutation is the startup() recovery path: if
 * start() throws (a bad webapp), it stops, removes every webapp from the HandlerList, and calls
 * start() again — the engine must come up serving everything else. This test pins that exact
 * sequence on a bare 9.4 Server + HandlerList. In Jetty 12 the handler tree becomes a core
 * ContextHandlerCollection (plan Stage 2), and this same recovery sequence — mutate the collection
 * after a failed start, then restart — must still work.
 */
public class WebServerStartupRecoveryCharacterizationTest {

    private Server server;
    private HttpClient httpClient = HttpClient.newHttpClient();

    @After
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** A handler that fails its lifecycle start — stands in for a broken webapp. */
    private static class FailingOnStartHandler extends AbstractHandler {
        @Override
        protected void doStart() throws Exception {
            throw new IllegalStateException("simulated broken webapp");
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {}
    }

    private static class MarkerHandler extends AbstractHandler {
        private final String marker;

        MarkerHandler(String marker) {
            this.marker = marker;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            response.setContentType("text/plain");
            response.getOutputStream().print(marker);
            baseRequest.setHandled(true);
        }
    }

    @Test
    public void failedStartRecoversAfterRemovingBadHandler() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler goodContext = new ContextHandler();
        goodContext.setContextPath("/good");
        // Same setting HttpReceiver uses — without it a bare "/good" gets a 302 to "/good/"
        goodContext.setAllowNullPathInfo(true);
        goodContext.setHandler(new MarkerHandler("ALIVE"));

        ContextHandler badContext = new ContextHandler();
        badContext.setContextPath("/bad");
        badContext.setHandler(new FailingOnStartHandler());

        HandlerList handlers = new HandlerList();
        handlers.addHandler(goodContext);
        handlers.addHandler(badContext);
        server.setHandler(handlers);

        // The exact MirthWebServer.startup() sequence: start -> fail -> stop (ignore errors)
        // -> removeHandler(bad) -> start again
        try {
            server.start();
            fail("expected start() to fail with the broken handler attached");
        } catch (Exception expected) {
        }
        try {
            server.stop();
        } catch (Throwable ignored) {
        }
        handlers.removeHandler(badContext);
        server.start();

        int port = connector.getLocalPort();
        assertTrue("connector not listening after recovery", port > 0);
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/good")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ALIVE", response.body());
    }
}
