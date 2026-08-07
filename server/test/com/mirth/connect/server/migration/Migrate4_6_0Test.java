package com.mirth.connect.server.migration;

import static org.junit.Assert.assertEquals;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.junit.Test;

/**
 * 4.6.0 ships Derby 10.17.1.0; hard-upgrading a pre-10.15 database can die deserializing its
 * stored prepared statements AND leaves the store stamped 10.17 so no older Derby can open it.
 * The engine therefore never hard-upgrades Derby: this migrator normalizes the flag that
 * {@link Migrate3_0_0} appends back to {@code upgrade=false}.
 */
public class Migrate4_6_0Test {

    private PropertiesConfiguration config(String database, String url) {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty("database", database);
        if (url != null) {
            configuration.setProperty("database.url", url);
        }
        return configuration;
    }

    @Test
    public void flipsHardUpgradeToSoftOnDerby() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    /**
     * The rewrite must not resolve ${dir.appdata} — getString() interpolates, and saving an
     * interpolated value back would bake the resolved path into the URL for good.
     */
    @Test
    public void preservesAppdataPlaceholder() {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty("dir.appdata", "appdata");
        configuration.setProperty("database", "derby");
        configuration.setProperty("database.url", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");

        new Migrate4_6_0().updateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    @Test
    public void leavesSoftUpgradeUrlUntouched() {
        String url = "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false";
        PropertiesConfiguration configuration = config("derby", url);
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals(url, configuration.getProperty("database.url"));
    }

    /** The URL carries the literal token, so only the derby guard can keep it unchanged. */
    @Test
    public void ignoresNonDerbyDatabases() {
        String url = "jdbc:postgresql://localhost:5432/mirthdb;upgrade=true";
        PropertiesConfiguration configuration = config("postgres", url);
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals(url, configuration.getProperty("database.url"));
    }

    /** Derby parses the attribute with Boolean.valueOf, which is case-insensitive. */
    @Test
    public void flipsUppercaseTrue() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=TRUE");
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    @Test
    public void flipsTokenThatIsNotLastInTheUrl() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;upgrade=true;create=true");
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;upgrade=false;create=true", configuration.getProperty("database.url"));
    }

    /** An adjacent value that merely starts with "true" is not the attribute — leave it alone. */
    @Test
    public void doesNotMangleAdjacentValues() {
        String url = "jdbc:derby:${dir.appdata}/mirthdb;upgrade=truex";
        PropertiesConfiguration configuration = config("derby", url);
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals(url, configuration.getProperty("database.url"));
    }

    @Test
    public void normalizesReadOnlyUrlToo() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");
        configuration.setProperty("database-readonly.url", "jdbc:derby:${dir.appdata}/mirthdb;upgrade=true");

        new Migrate4_6_0().updateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;upgrade=false", configuration.getProperty("database-readonly.url"));
    }

    @Test
    public void isIdempotent() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");
        new Migrate4_6_0().updateConfiguration(configuration);
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    @Test
    public void toleratesMissingDatabaseKey() {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty("database.url", "jdbc:derby:${dir.appdata}/mirthdb;upgrade=true");
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;upgrade=true", configuration.getProperty("database.url"));
    }

    /**
     * A duplicated key in mirth.properties makes commons-configuration2 hand back a List. This
     * migrator must not throw on it: DefaultConfigurationController.initialize() swallows the
     * exception, skips its save, and boots against the in-memory config — which by then still
     * holds the ";upgrade=true" Migrate3_0_0 appended earlier in the same pass.
     */
    @Test
    public void survivesDuplicatedDatabaseUrlKey() {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty("database", "derby");
        configuration.addProperty("database.url", "jdbc:derby:one;upgrade=true");
        configuration.addProperty("database.url", "jdbc:derby:two;upgrade=true");

        new Migrate4_6_0().updateConfiguration(configuration);
    }

    /**
     * Every configuration migrator re-runs on every boot, and 3.0.0's runs FIRST — it appends
     * {@code ;upgrade=true} to any Derby URL with no {@code ;upgrade=} token. Running the pair in
     * that order must converge on soft mode, and stay there on subsequent boots.
     */
    @Test
    public void convergesToSoftModeWhenRunAfterMigrate3_0_0() {
        PropertiesConfiguration configuration = config("derby", "jdbc:derby:${dir.appdata}/mirthdb;create=true");

        for (int boot = 0; boot < 3; boot++) {
            new Migrate3_0_0().updateConfiguration(configuration);
            new Migrate4_6_0().updateConfiguration(configuration);
            assertEquals("boot " + boot, "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
        }
    }

    @Test
    public void toleratesMissingUrl() {
        PropertiesConfiguration configuration = config("derby", null);
        new Migrate4_6_0().updateConfiguration(configuration);
        assertEquals(null, configuration.getProperty("database.url"));
    }
}
