package net.server.webadmin;

/**
 * Which language the admin panel is showing, so the messages and status notes the server writes
 * back arrive in the same one.
 * <p>
 * One static value rather than a per-request or per-session one, for two reasons. The panel is
 * bound to loopback with no login, so there is only ever one person looking at it; and the status
 * notes are written by the background sessions - the auto-attack tick, the auto-buff tick - which
 * are nowhere near a request and have no exchange to read a header off. The page sends its own
 * choice along with the state poll, which comes round every five seconds.
 */
public final class Lang {
    private static volatile boolean english;

    private Lang() {
    }

    /**
     * Takes the tag the panel sends, not the browser's Accept-Language: the page has a picker of
     * its own and that choice has to win. A missing tag leaves the setting alone rather than
     * resetting it, so a request that does not carry one cannot flip the language back.
     */
    public static void set(String tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        english = !tag.startsWith("zh");
    }

    public static String t(String zh, String en) {
        return english ? en : zh;
    }
}
