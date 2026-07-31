package constants.inventory;

/**
 * @author The Spookster (The Real Spookster)
 */
public enum EquipSlot {

    HAT("Cp", -1),
    SPECIAL_HAT("HrCp", -1),
    FACE_ACCESSORY("Af", -2),
    EYE_ACCESSORY("Ay", -3),
    EARRINGS("Ae", -4),
    TOP("Ma", -5),
    OVERALL("MaPn", -5),
    PANTS("Pn", -6),
    SHOES("So", -7),
    GLOVES("GlGw", -8),
    CASH_GLOVES("Gv", -8),
    CAPE("Sr", -9),
    SHIELD("Si", -10),
    WEAPON("Wp", -11),
    WEAPON_2("WpSi", -11),
    LOW_WEAPON("WpSp", -11),
    RING("Ri", -12, -13, -15, -16),
    PENDANT("Pe", -17),
    TAMED_MOB("Tm", -18),
    SADDLE("Sd", -19),
    MEDAL("Me", -49),
    BELT("Be", -50),
    // Badges post-date v83, so the client has no badge slot and files them under the pendant
    // slot instead -- verified: it sends -17 for 1129000. Without this entry the item falls
    // through to PET_EQUIP and the equip is refused with a packet-edit warning.
    BADGE("Ba", -17),
    PET_EQUIP;

    private String name;
    private int[] allowed;

    EquipSlot() {
    }

    EquipSlot(String wz, int... in) {
        name = wz;
        allowed = in;
    }

    public String getName() {
        return name;
    }

    public boolean isAllowed(int slot, boolean cash) {
        if (slot < 0) {
            if (allowed != null) {
                for (Integer allow : allowed) {
                    int condition = cash ? allow - 100 : allow;
                    if (slot == condition) {
                        return true;
                    }
                }
            }
        }
        return cash && slot < 0;
    }

    public static EquipSlot getFromTextSlot(String slot) {
        // getEquipmentSlot() returns null for an item with no server-side wz data; without this
        // guard the NPE escapes the packet handler and the client never gets enableActions().
        if (slot != null && !slot.isEmpty()) {
            for (EquipSlot c : values()) {
                if (c.getName() != null) {
                    if (c.getName().equals(slot)) {
                        return c;
                    }
                }
            }
        }
        return PET_EQUIP;
    }
}
