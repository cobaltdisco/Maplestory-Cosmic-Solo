package server.life;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSkillFactoryTest {
    private static final int TRIALS = 1000;

    @TempDir
    private Path wzPath;

    @BeforeEach
    void setWzPath() {
        MockitoAnnotations.openMocks(this);
        writeTestFileToTempDir();
        System.setProperty("wz-path", "%s/wz".formatted(wzPath.toString()));
    }

    private void writeTestFileToTempDir() {
        try {
            String testFileContents = readTestFileContents();
            writeTempDirFile(testFileContents);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String readTestFileContents() throws IOException {
        return new String(getClass()
                .getClassLoader()
                .getResourceAsStream("MobSkill-test.img.xml")
                .readAllBytes()
        );
    }

    private void writeTempDirFile(String fileContents) throws IOException {
        Path tempDirDirectory = wzPath.resolve("wz/Skill.wz");
        Files.createDirectories(tempDirDirectory);
        Path tempDirFile = Files.createFile(tempDirDirectory.resolve("MobSkill.img.xml"));
        Files.writeString(tempDirFile, fileContents);
    }

    @Test
    void shouldLoadExistingMobSkill() {
        Optional<MobSkill> possibleSkill = MobSkillFactory.getMobSkill(MobSkillType.ATTACK_UP, 1);

        assertTrue(possibleSkill.isPresent());
        MobSkill mobSkill = possibleSkill.get();
        assertAll("MobSkill",
                () -> assertEquals(115, mobSkill.getX()),
                () -> assertEquals(5, mobSkill.getMpCon()),
                () -> assertEquals(40_000, mobSkill.getCoolTime()),
                () -> assertEquals(30_000, mobSkill.getDuration())
        );
    }

    @Test
    void shouldThrowExceptionOnNonExisting() {
        assertThrows(IllegalArgumentException.class, () -> MobSkillFactory.getMobSkillOrThrow(MobSkillType.DEFENSE_UP, 1));
    }

    /**
     * The wz writes prop as a percentage. Reading it with integer division rounded every value under
     * 100 down to zero, and Math.random() is never below zero, so 86 of the 509 skill levels -- almost
     * all of them debuffs -- could not fire at all. Coolie Zombie's poison is prop 50, which is the
     * value in the test fixture.
     */
    @Test
    void chanceBasedSkillShouldFireAboutAsOftenAsPropSays() {
        MobSkill poison = MobSkillFactory.getMobSkillOrThrow(MobSkillType.POISON, 1);

        int successes = 0;
        for (int i = 0; i < TRIALS; i++) {
            if (poison.makeChanceResult()) {
                successes++;
            }
        }

        // prop 50 out of 1000 tries lands within a hair of 500. The bounds are loose enough that a
        // correct reading never trips them, and tight enough to catch the two ways this has broken:
        // always-off (the integer division) and always-on.
        assertTrue(successes > 300 && successes < 700,
                "expected roughly 500 of %d tries to fire, got %d".formatted(TRIALS, successes));
    }

    @Test
    void skillWithNoPropShouldAlwaysFire() {
        MobSkill attackUp = MobSkillFactory.getMobSkillOrThrow(MobSkillType.ATTACK_UP, 1);

        for (int i = 0; i < TRIALS; i++) {
            assertTrue(attackUp.makeChanceResult(), "a skill with no prop defaults to 100% and must always fire");
        }
    }

}
