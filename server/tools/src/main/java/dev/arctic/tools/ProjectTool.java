package dev.arctic.tools;

import java.util.List;

import dev.arctic.polarion.Projects;
import dev.arctic.polarion.Projects.Project;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Project tools. These never accept or return a Polarion token. */
public class ProjectTool {

    private final Projects projects;

    public ProjectTool(Projects projects) {
        this.projects = projects;
    }

    @Tool(name = "list_projects", description = """
            List Polarion projects this user can access.

            Use this when the project id is unknown. Pass the returned `id` to
            create_work_item. Optional Lucene `query` filters the list.""")
    public List<Project> listProjects(
            @ToolParam(required = false) String query,
            @ToolParam(required = false) Integer pageSize,
            @ToolParam(required = false) Integer pageNumber) {
        return projects.listProjects(
                pageSize != null ? pageSize : 100,
                pageNumber != null ? pageNumber : 1,
                query);
    }

    @Tool(name = "get_project", description = "Get one Polarion project by id.")
    public Project getProject(String projectId) {
        return projects.getProject(projectId);
    }
}
