package dev.arctic.tools;

import dev.arctic.polarion.PolarionClient;
import org.springframework.ai.tool.annotation.Tool;

/** Polarion server identity. It never accepts or returns a Polarion token. */
public class PolarionServerTool {

    private final PolarionClient polarion;

    public PolarionServerTool(PolarionClient polarion) {
        this.polarion = polarion;
    }

    @Tool(name = "polarion_server", description = """
            Return the Polarion REST root this session uses, for example \
            https://host/polarion/rest/v1.

            Call this when you need the Polarion origin. Do not invent work-item \
            UI URLs from it; Polarion's portal link is `links.portal` on the \
            work item (create_work_item returns it as portalUrl). This tool \
            does not take a token.""")
    public PolarionServer polarionServer() {
        return new PolarionServer(polarion.restRoot());
    }

    public record PolarionServer(String restRoot) {}
}
