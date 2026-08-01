/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ids are the suffix of the body/head artwork pair in Character.wz: skin n is drawn from
 * 0000200n.img (body) and 0001200n.img (head). Stock v83 ships 0-5 and 9-11; 6-8 and 12-16
 * are MapleLegends tones ported in, so every id here must have BOTH files present or the
 * client draws a headless or invisible character.
 */
public enum SkinColor {
    LIGHT(0),
    TANNED(1),
    DARK(2),
    PALE(3),
    BLUE(4),
    GREEN(5),
    GOLD(6),
    SLATE(7),
    BRONZE(8),
    WHITE(9),
    PINK(10),
    BROWN(11),
    IVORY(12),
    ASH(13),
    CORAL(14),
    ROSE(15),
    BLUSH(16);

    private static final Logger log = LoggerFactory.getLogger(SkinColor.class);

    final int id;

    SkinColor(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Never null. Callers -- CharacterFactory, NPCConversationManager and both Character
     * loaders -- feed the result straight into PacketCreator, which dereferences it while
     * building the character-list packet, so a null here is an NPE the player cannot
     * recover from: it fires before they can log in and change the value back.
     */
    public static SkinColor getById(int id) {
        for (SkinColor l : SkinColor.values()) {
            if (l.getId() == id) {
                return l;
            }
        }
        log.warn("Unknown skin colour {}, falling back to {}", id, LIGHT);
        return LIGHT;
    }
}
