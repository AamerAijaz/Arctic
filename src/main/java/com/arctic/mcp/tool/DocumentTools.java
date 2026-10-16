package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.Document;
import com.arctic.mcp.polarion.PolarionResources.DocumentPage;
import com.arctic.mcp.polarion.PolarionResources.DocumentPartPage;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Polarion LiveDoc tools. These never accept or return a Polarion token. */
public final class DocumentTools {

    static final String LIST_DOCUMENTS =
            """
            List Polarion documents (LiveDocs) in a project space.

            Returned `portal_url` opens the LiveDoc in Polarion. After import or
            edits, call list_document_parts to see headings and embedded work items.\
            """;

    static final String GET_DOCUMENT =
            """
            Get one Polarion document including a short home-page text summary.

            `portal_url` is the LiveDoc preview link in Polarion.\
            """;

    static final String CREATE_DOCUMENT =
            """
            Create a Polarion LiveDoc in a space.

            Call list_link_roles first to pick a valid `structure_link_role` for
            document hierarchy. Default dry_run=true only returns the POST body;
            set dry_run=false to create the document. `portal_url` in the result
            opens the LiveDoc preview.\
            """;

    static final String LIST_PARTS =
            """
            List parts of a LiveDoc (headings, text blocks, work-item embeds).

            Heading and work-item parts include `work_item_id` when Polarion
            linked that part to a work item. Call this after the user edits a
            document in Polarion to refresh structure and content.\
            """;

    static final String MOVE_PART =
            """
            Move a LiveDoc part so it sits after or before another part.

            Use list_document_parts first. `parent` nests a work-item part under a
            heading or another work item. `after`/`before` must be siblings of that parent.\
            """;

    static final String DELETE_PARTS = "Delete LiveDoc parts (typically leftover plain-text paragraphs).";

    static final String CREATE_DOCUMENT_WORK_ITEM =
            """
            Create a work item and embed it in a LiveDoc as a document part.

            `type` is the Polarion work-item type id from list_work_item_types.
            Creating with the document module puts the WI in the Recycle Bin.
            Pass `previous_part` or
            `next_part` (a part id from list_document_parts) to insert it in the
            body at that position. Without those, Polarion appends at the end;
            `parent`/`after`/`before` then move the appended part. Default
            dry_run=true only returns the work-item and part bodies Polarion
            would receive.\
            """;

    private final PolarionClient client;

    public DocumentTools(PolarionClient client) {
        this.client = client;
    }

    @Tool(name = "list_documents", description = LIST_DOCUMENTS)
    public DocumentPage listDocuments(
            String project_id,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) Integer page_size,
            @ToolParam(required = false) Integer page_number) {
        int pageNumber = page_number == null ? 1 : page_number;
        try {
            return new DocumentPage(client.listDocuments(project_id, space_id, page_size, pageNumber), pageNumber);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "get_document", description = GET_DOCUMENT)
    public Document getDocument(
            String project_id, String document_name, @ToolParam(required = false) String space_id) {
        try {
            return client.getDocument(project_id, document_name, space_id);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "create_document", description = CREATE_DOCUMENT)
    public Object createDocument(
            String project_id,
            String module_name,
            String document_type,
            String structure_link_role,
            @ToolParam(required = false) String title,
            @ToolParam(required = false) String home_page_content,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.createDocument(
                    project_id,
                    module_name,
                    document_type,
                    structure_link_role,
                    title,
                    home_page_content,
                    space_id,
                    ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "list_document_parts", description = LIST_PARTS)
    public DocumentPartPage listDocumentParts(
            String project_id,
            String document_name,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) Integer page_size,
            @ToolParam(required = false) Integer page_number) {
        int pageNumber = page_number == null ? 1 : page_number;
        try {
            return new DocumentPartPage(
                    client.listDocumentParts(project_id, document_name, space_id, page_size, pageNumber), pageNumber);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "move_document_part", description = MOVE_PART)
    public Object moveDocumentPart(
            String project_id,
            String document_name,
            String part_id,
            @ToolParam(required = false) String after,
            @ToolParam(required = false) String before,
            @ToolParam(required = false) String parent,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.moveDocumentPart(
                    project_id, document_name, part_id, after, before, parent, space_id, ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "delete_document_parts", description = DELETE_PARTS)
    public Object deleteDocumentParts(
            String project_id,
            String document_name,
            List<String> part_ids,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.deleteDocumentParts(project_id, document_name, part_ids, space_id, ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "create_document_work_item", description = CREATE_DOCUMENT_WORK_ITEM)
    public Object createDocumentWorkItem(
            String project_id,
            String document_name,
            String type,
            String title,
            @ToolParam(required = false) String space_id,
            @ToolParam(required = false) String description,
            @ToolParam(required = false) String after,
            @ToolParam(required = false) String before,
            @ToolParam(required = false) String parent,
            @ToolParam(required = false) String previous_part,
            @ToolParam(required = false) String next_part,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.createDocumentWorkItem(
                    project_id,
                    document_name,
                    type,
                    title,
                    space_id,
                    description,
                    after,
                    before,
                    parent,
                    previous_part,
                    next_part,
                    ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }
}
