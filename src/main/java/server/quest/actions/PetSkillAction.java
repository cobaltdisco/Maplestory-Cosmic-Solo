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
package server.quest.actions;

import client.Character;
import client.Client;
import client.inventory.Pet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataTool;
import server.quest.Quest;
import server.quest.QuestActionType;

/**
 * Teaches the pet leader one of the skills sold by Mr. Wetbottom -- quests 4660 and 4661.
 *
 * Both were uncompletable, and each layer had to be undone to get here. check() demanded
 * NOT_STARTED, which a quest being completed can never be, so it was constantly false and
 * Quest.complete() returned before forceComplete. Behind that, run() wrote to Item.flag, which for
 * a pet is never sent -- addItemInfo returns out of the pet branch before it reaches the flag --
 * and it went through ItemConstants.getFlagByInt, which maps petskill values onto *item* flags,
 * then truncated the result to a byte: 128 became -128 and 256 became 0. The word the client
 * actually reads is Pet.petAttribute, which is what PetSpeedAction has been using all along.
 *
 * @author Tyler (Twdtwd)
 */
public class PetSkillAction extends AbstractQuestAction {
    private static final Logger log = LoggerFactory.getLogger(PetSkillAction.class);

    private Pet.PetAttribute attribute;

    public PetSkillAction(Quest quest, Data data) {
        super(QuestActionType.PETSKILL, quest);
        questID = quest.getId();
        processData(data);
    }

    @Override
    public void processData(Data data) {
        // the node handed to us IS the petskill node, so its own value is the bit -- asking for a
        // child by that name only ever returned the 0 default, which is how this stayed hidden
        int petskill = DataTool.getInt(data);
        attribute = Pet.PetAttribute.from(petskill).orElse(null);
        if (attribute == null) {
            log.warn("Quest {} teaches pet skill {}, which is not a known pet attribute", questID, petskill);
        }
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        if (attribute == null) {
            return;
        }

        Client c = chr.getClient();
        Pet pet = chr.getPet(0);   // as in PetSpeedAction, only the pet leader learns it
        if (pet == null) {
            return;
        }

        c.lockClient();
        try {
            pet.addPetAttribute(c.getPlayer(), attribute);
        } finally {
            c.unlockClient();
        }
    }
}
