package net.server.webadmin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How this package reads the extracted wz XML.
 * <p>
 * The index builders scan those files line by line rather than going through the provider, which
 * re-parses a whole .img.xml per lookup behind a lock the game itself needs. They all need the
 * same encoding policy, and it is a decision worth keeping in one place.
 */
final class Wz {
    private Wz() {
    }

    /**
     * The files declare UTF-8 and mostly are ASCII, but not entirely - "G&#9733; Coconut Season"
     * is in there. A stray byte should cost one character rather than aborting the whole read
     * with a MalformedInputException, so malformed input is replaced instead of thrown.
     */
    static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }

    /** The five entities the wz XML dumps escape. */
    static String unescape(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        return raw.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** The value of an attribute on a one-tag-per-line element, or null. */
    static String attr(String tag, String key) {
        String needle = key + "=\"";
        int i = tag.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int end = tag.indexOf('"', i + needle.length());
        return end < 0 ? null : tag.substring(i + needle.length(), end);
    }
}
