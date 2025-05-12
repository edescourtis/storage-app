# STORAGE Application

A RESTful Spring Boot application for storing, sharing, and managing files, designed for the Teletronics interview project. This README is tailored for reviewers and interviewers.

---

## Project Overview

**STORAGE** provides a robust API for users to upload, list, update, and download files with:
- Per-user file isolation
- Visibility (PUBLIC/PRIVATE)
- Tagging (up to 5 tags per file, case-insensitive)
- Pagination, sorting, and filtering
- Duplicate prevention (by filename or content, per user)
- Unique, non-guessable download links
- No UI, no user/session management (user ID via header)

**Tech:** Java 21, Spring Boot, MongoDB GridFS, Maven, Docker

---

## How to Run

### Prerequisites
- Java 21
- Maven 3.8+
- Docker & Docker Compose

### 1. Start MongoDB (Replica Set Required)

The app requires MongoDB as a replica set (even single-node) for transactions:

```
docker compose up -d
```
- This uses `init-mongo.sh` to auto-initialize the replica set.
- MongoDB will be available at `localhost:27017`.

### 2. Run the Application

**Locally (dev):**
```bash
mvn spring-boot:run
```

**With Docker Compose:**
```bash
docker compose up -d --build
```
- The app will be available at [http://localhost:8080](http://localhost:8080)

---

## API Documentation

**Base URL:** `/api/v1`
**User Identification:** All endpoints requiring user context expect an `X-User-Id` header (e.g., `X-User-Id: user123`).

### 1. Upload File
- **POST** `/api/v1/files`
- **Headers:** `X-User-Id: <USER_ID>` (required)
- **Form Parts:**
  - `file`: The file to upload (required)
  - `properties`: JSON (Content-Type: application/json), e.g.:
    ```json
    { "filename": "myfile.txt", "visibility": "PRIVATE", "tags": ["tag1"] }
    ```
    - `filename` (string, required): Desired filename (must be unique per user, validated)
    - `visibility` (string, required): `PUBLIC` or `PRIVATE`
    - `tags` (array of strings, optional, max 5): Tags (case-insensitive, created if new)
- **Response:** 201 Created
  ```json
  {
    "id": "<uuid>",
    "filename": "myfile.txt",
    "visibility": "PRIVATE",
    "tags": ["tag1"],
    "uploadDate": "2025-05-12T09:16:01.312+00:00",
    "contentType": "text/plain",
    "size": 46,
    "downloadLink": "/api/v1/files/download/<token>"
  }
  ```
- **Errors:** 400 (validation), 409 (duplicate), 500 (server)
- **Example cURL:**
  ```bash
  curl -X POST -H "X-User-Id: user123" \
    -F "file=@test.txt" \
    -F 'properties={"filename":"test.txt","visibility":"PRIVATE","tags":["tag1"]};type=application/json' \
    http://localhost:8080/api/v1/files
  ```

### 2. List Files
- **GET** `/api/v1/files`
- **Headers:** Optional `X-User-Id` (lists user's files if present, otherwise all PUBLIC files)
- **Query Parameters:**
  - `tag` (string, optional): Filter by tag (case-insensitive)
  - `sortBy` (string, optional, default: `uploadDate`): `filename`, `uploadDate`, `contentType`, `size`, `tag`
  - `sortDir` (string, optional, default: `desc`): `asc` or `desc`
  - `page` (int, optional, default: `0`): Page number (0-indexed)
  - `size` (int, optional, default: `10`): Results per page
- **Response:** 200 OK
  ```json
  {
    "content": [
      {
        "id": "<uuid>",
        "filename": "myfile.txt",
        "visibility": "PRIVATE",
        "tags": ["tag1"],
        "uploadDate": "2025-05-12T09:16:01.312+00:00",
        "contentType": "text/plain",
        "size": 46,
        "downloadLink": "/api/v1/files/download/<token>"
      }
    ],
    "pageable": { ... },
    "totalPages": 1,
    "totalElements": 1,
    ...
  }
  ```
- **Example cURL (list all public files, tag=tag1, sort by filename):**
  ```bash
  curl -X GET "http://localhost:8080/api/v1/files?tag=tag1&sortBy=filename&sortDir=asc&page=0&size=5"
  ```
- **Example cURL (list files for user):**
  ```bash
  curl -X GET -H "X-User-Id: user123" "http://localhost:8080/api/v1/files?page=0&size=5"
  ```

### 3. Download File
- **GET** `/api/v1/files/download/{token}`
- **Path Parameter:**
  - `token` (string, required): Unique download token from upload/list response
- **Response:** 200 OK, file content (with correct Content-Type and Content-Disposition headers)
- **Errors:** 404 if not found
- **Example cURL:**
  ```bash
  curl -X GET http://localhost:8080/api/v1/files/download/<token> -o downloaded_file.txt
  ```

### 4. Update Filename
- **PATCH** `/api/v1/files/{fileId}`
- **Headers:** `X-User-Id: <USER_ID>` (required)
- **Path Parameter:**
  - `fileId` (string, required): File ID from upload/list response
- **Body:**
  ```json
  { "newFilename": "newname.txt" }
  ```
- **Response:** 200 OK, updated file metadata (same as upload response)
- **Errors:** 400 (validation), 404 (not found/not owned), 409 (duplicate)
- **Example cURL:**
  ```bash
  curl -X PATCH -H "X-User-Id: user123" \
    -H "Content-Type: application/json" \
    -d '{"newFilename":"newname.txt"}' \
    http://localhost:8080/api/v1/files/<fileId>
  ```

### 5. Delete File
- **DELETE** `/api/v1/files/{fileId}`
- **Headers:** `X-User-Id: <USER_ID>` (required)
- **Path Parameter:**
  - `fileId` (string, required): File ID from upload/list response
- **Response:** 204 No Content
- **Errors:** 404 if not found or not owned by user
- **Example cURL:**
  ```bash
  curl -X DELETE -H "X-User-Id: user123" http://localhost:8080/api/v1/files/<fileId>
  ```

### Error Response Structure
```json
{
  "timestamp": 1678886400000,
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": [ "filename: Filename must not be blank" ]
}
```

---

## Code Quality & Coverage

- **Formatting:**
  - Run `mvn spotless:apply` to auto-format code
  - CI and `mvn spotless:check` enforce formatting
- **Tests:**
  - Run all tests: `mvn test`
  - Coverage report: `mvn test` then open `target/site/jacoco/index.html`
  - Key tests: REST controllers, services, parallel upload, edge cases (see `src/test/java/...`)

---

## Where to Find What Reviewers Care About

- **API logic:** `src/main/java/com/example/storage_app/controller/FileController.java`
- **Validation:** `src/main/java/com/example/storage_app/util/FilenameValidator.java`, DTOs in `controller/dto/`
- **Service logic:** `src/main/java/com/example/storage_app/service/FileServiceImpl.java`
- **MongoDB/duplicate logic:** `src/main/java/com/example/storage_app/model/FileRecord.java`, `util/FileMetadataBuilder.java`, `util/GridFsHelper.java`
- **Tests:** `src/test/java/com/example/storage_app/`
- **Advanced/parallel upload tests:** See integration tests and `FileStorageIntegrationTests.java`
- **Indexing:** See `MongoIndexEnsurer.java` and notes in `CONSIDERATIONS.md`
- **Error handling:** `controller/advice/GlobalExceptionHandler.java`

---

## Requirements Coverage (Checklist)
- [x] Upload with filename, visibility, tags (up to 5)
- [x] No file size limit (tested up to 2GB+)
- [x] Change filename without re-upload
- [x] List all public files, or all user files
- [x] Filter by tag (case-insensitive, tag created if new)
- [x] Sort by filename, upload date, tag, content type, size
- [x] Pagination
- [x] Only owner can delete
- [x] File type detected after upload
- [x] Unique, non-guessable download link
- [x] Download by link (PRIVATE and PUBLIC)
- [x] Prevent duplicate upload (by filename or content, per user)
- [x] No global uniqueness
- [x] No UI, no user/session endpoints (user ID via header)
- [x] RESTful API only
- [x] MongoDB (with replica set)
- [x] Tests for REST controllers/services, parallel upload, edge cases
- [x] Dockerized, memory/disk limits in compose
- [x] CI-ready, code formatting enforced

---

## Reviewer Notes
- **Parallel upload and edge case tests** are in integration tests.
- **No UI, no user/session endpoints**—user ID is always via header.
- **MongoDB indexes**: Indexes are created automatically at startup by the application (see `MongoIndexEnsurer.java`). No manual setup required. For details, see `CONSIDERATIONS.md`.
- **Memory/disk limits**: See `docker-compose.yml` for resource constraints.
- **API is robust to edge cases and returns clear JSON errors.**

---
