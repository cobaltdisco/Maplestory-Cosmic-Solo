// The servants on the other side carry removeAfter in Mob.wz and mobTime 0 on the map:
// they are meant to appear, vanish, and come back, over and over. The old code spawned
// them once, guarded by a stage2b flag, and an instance map is not covered by the global
// respawn task -- so the room emptied out and stayed empty. Refill it on every entry, and
// let the event loop keep topping it up.
function enter(pi) {
    var eim = pi.getEventInstance();
    if (eim == null) {
        return false;
    }

    var map = pi.getMap(925100202);
    map.killAllMonsters();
    map.restoreMapSpawnPoints();
    map.instanceMapForceRespawn();

    pi.playPortalSound();
    pi.warp(925100202, 0);
    return true;
}
