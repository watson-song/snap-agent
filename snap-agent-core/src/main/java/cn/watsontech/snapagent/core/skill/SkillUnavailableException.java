package cn.watsontech.snapagent.core.skill;

/**
 * Thrown when {@link ReActGraphFactory#build} is called with a skill whose
 * {@link SkillAvailability} is not {@link SkillAvailability#AVAILABLE}.
 *
 * <p>The exception message contains the skill's {@code unavailableReason}
 * so callers can surface it to the user.</p>
 *
 * @see SkillMeta#getAvailability()
 * @see SkillMeta#getUnavailableReason()
 */
public class SkillUnavailableException extends RuntimeException {

    private final String skillName;
    private final SkillAvailability availability;
    private final String unavailableReason;

    /**
     * Construct the exception.
     *
     * @param skillName         the name of the unavailable skill
     * @param availability      the availability status (UNAVAILABLE or INVALID)
     * @param unavailableReason the reason the skill is not available
     */
    public SkillUnavailableException(String skillName,
                                      SkillAvailability availability,
                                      String unavailableReason) {
        super("Skill '" + skillName + "' is " + availability
                + (unavailableReason != null && !unavailableReason.isEmpty()
                    ? ": " + unavailableReason : ""));
        this.skillName = skillName;
        this.availability = availability;
        this.unavailableReason = unavailableReason;
    }

    public String getSkillName() {
        return skillName;
    }

    public SkillAvailability getAvailability() {
        return availability;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }
}
