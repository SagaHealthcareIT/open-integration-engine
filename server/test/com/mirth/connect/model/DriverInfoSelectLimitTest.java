/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * http://www.mirthcorp.com
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The select limit query of a database driver is executed verbatim by the Database Connector
 * metadata service (CVE-2026-82583), so only allowlisted "SELECT * FROM ?" row-limiting shapes are
 * accepted.
 */
public class DriverInfoSelectLimitTest {

    @Test
    public void testShippedDriverQueriesAreValid() {
        for (DriverInfo driver : DriverInfo.getDefaultDrivers()) {
            assertTrue(driver.getSelectLimit(), DriverInfo.isValidSelectLimit(driver.getSelectLimit()));
        }
        assertTrue(DriverInfo.isValidSelectLimit("SELECT * FROM ? LIMIT 1"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT * FROM ? WHERE ROWNUM < 2"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT TOP 1 * FROM ?"));
    }

    @Test
    public void testCommonVendorVariantsAreValid() {
        assertTrue(DriverInfo.isValidSelectLimit("select * from ? fetch first 1 row only"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT * FROM ? FETCH FIRST 1 ROWS ONLY"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT FIRST 1 * FROM ?"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT * FROM ? WHERE ROWNUM <= 1"));
        assertTrue(DriverInfo.isValidSelectLimit("SELECT * FROM ? WHERE 1 = 0"));
        assertTrue(DriverInfo.isValidSelectLimit("  SELECT  *  FROM  ?\n"));
    }

    @Test
    public void testEmptyMeansGenericMetadata() {
        assertTrue(DriverInfo.isValidSelectLimit(null));
        assertTrue(DriverInfo.isValidSelectLimit(""));
        assertTrue(DriverInfo.isValidSelectLimit("   "));
    }

    @Test
    public void testNonSelectStatementsAreRejected() {
        assertFalse(DriverInfo.isValidSelectLimit("CALL SYSCS_UTIL.SYSCS_EXPORT_QUERY('SELECT * FROM ?', '/tmp/x', null, null, null)"));
        assertFalse(DriverInfo.isValidSelectLimit("DROP TABLE ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECTX FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("WITH x AS (SELECT 1) SELECT * FROM ?"));
    }

    @Test
    public void testFreeFormSelectsAreRejected() {
        // projections, subqueries and functions would allow a boolean oracle or vendor side effects
        assertFalse(DriverInfo.isValidSelectLimit("SELECT ID AS CONFIGURED_ID FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT 1/(CASE WHEN (SELECT 1 FROM PERSON) = 1 THEN 1 ELSE 0 END) FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? INTO OUTFILE '/tmp/x'"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT lo_export(lo_import('/etc/passwd'), '/tmp/x') FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT SLEEP(30) FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT TOP 1 * FROM ? EXEC xp_cmdshell 'whoami'"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ?, PERSON LIMIT 1"));
    }

    @Test
    public void testStatementSeparatorsAndCommentsAreRejected() {
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? LIMIT 1; DROP TABLE PERSON"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT TOP 1 * FROM ?\nDROP TABLE PERSON"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? -- comment"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT /* x */ * FROM ?"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? # comment"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? LIMIT 1;"));
    }

    @Test
    public void testExactlyOnePlaceholderRequired() {
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM PERSON"));
        assertFalse(DriverInfo.isValidSelectLimit("SELECT * FROM ? WHERE ID = ?"));
    }
}
