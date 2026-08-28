package com.mirth.connect.server.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mirth.connect.model.util.MigrationException;

/**
 * Covers the initial-admin-password resolution used by {@link ServerMigrator#initDatabase} on the
 * one-time empty-database build. The env read and the digest step are excluded here (env vars
 * cannot be set from a unit test, and the digester needs a running engine); those are exercised by
 * a live boot. What matters and is testable is the FAIL-CLOSED resolution logic: a password that
 * was requested but cannot be produced must throw so the caller never falls back to the default
 * {@code admin}/{@code admin} seed, and the file and env sources must normalize identically.
 */
public class ServerMigratorInitialAdminPasswordTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File fileContaining(String contents) throws Exception {
        File f = folder.newFile();
        Files.write(f.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // ---- not requested -> null (default seed preserved) ------------------------------------

    @Test
    public void notRequested_returnsNull() throws Exception {
        assertNull(ServerMigrator.resolveInitialAdminPassword(null, null));
    }

    @Test
    public void blankFilePathAndNoDirectValue_returnsNull() throws Exception {
        // A blank file path is treated as "no file", not as a request.
        assertNull(ServerMigrator.resolveInitialAdminPassword("   ", null));
    }

    // ---- direct value (env var stand-in) --------------------------------------------------

    @Test
    public void directValue_returnedVerbatim() throws Exception {
        assertEquals("s3cr3t!", ServerMigrator.resolveInitialAdminPassword(null, "s3cr3t!"));
    }

    @Test
    public void directValue_singleTrailingNewlineStripped() throws Exception {
        assertEquals("s3cr3t!", ServerMigrator.resolveInitialAdminPassword(null, "s3cr3t!\n"));
    }

    @Test
    public void directValue_blank_failsClosed() throws Exception {
        assertThrows(MigrationException.class, () -> ServerMigrator.resolveInitialAdminPassword(null, "   "));
    }

    @Test
    public void directValue_empty_failsClosed() throws Exception {
        assertThrows(MigrationException.class, () -> ServerMigrator.resolveInitialAdminPassword(null, ""));
    }

    // ---- file source ----------------------------------------------------------------------

    @Test
    public void file_readAndNormalized() throws Exception {
        File f = fileContaining("filepw123\n");
        assertEquals("filepw123", ServerMigrator.resolveInitialAdminPassword(f.getAbsolutePath(), null));
    }

    @Test
    public void file_missing_failsClosed() throws Exception {
        String missing = new File(folder.getRoot(), "does-not-exist").getAbsolutePath();
        assertThrows(MigrationException.class, () -> ServerMigrator.resolveInitialAdminPassword(missing, null));
    }

    @Test
    public void file_blankContent_failsClosed() throws Exception {
        File f = fileContaining("   \n");
        assertThrows(MigrationException.class, () -> ServerMigrator.resolveInitialAdminPassword(f.getAbsolutePath(), null));
    }

    @Test
    public void file_isAuthoritative_noFallbackToDirectValue() throws Exception {
        // When the file reference is set it wins; the direct value must not leak in.
        File f = fileContaining("fromfile");
        assertEquals("fromfile", ServerMigrator.resolveInitialAdminPassword(f.getAbsolutePath(), "fromenv"));
    }

    @Test
    public void fileAndDirect_sameSecret_yieldSamePassword() throws Exception {
        // The consistency guarantee: "secret\n" resolves to the same password either way.
        File f = fileContaining("secret\n");
        String fromFile = ServerMigrator.resolveInitialAdminPassword(f.getAbsolutePath(), null);
        String fromDirect = ServerMigrator.resolveInitialAdminPassword(null, "secret\n");
        assertEquals(fromDirect, fromFile);
        assertEquals("secret", fromFile);
    }

    // ---- normalizeInitialPassword ---------------------------------------------------------

    @Test
    public void normalize_noTrailingNewline_unchanged() {
        assertEquals("pw", ServerMigrator.normalizeInitialPassword("pw"));
    }

    @Test
    public void normalize_stripsSingleLf() {
        assertEquals("pw", ServerMigrator.normalizeInitialPassword("pw\n"));
    }

    @Test
    public void normalize_stripsSingleCrLf() {
        assertEquals("pw", ServerMigrator.normalizeInitialPassword("pw\r\n"));
    }

    @Test
    public void normalize_stripsOnlyOneTrailingNewline() {
        assertEquals("pw\n", ServerMigrator.normalizeInitialPassword("pw\n\n"));
    }

    @Test
    public void normalize_preservesOtherWhitespace() {
        assertEquals("  pa ss  ", ServerMigrator.normalizeInitialPassword("  pa ss  "));
    }

    @Test
    public void normalize_nullSafe() {
        assertNull(ServerMigrator.normalizeInitialPassword(null));
    }
}
