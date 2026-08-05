/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.connectors.http;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletRequest;

import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.ssl.SSLContexts;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;

import com.mirth.connect.donkey.model.channel.ConnectorPluginProperties;
import com.mirth.connect.donkey.server.channel.Connector;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.util.MirthSSLUtil;

public class DefaultHttpConfiguration implements HttpConfiguration {

    private ConfigurationController configurationController = ControllerFactory.getFactory().createConfigurationController();

    @Override
    public void configureConnectorDeploy(Connector connector) throws Exception {
        if (connector instanceof HttpDispatcher) {
            configureSocketFactoryRegistry(null, ((HttpDispatcher) connector).getSocketFactoryRegistry());
        }
    }

    @Override
    public void configureConnectorUndeploy(Connector connector) {}

    @Override
    public void configureReceiver(HttpReceiver connector) throws Exception {
        org.eclipse.jetty.server.HttpConfiguration httpConfig = new org.eclipse.jetty.server.HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendXPoweredBy(false);

        /*
         * Jetty 12's default UriCompliance rejects with a bare 400 — before the channel ever sees
         * the request — URI shapes that Jetty 9.4 delivered as messages: empty path segments
         * (//a//b), encoded slashes (%2F), encoded dot-dot, and backslashes in the path. Channel
         * receivers stay 9.4-lenient so existing integrations don't silently lose messages; the
         * admin/API connectors (MirthWebServer) keep the strict Jetty 12 default. The two extra
         * violations on top of LEGACY cover backslash paths; UTF-8 and percent-encoding validity
         * checks remain active. (2026-08-05 decision — Jetty12-Migration-Plan section 3b.)
         */
        httpConfig.setUriCompliance(UriCompliance.LEGACY.with("mirth-http-receiver", UriCompliance.Violation.SUSPICIOUS_PATH_CHARACTERS, UriCompliance.Violation.ILLEGAL_PATH_CHARACTERS));

        ServerConnector listener = new ServerConnector(connector.getServer(), new HttpConnectionFactory(httpConfig));
        listener.setHost(connector.getHost());
        listener.setPort(connector.getPort());
        listener.setIdleTimeout(connector.getTimeout());
        connector.getServer().addConnector(listener);
    }

    @Override
    public void configureDispatcher(HttpDispatcher connector, HttpDispatcherProperties connectorProperties) throws Exception {}

    @Override
    public void configureSocketFactoryRegistry(ConnectorPluginProperties properties, RegistryBuilder<ConnectionSocketFactory> registry) throws Exception {
        String[] enabledProtocols = MirthSSLUtil.getEnabledHttpsProtocols(configurationController.getHttpsClientProtocols());
        String[] enabledCipherSuites = MirthSSLUtil.getEnabledHttpsCipherSuites(configurationController.getHttpsCipherSuites());
        SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(SSLContexts.createSystemDefault(), enabledProtocols, enabledCipherSuites, NoopHostnameVerifier.INSTANCE);
        registry.register("https", sslConnectionSocketFactory);
    }

    @Override
    public Map<String, Object> getRequestInformation(ServletRequest request) {
        return new HashMap<String, Object>();
    }
}