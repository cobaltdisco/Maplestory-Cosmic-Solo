package net.server.webadmin;

import java.util.regex.Pattern;

/**
 * Tells a real item name from a generated stand-in.
 * <p>
 * Nothing here guesses at game data - it recognises the output of the two tools that write these
 * placeholders, so the rule is exactly as tight as they are:
 * <ul>
 *   <li>{@code MISSING NAME} - written by Cosmic's own {@code NoItemNameFetcher}, which invents a
 *       String.wz entry for every id that has item data but no name, so the server can still find
 *       the item at all.</li>
 *   <li>{@code <Folder> <id>} - written by tools-local/StringInjector as {@code p[0] + " " + newId}
 *       when a ported MapleLegends item had no name of its own. The id has to be the item's own id
 *       for this to match, so a genuine name that happens to end in a number is safe.</li>
 * </ul>
 * These are not broken items - plenty of them have real stats and can be worn. They are just
 * impossible to pick deliberately out of a list, which is why the panel hides them by default
 * rather than dropping them from the index.
 */
final class Names {
    private static final String MISSING = "MISSING NAME";
    private static final Pattern FOLDER_AND_ID = Pattern.compile("[A-Za-z]+ \\d+");

    private Names() {
    }

    static boolean isPlaceholder(int id, String name) {
        if (name == null || name.isBlank() || MISSING.equals(name)) {
            return true;
        }
        return FOLDER_AND_ID.matcher(name).matches() && name.endsWith(" " + id);
    }
}
