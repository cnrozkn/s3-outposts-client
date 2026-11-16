package io.github.cnrozkn.s3client.web;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Formats byte counts for the templates, which call it as {@code @sizes.format(...)}. */
@Component("sizes")
public class SizeFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final double STEP = 1024;

    public String format(long bytes) {
        if (bytes < STEP) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= STEP && unit < UNITS.length - 1) {
            value /= STEP;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
