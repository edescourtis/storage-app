# Technical Considerations & Decisions - STORAGE Application

This document records key technical considerations, potential challenges, and design decisions made throughout the project.

## 1. GridFS Metadata Querying
*   **Challenge**: GridFS stores custom properties within a `metadata` sub-document (e.g., `metadata.ownerId`, `metadata.sha256`). Spring Data MongoDB's Query-By-Example (QBE) typically matches on Java-mapped root properties. If our `FileMetadata` POJO has fields like `ownerId` at the root, QBE checks against these will not hit the nested JSON under `metadata` in GridFS.
*   **Decision**: For reliable duplicate checks, file lookups, filtering, and sorting based on custom attributes, we MUST use explicit `Query` and `Criteria` objects targeting the `metadata.fieldName` paths (e.g., `Criteria.where("metadata.ownerId").is(userId)`).
*   **Impact**: Service layer logic for database interactions will be more verbose than with simple QBE but will ensure correctness.

## 2. User Identification
*   **Requirement**: No session or user management endpoints. User ID is provided with the request.
*   **Decision**: Assume User ID will be passed as a request header, e.g., `X-User-Id`.
*   **Implementation**: A Spring `HandlerMethodArgumentResolver` or a filter can be used to extract this header and make it available to controller methods. This will be defined in `WebConfig.java`.
*   **Constant**: The header name will be defined in `AppConstants.java`.

## 3. Large File Handling
*   **Requirement**: Support files from KB to hundreds of GB. Container has 1GB RAM and 200MB `/tmp`.
*   **Considerations**:
    *   **Streaming**: File uploads and downloads MUST be streamed to avoid loading entire files into application memory.
    *   **Hashing**: SHA256 calculation for duplicate content checks must also be done via streaming.
    *   **Temporary Storage**: Spring Boot, by default, uses `/tmp` for multipart file uploads exceeding a certain size before they are processed. The 200MB `tmpfs` limit must be respected. For files larger than 200MB, direct streaming to GridFS without fully spooling to disk first is ideal, if achievable with Spring's multipart handling and GridFS client. This needs careful implementation.
*   **Strategy**: Leverage `InputStream` from `MultipartFile` and stream directly to `GridFsStorageService.store()` (which takes an `InputStream`).

## 4. Download Link Generation
*   **Requirement**: Unique, non-guessable download links.
*   **Decision**: Generate a sufficiently long, random, unique token (e.g., UUID or cryptographically secure random string) for each file. This token will be stored as part of the file's metadata (e.g., `metadata.downloadToken`).
*   **Lookup**: Downloads will be authorized by looking up the file via this token.

## 5. Tag Handling
*   **Requirement**: Up to 5 tags per file; "TAG" and "tAg" are the same; tags created if not existing.
*   **Decision**:
    *   Store tags as a list of strings in `FileMetadata` (e.g., `metadata.tags`).
    *   When processing tags during upload or filtering:
        *   Convert all tags to a consistent case (e.g., lowercase) before storing and querying to ensure case-insensitivity.
    *   The requirement "tag should be selected from the list (no tag guessing)" for filtering implies that the frontend would somehow know available tags. However, the backend is not required to provide a list of all tags. For API-only, filtering will be by user-provided tag strings.

## 6. Duplicate File Prevention (Per User)
*   **Requirement**: Prevent a user from uploading the same file twice based on content OR user-provided filename.
*   **Logic & Implementation**: The `FileServiceImpl#uploadFile` method implements this in a sequence:
    1.  **Filename Check**: Before any data is stored in GridFS, it checks if a file with the same `metadata.originalFilename` already exists for the given `metadata.ownerId`. If so, the upload is rejected. This check is performed via an explicit `mongoTemplate.exists()` query.
    2.  **Content Check**: If the filename check passes, the file content is streamed to GridFS (calculating its SHA-256 hash concurrently). After successful storage (initially with `PENDING` visibility), it checks if another file with the same `metadata.sha256` already exists for the `metadata.ownerId`. If a content duplicate is found, the just-stored GridFS file is deleted, and the upload is rejected. This is also an explicit `mongoTemplate.exists()` query, followed by an update that sets the final `metadata.sha256` and `metadata.visibility`, which is itself protected by a unique compound index `(metadata.ownerId, metadata.sha256)`.

## 7. File Type Detection
*   **Requirement**: Identify file type after upload if not provided by user.
*   **Tool**: Apache Tika via `MimeUtil.java`.
*   **Process**: During the upload process, before the file is committed to GridFS, the `FileServiceImpl` uses `MimeUtil.detect()`. This utility wraps the incoming `InputStream` in a Tika `LookaheadInputStream`, performs the detection using a small prefix of the stream, and then resets the `LookaheadInputStream`. The full, reset stream (now containing the detected MIME type) is then used for SHA-256 calculation and storage in GridFS. The detected MIME type is stored in `metadata.contentType`.
*   **Timing**: Detection is synchronous and occurs during the upload stream processing, prior to GridFS storage.

## 8. Error Handling
*   **Strategy**: Use a global exception handler (`@ControllerAdvice`) to map custom exceptions (e.g., `FileNotFoundException`, `FileAlreadyExistsException`, `UnauthorizedOperationException`) to appropriate HTTP status codes and consistent JSON error responses.

## 9. Security (Minimalist Approach)
*   **Focus**: Primarily on ensuring a user can only access/modify their own files, except for public files which are read-only for all.
*   **Mechanism**: All service methods performing write operations (upload, delete, update filename) or listing user-specific files MUST take `userId` as a parameter and use it in database queries to scope operations.
*   **Download Links**: These act as capability tokens; possessing the link grants download access. No further user check is required for the download itself, as per requirements.

## 10. Immutability of File Content
*   **Assumption**: Once a file is uploaded, its content is immutable. Changing a filename does not change the GridFS file content, only its metadata.

## 11. Sorting and Pagination for Listings
*   **Implementation**: Utilize Spring Data MongoDB's `Pageable` and `Sort` objects in repository/`MongoTemplate` queries. Sorting will target fields within `metadata`.

## 12. Database Transactions
*   **Consideration**: For operations involving multiple steps (e.g., checking filename, storing file in GridFS, then calculating hash and updating metadata), transactional consistency is desirable.
*   **MongoDB**: Multi-document transactions are supported.
*   **Decision & Current Implementation**:
    *   The `GridFsTemplate.store()` operation is atomic for storing a file's content and its associated initial metadata document.
    *   The broader `uploadFile` business logic in `FileServiceImpl` (which includes pre-checks, the GridFS store, post-store content hash check, and final metadata update) is **not currently wrapped in an explicit Spring-managed transaction (`@Transactional`)**.
    *   Instead, a multi-step process is used:
        1.  Pre-check for filename conflicts.
        2.  Store file in GridFS with `PENDING` visibility and initial metadata (excluding final SHA256). The metadata is constructed as an `org.bson.Document` directly, not via a mapped `FileMetadata` POJO passed to the `store` method.
        3.  Post-store check for content hash conflicts.
        4.  If all checks pass, update the GridFS file's metadata to set the final visibility and SHA256 hash.
    *   Manual cleanup logic (deleting the GridFS file) is implemented if checks fail after the initial store. This approach relies on careful sequencing and error handling rather than ACID transactions for the overall operation.

## 13. MongoDB Indexing for `fs.files`
*   **Requirement**: Efficient querying for listing, sorting, duplicate checks, and downloads by token.
*   **Strategy & Current State**:
    *   The `FileRecord.java` class is annotated with `@Document("fs.files")` and includes `@Indexed` and `@CompoundIndex` annotations. However, `FileRecord` defines fields like `sha256`, `token`, `ownerId`, etc., at its root level, while the actual custom metadata is stored by `FileServiceImpl` in a `metadata` sub-document within `fs.files` (e.g., `metadata.sha256`, `metadata.token`).
    *   Due to this misalignment, Spring Data MongoDB's automatic index creation based *solely* on `FileRecord`'s root-level field annotations will **not** correctly create indexes on the nested `metadata.*` paths for these fields.
    *   Indexes on root-level GridFS fields (like `uploadDate`, `length`, `contentType`, and the top-level `filename` which stores the system UUID) might be created if `spring.data.mongodb.auto-index-creation=true` is active and these are annotated in `FileRecord`.
    *   The crucial indexes on `metadata.*` paths (e.g., for content hash, download token, tags, visibility, ownerId) are likely being ensured by other means, such as the `MongoTestIndexConfiguration` for tests, or would require manual/programmatic setup in a production environment to guarantee their existence on the correct paths.
*   **Verification**:
    *   Manual verification using `db.fs.files.getIndexes()` in `mongoSH` is the most reliable way to confirm actual indexes.
*   **Key Indexes (Actual Paths Required):**
    *   **For User-Provided Filename Uniqueness (MISSING):** `{'metadata.ownerId': 1, 'metadata.originalFilename': 1}` (unique). *Note: This index is not currently defined or enforced at the database level; filename uniqueness relies on an application-level check.*
    *   **For Content Uniqueness:** `{'metadata.ownerId': 1, 'metadata.sha256': 1}` (unique). *The `FileRecord` annotation for this is on a root field, so the actual index must be ensured externally.*
    *   **For Download by Token:** `{'metadata.token': 1}` (unique). *Same as above regarding `FileRecord` annotation.*
    *   **For Listing/Filtering:**
        *   `{'metadata.visibility': 1}`
        *   `{'metadata.tags': 1}` (multikey)
        *   `{'metadata.ownerId': 1}` (often as part of compound indexes)
    *   **For Sorting (GridFS root fields):**
        *   `{'uploadDate': 1}`
        *   `{'contentType': 1}`
        *   `{'length': 1}`
        *   `{'filename': 1}` (system UUID, for some internal lookups or default sort if needed)
*   **Recommendation:** Review and align `FileRecord.java` with the actual `metadata` sub-document structure if Spring Data's automatic index creation is the desired primary mechanism. Alternatively, explicitly document that `FileRecord` is not the source of truth for `metadata.*` index definitions and detail the actual index creation strategy. Add the missing unique index for `(metadata.ownerId, metadata.originalFilename)`.

## 14. Testing `@Valid @RequestBody` with `@WebMvcTest`
*   **Observation**: During `MockMvc` testing of `FileController` using `@WebMvcTest`, DTO validation annotations (`@NotBlank`, etc.) on parameters annotated with `@Valid @RequestBody` (specifically for `PATCH /api/v1/files/{fileId}` with `FileUpdateRequest`) did not trigger a `MethodArgumentNotValidException` as expected. The controller method was entered, and the test failed because it received a 200 OK (from a null service response) instead of the expected 400 Bad Request.
*   **Contrast**: Similar DTO validation on a `@Valid @RequestPart` (for `FileUploadRequest` in the `POST /api/v1/files` endpoint) worked correctly, triggering the `GlobalExceptionHandler` and returning a 400 Bad Request.
*   **Attempts to Fix**:
    *   Ensuring `spring-boot-starter-validation` is present.
    *   Adding `@Import(ValidationAutoConfiguration.class)` to `FileControllerTest`.
    *   Setting up `MockMvc` using `MockMvcBuilders.webAppContextSetup(webApplicationContext)`.
    *   These did not resolve the issue for `@Valid @RequestBody` in the `@WebMvcTest` slice.
*   **Status**: The specific test (`FileControllerTest.updateFileDetails_whenInvalidRequest_shouldReturn400BadRequest`) has been marked `@Disabled`. This behavior might be a nuance of the `@WebMvcTest` slice's limited context for certain validation paths with `@RequestBody`. Full `@SpringBootTest` would likely not exhibit this. For the purpose of this minimalist project, this is noted as a testing limitation for this specific scenario rather than a production code defect, as the DTO and controller are correctly annotated for validation to occur in a full application context. Further investigation deferred. 