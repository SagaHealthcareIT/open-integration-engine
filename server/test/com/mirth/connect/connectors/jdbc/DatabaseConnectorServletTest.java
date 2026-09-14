// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 benjodo (Innovar-Healthcare/BridgeLink pull request #200)
// SPDX-FileCopyrightText: 2026 Saga IT, LLC

package com.mirth.connect.connectors.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.SecurityContext;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.mirth.connect.model.DriverInfo;
import com.mirth.connect.server.api.ServletTestBase;
import com.mirth.connect.server.controllers.ContextFactoryController;
import com.mirth.connect.server.controllers.ControllerFactory;

/**
 * Regression tests for CVE-2026-82583: the {@code selectLimit} parameter of
 * {@code POST /connectors/jdbc/_getTables} was executed verbatim, letting any authenticated API
 * caller run arbitrary SQL against any reachable database (including the server's own).
 * <p>
 * The tests run against an in-memory Derby database so no external infrastructure is required.
 */
public class DatabaseConnectorServletTest extends ServletTestBase {

    private static final String DERBY_DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";
    private static final String DERBY_URL = "jdbc:derby:memory:cve82583;create=true";
    private static final String DERBY_USER = "APP";
    private static final String CHANNEL_ID = "channel";
    private static final String CHANNEL_NAME = "channel";

    /*
     * An allowlisted shape that Derby can execute. Which path ran is visible from the returned
     * columns: the configured query is executed against the schema-qualified table
     * ("APP"."PATIENT", because the user name matches the schema) and so returns only that
     * table's columns, whereas the generic DatabaseMetaData.getColumns(null, null, "PATIENT",
     * null) fallback returns the columns of EVERY table named PATIENT, including the decoy in
     * schema SCHEMA2 with its extra column.
     */
    private static final String CONFIGURED_SELECT_LIMIT = "SELECT * FROM ? FETCH FIRST 1 ROWS ONLY";
    private static final List<String> CONFIGURED_PATH_COLUMNS = Arrays.asList("ID", "NAME");
    private static final List<String> GENERIC_PATH_COLUMNS = Arrays.asList("ID", "NAME", "ID", "NAME", "EXTRA");

    /*
     * Valid Derby SQL that is NOT an allowlisted shape: if it were executed the returned column
     * would be CONFIGURED_ID instead of the table's real columns.
     */
    private static final String NON_ALLOWLISTED_SELECT_LIMIT = "SELECT ID AS CONFIGURED_ID FROM ?";

    private static ContextFactoryController contextFactoryController;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DatabaseConnectorServlet servlet;

    @BeforeClass
    public static void beforeClass() throws Exception {
        ServletTestBase.setup();

        contextFactoryController = mock(ContextFactoryController.class);
        when(controllerFactory.createContextFactoryController()).thenReturn(contextFactoryController);

        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                requestStaticInjection(ControllerFactory.class);
                bind(ControllerFactory.class).toInstance(controllerFactory);
            }
        });
        injector.getInstance(ControllerFactory.class);

        Class.forName(DERBY_DRIVER);
        try (Connection connection = DriverManager.getConnection(DERBY_URL, DERBY_USER, ""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE PATIENT (ID INTEGER, NAME VARCHAR(20))");
            statement.executeUpdate("CREATE TABLE OTHER (SECRET VARCHAR(20))");
            statement.executeUpdate("CREATE SCHEMA SCHEMA2");
            statement.executeUpdate("CREATE TABLE SCHEMA2.PATIENT (ID INTEGER, NAME VARCHAR(20), EXTRA VARCHAR(5))");
        }
    }

    @Before
    public void beforeTest() throws Exception {
        configureDrivers(derbyDriverInfo(CONFIGURED_SELECT_LIMIT));
        servlet = new TestDatabaseConnectorServlet(request, mock(SecurityContext.class));
    }

    // ========== getTables ==========

    @Test
    public void testCallerSelectLimitIsNotExecuted() throws Exception {
        SortedSet<Table> tables = getTables("SELECT 1 AS INJECTED FROM SYSIBM.SYSDUMMY1");

        // the configured query ran (not the generic fallback), and the caller's alias never appeared
        assertEquals(CONFIGURED_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    @Test
    public void testCallerSelectLimitCannotRedirectToAnotherTable() throws Exception {
        SortedSet<Table> tables = getTables("SELECT * FROM OTHER");

        assertEquals(CONFIGURED_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    /**
     * Mirrors the published proof of concept: a Derby system procedure that writes a file. Before
     * the fix the procedure ran (and wrote the file) even though executeQuery() then threw, because
     * the exception was swallowed by the generic fallback.
     */
    @Test
    public void testCallerSelectLimitCannotWriteFiles() throws Exception {
        File exported = new File(tempFolder.getRoot(), "cve-2026-82583.csv");
        String payload = "CALL SYSCS_UTIL.SYSCS_EXPORT_QUERY('SELECT * FROM PATIENT', '" + exported.getAbsolutePath().replace("'", "''") + "', null, null, null)";

        getTables(payload);

        assertFalse("caller-supplied SQL must not be executed", exported.exists());
    }

    @Test
    public void testConfiguredSelectLimitIsExecuted() throws Exception {
        SortedSet<Table> tables = getTables(CONFIGURED_SELECT_LIMIT);

        assertEquals(CONFIGURED_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    @Test
    public void testConfiguredSelectLimitIsUsedWhenCallerOmitsIt() throws Exception {
        SortedSet<Table> tables = getTables(null);

        assertEquals(CONFIGURED_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    @Test
    public void testUnknownDriverFallsBackToGenericColumnMetadata() throws Exception {
        configureDrivers(new DriverInfo("PostgreSQL", "org.postgresql.Driver", "jdbc:postgresql://host:port/dbname", "SELECT * FROM ? LIMIT 1"));

        SortedSet<Table> tables = getTables("SELECT 1 AS INJECTED FROM SYSIBM.SYSDUMMY1");

        assertEquals(GENERIC_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    @Test
    public void testDriverWithoutSelectLimitFallsBackToGenericColumnMetadata() throws Exception {
        configureDrivers(derbyDriverInfo(""));

        SortedSet<Table> tables = getTables("SELECT 1 AS INJECTED FROM SYSIBM.SYSDUMMY1");

        assertEquals(GENERIC_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    /**
     * The driver list is itself editable through PUT /server/databaseDrivers, so a configured
     * select limit that is not a single SELECT must not be executed either.
     */
    @Test
    public void testConfiguredNonSelectQueryIsNotExecuted() throws Exception {
        File exported = new File(tempFolder.getRoot(), "cve-2026-82583-configured.csv");
        String payload = "CALL SYSCS_UTIL.SYSCS_EXPORT_QUERY('SELECT * FROM ?', '" + exported.getAbsolutePath().replace("'", "''") + "', null, null, null)";
        configureDrivers(derbyDriverInfo(payload));

        SortedSet<Table> tables = getTables(payload);

        assertFalse("configured non-SELECT query must not be executed", exported.exists());
        assertEquals(GENERIC_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    /**
     * Valid SQL that Derby would happily run, but outside the allowlisted shapes: proves the
     * use-time validation, not just Derby's parser, is what stops it.
     */
    @Test
    public void testConfiguredQueryOutsideAllowlistIsNotExecuted() throws Exception {
        configureDrivers(derbyDriverInfo(NON_ALLOWLISTED_SELECT_LIMIT));

        SortedSet<Table> tables = getTables(null);

        assertEquals(GENERIC_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    @Test
    public void testNullDriverEntryIsSkipped() throws Exception {
        List<DriverInfo> drivers = new ArrayList<DriverInfo>();
        drivers.add(null);
        drivers.add(derbyDriverInfo(CONFIGURED_SELECT_LIMIT));
        when(configurationController.getDatabaseDrivers()).thenReturn(drivers);

        SortedSet<Table> tables = getTables(null);

        assertEquals(CONFIGURED_PATH_COLUMNS, columnNames(tables, "PATIENT"));
    }

    // ========== resolveSelectLimit ==========

    @Test
    public void testResolveSelectLimitMatchesAlternativeClassName() throws Exception {
        DriverInfo driverInfo = new DriverInfo("Derby", "org.apache.derby.jdbc.SomeOtherDriver", "jdbc:derby:dbname", CONFIGURED_SELECT_LIMIT, new ArrayList<String>(Arrays.asList(DERBY_DRIVER)));
        configureDrivers(driverInfo);

        assertEquals(CONFIGURED_SELECT_LIMIT, servlet.resolveSelectLimit(DERBY_DRIVER, "SELECT 1 AS INJECTED FROM SYSIBM.SYSDUMMY1"));
    }

    @Test
    public void testResolveSelectLimitTrimsWhitespace() throws Exception {
        assertEquals(CONFIGURED_SELECT_LIMIT, servlet.resolveSelectLimit(DERBY_DRIVER, "  " + CONFIGURED_SELECT_LIMIT + "\n"));
    }

    @Test
    public void testResolveSelectLimitBlankDriver() throws Exception {
        assertEquals("", servlet.resolveSelectLimit(null, CONFIGURED_SELECT_LIMIT));
        assertEquals("", servlet.resolveSelectLimit(" ", CONFIGURED_SELECT_LIMIT));
    }

    @Test
    public void testResolveSelectLimitNoConfiguredDrivers() throws Exception {
        when(configurationController.getDatabaseDrivers()).thenReturn(null);
        assertEquals("", servlet.resolveSelectLimit(DERBY_DRIVER, CONFIGURED_SELECT_LIMIT));

        when(configurationController.getDatabaseDrivers()).thenReturn(new ArrayList<DriverInfo>());
        assertEquals("", servlet.resolveSelectLimit(DERBY_DRIVER, CONFIGURED_SELECT_LIMIT));
    }

    // ========== helpers ==========

    private SortedSet<Table> getTables(String selectLimit) {
        Set<String> tableNamePatterns = new HashSet<String>(Arrays.asList("PATIENT"));
        return servlet.getTables(CHANNEL_ID, CHANNEL_NAME, DERBY_DRIVER, DERBY_URL, DERBY_USER, "", tableNamePatterns, selectLimit, new HashSet<String>());
    }

    private static List<String> columnNames(SortedSet<Table> tables, String tableName) {
        assertEquals(1, tables.size());
        Table table = tables.first();
        assertEquals(tableName, table.getName());

        List<String> names = new ArrayList<String>();
        for (Column column : table.getColumns()) {
            names.add(column.getName());
        }
        return names;
    }

    private static DriverInfo derbyDriverInfo(String selectLimit) {
        return new DriverInfo("Derby", DERBY_DRIVER, "jdbc:derby:dbname", selectLimit);
    }

    private static void configureDrivers(DriverInfo... drivers) throws Exception {
        when(configurationController.getDatabaseDrivers()).thenReturn(new ArrayList<DriverInfo>(Arrays.asList(drivers)));
    }

    /**
     * Bypasses login initialization and authorization, which are exercised by other tests.
     */
    private static class TestDatabaseConnectorServlet extends DatabaseConnectorServlet {
        public TestDatabaseConnectorServlet(HttpServletRequest request, SecurityContext sc) {
            super(request, sc);
        }

        @Override
        protected boolean isUserAuthorized() {
            return true;
        }
    }
}
