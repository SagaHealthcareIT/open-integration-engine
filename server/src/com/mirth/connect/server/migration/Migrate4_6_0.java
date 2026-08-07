/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * http://www.mirthcorp.com
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.server.migration;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.model.util.MigrationException;

public class Migrate4_6_0 extends Migrator implements ConfigurationMigrator {

    private Logger logger = LogManager.getLogger(getClass());

    @Override
    public void migrate() throws MigrationException {}

    @Override
    public Map<String, Object> getConfigurationPropertiesToAdd() {
        return null;
    }

    @Override
    public String[] getConfigurationPropertiesToRemove() {
        return null;
    }

    /**
     * Forces embedded Derby into soft-upgrade mode.
     *
     * 4.6.0 ships Derby 10.17.1.0 (up from 10.10.2.0, for CVE-2022-46337). Derby's HARD upgrade
     * path — {@code ;upgrade=true} — rewrites the data dictionary, and on a database created
     * before 10.15 that rewrite must deserialize the stored prepared statements it is replacing.
     * The 10.15 repackaging changed their serialization identity, so the upgrade can die with
     * {@code XSDA8} / {@code InvalidClassException: ExecRowBuilder}. The failed attempt has
     * already stamped the store as 10.17, after which NO older Derby (10.10 or 10.16) will open
     * the database at all — the install is stranded until the upgrade is somehow completed.
     *
     * Soft upgrade has none of that exposure: 10.17 reads and writes the old on-disk format,
     * recompiling stored statements instead of deserializing them, and the database stays
     * openable by the previous engine — so a downgrade remains possible.
     *
     * {@link Migrate3_0_0#updateConfiguration} appends {@code ;upgrade=true} to any Derby URL
     * lacking an {@code ;upgrade=} token — that is how installs acquired the flag in the first
     * place, and because every configuration migrator re-runs on every boot it would re-add it
     * forever. This migrator runs after it in the same pass and normalizes the flag back to
     * {@code false}, which both repairs existing installs and stops the re-append (3.0.0's check
     * sees a token present). A deliberate hard upgrade is an offline admin procedure — back up
     * the database directory, run {@code SYSCS_UTIL.SYSCS_INVALIDATE_STORED_STATEMENTS()} in a
     * soft-mode session, then upgrade — never something a server start does on its own.
     */
    /**
     * Derby reads the attribute with {@code Boolean.valueOf}, so {@code TRUE}/{@code True} enable
     * a hard upgrade just as {@code true} does; the token is matched case-insensitively and
     * anchored to the end of the URL or the next attribute so an adjacent value (a path, or
     * {@code ;upgrade=truex}) can't be mangled.
     */
    private static final Pattern HARD_UPGRADE = Pattern.compile("(?i);upgrade\\s*=\\s*true\\s*(?=;|$)");

    /**
     * Never throws. If it did, {@link com.mirth.connect.server.controllers.DefaultConfigurationController#initialize()}
     * would swallow the exception, skip its save, and start the server against the in-memory
     * configuration — which by then still carries the {@code ;upgrade=true} that
     * {@link Migrate3_0_0} appended earlier in this same pass. Boot would proceed into exactly
     * the hard upgrade this migrator exists to prevent.
     */
    @Override
    public void updateConfiguration(PropertiesConfiguration configuration) {
        try {
            if (!StringUtils.equalsIgnoreCase(configuration.getString("database"), "derby")) {
                return;
            }

            disableHardUpgrade(configuration, "database.url");
            disableHardUpgrade(configuration, "database-readonly.url");
        } catch (Throwable t) {
            logger.error("Failed to disable Derby hard upgrade; verify that database.url specifies upgrade=false before starting.", t);
        }
    }

    private void disableHardUpgrade(PropertiesConfiguration configuration, String key) {
        /*
         * getProperty, not getString: getString interpolates ${dir.appdata}, and writing the
         * result back would bake the resolved path into the URL permanently. A duplicated key in
         * mirth.properties yields a List rather than a String, hence the instanceof guard.
         */
        Object rawUrl = configuration.getProperty(key);

        if (!(rawUrl instanceof String)) {
            return;
        }

        Matcher matcher = HARD_UPGRADE.matcher((String) rawUrl);

        if (!matcher.find()) {
            return;
        }

        configuration.setProperty(key, matcher.replaceAll(";upgrade=false"));
        logger.info("Derby hard upgrade disabled in " + key + "; the database will be opened in soft-upgrade mode.");
    }

    @Override
    public void migrateSerializedData() throws MigrationException {}
}
