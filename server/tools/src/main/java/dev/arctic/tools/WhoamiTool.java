package dev.arctic.tools;

import dev.arctic.polarion.CurrentUser;
import dev.arctic.polarion.PolarionClient;
import org.springframework.ai.tool.annotation.Tool;

/** Identity tool. It never accepts or returns a Polarion token. */
public class WhoamiTool {

    private final PolarionClient polarion;

    public WhoamiTool(PolarionClient polarion) {
        this.polarion = polarion;
    }

    /** Failures surface as {@link dev.arctic.polarion.PolarionException}, whose messages are already redacted. */
    @Tool(name = "whoami", description = """
            Return the Polarion user for the configured personal access token: id, name, and email. \
            Call this before creating or changing Polarion data so you know which user the action will run as. \
            This tool does not take a token.""")
    public CurrentUser whoami() {
        return polarion.getCurrentUser();
    }
}
