package com.mirth.connect.server;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.nested.AbstractHandler;
import org.eclipse.jetty.ee8.nested.ContextHandler;
import org.eclipse.jetty.ee8.nested.Request;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.After;
import org.junit.Test;

/**
 * Jetty 12 migration characterization (see docs/Marketplace/Jetty12-Migration-Plan.md section 3.5
 * in the project-mdds-llm workspace repo).
 *
 * MirthWebServer serves two SAME-PATH context pairs where 9.4 relied on fallthrough: the Swagger
 * UI static context in front of the API servlet context at /api (existing public_api_html files
 * serve with no X-Requested-With header; everything else falls to the API filter chain — probed
 * live against a 9.4.57 install), and the public_html root context in front of the WebStart
 * servlet context (/webstart.jnlp). These tests pin the two Jetty 12 facts that force
 * MirthWebServer.createResourceContext to build CORE contexts:
 *
 * 1. An ee8 context can never decline: CoreToNestedHandler.handle discards the nested
 *    handled/unhandled result and unconditionally returns handled (verified against the
 *    12.0.38 bytecode), so a miss inside an ee8 context dead-ends instead of reaching the
 *    same-path sibling.
 *
 * 2. A core ResourceHandler context declines misses properly, and ContextHandlerCollection then
 *    tries the next same-path branch — so core-static-first over an ee8 servlet context
 *    reproduces the 9.4 contract.
 */
public class WebServerSamePathFallthroughCharacterizationTest {

    private Server server;
    private HttpClient httpClient = HttpClient.newHttpClient();

    @After
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** ee8 nested handler that declines everything — the 9.4 idiom for "not mine". */
    private static class DecliningHandler extends AbstractHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            // leave unhandled
        }
    }

    private static class ApiServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getOutputStream().print("API");
        }
    }

    private ServletContextHandler apiContext(String contextPath) {
        ServletContextHandler apiContext = new ServletContextHandler();
        apiContext.setContextPath(contextPath);
        apiContext.addServlet(new ServletHolder(new ApiServlet()), "/*");
        return apiContext;
    }

    private int startServer(org.eclipse.jetty.server.Handler first, ContextHandler ee8Second) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        ee8Second.setServer(server);
        List<org.eclipse.jetty.server.Handler> coreHandlers = new ArrayList<org.eclipse.jetty.server.Handler>();
        coreHandlers.add(first);
        coreHandlers.add(ee8Second.getCoreContextHandler());
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(coreHandlers);
        server.setHandler(contexts);
        server.start();
        return connector.getLocalPort();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The DEFECT that rules out ee8 static contexts: even a fully-declining ee8 context swallows
     * the request (bridge always reports handled), so the same-path sibling never runs and the
     * miss dead-ends as a 404. If this test ever FAILS with body "API", the ee8 bridge learned to
     * propagate unhandled and createResourceContext could go back to ee8 contexts.
     */
    @Test
    public void ee8ContextSwallowsDeclinedRequests() throws Exception {
        ContextHandler decliningContext = new ContextHandler();
        decliningContext.setContextPath("/same");
        decliningContext.setAllowNullPathInfo(true);
        decliningContext.setHandler(new DecliningHandler());
        ServletContextHandler apiContext = apiContext("/same");

        int port = startServerWithEe8First(decliningContext, apiContext);

        HttpResponse<String> response = get(port, "/same/users/current");
        assertEquals(404, response.statusCode());
    }

    private int startServerWithEe8First(ContextHandler ee8First, ContextHandler ee8Second) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        ee8First.setServer(server);
        ee8Second.setServer(server);
        List<org.eclipse.jetty.server.Handler> coreHandlers = new ArrayList<org.eclipse.jetty.server.Handler>();
        coreHandlers.add(ee8First.getCoreContextHandler());
        coreHandlers.add(ee8Second.getCoreContextHandler());
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(coreHandlers);
        server.setHandler(contexts);
        server.start();
        return connector.getLocalPort();
    }

    /**
     * The DESIGN MirthWebServer uses instead: a core resource context first, ee8 servlet context
     * second, same path. Existing files serve from the static context; misses decline and the
     * servlet context answers — the 9.4 /api and root contracts.
     */
    @Test
    public void coreResourceContextMissesFallThroughToEe8Sibling() throws Exception {
        Path docroot = Files.createTempDirectory("jetty12-fallthrough");
        Files.writeString(docroot.resolve("static.txt"), "STATIC");

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.of(resourceHandler).newResource(docroot.toString()));
        org.eclipse.jetty.server.handler.ContextHandler staticContext = new org.eclipse.jetty.server.handler.ContextHandler("/same");
        staticContext.setHandler(resourceHandler);

        int port = startServer(staticContext, apiContext("/same"));

        HttpResponse<String> staticResponse = get(port, "/same/static.txt");
        assertEquals(200, staticResponse.statusCode());
        assertEquals("STATIC", staticResponse.body());

        HttpResponse<String> apiResponse = get(port, "/same/users/current");
        assertEquals(200, apiResponse.statusCode());
        assertEquals("API", apiResponse.body());
    }
}
