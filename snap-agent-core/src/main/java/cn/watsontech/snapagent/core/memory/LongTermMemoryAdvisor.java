package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Advisor that injects long-term memory (Layer 6) — stable user preferences
 * and project facts — into the system prompt before the agent node.
 *
 * <p>Order: 150 (runs after {@link MessageChatMemoryAdvisor}(100),
 * before {@code RetrievalAugmentationAdvisor}(200)).</p>
 *
 * <p>beforeNode("agent"): loads {@link UserProfile} from
 * {@link UserProfileStore} and {@link ProjectFact} list from
 * {@link ProjectFactsStore}, appends them to {@code system.prompt}
 * as {@code <user_profile>} and {@code <project_facts>} blocks.</p>
 *
 * <p>afterNode: no-op.</p>
 */
public class LongTermMemoryAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryAdvisor.class);

    private static final String USER_PROFILE_OPEN = "\n\n<user_profile>\n";
    private static final String USER_PROFILE_CLOSE = "\n</user_profile>\n";
    private static final String PROJECT_FACTS_OPEN = "\n\n<project_facts>\n";
    private static final String PROJECT_FACTS_CLOSE = "\n</project_facts>\n";

    private final UserProfileStore userProfileStore;
    private final ProjectFactsStore projectFactsStore;
    private final String userIdKey;
    private final String projectIdKey;

    /**
     * Construct with default state keys: "user.id" and "project.id".
     */
    public LongTermMemoryAdvisor(UserProfileStore userProfileStore,
                                  ProjectFactsStore projectFactsStore) {
        this(userProfileStore, projectFactsStore, "user.id", "project.id");
    }

    /**
     * Construct with custom state keys for user ID and project ID.
     *
     * @param userProfileStore  the user profile store
     * @param projectFactsStore the project facts store
     * @param userIdKey         state key for the user ID
     * @param projectIdKey      state key for the project ID
     */
    public LongTermMemoryAdvisor(UserProfileStore userProfileStore,
                                  ProjectFactsStore projectFactsStore,
                                  String userIdKey, String projectIdKey) {
        if (userProfileStore == null) {
            throw new IllegalArgumentException("userProfileStore cannot be null");
        }
        if (projectFactsStore == null) {
            throw new IllegalArgumentException("projectFactsStore cannot be null");
        }
        this.userProfileStore = userProfileStore;
        this.projectFactsStore = projectFactsStore;
        this.userIdKey = userIdKey;
        this.projectIdKey = projectIdKey;
    }

    @Override
    public int getOrder() {
        return 150;
    }

    @Override
    public String getName() {
        return "long-term-memory";
    }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        if (!"agent".equals(nodeName)) {
            return state;
        }

        String systemPrompt = state.get(StateKeys.SYSTEM_PROMPT);
        if (systemPrompt == null) {
            systemPrompt = "";
        }

        StringBuilder sb = new StringBuilder(systemPrompt);

        // Inject user profile
        String userId = state.get(userIdKey);
        if (userId != null && !userId.isEmpty()) {
            try {
                UserProfile profile = userProfileStore.load(userId);
                if (profile != null) {
                    sb.append(buildUserProfileBlock(profile));
                }
            } catch (RuntimeException e) {
                log.warn("Failed to load user profile for {}: {}", userId, e.getMessage());
            }
        }

        // Inject project facts
        String projectId = state.get(projectIdKey);
        if (projectId != null && !projectId.isEmpty()) {
            try {
                List<ProjectFact> facts = projectFactsStore.load(projectId);
                if (facts != null && !facts.isEmpty()) {
                    sb.append(buildProjectFactsBlock(facts));
                }
            } catch (RuntimeException e) {
                log.warn("Failed to load project facts for {}: {}", projectId, e.getMessage());
            }
        }

        return state.with(StateKeys.SYSTEM_PROMPT, sb.toString());
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        return state;
    }

    private String buildUserProfileBlock(UserProfile profile) {
        StringBuilder sb = new StringBuilder(USER_PROFILE_OPEN);
        if (profile.getLanguage() != null) {
            sb.append("language: ").append(profile.getLanguage()).append("\n");
        }
        if (profile.getOutputStyle() != null) {
            sb.append("output_style: ").append(profile.getOutputStyle()).append("\n");
        }
        if (!profile.getFrequentServices().isEmpty()) {
            sb.append("frequent_services: ").append(String.join(", ", profile.getFrequentServices())).append("\n");
        }
        sb.append(USER_PROFILE_CLOSE);
        return sb.toString();
    }

    private String buildProjectFactsBlock(List<ProjectFact> facts) {
        StringBuilder sb = new StringBuilder(PROJECT_FACTS_OPEN);
        for (ProjectFact fact : facts) {
            sb.append(fact.getKey()).append(": ").append(fact.getValue()).append("\n");
        }
        sb.append(PROJECT_FACTS_CLOSE);
        return sb.toString();
    }
}
