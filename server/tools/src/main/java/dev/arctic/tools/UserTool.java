package dev.arctic.tools;

import java.util.List;

import dev.arctic.polarion.PolarionClient;
import dev.arctic.polarion.ProjectAllowlistSource;
import dev.arctic.polarion.Users;
import org.springframework.ai.tool.annotation.Tool;

/** Project user and assignee tools. These never accept or return a Polarion token. */
public class UserTool {

    private final Users users;

    public UserTool(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        this.users = new Users(polarion, allowlist);
    }

    @Tool(name = "list_project_users", description = """
            List Polarion users for a project, filtered by project role. \
            Call this before assign_work_item to pick valid user ids (for example \
            users with the project_assignable role).""")
    public ProjectUsersResult listProjectUsers(
            String projectId, String role, Integer pageSize, Integer pageNumber) {
        String resolvedRole = role != null ? role : "project_assignable";
        int size = pageSize != null ? pageSize : 100;
        int number = pageNumber != null ? pageNumber : 1;
        return new ProjectUsersResult(
                users.listProjectUsers(projectId, resolvedRole, size, number), number);
    }

    @Mutating
    @Tool(name = "assign_work_item", description = """
            Assign Polarion users to a work item. \
            Call list_project_users first to choose assignable user ids. Default \
            dry_run=true only returns the PATCH body; set dry_run=false to apply. \
            Pass an empty user_ids list to clear assignees.""")
    public Object assignWorkItem(String projectId, String workItemId, List<String> userIds, Boolean dryRun) {
        return users.assignWorkItem(projectId, workItemId, userIds, dryRun);
    }

    /** Tool return shape matching the prototype's {@code users} + {@code page_number} wrapper. */
    public record ProjectUsersResult(List<Users.ProjectUser> users, int page_number) {
    }
}
