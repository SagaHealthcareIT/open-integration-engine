package com.mirth.connect.server;

import static org.junit.Assert.assertEquals;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.session.DatabaseAdaptor;
import org.junit.Test;

/**
 * Jetty 12 migration Stage 0 characterization (see docs/Marketplace/Jetty12-Migration-Plan.md in
 * the project-mdds-llm workspace repo, section 4).
 *
 * MirthWebServer.createSessionDataStoreFactory installs an anonymous DatabaseAdaptor overriding
 * getBlobType() so SQL Server session stores use the "image" data type. That override depends on
 * three pieces of Jetty API surface surviving the migration: the protected _blobType field, the
 * public getDBName()/setBlobType() accessors, and getBlobType() being overridable. This test uses
 * the identical subclassing technique to pin that surface (the anonymous class itself is private
 * to MirthWebServer, so the technique — not the instance — is what can be characterized).
 */
public class DatabaseAdaptorBlobTypeCharacterizationTest {

    private DatabaseAdaptor adaptorForDatabase(String dbName) {
        return new DatabaseAdaptor() {
            @Override
            public String getDBName() {
                return dbName;
            }

            @Override
            public String getBlobType() {
                if (_blobType == null && StringUtils.containsIgnoreCase(getDBName(), "sql server")) {
                    setBlobType("image");
                }
                return super.getBlobType();
            }
        };
    }

    @Test
    public void sqlServerGetsImageBlobType() {
        assertEquals("image", adaptorForDatabase("Microsoft SQL Server").getBlobType());
    }

    @Test
    public void explicitBlobTypeWinsOverSqlServerDetection() {
        DatabaseAdaptor adaptor = adaptorForDatabase("Microsoft SQL Server");
        adaptor.setBlobType("varbinary(max)");
        assertEquals("varbinary(max)", adaptor.getBlobType());
    }
}
