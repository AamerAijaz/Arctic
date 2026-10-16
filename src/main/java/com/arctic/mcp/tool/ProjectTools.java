package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.Project;
import com.arctic.mcp.polarion.PolarionResources.ProjectPage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Project discovery. These tools never accept or return a Polarion token. */
public final class ProjectTools {

    static final String LIST_PROJECTS =
            """
            List Polarion projects this user can access.

            Use this when the project id is unknown. Pass the returned `id` to
            create_work_item. Optional Lucene `query` filters the list.\
            """;

    static final String GET_PROJECT = "Get one Polarion project by id.";

    private final PolarionClient client;

    public ProjectTools(PolarionClient client) {
        this.client = client;
    }

    @Tool(name = "list_projects", description = LIST_PROJECTS)
    public ProjectPage listProjects(
            @ToolParam(required = false) String query,
            @ToolParam(required = false) Integer page_size,
            @ToolParam(required = false) Integer page_number) {
        int pageNumber = page_number == null ? 1 : page_number;
        try {
            return new ProjectPage(client.listProjects(page_size, pageNumber, query), pageNumber);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "get_project", description = GET_PROJECT)
    public Project getProject(String project_id) {
        try {
            return client.getProject(project_id);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }
}
