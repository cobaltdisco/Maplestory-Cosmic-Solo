/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
	Food depot on Ariant Residential area
 */

var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            // The progress string used to advance whether or not a jewel was actually handed
            // over: removing an item you do not have is silent, so dropping the jewels and
            // walking back in still marked the spot as done. Check first -- and say something
            // either way, since this NPC used to answer with nothing at all.
            if (!cm.isQuestStarted(3929)) {
                cm.sendOk("There does not seem to be anything special about this place.");
            } else {
                var progress = cm.getQuestProgress(3929);
                var slot = 2;

                var ch = progress[slot];
                if (ch == '3') {
                    cm.sendOk("You have already hidden a #b#t4031580##k here.");
                } else if (ch != '2') {
                    cm.sendOk("This is not the house you were told to hide the jewels in.");
                } else if (!cm.haveItem(4031580, 1)) {
                    cm.sendOk("You do not have a #b#t4031580##k to hide.");
                } else {
                    var nextProgress = progress.substr(0, slot) + '3' + progress.substr(slot + 1);

                    cm.gainItem(4031580, -1);
                    cm.setQuestProgress(3929, nextProgress);
                    cm.sendOk("You quietly hid a #b#t4031580##k in the house.");
                }
            }

            cm.dispose();
        }
    }
}
