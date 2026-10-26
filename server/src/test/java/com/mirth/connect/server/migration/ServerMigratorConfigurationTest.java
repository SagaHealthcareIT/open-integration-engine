package com.mirth.connect.server.migration;

import static org.junit.Assert.assertEquals;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.junit.Test;

/**
 * End-to-end cover for the configuration-migration pass itself, rather than an individual
 * migrator. It pins the three assumptions the Derby soft-upgrade fix depends on:
 *
 * 1. {@link Migrate4_6_0} is actually dispatched by {@link ServerMigrator} — the single
 *    {@code case v4_6_0} line in the switch is otherwise untested, so deleting it would break
 *    every install while the unit tests stayed green.
 * 2. It runs AFTER {@link Migrate3_0_0}, which appends {@code ;upgrade=true} to Derby URLs.
 * 3. Configuration migrators re-run on every boot (no version gating), so an install that is
 *    already at the latest version still gets repaired.
 */
public class ServerMigratorConfigurationTest {

    private PropertiesConfiguration derbyConfig(String version, String url) {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        if (version != null) {
            configuration.setProperty("version", version);
        }
        configuration.setProperty("dir.appdata", "appdata");
        configuration.setProperty("database", "derby");
        configuration.setProperty("database.url", url);
        return configuration;
    }

    /** A 4.5.x install upgrading to 4.6.0, carrying the flag it appended to itself years ago. */
    @Test
    public void repairsHardUpgradeFlagOnUpgradeFromPreviousVersion() throws Exception {
        PropertiesConfiguration configuration = derbyConfig("4.5.2", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");

        new ServerMigrator().migrateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    /** An install already at the latest version must still be repaired on a later boot. */
    @Test
    public void repairsHardUpgradeFlagWhenAlreadyAtLatestVersion() throws Exception {
        PropertiesConfiguration configuration = derbyConfig("4.6.0", "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=true");

        new ServerMigrator().migrateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    /**
     * A fresh install: the shipped template already carries an {@code ;upgrade=} token, so 3.0.0
     * must not append and 4.6.0 must leave the value alone.
     */
    @Test
    public void leavesFreshInstallTemplateUntouched() throws Exception {
        PropertiesConfiguration configuration = derbyConfig(null, "jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false");

        new ServerMigrator().migrateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }

    /**
     * The whole pass on a URL with no upgrade token at all: 3.0.0 appends true, 4.6.0 flips it,
     * and the net result after a full migration pass is soft mode.
     */
    @Test
    public void netResultOfFullPassIsSoftUpgrade() throws Exception {
        PropertiesConfiguration configuration = derbyConfig(null, "jdbc:derby:${dir.appdata}/mirthdb;create=true");

        new ServerMigrator().migrateConfiguration(configuration);

        assertEquals("jdbc:derby:${dir.appdata}/mirthdb;create=true;upgrade=false", configuration.getProperty("database.url"));
    }
}
