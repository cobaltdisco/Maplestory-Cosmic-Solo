/* Ms. Tan -- Henesys skin care.
 *
 * Replaces the stock coupon script (which offered skins 0-4 for a #5153000) with the whole
 * palette, free: stock v83's 0-5 and 9-11, plus 6-8 and 12-16 ported from MapleLegends.
 *
 * The preview dialog draws one avatar per entry and the packet stores the count in a single
 * byte, so the palette is paged seven at a time -- the same limit Natalie works around for
 * hair and eyes. Paging is by id so the numbers in the text stay predictable.
 *
 * Every id listed here must have BOTH 0000 20xx.img and 0001 20xx.img in the client's
 * Character.wz; a missing head file draws a headless character rather than failing loudly.
 */

var PER_PAGE = 7;

// Index is the skin id, so the gaps stock v83 left (6-8) are filled in place.
var NAMES = [
    "Light", "Tanned", "Dark", "Pale", "Blue", "Green", "Gold", "Slate", "Bronze",
    "White", "Pink", "Brown", "Ivory", "Ash", "Coral", "Rose", "Blush"
];

var state;
var options;        // skin ids currently drawn in the preview dialog

function start() {
    state = "menu";
    options = [];
    cm.sendSimple(menuText());
}

function pageCount() {
    return Math.ceil(NAMES.length / PER_PAGE);
}

function menuText() {
    var now = cm.getPlayer().getSkinColor().getId();
    var s = "Welcome to Henesys Skin-Care. No coupon needed any more -- try as many as you like.\r\n"
        + "You're wearing #b" + nameOf(now) + "#k right now.\r\n\r\n";
    for (var p = 0; p < pageCount(); p++) {
        s += "#b#L" + p + "#" + rangeLabel(p) + "#l\r\n";
    }
    return s + "#k";
}

function nameOf(id) {
    return id >= 0 && id < NAMES.length ? NAMES[id] : "something I don't recognise";
}

function idsOn(page) {
    var ids = [];
    for (var i = page * PER_PAGE; i < NAMES.length && i < (page + 1) * PER_PAGE; i++) {
        ids.push(i);
    }
    return ids;
}

function rangeLabel(page) {
    var names = [];
    var ids = idsOn(page);
    for (var i = 0; i < ids.length; i++) {
        names.push(NAMES[ids[i]]);
    }
    return names.join(", ");
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.dispose();
        return;
    }

    if (state === "menu") {
        if (selection < 0 || selection >= pageCount()) {
            cm.dispose();
            return;
        }
        options = idsOn(selection);
        state = "pick";
        var caption = "";
        for (var i = 0; i < options.length; i++) {
            caption += "\r\n#b" + (i + 1) + ".#k " + NAMES[options[i]];
        }
        // sendStyle takes int[]; convert explicitly rather than relying on how a JS array
        // built by push() happens to bind to it.
        cm.sendStyle("Have a look at yourself in each of these." + caption,
            Java.to(options, "int[]"));
        return;
    }

    if (state === "pick") {
        if (selection < 0 || selection >= options.length) {
            cm.dispose();
            return;
        }
        var id = options[selection];
        cm.setSkin(id);
        cm.sendOk("#b" + NAMES[id] + "#k it is. Come back whenever you fancy a change.");
        cm.dispose();
        return;
    }

    cm.dispose();
}
