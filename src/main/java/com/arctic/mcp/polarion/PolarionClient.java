package com.arctic.mcp.polarion;

import static com.arctic.mcp.polarion.PolarionResources.AssignedWorkItem;
import static com.arctic.mcp.polarion.PolarionResources.CreatedDocument;
import static com.arctic.mcp.polarion.PolarionResources.CreatedDocumentWorkItem;
import static com.arctic.mcp.polarion.PolarionResources.CreatedWorkItem;
import static com.arctic.mcp.polarion.PolarionResources.CreatedWorkItemLink;
import static com.arctic.mcp.polarion.PolarionResources.CreatedWorkItemType;
import static com.arctic.mcp.polarion.PolarionResources.DeletedWorkItemLink;
import static com.arctic.mcp.polarion.PolarionResources.Document;
import static com.arctic.mcp.polarion.PolarionResources.DocumentCreatePreview;
import static com.arctic.mcp.polarion.PolarionResources.DocumentImportPreview;
import static com.arctic.mcp.polarion.PolarionResources.DocumentPart;
import static com.arctic.mcp.polarion.PolarionResources.DocumentPartDeletePreview;
import static com.arctic.mcp.polarion.PolarionResources.DocumentPartDeleteResult;
import static com.arctic.mcp.polarion.PolarionResources.DocumentPartMovePreview;
import static com.arctic.mcp.polarion.PolarionResources.DocumentPartMoveResult;
import static com.arctic.mcp.polarion.PolarionResources.DocumentWorkItemCreatePreview;
import static com.arctic.mcp.polarion.PolarionResources.LinkRole;
import static com.arctic.mcp.polarion.PolarionResources.Project;
import static com.arctic.mcp.polarion.PolarionResources.ProjectUser;
import static com.arctic.mcp.polarion.PolarionResources.RequirementWorkItemPreview;
import static com.arctic.mcp.polarion.PolarionResources.TestStepAppendPreview;
import static com.arctic.mcp.polarion.PolarionResources.TestStepTable;
import static com.arctic.mcp.polarion.PolarionResources.TestStepsAppended;
import static com.arctic.mcp.polarion.PolarionResources.UpdatedWorkItem;
import static com.arctic.mcp.polarion.PolarionResources.WorkItem;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemAssignPreview;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemCreatePreview;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemLink;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemLinkDeletePreview;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemLinkPreview;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemType;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemTypeCreatePreview;
import static com.arctic.mcp.polarion.PolarionResources.WorkItemUpdatePreview;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Speaks Polarion's REST API. Callers pass a {@link PolarionCredentialsSource}; this type never
 * reads one.
 */
public final class PolarionClient {

  private static final Pattern BEARER = Pattern.compile("Bearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
  private static final Pattern HTML = Pattern.compile("<[^>]+>");
  private static final Pattern DOCUMENT_ATTACHMENT = Pattern.compile("attachment:([^\"'\\s>]+)");
  private static final String REJECTED_TOKEN =
      "Polarion rejected the token. Create a personal access token in Polarion and set"
          + " POLARION_TOKEN.";
  private static final String REST_DISABLED = "Polarion REST API is not enabled on this instance.";
  private static final String UNEXPECTED_USER = "Polarion returned an unexpected user response.";
  private static final String MISSING_ID = "Polarion returned a user response without an id.";
  private static final String PROJECT_FIELDS = "id,name,description,active,trackerPrefix";
  private static final String WORK_ITEM_FIELDS = "id,title,type,status";
  private static final String WORK_ITEM_DETAIL_FIELDS = "id,title,type,status,description,assignee";
  private static final String DOCUMENT_FIELDS = "id,moduleName,title,type,status";
  private static final String DOCUMENT_DETAIL_FIELDS =
      "id,moduleName,title,type,status,homePageContent";
  private static final String DOCUMENT_PART_FIELDS = "id,type,level,headingText,content,workItem";
  private static final String USER_FIELDS = "id,name,email,projectRoles";
  private static final int CONTENT_SUMMARY_LEN = 400;
  private static final MediaType WORD_DOCUMENT =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  private static final TypeReference<List<LinkedHashMap<String, Object>>> OPTION_LIST =
      new TypeReference<>() {};
  private static final TypeReference<List<LinkedHashMap<String, Object>>> RULE_LIST =
      new TypeReference<>() {};

  private final PolarionCredentialsSource credentials;
  private final RestClient http;
  private final ProjectAllowlist allowlist;
  private final JsonMapper json = JsonMapper.builder().build();

  public PolarionClient(PolarionCredentialsSource credentials, RestClient http) {
    this(credentials, http, ProjectAllowlist.fromEnvironment());
  }

  public PolarionClient(
      PolarionCredentialsSource credentials, RestClient http, ProjectAllowlist allowlist) {
    this.credentials = credentials;
    this.http = http;
    this.allowlist = allowlist;
  }

  public CurrentUser getCurrentUser() {
    return parseCurrentUser(exchange(HttpMethod.GET, "/user", Map.of(), null, null));
  }

  public List<Project> listProjects(Integer pageSize, Integer pageNumber, String query) {
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[projects]", PROJECT_FIELDS);
    if (query != null && !query.isBlank()) {
      params.put("query", query);
    }
    JsonNode payload =
        tree(exchange(HttpMethod.GET, "/projects", params, null, null), "project list");
    List<Project> projects = new ArrayList<>();
    for (JsonNode item : listData(payload, "project list")) {
      Project project = parseProject(item);
      if (allowlist.allows(project.id())) {
        projects.add(project);
      }
    }
    return List.copyOf(projects);
  }

  public Project getProject(String projectId) {
    allowlist.assertAllowed(projectId);
    String body =
        exchange(
            HttpMethod.GET,
            "/projects/" + segment(projectId),
            Map.of("fields[projects]", PROJECT_FIELDS),
            null,
            "Project not found: " + projectId);
    return parseProject(singleData(tree(body, "project"), "project"));
  }

  public List<WorkItem> listWorkItems(
      String projectId, String query, Integer pageSize, Integer pageNumber) {
    allowlist.assertAllowed(projectId);
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[workitems]", WORK_ITEM_FIELDS);
    if (query != null && !query.isBlank()) {
      params.put("query", query);
    }
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.GET,
                "/projects/" + segment(projectId) + "/workitems",
                params,
                null,
                null),
            "work item list");
    List<WorkItem> items = new ArrayList<>();
    for (JsonNode item : listData(payload, "work item list")) {
      items.add(parseWorkItem(item));
    }
    return List.copyOf(items);
  }

  public WorkItem getWorkItem(String projectId, String workItemId) {
    allowlist.assertAllowed(projectId);
    String body =
        exchange(
            HttpMethod.GET,
            "/projects/" + segment(projectId) + "/workitems/" + segment(workItemId),
            Map.of("fields[workitems]", WORK_ITEM_DETAIL_FIELDS, "include", "assignee"),
            null,
            "Work item not found: " + projectId + "/" + workItemId);
    return parseWorkItem(singleData(tree(body, "work item"), "work item"));
  }

  public Object createWorkItem(
      String projectId, String type, String title, String description, boolean dryRun) {
    allowlist.assertAllowed(projectId);
    Map<String, Object> body = workItemCreateBody(type, title, description, null);
    if (dryRun) {
      return new WorkItemCreatePreview(true, projectId, body);
    }
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.POST,
                "/projects/" + segment(projectId) + "/workitems",
                Map.of(),
                writeJson(body),
                null),
            "create-work-item");
    return parseCreatedWorkItem(payload);
  }

  public Object updateWorkItem(
      String projectId,
      String workItemId,
      String title,
      String description,
      String status,
      String changeTypeTo,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String localId = localId(workItemId);
    Map<String, Object> body =
        workItemUpdateBody(projectId, localId, title, description, status, changeTypeTo != null);
    if (dryRun) {
      return new WorkItemUpdatePreview(true, projectId, localId, body, changeTypeTo);
    }
    Map<String, String> params = new LinkedHashMap<>();
    if (changeTypeTo != null) {
      params.put("changeTypeTo", changeTypeTo);
    }
    exchange(
        HttpMethod.PATCH,
        "/projects/" + segment(projectId) + "/workitems/" + segment(localId),
        params,
        writeJson(body),
        "Work item not found: " + projectId + "/" + localId);
    return new UpdatedWorkItem(projectId + "/" + localId);
  }

  public TestStepTable getTestSteps(String projectId, String workItemId) {
    if (projectId == null || projectId.isBlank() || workItemId == null || workItemId.isBlank()) {
      throw new PolarionException("project_id and work_item_id must be non-empty.");
    }
    allowlist.assertAllowed(projectId);
    String local = localId(workItemId);
    List<TestStepRows.Row> rows = fetchTestStepRows(projectId, local);
    List<String> columns = TestStepRows.columnsOf(rows);
    if (columns.isEmpty()) {
      columns = configuredColumns(projectId);
    }
    if (columns.isEmpty()) {
      columns = siblingColumns(projectId, local);
    }
    List<Map<String, String>> steps = TestStepRows.toSteps(columns, rows);
    return new TestStepTable(
        projectId, projectId + "/" + local, columns, steps, TestStepRows.version(columns, steps));
  }

  public Object appendTestSteps(
      String projectId,
      String workItemId,
      List<Map<String, String>> steps,
      String expectedVersion,
      boolean dryRun) {
    if (projectId == null || projectId.isBlank() || workItemId == null || workItemId.isBlank()) {
      throw new PolarionException("project_id and work_item_id must be non-empty.");
    }
    if (steps == null || steps.isEmpty()) {
      throw new PolarionException("steps must contain at least one row.");
    }
    allowlist.assertAllowed(projectId);
    String local = localId(workItemId);
    TestStepTable current = getTestSteps(projectId, local);
    if (current.columns().isEmpty()) {
      throw new PolarionException(
          "Work item "
              + current.workItemId()
              + " has no Test Steps columns. The testing/test-step-keys enumeration is missing and"
              + " no saved step of this type was found.");
    }
    List<Map<String, String>> aligned = TestStepRows.align(current.columns(), steps);
    boolean alreadyStored = TestStepRows.endsWith(current.steps(), aligned);
    if (!alreadyStored && !current.steps().isEmpty()) {
      if (expectedVersion == null) {
        throw new PolarionException(
            current.workItemId()
                + " already has "
                + current.steps().size()
                + (current.steps().size() == 1 ? " test step" : " test steps")
                + "; pass expected_version from get_test_steps.");
      }
      if (!expectedVersion.equals(current.stepsVersion())) {
        throw new PolarionException(
            "Test steps changed since they were read (now "
                + current.stepsVersion()
                + "). Call get_test_steps again.");
      }
    }
    if (dryRun) {
      return new TestStepAppendPreview(
          true,
          projectId,
          current.workItemId(),
          current.columns(),
          aligned,
          current.steps().size(),
          alreadyStored);
    }
    if (alreadyStored) {
      return stored(projectId, current, current.steps().size(), true, current.stepsVersion());
    }
    exchange(
        HttpMethod.POST,
        testStepsPath(projectId, local),
        Map.of(),
        writeJson(TestStepRows.postBody(current.columns(), aligned)),
        null);
    List<Map<String, String>> combined = new ArrayList<>();
    combined.addAll(current.steps());
    combined.addAll(aligned);
    return stored(
        projectId,
        current,
        combined.size(),
        false,
        TestStepRows.version(current.columns(), combined));
  }

  private static TestStepsAppended stored(
      String projectId,
      TestStepTable current,
      int stepCount,
      boolean alreadyStored,
      String stepsVersion) {
    return new TestStepsAppended(
        projectId,
        current.workItemId(),
        current.columns(),
        stepCount,
        current.steps().size(),
        alreadyStored,
        stepsVersion);
  }

  private List<TestStepRows.Row> fetchTestStepRows(String projectId, String workItemId) {
    try {
      return readTestStepRows(projectId, workItemId);
    } catch (PolarionApiException exception) {
      if (exception.statusCode() == 400 || exception.statusCode() == 404) {
        WorkItem item = getWorkItem(projectId, workItemId);
        throw new PolarionException(noTestSteps(projectId, workItemId, item.type()));
      }
      throw exception;
    }
  }

  private List<String> configuredColumns(String projectId) {
    try {
      JsonNode payload =
          tree(
              exchange(
                  HttpMethod.GET,
                  "/projects/" + segment(projectId) + "/enumerations/testing/test-step-keys/~",
                  Map.of("fields[enumerations]", "@all"),
                  null,
                  null),
              "test step columns");
      return TestStepRows.columnIds(payload);
    } catch (PolarionApiException exception) {
      if (exception.statusCode() == 404) {
        return List.of();
      }
      throw exception;
    }
  }

  private List<String> siblingColumns(String projectId, String workItemId) {
    WorkItem self = getWorkItem(projectId, workItemId);
    if (self.type() == null || self.type().isBlank()) {
      return List.of();
    }
    List<String> found = null;
    for (WorkItem item : listWorkItems(projectId, "type:" + self.type(), 20, 1)) {
      String local = localId(item.id());
      if (local.equals(workItemId)) {
        continue;
      }
      List<TestStepRows.Row> rows;
      try {
        rows = readTestStepRows(projectId, local);
      } catch (PolarionApiException exception) {
        if (exception.statusCode() == 400 || exception.statusCode() == 404) {
          continue;
        }
        throw exception;
      }
      if (rows.isEmpty()) {
        continue;
      }
      List<String> keys = TestStepRows.columnsOf(rows);
      if (found == null) {
        found = keys;
      } else if (!found.equals(keys)) {
        throw new PolarionException(
            "Test step columns for type " + self.type() + " disagree across work items.");
      }
    }
    return found == null ? List.of() : found;
  }

  private List<TestStepRows.Row> readTestStepRows(String projectId, String workItemId) {
    return TestStepRows.parse(
        tree(
            exchange(
                HttpMethod.GET,
                testStepsPath(projectId, workItemId),
                Map.of("fields[teststeps]", "@all"),
                null,
                null),
            "test steps"));
  }

  private static String testStepsPath(String projectId, String workItemId) {
    return "/projects/" + segment(projectId) + "/workitems/" + segment(workItemId) + "/teststeps";
  }

  private static String noTestSteps(String projectId, String workItemId, String type) {
    return "Work item "
        + projectId
        + "/"
        + workItemId
        + " (type "
        + type
        + ") has no Test Steps table. It is not a test case, or the Test Steps field is missing or"
        + " hidden.";
  }

  public List<WorkItemType> listWorkItemTypes(String projectId) {
    return parseWorkItemTypes(workItemTypeEnumeration(projectId));
  }

  public Object createWorkItemType(
      String projectId,
      String typeId,
      String name,
      String description,
      String color,
      String iconUrl,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String id = typeId == null ? "" : typeId.strip();
    String typeName = name == null ? "" : name.strip();
    if (id.isEmpty() || id.chars().anyMatch(Character::isWhitespace)) {
      throw new PolarionException("Work-item type id must be non-empty and contain no spaces.");
    }
    if (typeName.isEmpty()) {
      throw new PolarionException("Work-item type name must be non-empty.");
    }
    JsonNode payload = workItemTypeEnumeration(projectId);
    List<LinkedHashMap<String, Object>> options = enumerationOptions(payload);
    for (Map<String, Object> option : options) {
      if (id.equals(option.get("id"))) {
        if (dryRun) {
          return new WorkItemTypeCreatePreview(true, projectId, true, null);
        }
        return workItemTypeFromOption(option, true);
      }
    }
    Map<String, Object> created =
        newWorkItemTypeOption(options, id, typeName, description, color, iconUrl);
    List<Map<String, Object>> merged = new ArrayList<>(options);
    merged.add(created);
    Map<String, Object> body =
        map(
            "data",
            map(
                "type",
                "enumerations",
                "id",
                projectId + "/~/workitem-type/~",
                "attributes",
                map("options", merged)));
    if (dryRun) {
      return new WorkItemTypeCreatePreview(true, projectId, false, body);
    }
    exchange(
        HttpMethod.PATCH,
        "/projects/" + segment(projectId) + "/enumerations/~/workitem-type/~",
        Map.of(),
        writeJson(body),
        null);
    String desc = description == null ? "" : description.strip();
    return new CreatedWorkItemType(id, typeName, desc.isEmpty() ? null : desc, false);
  }

  public List<LinkRole> listLinkRoles(String projectId) {
    allowlist.assertAllowed(projectId);
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.GET,
                "/projects/" + segment(projectId) + "/enumerations/~/workitem-link-role/~",
                Map.of("fields[enumerations]", "@all"),
                null,
                null),
            "link role enumeration");
    return parseLinkRoles(payload);
  }

  public List<WorkItemLink> listWorkItemLinks(String projectId, String workItemId) {
    return listLinks(projectId, workItemId, false);
  }

  public List<WorkItemLink> listWorkItemBacklinks(String projectId, String workItemId) {
    return listLinks(projectId, workItemId, true);
  }

  public Object createWorkItemLink(
      String projectId,
      String workItemId,
      String targetWorkItemId,
      String role,
      String targetProjectId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String targetProject =
        targetProjectId == null || targetProjectId.isBlank() ? projectId : targetProjectId;
    allowlist.assertAllowed(targetProject);
    Map<String, Object> body =
        map(
            "data",
            List.of(
                map(
                    "type",
                    "linkedworkitems",
                    "attributes",
                    map("role", role),
                    "relationships",
                    map(
                        "workItem",
                        map(
                            "data",
                            map(
                                "type",
                                "workitems",
                                "id",
                                targetProject + "/" + targetWorkItemId))))));
    if (dryRun) {
      return new WorkItemLinkPreview(true, projectId, workItemId, body);
    }
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.POST,
                "/projects/"
                    + segment(projectId)
                    + "/workitems/"
                    + segment(workItemId)
                    + "/linkedworkitems",
                Map.of(),
                writeJson(body),
                "Work item not found: " + projectId + "/" + workItemId),
            "create-link");
    return parseCreatedLink(payload);
  }

  public Object deleteWorkItemLink(
      String projectId,
      String workItemId,
      String targetWorkItemId,
      String role,
      String targetProjectId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String targetProject =
        targetProjectId == null || targetProjectId.isBlank() ? projectId : targetProjectId;
    allowlist.assertAllowed(targetProject);
    String path =
        "/projects/"
            + segment(projectId)
            + "/workitems/"
            + segment(workItemId)
            + "/linkedworkitems/"
            + segment(role)
            + "/"
            + segment(targetProject)
            + "/"
            + segment(targetWorkItemId);
    String linkId =
        projectId + "/" + workItemId + "/" + role + "/" + targetProject + "/" + targetWorkItemId;
    if (dryRun) {
      return new WorkItemLinkDeletePreview(
          true, projectId, workItemId, role, targetProject, targetWorkItemId, path);
    }
    exchange(HttpMethod.DELETE, path, Map.of(), null, "Link not found: " + linkId);
    return new DeletedWorkItemLink(linkId);
  }

  public List<Document> listDocuments(
      String projectId, String spaceId, Integer pageSize, Integer pageNumber) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[documents]", DOCUMENT_FIELDS);
    JsonNode payload =
        tree(
            exchange(HttpMethod.GET, documentsPath(projectId, space), params, null, null),
            "document list");
    String restRoot = credentials.get().restRoot();
    List<Document> documents = new ArrayList<>();
    for (JsonNode item : listData(payload, "document list")) {
      documents.add(parseDocument(item, space, restRoot, projectId, false));
    }
    return List.copyOf(documents);
  }

  public Document getDocument(String projectId, String documentName, String spaceId) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    String body =
        exchange(
            HttpMethod.GET,
            documentsPath(projectId, space) + "/" + segment(documentName),
            Map.of("fields[documents]", DOCUMENT_DETAIL_FIELDS),
            null,
            "Document not found: " + projectId + "/" + space + "/" + documentName);
    return parseDocument(
        singleData(tree(body, "document"), "document"),
        space,
        credentials.get().restRoot(),
        projectId,
        true);
  }

  public Object createDocument(
      String projectId,
      String moduleName,
      String documentType,
      String structureLinkRole,
      String title,
      String homePageContent,
      String spaceId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("moduleName", moduleName);
    attributes.put("type", documentType);
    attributes.put("structureLinkRole", structureLinkRole);
    if (title != null) {
      attributes.put("title", title);
    }
    if (homePageContent != null && !homePageContent.isEmpty()) {
      attributes.put("homePageContent", map("type", "text/html", "value", homePageContent));
    }
    Map<String, Object> body =
        map("data", List.of(map("type", "documents", "attributes", attributes)));
    if (dryRun) {
      return new DocumentCreatePreview(true, projectId, space, body);
    }
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.POST, documentsPath(projectId, space), Map.of(), writeJson(body), null),
            "create-document");
    return parseCreatedDocument(
        payload, credentials.get().restRoot(), projectId, space, moduleName);
  }

  public Object importWordDocument(
      String projectId,
      String filePath,
      String moduleName,
      String documentType,
      String title,
      String spaceId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("documentName", moduleName);
    parameters.put("documentType", documentType);
    parameters.put("title", title);
    String path = documentsPath(projectId, space) + "/actions/importWordDocument";
    Path file = Path.of(filePath);
    String filename = file.getFileName().toString();
    if (dryRun) {
      return new DocumentImportPreview(
          true, projectId, space, path, Map.copyOf(parameters), filename);
    }
    byte[] content = readWordFile(file);
    MultipartBodyBuilder multipart = new MultipartBodyBuilder();
    multipart.part("parameters", writeJson(parameters));
    multipart
        .part(
            "file",
            new ByteArrayResource(content) {
              @Override
              public String getFilename() {
                return filename;
              }
            })
        .contentType(WORD_DOCUMENT);
    PolarionJob job = parseJob(exchangeMultipart(path, multipart.build()), "job");
    Instant deadline = Instant.now().plusSeconds(120);
    while (!job.terminal()) {
      if (Instant.now().isAfter(deadline)) {
        throw new PolarionApiException(504, "Timed out waiting for Polarion job " + job.id());
      }
      job =
          parseJob(
              exchange(
                  HttpMethod.GET,
                  "/jobs/" + segment(job.id()),
                  Map.of("fields[jobs]", "id,name,state,status"),
                  null,
                  null),
              "job");
      if (!job.terminal()) {
        waitForJob();
      }
    }
    if (job.failed()) {
      String detail =
          job.message() == null || job.message().isBlank()
              ? "Job " + job.id() + " failed"
              : job.message();
      throw new PolarionApiException(500, detail);
    }
    return new CreatedDocument(
        projectId + "/" + space + "/" + moduleName,
        documentPortalUrl(credentials.get().restRoot(), projectId, space, moduleName));
  }

  public List<DocumentPart> listDocumentParts(
      String projectId, String documentName, String spaceId, Integer pageSize, Integer pageNumber) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[document_parts]", DOCUMENT_PART_FIELDS);
    params.put("include", "workItem");
    JsonNode payload =
        tree(
            exchange(HttpMethod.GET, partsPath(projectId, space, documentName), params, null, null),
            "document part list");
    List<DocumentPart> parts = new ArrayList<>();
    for (JsonNode item : listData(payload, "document part list")) {
      parts.add(parseDocumentPart(item));
    }
    return List.copyOf(parts);
  }

  public Object moveDocumentPart(
      String projectId,
      String documentName,
      String partId,
      String after,
      String before,
      String parent,
      String spaceId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    Map<String, Object> body = new LinkedHashMap<>();
    if (after != null) {
      body.put("after", after);
    }
    if (before != null) {
      body.put("before", before);
    }
    if (parent != null) {
      body.put("parent", parent);
    }
    String path =
        partsPath(projectId, space, documentName)
            + "/"
            + segment(localId(partId))
            + "/actions/move";
    if (dryRun) {
      return new DocumentPartMovePreview(true, path, body);
    }
    exchange(HttpMethod.POST, path, Map.of(), writeJson(body), null);
    return new DocumentPartMoveResult(partId, true, after, before, parent);
  }

  public Object deleteDocumentParts(
      String projectId, String documentName, List<String> partIds, String spaceId, boolean dryRun) {
    allowlist.assertAllowed(projectId);
    String space = space(spaceId);
    List<Map<String, Object>> data = new ArrayList<>();
    for (String partId : partIds) {
      data.add(map("type", "document_parts", "id", partId));
    }
    Map<String, Object> body = map("data", data);
    String path = partsPath(projectId, space, documentName);
    if (dryRun) {
      return new DocumentPartDeletePreview(true, path, body);
    }
    exchange(HttpMethod.DELETE, path, Map.of(), writeJson(body), null);
    return new DocumentPartDeleteResult(List.copyOf(partIds));
  }

  public Object createDocumentWorkItem(
      String projectId,
      String documentName,
      String type,
      String title,
      String spaceId,
      String description,
      String after,
      String before,
      String parent,
      String previousPart,
      String nextPart,
      List<String> sourcePartIds,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    if (previousPart != null && nextPart != null) {
      throw new PolarionException("Specify only one of previous_part or next_part.");
    }
    String space = space(spaceId);
    String documentId = projectId + "/" + space + "/" + documentName;
    List<String> sources = sourcePartIds == null ? List.of() : List.copyOf(sourcePartIds);
    if (!sources.isEmpty()) {
      rejectHeadingDeletion(
          sourceParts(projectId, documentName, space), sources, previousPart, nextPart);
    }
    Map<String, Object> workItemBody = workItemCreateBody(type, title, description, documentId);
    boolean insertInPlace = previousPart != null || nextPart != null;
    Map<String, Object> previewPart = documentPartBody("", previousPart, nextPart);
    if (dryRun) {
      return new DocumentWorkItemCreatePreview(
          true,
          projectId,
          workItemBody,
          previewPart,
          after,
          before,
          parent,
          previousPart,
          nextPart,
          sources);
    }
    JsonNode createdPayload =
        tree(
            exchange(
                HttpMethod.POST,
                "/projects/" + segment(projectId) + "/workitems",
                Map.of(),
                writeJson(workItemBody),
                null),
            "create-work-item");
    CreatedWorkItem created = parseCreatedWorkItem(createdPayload);
    Map<String, Object> partBody = documentPartBody(created.id(), previousPart, nextPart);
    JsonNode partPayload =
        tree(
            exchange(
                HttpMethod.POST,
                partsPath(projectId, space, documentName),
                Map.of(),
                writeJson(partBody),
                null),
            "create-part");
    String partId = createdPartId(partPayload);
    if (!insertInPlace && (after != null || before != null || parent != null)) {
      moveDocumentPart(projectId, documentName, partId, after, before, parent, space, false);
    }
    List<String> deleted =
        sources.stream().filter(sourcePartId -> !samePart(sourcePartId, partId)).toList();
    if (!deleted.isEmpty()) {
      deleteDocumentParts(projectId, documentName, deleted, space, false);
    }
    return new CreatedDocumentWorkItem(created.id(), partId, created.portalUrl(), deleted);
  }

  private static void rejectHeadingDeletion(
      List<SourcePart> parts, List<String> sourcePartIds, String previousPart, String nextPart) {
    Map<String, SourcePart> byId = new LinkedHashMap<>();
    for (SourcePart part : parts) {
      if (part.id() == null) {
        continue;
      }
      byId.put(part.id(), part);
      byId.putIfAbsent(localId(part.id()), part);
    }
    for (String sourcePartId : sourcePartIds) {
      SourcePart part = byId.get(sourcePartId);
      if (part == null) {
        throw new PolarionException("Document part not found: " + sourcePartId);
      }
      if ("heading".equals(part.partType())) {
        throw new PolarionException("Refusing to delete a heading: " + sourcePartId);
      }
      if (samePart(sourcePartId, previousPart) || samePart(sourcePartId, nextPart)) {
        throw new PolarionException(
            "Refusing to delete the part the work item is inserted beside: " + sourcePartId);
      }
    }
  }

  private static boolean samePart(String left, String right) {
    return left != null
        && right != null
        && (left.equals(right) || localId(left).equals(localId(right)));
  }

  public Object createRequirementWorkItem(
      String projectId,
      String documentName,
      String type,
      String title,
      List<String> partIds,
      String spaceId,
      boolean dryRun) {
    allowlist.assertAllowed(projectId);
    if (partIds == null || partIds.isEmpty()) {
      throw new PolarionException(
          "part_ids must list the LiveDoc parts that make up the requirement.");
    }
    String space = space(spaceId);
    String description =
        requirementDescription(sourceParts(projectId, documentName, space), partIds);
    String documentId = projectId + "/" + space + "/" + documentName;
    if (dryRun) {
      return new RequirementWorkItemPreview(true, projectId, description);
    }
    Map<String, Object> body = workItemCreateBody(type, title, description, documentId);
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.POST,
                "/projects/" + segment(projectId) + "/workitems",
                Map.of(),
                writeJson(body),
                null),
            "create-work-item");
    CreatedWorkItem created = parseCreatedWorkItem(payload);
    copyDocumentAttachments(projectId, documentName, space, localId(created.id()), description);
    return created;
  }

  private void copyDocumentAttachments(
      String projectId, String documentName, String space, String workItemId, String description) {
    for (String filename : attachmentNames(description)) {
      String contentPath =
          "/projects/"
              + segment(projectId)
              + "/spaces/"
              + segment(space)
              + "/documents/"
              + segment(documentName)
              + "/attachments/"
              + segment(filename)
              + "/content";
      byte[] content =
          exchangeBytes(HttpMethod.GET, contentPath, "Document attachment not found: " + filename);
      uploadWorkItemAttachment(projectId, workItemId, filename, content);
    }
  }

  private static List<String> attachmentNames(String html) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    Matcher matcher = DOCUMENT_ATTACHMENT.matcher(html == null ? "" : html);
    while (matcher.find()) {
      if (!matcher.group(1).isBlank()) {
        names.add(matcher.group(1));
      }
    }
    return List.copyOf(names);
  }

  private void uploadWorkItemAttachment(
      String projectId, String workItemId, String filename, byte[] content) {
    Map<String, Object> resource =
        map(
            "data",
            List.of(map("type", "workitem_attachments", "attributes", map("fileName", filename))));
    MultipartBodyBuilder multipart = new MultipartBodyBuilder();
    multipart.part("resource", writeJson(resource), MediaType.APPLICATION_JSON);
    multipart.part(
        "files",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        },
        MediaType.APPLICATION_OCTET_STREAM);
    exchangeMultipart(
        "/projects/" + segment(projectId) + "/workitems/" + segment(workItemId) + "/attachments",
        multipart.build());
  }

  private List<SourcePart> sourceParts(String projectId, String documentName, String space) {
    List<SourcePart> parts = new ArrayList<>();
    int pageNumber = 1;
    while (true) {
      JsonNode payload =
          tree(
              exchange(
                  HttpMethod.GET,
                  partsPath(projectId, space, documentName),
                  partQuery(100, pageNumber),
                  null,
                  null),
              "document part list");
      List<JsonNode> items = listData(payload, "document part list");
      for (JsonNode item : items) {
        parts.add(sourcePart(item));
      }
      if (items.size() < 100) {
        return List.copyOf(parts);
      }
      pageNumber++;
    }
  }

  private static Map<String, String> partQuery(int pageSize, int pageNumber) {
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[document_parts]", DOCUMENT_PART_FIELDS);
    params.put("include", "workItem");
    return params;
  }

  private static SourcePart sourcePart(JsonNode item) {
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    Integer level = null;
    JsonNode levelNode = attributes.get("level");
    if (levelNode != null && levelNode.isInt()) {
      level = levelNode.asInt();
    }
    return new SourcePart(
        textOrNull(item.get("id")),
        textValue(attributes.get("type")),
        level,
        textValue(attributes.get("headingText")),
        textValue(attributes.get("content")));
  }

  private static String requirementDescription(List<SourcePart> parts, List<String> partIds) {
    Map<String, SourcePart> byId = new LinkedHashMap<>();
    for (SourcePart part : parts) {
      if (part.id() == null) {
        continue;
      }
      byId.put(part.id(), part);
      byId.putIfAbsent(localId(part.id()), part);
    }
    StringBuilder html = new StringBuilder();
    for (String partId : partIds) {
      SourcePart part = byId.get(partId);
      if (part == null) {
        throw new PolarionException("Document part not found: " + partId);
      }
      if (part.html() != null && !part.html().isBlank()) {
        html.append(part.html());
      } else if (part.headingText() != null) {
        int level = part.level() == null ? 1 : Math.min(6, Math.max(1, part.level()));
        html.append("<h")
            .append(level)
            .append('>')
            .append(escapeHtml(part.headingText()))
            .append("</h")
            .append(level)
            .append('>');
      }
    }
    return html.toString();
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private record SourcePart(
      String id, String partType, Integer level, String headingText, String html) {}

  public List<ProjectUser> listProjectUsers(
      String projectId, String role, Integer pageSize, Integer pageNumber) {
    allowlist.assertAllowed(projectId);
    String wanted = role == null || role.isBlank() ? "project_assignable" : role;
    Map<String, String> params = paging(pageSize, pageNumber);
    params.put("fields[users]", USER_FIELDS);
    params.put("include", "projectRoles");
    JsonNode payload = tree(exchange(HttpMethod.GET, "/users", params, null, null), "user list");
    List<ProjectUser> users = new ArrayList<>();
    for (JsonNode item : listData(payload, "user list")) {
      if (matchesProjectRole(item, projectId, wanted)) {
        users.add(parseProjectUser(item));
      }
    }
    return List.copyOf(users);
  }

  public Object assignWorkItem(
      String projectId, String workItemId, List<String> userIds, boolean dryRun) {
    allowlist.assertAllowed(projectId);
    List<Map<String, Object>> assignees = new ArrayList<>();
    for (String userId : userIds) {
      assignees.add(map("type", "users", "id", userId));
    }
    Map<String, Object> body =
        map(
            "data",
            map(
                "type",
                "workitems",
                "id",
                projectId + "/" + workItemId,
                "relationships",
                map("assignee", map("data", assignees))));
    if (dryRun) {
      return new WorkItemAssignPreview(true, projectId, workItemId, body);
    }
    exchange(
        HttpMethod.PATCH,
        "/projects/" + segment(projectId) + "/workitems/" + segment(workItemId),
        Map.of(),
        writeJson(body),
        "Work item not found: " + projectId + "/" + workItemId);
    return new AssignedWorkItem(projectId + "/" + workItemId);
  }

  private JsonNode workItemTypeEnumeration(String projectId) {
    allowlist.assertAllowed(projectId);
    return tree(
        exchange(
            HttpMethod.GET,
            "/projects/" + segment(projectId) + "/enumerations/~/workitem-type/~",
            Map.of("fields[enumerations]", "@all"),
            null,
            null),
        "work item type enumeration");
  }

  private List<WorkItemLink> listLinks(String projectId, String workItemId, boolean backlinks) {
    allowlist.assertAllowed(projectId);
    String collection = backlinks ? "backlinkedworkitems" : "linkedworkitems";
    String kind = backlinks ? "backlink list" : "link list";
    JsonNode payload =
        tree(
            exchange(
                HttpMethod.GET,
                "/projects/"
                    + segment(projectId)
                    + "/workitems/"
                    + segment(workItemId)
                    + "/"
                    + collection,
                Map.of(
                    "fields[linkedworkitems]",
                    "role",
                    "fields[workitems]",
                    WORK_ITEM_FIELDS,
                    "include",
                    "workItem"),
                null,
                "Work item not found: " + projectId + "/" + workItemId),
            kind);
    Map<String, String> titles = includedTitles(payload);
    List<WorkItemLink> links = new ArrayList<>();
    for (JsonNode item : listData(payload, kind)) {
      links.add(parseWorkItemLink(item, titles));
    }
    return List.copyOf(links);
  }

  private String exchangeMultipart(String path, MultiValueMap<String, HttpEntity<?>> body) {
    PolarionCredentials current = credentials.get();
    String url = url(current.restRoot(), path, Map.of());
    try {
      return http.method(HttpMethod.POST)
          .uri(URI.create(url))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.token())
          .accept(MediaType.APPLICATION_JSON)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .exchange(
              (request, response) ->
                  read(response.getStatusCode(), body(response), current.token(), null));
    } catch (RestClientException exception) {
      throw new PolarionUnavailableException(
          "Could not reach Polarion: "
              + redact(String.valueOf(exception.getMessage()), current.token()));
    }
  }

  private static byte[] readWordFile(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException exception) {
      throw new PolarionException("Word file not found: " + file);
    }
  }

  private PolarionJob parseJob(String body, String label) {
    JsonNode data = tree(body, label).get("data");
    JsonNode item = data != null && data.isArray() ? (data.isEmpty() ? null : data.get(0)) : data;
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected job response.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null || id.isBlank()) {
      throw new PolarionException("Polarion job response had no id.");
    }
    JsonNode attributes = item.get("attributes");
    JsonNode status = attributes == null ? null : attributes.get("status");
    return new PolarionJob(
        id,
        attributes == null ? null : textOrNull(attributes.get("state")),
        status == null || !status.isObject() ? null : textOrNull(status.get("type")),
        status == null || !status.isObject() ? null : textOrNull(status.get("message")));
  }

  private byte[] exchangeBytes(HttpMethod method, String path, String notFoundDetail) {
    PolarionCredentials current = credentials.get();
    String requestUrl = url(current.restRoot(), path, Map.of());
    try {
      return http.method(method)
          .uri(URI.create(requestUrl))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.token())
          .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
          .exchange(
              (request, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                read(
                    response.getStatusCode(),
                    new String(bytes, StandardCharsets.UTF_8),
                    current.token(),
                    notFoundDetail);
                return bytes;
              });
    } catch (RestClientException exception) {
      throw new PolarionUnavailableException(
          "Could not reach Polarion: "
              + redact(String.valueOf(exception.getMessage()), current.token()));
    }
  }

  private String exchange(
      HttpMethod method,
      String path,
      Map<String, String> query,
      String jsonBody,
      String notFoundDetail) {
    PolarionCredentials current = credentials.get();
    String url = url(current.restRoot(), path, query);
    try {
      RestClient.RequestBodySpec spec =
          http.method(method)
              .uri(URI.create(url))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + current.token())
              .accept(MediaType.APPLICATION_JSON);
      if (jsonBody != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
      }
      return spec.exchange(
          (request, response) ->
              read(response.getStatusCode(), body(response), current.token(), notFoundDetail));
    } catch (RestClientException exception) {
      throw new PolarionUnavailableException(
          "Could not reach Polarion: "
              + redact(String.valueOf(exception.getMessage()), current.token()));
    }
  }

  private String read(
      HttpStatusCode status, String responseBody, String token, String notFoundDetail) {
    int code = status.value();
    if (code == 401) {
      throw new PolarionAuthException(REJECTED_TOKEN);
    }
    if (code == 503) {
      throw new PolarionUnavailableException(REST_DISABLED);
    }
    if (code >= 400) {
      String detail =
          code == 404 && notFoundDetail != null
              ? notFoundDetail
              : redact(errorDetail(responseBody, status), token);
      throw new PolarionApiException(code, detail);
    }
    return responseBody == null ? "" : responseBody;
  }

  private static String body(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws IOException {
    byte[] bytes = response.getBody().readAllBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private CurrentUser parseCurrentUser(String body) {
    JsonNode root;
    try {
      root = body == null || body.isBlank() ? null : json.readTree(body);
    } catch (RuntimeException exception) {
      throw new PolarionException(UNEXPECTED_USER);
    }
    JsonNode data = root == null ? null : root.get("data");
    if (data == null || !data.isObject()) {
      throw new PolarionException(UNEXPECTED_USER);
    }
    JsonNode id = data.get("id");
    if (id == null || !id.isString() || id.asString().isEmpty()) {
      throw new PolarionException(MISSING_ID);
    }
    String name = null;
    String email = null;
    JsonNode attributes = data.get("attributes");
    if (attributes != null && attributes.isObject()) {
      name = textOrNull(attributes.get("name"));
      email = textOrNull(attributes.get("email"));
    }
    return new CurrentUser(id.asString(), name, email);
  }

  private JsonNode tree(String body, String kind) {
    if (body == null || body.isBlank()) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
    try {
      return json.readTree(body);
    } catch (RuntimeException exception) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
  }

  private static List<JsonNode> listData(JsonNode payload, String kind) {
    if (payload == null || !payload.isObject()) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
    JsonNode data = payload.get("data");
    if (data == null || !data.isArray()) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
    List<JsonNode> items = new ArrayList<>();
    data.forEach(items::add);
    return items;
  }

  private static JsonNode singleData(JsonNode payload, String kind) {
    if (payload == null || !payload.isObject()) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
    JsonNode data = payload.get("data");
    if (data == null || !data.isObject()) {
      throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
    }
    return data;
  }

  private Project parseProject(JsonNode item) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected project response.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a project without an id.");
    }
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    Boolean active = null;
    JsonNode activeNode = attributes.get("active");
    if (activeNode != null && activeNode.isBoolean()) {
      active = activeNode.asBoolean();
    }
    return new Project(
        id,
        textValue(attributes.get("name")),
        textValue(attributes.get("description")),
        active,
        textValue(attributes.get("trackerPrefix")));
  }

  private WorkItem parseWorkItem(JsonNode item) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected work item response.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a work item without an id.");
    }
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    return new WorkItem(
        id,
        textValue(attributes.get("title")),
        textValue(attributes.get("type")),
        textValue(attributes.get("status")),
        textValue(attributes.get("description")),
        relationshipIds(item, "assignee"));
  }

  private CreatedWorkItem parseCreatedWorkItem(JsonNode payload) {
    JsonNode item =
        createdItem(
            payload,
            "Polarion returned an unexpected create-work-item response.",
            "Polarion create response had no work item id.");
    String portal = null;
    JsonNode links = item.get("links");
    if (links != null && links.isObject()) {
      portal = textOrNull(links.get("portal"));
    }
    return new CreatedWorkItem(textOrNull(item.get("id")), portal);
  }

  private List<WorkItemType> parseWorkItemTypes(JsonNode payload) {
    List<WorkItemType> types = new ArrayList<>();
    for (Map<String, Object> option : enumerationOptions(payload)) {
      CreatedWorkItemType parsed = workItemTypeFromOption(option, false);
      if (parsed != null) {
        types.add(new WorkItemType(parsed.id(), parsed.name(), parsed.description()));
      }
    }
    return List.copyOf(types);
  }

  private List<LinkedHashMap<String, Object>> enumerationOptions(JsonNode payload) {
    JsonNode data = singleData(payload, "work item type enumeration");
    JsonNode attributes = objectOrEmpty(data.get("attributes"));
    JsonNode options = attributes.get("options");
    if (options == null || !options.isArray()) {
      throw new PolarionException("Polarion returned an unexpected work-item type response.");
    }
    return json.convertValue(options, OPTION_LIST);
  }

  private static CreatedWorkItemType workItemTypeFromOption(
      Map<String, Object> option, boolean alreadyExists) {
    Object rawId = option.get("id");
    if (!(rawId instanceof String typeId) || typeId.isEmpty()) {
      return null;
    }
    return new CreatedWorkItemType(
        typeId, text(option.get("name")), text(option.get("description")), alreadyExists);
  }

  private static Map<String, Object> newWorkItemTypeOption(
      List<LinkedHashMap<String, Object>> existing,
      String typeId,
      String name,
      String description,
      String color,
      String iconUrl) {
    Map<String, Object> template = templateOption(existing);
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("id", typeId);
    option.put("name", name);
    String desc = description == null ? "" : description.strip();
    if (!desc.isEmpty()) {
      option.put("description", desc);
    }
    String resolvedColor =
        color != null && !color.isBlank() ? color.strip() : text(template.get("color"));
    if (resolvedColor != null) {
      option.put("color", resolvedColor);
    }
    String resolvedIcon =
        iconUrl != null && !iconUrl.isBlank() ? iconUrl.strip() : text(template.get("iconURL"));
    if (resolvedIcon != null) {
      option.put("iconURL", resolvedIcon);
    }
    return option;
  }

  private static Map<String, Object> templateOption(List<LinkedHashMap<String, Object>> existing) {
    for (String candidate : List.of("softwarerequirement", "systemrequirement", "requirement")) {
      for (Map<String, Object> option : existing) {
        if (candidate.equals(option.get("id"))) {
          return option;
        }
      }
    }
    return existing.isEmpty() ? Map.of() : existing.get(existing.size() - 1);
  }

  private List<LinkRole> parseLinkRoles(JsonNode payload) {
    JsonNode data = singleData(payload, "link role enumeration");
    JsonNode attributes = objectOrEmpty(data.get("attributes"));
    JsonNode options = attributes.get("options");
    if (options == null || !options.isArray()) {
      throw new PolarionException("Polarion returned an unexpected link-role response.");
    }
    List<LinkRole> roles = new ArrayList<>();
    for (JsonNode option : options) {
      if (option == null || !option.isObject()) {
        continue;
      }
      String id = textOrNull(option.get("id"));
      if (id == null) {
        continue;
      }
      List<Map<String, Object>> rules = null;
      JsonNode rawRules = option.get("linkRules");
      if (rawRules != null && rawRules.isArray()) {
        rules = List.copyOf(json.convertValue(rawRules, RULE_LIST));
      }
      roles.add(
          new LinkRole(
              id, textOrNull(option.get("name")), textOrNull(option.get("oppositeName")), rules));
    }
    return List.copyOf(roles);
  }

  private Map<String, String> includedTitles(JsonNode payload) {
    Map<String, String> titles = new LinkedHashMap<>();
    JsonNode included = payload.get("included");
    if (included == null || !included.isArray()) {
      return titles;
    }
    for (JsonNode item : included) {
      if (item == null || !item.isObject() || !"workitems".equals(textOrNull(item.get("type")))) {
        continue;
      }
      String id = textOrNull(item.get("id"));
      if (id != null) {
        titles.put(id, textValue(objectOrEmpty(item.get("attributes")).get("title")));
      }
    }
    return titles;
  }

  private static WorkItemLink parseWorkItemLink(JsonNode item, Map<String, String> titles) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected work item link.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a work item link without an id.");
    }
    String targetId = relationshipId(item, "workItem");
    String title = targetId == null ? null : titles.get(targetId);
    return new WorkItemLink(
        id, textValue(objectOrEmpty(item.get("attributes")).get("role")), targetId, title);
  }

  private static CreatedWorkItemLink parseCreatedLink(JsonNode payload) {
    JsonNode item =
        createdItem(
            payload,
            "Polarion returned an unexpected create-link response.",
            "Polarion create-link response had no id.");
    return new CreatedWorkItemLink(textOrNull(item.get("id")));
  }

  private Document parseDocument(
      JsonNode item, String spaceId, String restRoot, String projectId, boolean summary) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected document response.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a document without an id.");
    }
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    String moduleName = textValue(attributes.get("moduleName"));
    String portal = null;
    JsonNode links = item.get("links");
    if (links != null && links.isObject()) {
      portal = textOrNull(links.get("portal"));
    }
    if (portal == null && moduleName != null) {
      portal = documentPortalUrl(restRoot, projectId, spaceId, moduleName);
    }
    String contentSummary = summary ? contentSummary(attributes.get("homePageContent")) : null;
    return new Document(
        id,
        moduleName,
        textValue(attributes.get("title")),
        textValue(attributes.get("type")),
        textValue(attributes.get("status")),
        spaceId,
        portal,
        contentSummary);
  }

  private static CreatedDocument parseCreatedDocument(
      JsonNode payload, String restRoot, String projectId, String spaceId, String moduleName) {
    JsonNode item =
        createdItem(
            payload,
            "Polarion returned an unexpected create-document response.",
            "Polarion create-document response had no id.");
    String portal = null;
    JsonNode links = item.get("links");
    if (links != null && links.isObject()) {
      portal = textOrNull(links.get("portal"));
    }
    if (portal == null) {
      portal = documentPortalUrl(restRoot, projectId, spaceId, moduleName);
    }
    return new CreatedDocument(textOrNull(item.get("id")), portal);
  }

  private static DocumentPart parseDocumentPart(JsonNode item) {
    if (item == null || !item.isObject()) {
      throw new PolarionException("Polarion returned an unexpected document part.");
    }
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a document part without an id.");
    }
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    Integer level = null;
    JsonNode levelNode = attributes.get("level");
    if (levelNode != null && levelNode.isInt()) {
      level = levelNode.asInt();
    }
    String raw = textValue(attributes.get("content"));
    String text = raw == null ? null : stripHtml(raw);
    if (text != null && text.isEmpty()) {
      text = null;
    }
    return new DocumentPart(
        id,
        textValue(attributes.get("type")),
        level,
        textValue(attributes.get("headingText")),
        text,
        relationshipId(item, "workItem"));
  }

  private static String createdPartId(JsonNode payload) {
    JsonNode item =
        createdItem(
            payload,
            "Polarion returned an unexpected create-part response.",
            "Polarion create-part response had no id.");
    return textOrNull(item.get("id"));
  }

  private static JsonNode createdItem(JsonNode payload, String unexpected, String missingId) {
    if (payload == null || !payload.isObject()) {
      throw new PolarionException(unexpected);
    }
    JsonNode data = payload.get("data");
    JsonNode item = null;
    if (data != null && data.isArray() && !data.isEmpty()) {
      item = data.get(0);
    } else if (data != null && data.isObject()) {
      item = data;
    }
    if (item == null || !item.isObject() || textOrNull(item.get("id")) == null) {
      throw new PolarionException(item == null || !item.isObject() ? unexpected : missingId);
    }
    return item;
  }

  private static ProjectUser parseProjectUser(JsonNode item) {
    String id = textOrNull(item.get("id"));
    if (id == null) {
      throw new PolarionException("Polarion returned a user without an id.");
    }
    JsonNode attributes = objectOrEmpty(item.get("attributes"));
    return new ProjectUser(
        id, textValue(attributes.get("name")), textValue(attributes.get("email")));
  }

  private static boolean matchesProjectRole(JsonNode item, String projectId, String role) {
    JsonNode relationships = item.get("relationships");
    if (relationships == null || !relationships.isObject()) {
      return false;
    }
    JsonNode projectRoles = relationships.get("projectRoles");
    if (projectRoles == null || !projectRoles.isObject()) {
      return false;
    }
    JsonNode data = projectRoles.get("data");
    if (data == null || !data.isArray()) {
      return false;
    }
    for (JsonNode entry : data) {
      String roleId = entry == null ? null : textOrNull(entry.get("id"));
      if (roleId != null && hasProjectRole(roleId, projectId, role)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasProjectRole(String roleId, String projectId, String role) {
    if (roleId.equals(projectId + "/" + role)) {
      return true;
    }
    String prefix = projectId + "/";
    return roleId.startsWith(prefix) && roleId.substring(roleId.lastIndexOf('/') + 1).equals(role);
  }

  private String contentSummary(JsonNode homePage) {
    String plain = textValue(homePage);
    if (plain == null) {
      return null;
    }
    plain = stripHtml(plain);
    if (plain.isEmpty()) {
      return null;
    }
    return plain.length() <= CONTENT_SUMMARY_LEN ? plain : plain.substring(0, CONTENT_SUMMARY_LEN);
  }

  private static String documentPortalUrl(
      String restRoot, String projectId, String spaceId, String moduleName) {
    URI root = URI.create(restRoot);
    String module = segment(moduleName);
    String project = segment(projectId);
    String space = space(spaceId);
    String wiki =
        "_default".equals(space)
            ? project + "/wiki/" + module
            : project + "/wiki/" + segment(space) + "/" + module;
    return root.getScheme() + "://" + root.getRawAuthority() + "/polarion/#/project/" + wiki;
  }

  private static Map<String, Object> workItemCreateBody(
      String type, String title, String description, String module) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("type", type);
    attributes.put("title", title);
    if (description != null && !description.isEmpty()) {
      attributes.put("description", map("type", "text/html", "value", description));
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("type", "workitems");
    entry.put("attributes", attributes);
    if (module != null) {
      entry.put(
          "relationships", map("module", map("data", map("type", "documents", "id", module))));
    }
    return map("data", List.of(entry));
  }

  private static Map<String, Object> workItemUpdateBody(
      String projectId,
      String workItemId,
      String title,
      String description,
      String status,
      boolean allowEmpty) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (title != null) {
      attributes.put("title", title);
    }
    if (description != null) {
      attributes.put("description", map("type", "text/html", "value", description));
    }
    if (status != null) {
      attributes.put("status", status);
    }
    if (attributes.isEmpty() && !allowEmpty) {
      throw new PolarionException(
          "Provide at least one of title, description, status, or change_type_to.");
    }
    return map(
        "data",
        map("type", "workitems", "id", projectId + "/" + workItemId, "attributes", attributes));
  }

  private static Map<String, Object> documentPartBody(
      String workItemId, String previousPart, String nextPart) {
    Map<String, Object> relationships = new LinkedHashMap<>();
    if (workItemId != null && !workItemId.isEmpty()) {
      relationships.put("workItem", map("data", map("type", "workitems", "id", workItemId)));
    }
    if (previousPart != null) {
      relationships.put(
          "previousPart", map("data", map("type", "document_parts", "id", previousPart)));
    }
    if (nextPart != null) {
      relationships.put("nextPart", map("data", map("type", "document_parts", "id", nextPart)));
    }
    return map(
        "data",
        List.of(
            map(
                "type",
                "document_parts",
                "attributes",
                map("type", "workitem"),
                "relationships",
                relationships)));
  }

  private String errorDetail(String responseBody, HttpStatusCode status) {
    try {
      JsonNode root = json.readTree(responseBody);
      JsonNode errors = root.get("errors");
      if (errors != null && errors.isArray() && !errors.isEmpty()) {
        JsonNode first = errors.get(0);
        String detail = textOrNull(first.get("detail"));
        if (detail == null) {
          detail = textOrNull(first.get("title"));
        }
        if (detail != null) {
          return detail;
        }
      }
    } catch (RuntimeException ignored) {
      String text = responseBody == null ? "" : responseBody.trim();
      if (!text.isEmpty()) {
        return text;
      }
    }
    return status.toString();
  }

  private String writeJson(Object value) {
    return json.writeValueAsString(value);
  }

  private static Map<String, String> paging(Integer pageSize, Integer pageNumber) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("page[size]", Integer.toString(pageSize == null ? 100 : pageSize));
    params.put("page[number]", Integer.toString(pageNumber == null ? 1 : pageNumber));
    return params;
  }

  private static String url(String restRoot, String path, Map<String, String> query) {
    StringBuilder builder = new StringBuilder(restRoot).append(path);
    if (!query.isEmpty()) {
      builder.append('?');
      boolean first = true;
      for (Map.Entry<String, String> entry : query.entrySet()) {
        if (!first) {
          builder.append('&');
        }
        first = false;
        builder
            .append(encodeQuery(entry.getKey()))
            .append('=')
            .append(encodeQuery(entry.getValue()));
      }
    }
    return builder.toString();
  }

  private static String encodeQuery(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String documentsPath(String projectId, String spaceId) {
    return "/projects/" + segment(projectId) + "/spaces/" + segment(spaceId) + "/documents";
  }

  private static String partsPath(String projectId, String spaceId, String documentName) {
    return documentsPath(projectId, spaceId) + "/" + segment(documentName) + "/parts";
  }

  private static String space(String spaceId) {
    return spaceId == null || spaceId.isBlank() ? "_default" : spaceId;
  }

  private static String segment(String value) {
    return UriUtilsPath.encode(value);
  }

  private static String localId(String id) {
    int slash = id.lastIndexOf('/');
    return slash < 0 ? id : id.substring(slash + 1);
  }

  private static String relationshipId(JsonNode item, String name) {
    JsonNode relationships = item.get("relationships");
    if (relationships == null || !relationships.isObject()) {
      return null;
    }
    JsonNode rel = relationships.get(name);
    if (rel == null || !rel.isObject()) {
      return null;
    }
    JsonNode data = rel.get("data");
    if (data == null || !data.isObject()) {
      return null;
    }
    return textOrNull(data.get("id"));
  }

  private static List<String> relationshipIds(JsonNode item, String name) {
    JsonNode relationships = item.get("relationships");
    if (relationships == null || !relationships.isObject()) {
      return List.of();
    }
    JsonNode rel = relationships.get(name);
    if (rel == null || !rel.isObject()) {
      return List.of();
    }
    JsonNode data = rel.get("data");
    if (data == null || !data.isArray()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode entry : data) {
      String id = entry == null ? null : textOrNull(entry.get("id"));
      if (id != null) {
        ids.add(id);
      }
    }
    return List.copyOf(ids);
  }

  private static JsonNode objectOrEmpty(JsonNode node) {
    return node != null && node.isObject() ? node : JsonNodeMissing.EMPTY;
  }

  private static String textValue(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isString()) {
      return node.asString().isEmpty() ? null : node.asString();
    }
    if (node.isObject()) {
      return textOrNull(node.get("value"));
    }
    return null;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || !node.isString() || node.asString().isEmpty()) {
      return null;
    }
    return node.asString();
  }

  private static String text(Object value) {
    return value instanceof String text && !text.isEmpty() ? text : null;
  }

  private static String stripHtml(String html) {
    return HTML.matcher(html).replaceAll("").strip();
  }

  private static Map<String, Object> map(Object... pairs) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  static String redact(String text, String token) {
    String cleaned = text == null ? "" : BEARER.matcher(text).replaceAll("Bearer [redacted]");
    if (token != null && !token.isEmpty()) {
      cleaned = cleaned.replace(token, "[redacted]");
    }
    return cleaned;
  }

  private static void waitForJob() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new PolarionException("Interrupted while waiting for Polarion job.");
    }
  }

  private record PolarionJob(String id, String state, String statusType, String message) {
    private boolean terminal() {
      return failed() || succeeded();
    }

    private boolean succeeded() {
      if (failed() || running()) {
        return false;
      }
      boolean done = oneOf(jobState(), "FINISHED", "DONE");
      if (status().equals("OK")) {
        return done || statusType != null && (state == null || state.isBlank());
      }
      return done && status().isEmpty();
    }

    private boolean failed() {
      if (oneOf(status(), "FAILED", "CANCELLED", "ABORTED", "ERROR")
          || oneOf(jobState(), "FAILED", "CANCELLED", "ABORTED")) {
        return true;
      }
      return oneOf(jobState(), "FINISHED", "DONE") && !status().isEmpty() && !status().equals("OK");
    }

    private boolean running() {
      return jobState().equals("RUNNING");
    }

    private String status() {
      return statusType == null ? "" : statusType.toUpperCase();
    }

    private String jobState() {
      return state == null ? "" : state.toUpperCase();
    }

    private static boolean oneOf(String value, String... options) {
      for (String option : options) {
        if (value.equals(option)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Encodes one path segment without pulling a second URI builder into every call. */
  private static final class UriUtilsPath {
    private static String encode(String value) {
      return org.springframework.web.util.UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
  }

  /** Stand-in so attribute lookups on a missing object do not throw. */
  private static final class JsonNodeMissing {
    private static final JsonNode EMPTY = JsonMapper.builder().build().createObjectNode();
  }
}
