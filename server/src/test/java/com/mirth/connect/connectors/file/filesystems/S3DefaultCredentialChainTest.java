// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Saga IT, LLC

package com.mirth.connect.connectors.file.filesystems;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.mirth.connect.connectors.file.FileSystemConnectionOptions;
import com.mirth.connect.connectors.file.S3SchemeProperties;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

/**
 * The S3 connector's default credential chain against the real AWS SDK, with no network access.
 *
 * SDK 2.15.28 failed on Jackson 2.18: EC2MetadataUtils read a field Jackson removed, so the instance
 * role path threw NoSuchFieldError and then NoClassDefFoundError. Neither is caught by catch
 * (Exception), and messages stuck at the S3 destination. Closing a client closes its credentials
 * provider, and later SDKs let that close reach the shared chain's STS clients.
 *
 * Every server test runs in one JVM, and S3Connection's default chain is built on first use and
 * kept, so this class configures it once (web identity against a local mock STS) for all of its
 * tests. No other test resolves that chain.
 */
public class S3DefaultCredentialChainTest {

    private static final AtomicInteger stsCalls = new AtomicInteger();
    private static final Map<String, String> savedProperties = new HashMap<String, String>();
    private static volatile int credentialLifetimeSeconds = 3600;
    private static HttpServer sts;
    private static Path tokenFile;

    @BeforeClass
    public static void setUpClass() throws Exception {
        sts = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sts.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            stsCalls.incrementAndGet();
            byte[] body = assumeRoleWithWebIdentityResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        sts.start();

        tokenFile = Files.createTempFile("web-identity", ".token");
        Files.writeString(tokenFile, "test-token");

        setProperty("aws.webIdentityTokenFile", tokenFile.toString());
        setProperty("aws.roleArn", "arn:aws:iam::123456789012:role/test");
        setProperty("aws.roleSessionName", "s3-connection-test");
        setProperty("aws.region", Region.US_EAST_1.id());
        setProperty("aws.endpointUrlSts", "http://127.0.0.1:" + sts.getAddress().getPort());
        // Nothing listens here, so the instance role path fails fast instead of reaching a real metadata service
        setProperty("aws.ec2MetadataServiceEndpoint", "http://127.0.0.1:" + closedPort());
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        for (Map.Entry<String, String> entry : savedProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        if (sts != null) {
            sts.stop(0);
        }
        if (tokenFile != null) {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    public void testEc2MetadataUtilsInitializes() throws Exception {
        Class.forName("software.amazon.awssdk.regions.internal.util.EC2MetadataUtils", true, getClass().getClassLoader());
    }

    @Test
    public void testInstanceProfileFailsWithSdkClientExceptionWhenMetadataServiceUnreachable() throws Exception {
        InstanceProfileCredentialsProvider provider = InstanceProfileCredentialsProvider.builder().build();
        try {
            provider.resolveCredentials();
            fail("Resolved credentials from a closed port");
        } catch (SdkClientException expected) {
            // An Error here (NoSuchFieldError, NoClassDefFoundError) is the regression
        } finally {
            provider.close();
        }
    }

    @Test
    public void testDestroyingOneConnectionLeavesTheSharedChainWorking() throws Exception {
        assumeTrue("AWS credentials in the environment come before web identity in the chain", System.getenv("AWS_ACCESS_KEY_ID") == null);

        FileSystemConnectionOptions options = defaultChainOptions();
        int callsBefore = stsCalls.get();

        // Credentials that go stale after three seconds
        credentialLifetimeSeconds = 63;
        S3Connection first = new S3Connection(options, 10000);
        first.createCredentialsProvider(options).resolveCredentials();
        first.destroy();

        // Once they are stale the next resolve must call STS through the chain's own client
        credentialLifetimeSeconds = 3600;
        Thread.sleep(3500);
        S3Connection second = new S3Connection(options, 10000);
        try {
            AwsCredentials credentials = second.createCredentialsProvider(options).resolveCredentials();
            assertEquals("ASIATESTACCESSKEY", credentials.accessKeyId());
            assertEquals(2, stsCalls.get() - callsBefore);
        } finally {
            second.destroy();
        }

        // One chain for every connection: an hour's credentials are served from its cache
        S3Connection third = new S3Connection(options, 10000);
        try {
            third.createCredentialsProvider(options).resolveCredentials();
            assertEquals(2, stsCalls.get() - callsBefore);
        } finally {
            third.destroy();
        }
    }

    private static void setProperty(String key, String value) {
        savedProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static FileSystemConnectionOptions defaultChainOptions() {
        S3SchemeProperties schemeProps = new S3SchemeProperties();
        schemeProps.setUseDefaultCredentialProviderChain(true);
        schemeProps.setUseTemporaryCredentials(false);
        schemeProps.setRegion(Region.US_EAST_1.id());
        return new FileSystemConnectionOptions(false, null, null, schemeProps);
    }

    /*
     * The SDK's STS credentials provider treats credentials as stale one minute before they expire,
     * and refreshes them on every resolve in the five minutes before that, so a 63 second lifetime
     * goes stale after three and an hour is served from the cache.
     */
    private static String assumeRoleWithWebIdentityResponse() {
        return "<AssumeRoleWithWebIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">" //
                + "<AssumeRoleWithWebIdentityResult><Credentials>" //
                + "<AccessKeyId>ASIATESTACCESSKEY</AccessKeyId>" //
                + "<SecretAccessKey>test-secret</SecretAccessKey>" //
                + "<SessionToken>test-session-token</SessionToken>" //
                + "<Expiration>" + Instant.now().plusSeconds(credentialLifetimeSeconds) + "</Expiration>" //
                + "</Credentials>" //
                + "<AssumedRoleUser><AssumedRoleId>AROATEST:s3-connection-test</AssumedRoleId><Arn>arn:aws:sts::123456789012:assumed-role/test/s3-connection-test</Arn></AssumedRoleUser>" //
                + "</AssumeRoleWithWebIdentityResult>" //
                + "<ResponseMetadata><RequestId>test</RequestId></ResponseMetadata>" //
                + "</AssumeRoleWithWebIdentityResponse>";
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
