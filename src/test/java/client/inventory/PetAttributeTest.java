package client.inventory;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wz value each pet attribute is granted by.
 *
 * These numbers are not ours to choose -- a quest's Act writes the bit itself into `petspeed` or
 * `petskill`, and the client reads the assembled word straight out of the pet's attribute field.
 * They are worth a test because the previous route to them, ItemConstants.getFlagByInt, mapped
 * petskill values onto item flags and then truncated to a byte: 128 arrived as -128 and 256 as 0,
 * so quests 4660 and 4661 could not have granted anything even once their other blocker was lifted.
 */
class PetAttributeTest {

    @Test
    void shouldMapWzValuesToAttributes() {
        assertEquals(Optional.of(Pet.PetAttribute.OWNER_SPEED), Pet.PetAttribute.from(1));
        assertEquals(Optional.of(Pet.PetAttribute.PET_SUMMON), Pet.PetAttribute.from(128));
        assertEquals(Optional.of(Pet.PetAttribute.SELF_SPEAKING), Pet.PetAttribute.from(256));
    }

    @Test
    void shouldKeepValuesOutsideByteRange() {
        // the old failure mode: (byte) 128 is -128 and (byte) 256 is 0
        assertTrue(Pet.PetAttribute.PET_SUMMON.getValue() > Byte.MAX_VALUE);
        assertTrue(Pet.PetAttribute.SELF_SPEAKING.getValue() > Byte.MAX_VALUE);
    }

    @Test
    void shouldReturnEmptyForValueWithNoAttribute() {
        assertTrue(Pet.PetAttribute.from(0).isEmpty());
        assertTrue(Pet.PetAttribute.from(64).isEmpty());
    }
}
