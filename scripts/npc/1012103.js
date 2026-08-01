/* Natalie -- Henesys hair salon.
 *
 * Replaces the stock VIP-coupon script with a browser for the whole ported catalogue:
 * ~1300 hair styles and ~670 eye styles, each with its colour variants.
 *
 * The style-preview dialog shows a handful of avatars at a time (stock scripts use seven,
 * and the packet stores the count in one byte), so 16000 ids cannot simply be listed. Three
 * ways in are offered instead:
 *   - type the id straight in, for anyone who picked one out of the gallery beforehand
 *   - page through the catalogue seven at a time
 *   - show seven at random
 *
 * Style membership comes from server.CosmeticStyles, not from arithmetic on the id: the
 * ported styles were renumbered into whatever slots were free, which scattered each style's
 * colours, so the stock "base + colour offset" rule does not hold here.
 */

var Styles = Java.type("server.CosmeticStyles");

var PER_PAGE = 7;

var state;          // where we are in the conversation
var kind;           // "Hair" or "Face"
var options;        // ids currently shown in a style dialog

function start() {
    state = "menu";
    options = [];
    cm.sendSimple(menuText());
}

function menuText() {
    return "Sit down, let's see what suits you.\r\n"
        + "I have #b" + Styles.styleCount("Hair") + "#k hairstyles and #b"
        + Styles.styleCount("Face") + "#k looks for your eyes.\r\n\r\n"
        + "#b#L1#Hair - enter a number#l\r\n"
        + "#L2#Hair - browse page by page#l\r\n"
        + "#L3#Hair - surprise me#l\r\n"
        + "#L4#Hair - change colour only#l\r\n"
        + "#L5#Eyes - enter a number#l\r\n"
        + "#L6#Eyes - browse page by page#l\r\n"
        + "#L7#Eyes - surprise me#l\r\n"
        + "#L8#Eyes - change colour only#l#k";
}

function current() {
    return kind === "Hair" ? cm.getPlayer().getHair() : cm.getPlayer().getFace();
}

function apply(id) {
    if (kind === "Hair") {
        cm.setHair(id);
    } else {
        cm.setFace(id);
    }
    cm.sendOk("There you go - #b" + Styles.name(id) + "#k (#b" + id + "#k).");
    cm.dispose();
}

function showStyles(ids, header) {
    if (ids.length === 0) {
        cm.sendOk("There's nothing to show there. Try another page.");
        cm.dispose();
        return;
    }
    options = ids;
    state = "pick";
    // The client captions each avatar from its own String.wz, but list the names in the
    // dialog text too so the numbering is unambiguous.
    cm.sendStyle(header + Styles.captions(ids), ids);
}

function askNumberEntry() {
    state = "number";
    var now = current();
    var lo = kind === "Hair" ? 30000 : 20000;
    var hi = kind === "Hair" ? 39999 : 29999;
    cm.sendGetNumber(
        "Type the number of the style you want.\r\n"
        + "You're wearing #b" + Styles.name(now) + "#k (#b" + now + "#k).",
        now, lo, hi);
}

function askPage() {
    state = "page";
    var pages = Styles.pageCount(kind, PER_PAGE);
    var here = Math.floor((Styles.styleNumber(current()) - 1) / PER_PAGE) + 1;
    if (here < 1) {
        here = 1;
    }
    cm.sendGetNumber("Which page? There are #b" + pages + "#k of them, "
        + PER_PAGE + " styles each.", here, 1, pages);
}

function askColour() {
    var now = current();
    var ids = Styles.coloursOf(now);
    if (ids.length <= 1) {
        cm.sendOk("#b" + Styles.styleName(now) + "#k only comes in the one colour.");
        cm.dispose();
        return;
    }
    showStyles(ids, "Here's #b" + Styles.styleName(now) + "#k in every colour it comes in.");
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.dispose();
        return;
    }

    if (state === "menu") {
        switch (selection) {
            case 1: kind = "Hair"; askNumberEntry(); break;
            case 2: kind = "Hair"; askPage(); break;
            case 3: kind = "Hair"; showStyles(Styles.random("Hair", PER_PAGE, Styles.colour(current())),
                        "How about one of these?"); break;
            case 4: kind = "Hair"; askColour(); break;
            case 5: kind = "Face"; askNumberEntry(); break;
            case 6: kind = "Face"; askPage(); break;
            case 7: kind = "Face"; showStyles(Styles.random("Face", PER_PAGE, Styles.colour(current())),
                        "How about one of these?"); break;
            case 8: kind = "Face"; askColour(); break;
            default: cm.dispose();
        }
        return;
    }

    if (state === "number") {
        var id = selection;
        if (!Styles.exists(id)) {
            cm.sendOk("I don't have anything numbered #b" + id + "#k. "
                + "Check the number and come back.");
            cm.dispose();
            return;
        }
        // Show it before committing -- the preview is the whole point of this dialog.
        showStyles([id], "Is this the one?\r\n#b" + Styles.name(id) + "#k");
        return;
    }

    if (state === "page") {
        showStyles(Styles.page(kind, selection - 1, PER_PAGE, Styles.colour(current())),
            "Page #b" + selection + "#k of #b" + Styles.pageCount(kind, PER_PAGE) + "#k.");
        return;
    }

    if (state === "pick") {
        if (selection < 0 || selection >= options.length) {
            cm.dispose();
            return;
        }
        apply(options[selection]);
        return;
    }

    cm.dispose();
}
