package com.thebirdhouse.plugin;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is, read from a resource the Gradle build stamps.
 *
 * Every request carries it. Without it a bug report cannot be answered without first
 * asking the reporter which version they are on and taking their word for it, and a
 * server-side log of a misbehaving submission says nothing about whether the fix for it
 * had already shipped.
 */
final class PluginVersion {

    /** What to report when the resource is missing, so a request is never blocked by it. */
    private static final String UNKNOWN = "unknown";

    static final String VERSION = read();

    private PluginVersion() {
    }

    private static String read() {
        try (InputStream in = PluginVersion.class.getResourceAsStream("/birdhouse.properties")) {
            if (in == null) return UNKNOWN;
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            return version == null || version.trim().isEmpty() ? UNKNOWN : version.trim();
        } catch (Exception e) {
            // Reporting a version is a convenience for diagnosis and never worth failing
            // a submission over.
            return UNKNOWN;
        }
    }
}
