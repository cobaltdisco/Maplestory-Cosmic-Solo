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

/*
    NPC Name:       Adonis
    Map(s):         El Nath (211000000)
    Description:    Quest - Lost Spirits.
    Quest ID:       8256

    The daily that follows 8255: join the expedition, defeat Zakum, come back for the reward.
    Every line below is the one already sitting in Quest.wz/Say.img/8256 -- upstream wrote the
    dialogue and the requirements and then never wrote the script to speak them, so clicking
    Adonis produced nothing at all.

    Only the start needs a script. The client carries no endscript for this quest, so handing it
    in takes the ordinary route: canComplete checks the Zakum 3 (8800002) kill and Quest.complete
    pays out the Act, 20000 exp and a fame. That is why end() below only lets go.
*/

var status = -1;

function start(mode, type, selection) {
    if (mode == -1) {
        qm.dispose();
        return;
    }

    if (mode == 0) {
        qm.sendOk("Alright, then. You were brave the other time, well done.");
        qm.dispose();
        return;
    }

    status++;

    if (status == 0) {
        qm.sendAcceptDecline("A new expedition against Zakum is going to be formed. Will you join?");
    } else if (status == 1) {
        qm.forceStartQuest();
        qm.sendOk("You just need to defeat Zakum to complete this mission. Good luck!");
        qm.dispose();
    } else {
        qm.dispose();
    }
}

function end(mode, type, selection) {
    qm.dispose();
}
