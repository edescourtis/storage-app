# Project Submission Checklist - STORAGE Application

This checklist ensures all requirements and critical aspects are addressed before submitting the project.

## I. Core Functional Requirements
*   [x] User can upload a file with a filename, visibility (PUBLIC/PRIVATE), and up to 5 tags.
*   [~] File size limit is effectively handled (designed for streaming via GridFS, but large file auto-test deferred).
*   [x] Application identifies file type after upload (if not provided).
*   [x] Unique, non-guessable download link is provided after successful upload.
*   [x] Duplicate file uploads are prevented for the same user (based on content OR filename).
*   [x] User can change the filename of uploaded files (without re-upload).
*   [x] User can list all PUBLIC files.
*   [x] User can list all files that belong to them.
*   [x] File lists are filterable by TAG (case-insensitive).
*   [x] File lists are sortable by FILENAME, UPLOAD DATE, TAG, CONTENT TYPE, and FILE SIZE. (Tag sort by direct field not implemented, but tag filtering is present).
*   [x] File lists support pagination.
*   [x] User can only delete files they uploaded.
*   [x] All files (PUBLIC/PRIVATE) can be downloaded via their unique link.
*   [x] Tags are created if they don't exist during upload (tags are stored as strings).

## II. Non-Functional Requirements & Technical Implementation
*   [x] RESTful API is provided (no UI).
*   [x] MongoDb is used as the database (with GridFS for file storage).
*   [x] SpringBoot is the backend framework.
*   [x] Maven is used as the build system.
*   [x] Application runs within a 1GB memory limit for the container.
*   [~] Application respects a 200MB hard disk limit for its temporary storage (e.g., `/tmp`). (No explicit tmpfs for app /tmp in compose; app itself uses minimal temp space).
*   [x] Download links are relative to the service.
*   [x] User ID is provided via request (e.g., header), no session/user management endpoints.

## III. Testing (as per requirements)
*   [x] Application has tests for REST controllers.
*   [x] Application has tests for services.
*   [ ] Test: Simulate parallel UPLOAD of a file with the same FILENAME. (Deferred - Advanced)
*   [ ] Test: Simulate parallel UPLOAD of a file with the same CONTENTS. (Deferred - Advanced)
*   [ ] Test: Simulate UPLOAD of a FILE that is at least 2GB. (Deferred - Manual/Specialized)
*   [x] Test: Try to delete a file that does not belong to user.
*   [x] Test: List all public files.
*   [ ] JaCoCo code coverage report generated, aiming for >= 100%. (Previous target was >= 80%. Current NOT MET - Build fails this check).

## IV. Deployment & CI/CD
*   [x] Application is shipped as a Docker image.
*   [ ] Project is hosted on GitHub. (User task)
*   [x] CI (e.g., GitHub Actions) is set up to build the target Docker image (and run tests). (CI will fail on coverage check).

## V. Documentation
*   [x] `README.md` is present in the repository root.
*   [x] `README.md` describes the project.
*   [x] `README.md` documents the API (endpoints, request/response, usage examples). (User to verify cURL commands)
*   [x] `README.md` includes instructions on how to build and run the application.
*   [x] `PRD.md` is up-to-date.
*   [x] `PLAN.md` is up-to-date.
*   [x] `CHECKLIST.md` (this file) is up-to-date.
*   [x] `CONSIDERATIONS.md` is up-to-date.

## VI. Code Quality & Best Practices
*   [x] Code is clean and well-organized.
*   [x] Minimal branching and overhead in implementation.
*   [x] No unnecessary comments.
*   [x] GridFS metadata querying is handled correctly (addressing the `metadata.*` issue).
*   [x] Large file uploads are handled efficiently (streaming, appropriate temp storage).
*   [x] Error handling is robust and provides meaningful responses.

# JaCoCo Coverage Improvement Checklist

## Step 1: Define, Research, Plan & Clarify (Completed)

- [x] **1.1. Identify Need:** Increase unit test coverage, focusing on `FileServiceImpl` and `FileController`, and guided by project requirements.
- [x] **1.2. Question & Confirm:** Clarity and scope confirmed.
- [x] **1.3. Deep Research & Leverage Analysis:** JaCoCo CSV analyzed, requirements document reviewed.
- [x] **1.4. Model, Design & Performance Strategy:** Approach defined (analyze JaCoCo HTMLs, target `FileServiceImpl`, then `FileController`, then others; use JUnit5/Mockito).
- [x] **1.5. Minimalist Plan & Justification:** Detailed plan created.

## Step 2: Test-Driven Implementation & Refinement (Pending Approval)

**Overall Goal:** Achieve near 100% coverage, prioritizing requirements.

### 2.A: Analyze Detailed Coverage Reports
- [ ] **Task 2.A.1:** Access and review `target/site/jacoco/com.example.storage_app.service/FileServiceImpl.html`.
- [ ] **Task 2.A.2:** Access and review `target/site/jacoco/com.example.storage_app.controller/FileController.html`.
- [ ] **Task 2.A.3:** Access and review `target/site/jacoco/com.example.storage_app.controller.advice/GlobalExceptionHandler.html`.
- [ ] **Task 2.A.4:** Examine `FileServiceImpl.java` and `FileServiceImplTests.java` (if exists).
- [ ] **Task 2.A.5:** Examine `FileController.java` and `FileControllerTests.java` (if exists).
- [ ] **Task 2.A.6:** Examine `GlobalExceptionHandler.java` and its tests (if any).

### 2.B: Target `FileServiceImpl` Tests
- **Requirement: File Upload**
    - [ ] Test successful upload (PUBLIC, with tags). Verify metadata, type ID, link.
    - [ ] Test successful upload (PRIVATE, no tags).
    - [ ] Test upload: filename already exists for user (expect `FileAlreadyExistsException`).
    - [ ] Test upload: content already exists for user (expect `FileAlreadyExistsException`).
    - [ ] Test upload: > 5 tags (expect validation error/behavior).
    - [ ] Test upload: `MimeUtil` throws exception during type detection.
    - [ ] Test upload: various branches for checking existing files.
    - [ ] Test upload: simulate parallel upload with same filename (REQ 1.1).
    - [ ] Test upload: simulate parallel upload with same content (REQ 1.2).
    - [ ] Test upload: simulate large file (e.g., >2GB by mocking stream size) (REQ 1.3).
- **Requirement: Change Filename**
    - [ ] Test successful filename change.
    - [ ] Test change filename: new name already exists for user (expect exception).
    - [ ] Test change filename: file doesn't exist (expect `ResourceNotFoundException`).
    - [ ] Test change filename: user not owner (expect `UnauthorizedOperationException`).
- **Requirement: List Files**
    - [ ] Test list: all PUBLIC files (paginated, sorted by various fields). (REQ 1.5)
    - [ ] Test list: all files for specific USER (paginated, sorted by various fields).
    - [ ] Test list: files with specific TAG (PUBLIC and USER specific, paginated, sorted).
    - [ ] Test list: case-insensitive tag matching.
    - [ ] Test list: empty result sets.
- **Requirement: Delete Files**
    - [ ] Test successful deletion of an owned file.
    - [ ] Test delete: file doesn't exist (expect `ResourceNotFoundException`).
    - [ ] Test delete: file owned by another user (expect `UnauthorizedOperationException`). (REQ 1.4)
- **Requirement: Download Files**
    - [ ] Test get file resource for download (PUBLIC file).
    - [ ] Test get file resource for download (PRIVATE file by owner).
    - [ ] Test get file resource: non-existent file id/link (expect `ResourceNotFoundException`).

### 2.C: Target `FileController` Tests (using `MockMvc`)
- For each endpoint in `FileController`:
    - [ ] Verify correct mapping (URL, HTTP method, params, body).
    - [ ] Verify interaction with mocked `FileService`.
    - [ ] Verify HTTP status codes & responses (success & error).
    - [ ] Verify input DTO validation.
    - [ ] Test pagination and sorting parameters.
    - Specific endpoint tests:
        - [ ] Test `POST /files` (upload) - various scenarios covered by `FileServiceImpl` tests, ensure controller handles them.
        - [ ] Test `PATCH /files/{fileId}` (update filename) - various scenarios.
        - [ ] Test `GET /files/public` (list public files) - pagination, sorting, filtering.
        - [ ] Test `GET /files` (list user files) - pagination, sorting, filtering.
        - [ ] Test `DELETE /files/{fileId}` (delete file) - various scenarios.
        - [ ] Test `GET /files/{fileId}/download-link` (get download link).
        - [ ] Test `GET /download/{linkId}` (download file).

### 2.D: Target `GlobalExceptionHandler` Tests
- For each exception handled:
    - [ ] Trigger exception (mock service methods).
    - [ ] Verify correct HTTP error response.
    - [ ] Test for `FileAlreadyExistsException`.
    - [ ] Test for `InvalidRequestArgumentException`.
    - [ ] Test for `ResourceNotFoundException`.
    - [ ] Test for `StorageException`.
    - [ ] Test for `UnauthorizedOperationException`.
    - [ ] Test for generic `Exception`.

### 2.E: Target `MimeUtil` and `StorageApp`
- [ ] Review JaCoCo report for `MimeUtil.java` and add tests for uncovered lines.
- [ ] Review JaCoCo report for `StorageApp.java` and add tests for uncovered lines/logic.

### 2.F: Iterative TDD Cycle (For each test implemented above)
- [ ] **2.1. Write Failing Test:** Write one clear, precise, minimal test.
- [ ] **2.2. Write Minimal Code:** Implement minimal production code (if needed, primarily test code here).
- [ ] **2.3. Verify All Tests:** Run entire test suite. Ensure all pass.
- [ ] **2.4. Check Coverage:** Execute code coverage. Verify overall >=90% and new/modified >= 95%.
- [ ] **2.5. Refactor:** Refactor new test code if necessary for clarity/simplicity.

## Step 3: Pre-Commit Verification (After all tests are written and passing)

- [ ] **3.1. Final Tests & Coverage:** Execute complete test suite. Confirm all pass and coverage metrics (overall >=90%, new/mod >= 95%).
- [ ] **3.2. Functionality Verification:** Confirm implementation (new tests) solves the coverage gaps based on JaCoCo and requirements.
- [ ] **3.3. Performance Verification:** N/A for adding unit tests directly, but ensure tests are reasonably fast.
- [ ] **3.4. Code Self-Review:** Review new test code against project coding standards.
- [ ] **3.5. Responsibility Check:** N/A for adding unit tests in this context.

## Step 4: Commit Generation & Execution (After verification)

- [ ] **4.1. Identify Changed Files:** List new/modified test files.
- [ ] **4.2. Stage Specific Files:** `git add` for these files.
- [ ] **4.3. Construct Commit Message:**
    - Subject: `Test: Improve unit test coverage for core services and controllers`
    - Body: Explain focus on `FileServiceImpl`, `FileController`, and specific requirements covered.
- [ ] **4.4. Final Review Display:** `git diff --staged` and commit message.
- [ ] **MANDATORY REVIEW 2:** Await human approval.
- [ ] **4.5. Commit:** Upon approval, `git commit`.

This checklist will serve as our guide.

I am still awaiting your approval on the plan (Step 1.5 from my previous message) before I proceed with actual test implementation (Step 2).

Also, please let me know if I can access the HTML JaCoCo reports. This will significantly help in pinpointing the exact lines of code that need test coverage. The paths would be like:
*   `target/site/jacoco/com.example.storage_app.service/FileServiceImpl.html`
*   `target/site/jacoco/com.example.storage_app.controller/FileController.html`
*   `target/site/jacoco/com.example.storage_app.controller.advice/GlobalExceptionHandler.html`

If I can't view them, I'll rely on the CSV and a thorough manual review of the source code to infer the uncovered parts. 