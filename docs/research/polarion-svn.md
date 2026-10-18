# How Polarion stores artifacts in Subversion, and what Arctic's REST writes leave behind

Polarion ALM keeps its primary artifact store in an integrated Subversion repository, and it keeps a PostgreSQL database that mirrors that repository for queries. Arctic's MCP server never talks to that repository. It only calls the Polarion REST API as the user who owns a personal access token. This note asks which artifact types live in Subversion, whether those REST writes are recorded as SVN revisions (and with what author and commit message), and which official mechanisms an MCP server or desktop app may use to read or write the repository.

**Date:** 2026-10-16.

**Polarion documentation used:**

- REST API User Guide for Polarion, **Polarion 2606** (document published 2026-06-02). Siemens docs host `docs.sw.siemens.com`.
- Administrator and User Help, **Polarion 21 R2** (help bundle `PL20210414112206988`, copyright line 2021). This is the latest help edition whose repository-topic HTML could be opened.
- User and Administration Help whose shell metadata says **Polarion 20 R1** (`PL20200109160045845`). Used only where the 21 R2 HTML for the same topic did not load. The 21 R2 table of contents still lists those topics.
- SDK pages on `testdrive.polarion.com`. That server's REST changelog begins at **Version 3.26.6**, and its SDK index links to the Polarion 2606 REST API User Guide. The javadoc pages themselves do not print a Polarion release number.
- One older Siemens PDF, copyright **2013**, cited only as historical corroboration.

The Polarion 2606 documentation set lists an Administrator and User Help and a Deployment and Maintenance Guide. Their article bodies did not load as static HTML during this pass, so this note does not treat them as read. Where 20 R1, 21 R2, and the 2606 SDK disagree or where a later page was not opened, that is stated next to the claim.

## How Polarion uses SVN

Polarion's integrated Subversion repository is the primary store for Polarion data. The Polarion 21 R2 administrator topic *Direct SVN access* says: "All primary Polarion data is stored in the Subversion repository." [1] The Polarion 20 R1 topic *Optimize Subversion* says: "Polarion uses a Subversion (SVN) repository as its main data storage." [2] The 21 R2 copy of that Optimize Subversion page returned a login error, so the "main data storage" sentence is cited from the 20 R1 page only. A 2013 Siemens high-availability paper already called Subversion "the persistence storage" and placed the repository at `data/svn/repo` [3]. That PDF is not evidence for Polarion 2606.

The same 21 R2 help says the integrated repository "stores Polarion artifacts like Work Items, Documents and Test Runs." [4] It warns that putting additional files, "especially large files like binaries," into that repository "can adversely affect system performance." [4]

**Work items.** Tracker work items "exist in XML storage," which the help calls standard storage. Work items shown in a LiveDoc are still "stored in a container which maintains their structure." Modules are the pre-2011 container and are deprecated. [5] This page describes XML versus a document container. It does not say the XML lives in PostgreSQL. Combined with [4], work items are among the artifacts in the integrated SVN repository. The help pages opened for this note do not give the repository path of a work-item file.

**LiveDocs / documents.** Every save of a Document "a new revision is created in the history." Each revision gets "a unique numerical ID" and "is stamped with the date and time the revision was written to the repository." [6] Document folders used for access control sit under the project in the `modules` subfolder; the help example path is `Default Repository/Demo Projects/elibrary/modules/Specification/Catalog Specification/`. [7] That access-control page is the Polarion 20 R1 text. The 21 R2 contents still list *Repository access management overview*; its body was not re-read.

**Wiki and pages.** "The Polarion Wiki uses the underlying Subversion repository to store Wiki content." "Each time a Wiki page is changed and saved, a revision is created in the repository." Revision numbers "are for the repository as a whole, not for the specific Wiki page." [8] Page history lists "the number, author, age, and date of every revision," and opening a revision shows the page "at the time the revision was committed to the repository." [9]

**Attachments.** A Wiki-page revision "contains a version of any attachments." Adding, removing, or updating an attachment creates a new repository revision of the page. [8] The 2606 REST guide documents attachment upload and download as their own resources and says Polarion returns the uploaded bytes "as is" and does not scan them. It does not say whether those bytes are SVN files. [10]

**Project configuration.** *Direct SVN access* says all primary data is in the repository, which is broader than the work-item/document/test-run list in [4]. [1] The SVN authz file is how Polarion controls repository access; the 21 R2 sample points `AuthzSVNAccessFile` at `[Polarion_Home]/data/svn/access`. [1] Enumeration options are configuration: unlike work items or LiveDocs, they "are not automatically updated in the database table when there's a configuration change." [11] The pages opened here do not say that a work-item-type enumeration file is itself an SVN object, and they do not describe the commit message used when that configuration changes.

**History, authors, and commit messages.** History of documents, wiki pages, and pages is repository history. [6] [8] [9] Work Item history in the portal is a toolbar action; the 21 R2 topic is one sentence and does not mention Subversion, an author, or a commit message. [12] The only commit message the help tells a user to type is in the Repository Browser: on **Commit File**, "Enter a commit message in the **Comment** field." [13] *Direct SVN access* says writes stay authenticated "in order to ensure the traceability of commits to their authors." [1] The SDK type `IRevisionMetaData` exposes `getAuthor()`, `getDate()`, `getName()`, and `getDescription()`, and it glosses description as "Revision description (also called comment, commit message etc.)." [14] That is the shape of revision metadata. It does not say what string Polarion writes when someone saves a work item in the portal or through REST.

**What the database holds.** PostgreSQL is integrated "to improve performance on complex queries that can bog down the Lucene query engine." [15] Reindex help calls "the embedded historical SQL database" something "which replicates the Subversion repository to facilitate complex queries." While that historical index runs, baselines, historical SQL reports, and some charts are unavailable or wrong. [16] Those two sentences are from the Polarion 20 R1 help. The SDK index on the 2606-era testdrive says the same architecture without using the word Subversion: "a database that mirrors the current state of the Polarion repository, and one that replicates the repository history." Querying that database can be "significantly more efficient than using the Lucene query engine." [17] So the database is a mirror and a history replica for queries. The help does not describe it as the system of record.

**On disk and on the wire.** Backups must include the Subversion repository at `[POLARION DATA]/svn` (Linux) or `[POLARION DATA]\svn` (Windows). "The default storage method is file-based and so does not modify existing files, only adds new ones." [18] The 21 R2 Apache sample sets `SVNPath` to `[Polarion_Home]/data/svn/repo` and serves it with `DAV svn`. The default location is `/repo`. [1] The system user should use `repoSystem=svn://localhost` for read access; the help says the `file://` protocol "is no longer reccomended for production use." [2] The `repo` property in `polarion.properties` is the HTTP(S) repository URL, for example `http://localhost:81/repo/` or `http://polarion.yourdomain.com/repo/`. [19]

## Are MCP/REST mutations SVN commits?

**No Siemens document opened for this note says that a Polarion REST write is an SVN commit, names the revision author, or describes the commit message.**

What the documents do say:

- Primary Polarion data, including work items, documents, and test runs, is stored in the integrated Subversion repository. [1] [4]
- Saving a Document or a Wiki page creates a new repository revision. [6] [8]
- REST resources for those artifacts carry a numeric `revision`. The 2606 guide's examples use values such as `15357` and `15367` on a document and on linked work items. The `revision` query parameter "retrieves the HEAD or a specific REVISION of a resource" on selected endpoints, "generally single-resource endpoints and endpoints listing their child resources," for example `workitems/id` and `workitems/id/comments`. [20]
- REST calls authenticate as a Polarion user via a personal access token. They do not open an SVN session. "Every call is individually authenticated, there are no persistent sessions." The header is `Authorization: Bearer {personal_access_token}`. [21] [22]

A successful REST change to a work item or document is therefore a change to data that Polarion stores in Subversion, and document saves are documented to add a repository revision. That is as far as the manuals go. They do not say that each REST call becomes one SVN revision, that several REST calls in one Arctic tool are batched into one revision, that the revision author string is the PAT user's id, or what text is stored as the commit message. Arctic's client also never sends a commit message. Its write tools default to `dry_run=true`, and a dry run does not call Polarion.

Arctic's read tools (`get_work_item`, `get_document`, `list_document_parts`, and the list endpoints) do not pass `revision` and do not request a revision field. The 2606 guide's document example only returns `revision` when the request asks for that historical state. [20] Nothing in the Arctic client reads Work Item history or Document history.

| Arctic write tool | REST call in `polarion_client` | Does Polarion record an SVN revision? | Author | Commit message / history entry |
| --- | --- | --- | --- | --- |
| `create_work_item` | `POST /projects/{projectId}/workitems` | Not stated. Work items are stored in the integrated SVN repository [4] [5]. The REST guide does not say this POST creates a revision. | The call is authenticated as the PAT user [21]. The docs do not say that user is written as the SVN revision author. | Not stated. The REST guide does not document a message for this POST. |
| `update_work_item` | `PATCH /projects/{projectId}/workitems/{workItemId}`, optional query `changeTypeTo` | Not stated. Same work-item store as above. The 2606 guide documents `changeTypeTo` on this PATCH and says not to change type and run a workflow action in the same call [23]. It does not mention a revision or a commit. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `create_work_item_type` | `GET` then `PATCH /projects/{projectId}/enumerations/~/workitem-type/~` (full options list) | Not stated. This edits enumeration configuration, not a work item. Enumeration options are not auto-copied into the PostgreSQL enum table when configuration changes [11]. The docs do not say the PATCH is an SVN commit. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `create_work_item_link` | `POST /projects/{projectId}/workitems/{workItemId}/linkedworkitems` | Not stated. Links are their own REST resources [24]. A GET of linked work items can be scoped with `revision` [20]. The guide does not say the POST creates an SVN revision. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `delete_work_item_link` | `DELETE /projects/{projectId}/workitems/{workItemId}/linkedworkitems/{role}/{targetProjectId}/{targetWorkItemId}` | Not stated. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `assign_work_item` | `PATCH` the same work-item URL, replacing the assignee list | Not stated. The 2606 PATCH topic says a multi-valued field is replaced by the list you send [25]. It does not mention SVN. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `create_document` | `POST /projects/{projectId}/spaces/{spaceId}/documents` | Not stated for the REST call. Saving a Document in the product creates a repository revision [6]. The REST guide's create-document example does not mention history [26]. | Not stated, beyond PAT authentication [21]. Page history, for Pages rather than this LiveDoc call, does show an author [9]. | Not stated. |
| `import_document` for `.docx` (`import_word_document`) | `POST .../documents/actions/importWordDocument`, then `GET /jobs/{jobId}` until the job finishes | Not stated. The action is asynchronous and the job result points at the new document [27]. How many repository revisions the job writes is not documented. | Not stated, beyond PAT authentication of the POST [21]. | Not stated. |
| `import_document` for `.reqif` / `.reqifz` | No ReqIF REST action. Arctic parses the file locally, then calls `create_document`, `create_document_work_item`, attachment uploads, and `create_work_item_link`. | Not stated. This is several of the calls above. The docs do not say whether Polarion batches them into one revision. | Not stated, beyond PAT authentication of each call [21]. | Not stated. |
| `create_document_work_item` | `POST /projects/{projectId}/workitems` with a document module, then `POST .../documents/{name}/parts`, then optionally `POST .../parts/{partId}/actions/move` | Not stated. Two or three REST writes. Document and work-item storage are the SVN-backed artifacts in [4] [5] [6]. The REST document-parts topic describes the DELETE and POST operations and does not mention revisions. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `delete_document_parts` | `DELETE .../documents/{name}/parts` with a list of part ids | Not stated. The 2606 parts topic says this "Deletes specified Document Parts from a Document." [28] It does not mention SVN. | Not stated, beyond PAT authentication [21]. | Not stated. |
| `convert_heading_to_work_item` | Same as `update_work_item`: `PATCH` the heading's work item with `changeTypeTo` | Not stated. See `update_work_item`. The heading must already have a work item; Arctic does not create one here. | Not stated, beyond PAT authentication [21]. | Not stated. |

`assign_work_items_round_robin` and `convert_headings_to_work_items` repeat `assign_work_item` and `convert_heading_to_work_item`. They add no further history behavior in the Polarion docs.

## Using SVN from an MCP server or a desktop app

**Supported inside Polarion.** The Repository Browser browses the integrated repository, starting at the root and the Head revision, and can open another revision, compare file revisions, download a file or a zip of a directory, add a file or directory, delete, and commit one locally changed file. The commit page takes a **Comment**. [13] [29] A zip or file download "is not a true SVN working copy." [13] The 21 R2 Repository operations topic still says: "You can use almost any Subversion client to access the Polarion repository from outside the portal," given the repository URL and access permission. The default root URL is shown in Repository Home; a project's URL is on the project Home page unless that page was customized. [13]

**Discouraged for clients outside Polarion.** The 21 R2 *Direct SVN access* topic, in the same help edition, says: "Accessing SVN via an external tool is no longer recommended. We recommend that you only access SVN via Polarion." [1] If an administrator still allows a browser or a client such as TortoiseSVN, they must add a second Apache `<Location>` (the sample uses `/repo-access`). Polarion's own `<Location /repo>` stays pointed at the internal password file. [1]

**Auth.** Direct access is HTTP Basic against a password file or LDAP. "Polarion does not currently support HTTP Digest access authentication." [1]

- `passwd` holds "system-generated secrets for internal Polarion processes" and "is used exclusively by Polarion." "Any changes to this file will be overwritten." [1]
- `passwd_credentials` (the shorter example) or `passwd_external` (the LDAP example) holds local passwords for third-party clients. [1]
- Path-based rights come from the SVN access file. The 21 R2 sample uses `AuthzSVNAccessFile` on disk. [1] [7]
- The system user's faster read path is `svn://` via `repoSystem`, and the help tells administrators to bind `svnserve` to `127.0.0.1` and to disable anonymous `svn://` access. [2] That is server-local access for Polarion itself, not a client API.

A personal access token is specified for REST (`Authorization: Bearer`) and for SOAP `SessionWebService.logInWithToken` with mechanism `AccessToken` (since Polarion 3.22.1). [21] [30] **No page read for this note says a PAT is accepted as an SVN, WebDAV, or `svn://` credential.**

**WebDAV.** The official Apache sample enables `DAV svn` and `SVNAutoversioning on`, and the comment in that sample says: "Write requests from WebDAV clients result in automatic commits." [1] That is what the server does if direct HTTP access is configured. It is part of the setup the same page says is no longer recommended.

**REST history.** The supported remote read of historical artifact state is the `revision` query parameter on selected REST endpoints, using the same PAT. [20] [21] The guide does not document a commit-log resource, an author field, or a message field on that parameter. Sparse fieldsets in the generated REST reference include a `fields[revisions]` parameter name; this note did not find a user-guide chapter that lists repository revisions or returns commit messages. [20] [31]

**SDK `IRepositoryService`.** This is an in-process Java API: "Stores arbitrary files in a hierarchical (folders-files) structure," with mandatory transactions. `getConnection` is valid only inside a transaction. `getReadOnlyConnection` can run outside one. `getRevisionMetaData` is on the read-only connection. `getAccessibleURLForLocation` returns a repository URL for a location. `startPulling` and `startForcePulling` are marked "Internal API, do not use." [32] [33] [14] The SDK index presents this as material for developers extending a Polarion installation, not as a remote protocol. [17] An MCP server or desktop app running outside the Polarion JVM cannot call `IRepositoryService` unless it is loaded as a Polarion extension inside the server. Arctic is not that kind of process.

**Risk of committing straight into the Polarion repository.** The 21 R2 admin guide says not to use an external client, and to keep external writes on a separate Apache location from the one Polarion uses. [1] Extra binaries in this repository hurt performance. [4] WebDAV autoversioning turns ordinary writes into commits. [1] Editing `passwd` is futile because Polarion overwrites it. [1] The manuals do not spell out a further list of corrupted-workflow scenarios; the stated constraint is to let Polarion be the writer.

## Implications for Arctic

Arctic already matches the supported remote API: REST, enabled on the server, called with the PAT user's bearer token. [21] [22] That identity is a Polarion user for item permissions and for REST endpoint permissions (`GET` / `POST` / `PATCH` / `DELETE`). [34] It is not, in any manual read here, an SVN user.

Do not add an SVN checkout, `svn commit`, or WebDAV write to the Polarion `/repo` URL from the MCP server. External SVN access is explicitly no longer recommended, and a PAT is not documented as a way to authenticate it. [1] [21]

History of Arctic's writes, if it is needed, has to be read back through Polarion. The documented REST mechanism is `revision` on a later GET. [20] The current client does not send that parameter. Document and page history in the portal show repository revisions and, for pages, an author. [6] [9] The manuals never say the author will be the PAT user or what the message will be.

`import_document` on a Word file is one Polarion job. [27] On a ReqIF file it is a sequence of REST creates. Nothing in the REST guide says those calls share one repository revision.

`create_work_item_type` writes enumeration configuration. That configuration is not kept in sync with the PostgreSQL enumeration table by itself. [11] Whether the PATCH appears in SVN history is not documented.

## Gaps

- The Polarion 2606 Administrator and User Help and Deployment and Maintenance Guide are listed in the 2606 documentation set, but their topic HTML was not retrieved. This note cannot show whether Polarion 2606 still prints "All primary Polarion data is stored in the Subversion repository."
- The 21 R2 *Optimize Subversion* HTML did not load (Siemens login error). The "main data storage" sentence is from the Polarion 20 R1 page of that title. [2]
- No REST, help, or SDK page read here states that a REST POST, PATCH, or DELETE creates one SVN revision, names the author, or sets a commit message. The table above is that absence, not a guess that the commit does not happen.
- Batching is undocumented: one Arctic tool that issues two or three REST calls may or may not be one repository revision.
- The on-disk XML layout of work items inside the repository was not in the pages opened here.
- A Siemens community article and a Siemens product blog say that Polarion 2506 moved the SVN access file into the repository at `/.polarion/access`. Those are not the help, REST, SDK, or release-note pages this note is allowed to treat as evidence. The 2506/2606 release-note page that would confirm it was not opened. Treat that move as unverified.
- `testdrive.polarion.com` javadoc does not label its own Polarion version. The 3.26.6 changelog and the link to the 2606 REST guide are the reasons this note calls that SDK 2606-era. [17] [35]
- Arctic was not pointed at a live Polarion server, so revision numbers on real REST responses were not observed.

## Sources

1. *Direct SVN access*, Polarion 21 R2 Administrator and User Help. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1974922 — HTML topic fetched from https://docs.sw.siemens.com/documentation/external/PL20210414112206988/en-US/polarion_help_sc/polarion/21R2/polarion_help_sc/en-US/xid1465510/xid1974897/xid1974919/xid1974922.html (footer: Polarion 21 R2, 2021).
2. *Optimize Subversion*, User and Administration Help, shell metadata Polarion 20 R1. https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1553075
3. *High Availability of the Polarion Server*, Polarion Software, copyright 2013. https://support.industrysoftware.automation.siemens.com/docs_general/polarion/manuals/Polarion-High-Availability-of-the-Polarion-Server.pdf
4. *Configure Repositories overview*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1551304
5. *Understand Work Item storage types*, User and Administration Help, Polarion 20 R1 text (topic still listed in the 21 R2 contents). https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1489241
6. *Document History*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1500895
7. *Repository access management overview*, User and Administration Help, Polarion 20 R1 text (topic still listed in the 21 R2 contents). https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1553098
8. *Working with Wiki History*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1503859
9. *Page History*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1490060
10. *Attachments*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138068
11. *Enumerations in the Polarion database*, User and Administration Help, Polarion 20 R1. https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1552841
12. *View Work Item History*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1488927
13. *Repository operations*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1579253
14. `IRevisionMetaData`, Polarion Open Java API, testdrive SDK (2606-era; see source 35). https://testdrive.polarion.com/polarion/sdk/doc/javadoc/com/polarion/platform/service/repository/IRevisionMetaData.html
15. *Optimize the PostgreSQL database*, User and Administration Help, Polarion 20 R1. https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1552840
16. *Index and Reindex*, User and Administration Help, Polarion 20 R1. https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1552831
17. Polarion ALM Software Development Kit index, Database section, testdrive. https://testdrive.polarion.com/polarion/sdk/index.html
18. *Back up Polarion data*, Polarion 21 R2. https://docs.sw.siemens.com/en-US/doc/230235217/PL20210414112206988.xid1465510/xid1552820
19. *Configure secure access to the repository*, User and Administration Help, Polarion 20 R1 text (topic still listed in the 21 R2 contents). https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1550504
20. *Revisions*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138064
21. *Authentication with PAT*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/wgy1721636350971
22. *Authentication overview*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138052
23. *Changing the Type*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/sqp1695893121360
24. *Linked Work Items*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138067
25. *Update using PATCH*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138466
26. *Creating Documents*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2213335
27. *Import Word Document*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/kgo4306342782377
28. *Document Parts*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2213345
29. *Repository Browser overview*, User and Administration Help, Polarion 20 R1 text (topic still listed in the 21 R2 contents). https://docs.sw.siemens.com/en-US/doc/230235217/PL20200109160045845.xid1465510/xid1489494
30. `SessionWebService.logInWithToken`, Polarion Web Services client javadoc, testdrive SDK. https://testdrive.polarion.com/polarion/sdk/doc/javadoc/com/polarion/alm/ws/client/session/SessionWebService.html
31. Polarion REST API reference (generated from the OpenAPI description), testdrive SDK. https://testdrive.polarion.com/polarion/sdk/doc/rest/index.html
32. `IRepositoryService`, Polarion Open Java API, testdrive SDK. https://testdrive.polarion.com/polarion/sdk/doc/javadoc/com/polarion/platform/service/repository/IRepositoryService.html
33. `IRepositoryConnection`, Polarion Open Java API, testdrive SDK. https://testdrive.polarion.com/polarion/sdk/doc/javadoc/com/polarion/platform/service/repository/IRepositoryConnection.html
34. *Authorization*, REST API User Guide for Polarion, Polarion 2606. https://docs.sw.siemens.com/en-US/doc/230235217/PL20251205943446859.polarion_help_sc.xid2134849/xid2138053
35. REST API changelog, first header `Version 3.26.6`, testdrive SDK. https://testdrive.polarion.com/polarion/sdk/doc/rest/changes.txt
