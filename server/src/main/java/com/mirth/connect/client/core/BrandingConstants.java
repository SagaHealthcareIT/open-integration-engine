package com.mirth.connect.client.core;

public class BrandingConstants {

    public static final String PRODUCT_NAME = "OpenShare Engine";
    public static final String COMPANY_NAME = "OpenShare";
    public static final String COMPANY_URL = "https://openshare.health";

    // NOTE: wire-protocol identifier shared with the Administrator client; rebrand deliberately
    // (both client + server must change together) — left at the upstream value for now.
    public static final String CLIENT_CONNECTION_HEADER = "openintegrationengine-client";

    // NOTE: CN of the auto-generated self-signed server cert; affects only newly generated certs.
    public static final String SERVER_CERTIFICATE_CN = "oie-engine";

    public static final String CONNECT_SERVER_URL = "https://connect.openintegrationengine.org";
    public static final String NOTIFICATIONS_URL = "https://api.github.com/repos/SagaHealthcareIT/open-integration-engine/releases";

    public static final boolean CENTRAL_USER_REGISTRATION = false;
    public static final boolean MANDATORY_USER_REGISTRATION = false;
    public static final boolean CHECK_FOR_NOTIFICATIONS = true;
    public static final boolean SEND_USAGE_STATISTICS = false;
}
