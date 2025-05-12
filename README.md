# STORAGE Application

A minimal RESTful application for storing and retrieving files using Spring Boot and MongoDB GridFS.

## Features

*   Upload files with visibility (PUBLIC/PRIVATE) and tags.
*   Prevent duplicate uploads per user based on filename or content.
*   Automatic file type detection upon upload (using Apache Tika).
*   List public files or files owned by a specific user.
*   Filter file lists by tag (case-insensitive).
*   Sort file lists by filename, upload date, content type, or size.
*   Paginated file lists.
*   Change filename of uploaded files.
*   Delete files (only by owner).
*   Unique, non-guessable download links for files.

## Prerequisites

*   Java 21
*   Maven 3.8+
*   Docker & Docker Compose (for running MongoDB or the application in a container)

## Running Locally

1.  **MongoDB Setup**:
    Ensure you have a MongoDB instance running. You can use Docker:
    ```bash
    docker compose up -d mongo
    ```
    This will start a MongoDB instance accessible on `localhost:27017`.

2.  **Application**:
    Run the Spring Boot application using Maven:
    ```bash
    mvn spring-boot:run
    ```
    The application will be accessible at `http://localhost:8080`.
    The `X-User-Id` header is used for user identification in API requests.

## Building Docker Image

To build the application Docker image:
```bash
docker compose build app
```
(Replace `your-dockerhub-username` if you plan to push it)

Alternatively, to build the JAR first and then use the `Dockerfile`:

### Building the Application JAR

To build the executable JAR file locally (e.g., for manual Docker builds or deployment):
```bash
mvn clean package
```
This will produce `storage-app-0.0.1-SNAPSHOT.jar` in the `target/` directory. The `Dockerfile` uses this JAR.

Then, you can build the Docker image using the Docker CLI:
```bash
docker compose build app
```

### Running with Docker Compose

Once the image is built (either via `spring-boot:build-image` or `docker compose build`), you can run the entire application using Docker Compose:
```bash
docker compose up -d --build
```
Or, for development with live reload (this will build the image using the Dockerfile if not present):
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build app
```

## API Documentation

The STORAGE application provides a RESTful API for managing files.

**Base URL**: `/api/v1`

**User Identification**: All endpoints requiring user context expect an `X-User-Id` header providing the ID of the user performing the operation (e.g., `X-User-Id: user123`).

### Endpoints

#### 1. Upload File

*   **POST** `/api/v1/files`
*   **Description**: Uploads a new file with associated metadata.
*   **Request Type**: `multipart/form-data`
*   **Headers**:
    *   `X-User-Id: <USER_ID_STRING>` (Required)
*   **Form Parts**:
    *   `file`: The binary file content (e.g., `@path/to/your/file.txt`).
    *   `properties`: A JSON object part describing file attributes. Set `Content-Type` for this part to `application/json`.
        *Example JSON for `properties` part*:
        ```json
        {
          "filename": "your-document.pdf",
          "visibility": "PRIVATE", 
          "tags": ["important", "project-alpha"] 
        }
        ```
        *Field Details*:
            *   `filename` (string, required, not blank): The desired filename for storage.
            *   `visibility` (string, required, enum: `PUBLIC`, `PRIVATE`): File visibility.
            *   `tags` (array of strings, optional, max 5 elements): Tags associated with the file. Stored in lowercase.
*   **Success Response (201 Created)**:
    *   `Location` Header: URL to the download endpoint for the created file (e.g., `/api/v1/files/download/unique-token`).
    *   Body (`FileResponse`):
        ```json
        {
          "id": "your-system-generated-file-uuid",
          "filename": "your-document.pdf",
          "visibility": "PRIVATE",
          "tags": ["important", "project-alpha"],
          "uploadDate": "2023-05-15T10:30:00.000+00:00",
          "contentType": "application/pdf",
          "size": 1024768, 
          "downloadLink": "/api/v1/files/download/unique-download-token-string"
        }
        ```
*   **Error Responses**:
    *   `400 Bad Request`: DTO validation errors (e.g., blank filename, >5 tags), or invalid arguments.
    *   `409 Conflict`: File with the same name or content already exists for the user.
    *   `500 Internal Server Error`: Unexpected server errors.
*   **Example cURL**:
    ```bash
    curl -X POST -H "X-User-Id: user123" \
         -F "file=@/path/to/local/file.pdf" \
         -F "properties=_{\"filename\":\"my-api-doc.pdf\", \"visibility\":\"PRIVATE\", \"tags\":[\"api\", \"docs\"]};type=application/json" \
         http://localhost:8080/api/v1/files
    ```

#### 2. List Files

*   **GET** `/api/v1/files`
*   **Description**: Lists files. If `X-User-Id` header is provided, lists files for that user (both PUBLIC and PRIVATE). If the header is omitted, lists all PUBLIC files from all users.
*   **Headers**:
    *   `X-User-Id: <USER_ID_STRING>` (Optional)
*   **Query Parameters**:
    *   `tag` (string, optional): Filter by a specific tag (case-insensitive match).
    *   `sortBy` (string, optional, default: `uploadDate`): Field to sort by. Valid values: `filename`, `uploadDate`, `contentType`, `size`, `tag`.
    *   `sortDir` (string, optional, default: `desc`): Sort direction. Valid values: `asc`, `desc`.
    *   `page` (int, optional, default: `0`): Page number (0-indexed).
    *   `size` (int, optional, default: `10`): Number of items per page.
*   **Success Response (200 OK)**: A paginated list of `FileResponse` objects.
    ```json
    {
      "content": [
        {
          "id": "fileId1", "filename": "file1.txt", 
          "visibility": "PUBLIC", "tags": ["tagA"], 
          "uploadDate": "2023-05-15T12:00:00.000+00:00", 
          "contentType": "text/plain", "size": 1024,
          "downloadLink": "/api/v1/files/download/token1"
        }
      ],
      "pageable": {
        "sort": { "sorted": true, "unsorted": false, "empty": false },
        "offset": 0,
        "pageNumber": 0,
        "pageSize": 10,
        "paged": true,
        "unpaged": false
      },
      "totalPages": 1,
      "totalElements": 1,
      "last": true,
      "size": 10,
      "number": 0,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "numberOfElements": 1,
      "first": true,
      "empty": false
    }
    ```
*   **Error Responses**:
    *   `500 Internal Server Error`.
*   **Example cURL (List public files, tag "work", sort by filename)**:
    ```bash
    curl -X GET "http://localhost:8080/api/v1/files?tag=work&sortBy=filename&sortDir=asc&page=0&size=5"
    ```
*   **Example cURL (List files for user "user123")**:
    ```bash
    curl -X GET -H "X-User-Id: user123" "http://localhost:8080/api/v1/files?page=0&size=5"
    ```

#### 3. Download File

*   **GET** `/api/v1/files/download/{token}`
*   **Description**: Downloads the content of a specific file using its unique download token.
*   **Path Parameters**:
    *   `token` (string, required): The file's unique download token.
*   **Success Response (200 OK)**:
    *   Headers: `Content-Type`, `Content-Disposition: attachment; filename="<FILENAME>"`, `Content-Length`.
    *   Body: The raw binary content of the file.
*   **Error Responses**:
    *   `404 Not Found`: If no file matches the provided token.
*   **Example cURL**:
    ```bash
    curl -X GET http://localhost:8080/api/v1/files/download/your-unique-download-token -o downloaded_file_name
    ```

#### 4. Update File Details (Filename)

*   **PATCH** `/api/v1/files/{fileId}`
*   **Description**: Changes the filename of a file owned by the user.
*   **Headers**:
    *   `X-User-Id: <USER_ID_STRING>` (Required)
*   **Path Parameters**:
    *   `fileId` (string, required): The ID (ObjectId hex string) of the file to update.
*   **Request Body** (`application/json`, `FileUpdateRequest`):
    ```json
    {
      "newFilename": "new-report-name.docx"
    }
    ```
    *   `newFilename` (string, required, not blank)
*   **Success Response (200 OK)**: `FileResponse` with updated details.
*   **Error Responses**:
    *   `400 Bad Request`: Invalid `newFilename` (e.g., blank).
    *   `404 Not Found`: File not found for the user or user not authorized.
    *   `409 Conflict`: The `newFilename` already exists for another file owned by the user.
    *   `500 Internal Server Error`: If the update operation fails for other reasons.
*   **Example cURL**:
    ```bash
    curl -X PATCH -H "X-User-Id: user123" \
         -H "Content-Type: application/json" \
         -d "{\"newFilename\":\"updated_report.pdf\"}" \
         http://localhost:8080/api/v1/files/your_file_id
    ```

#### 5. Delete File

*   **DELETE** `/api/v1/files/{fileId}`
*   **Description**: Deletes a file owned by the user.
*   **Headers**:
    *   `X-User-Id: <USER_ID_STRING>` (Required)
*   **Path Parameters**:
    *   `fileId` (string, required): The ID (ObjectId hex string) of the file to delete.
*   **Success Response (204 No Content)**: Empty body.
*   **Error Responses**:
    *   `404 Not Found`: File not found for the user or user not authorized.
*   **Example cURL**:
    ```bash
    curl -X DELETE -H "X-User-Id: user123" http://localhost:8080/api/v1/files/your_file_id
    ```

### Error Response Structure

When an error occurs (e.g., 4xx or 5xx status codes), the API generally returns a JSON body with the following structure:

```json
{
  "timestamp": 1678886400000, 
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed", 
  "errors": [ "filename: Filename must not be blank" ] 
}
```
Or for non-validation errors (example for 404):
```json
{
  "timestamp": 1678886400000, 
  "status": 404,
  "error": "Not Found",
  "message": "File not found for token: some-token"
}
```

## Development Notes

### Code Formatting

This project uses Spotless with google-java-format to maintain consistent code style. 

To format your code, run:
```bash
mvn spotless:apply
```

To check if the code is formatted correctly (e.g., in a CI pipeline), run:
```bash
mvn spotless:check
```
The build is configured to automatically run `spotless:check` during the `compile` phase. If formatting issues are found, the build will fail.

### MongoDB Indexes
Efficient querying and uniqueness constraints in the application rely on several MongoDB indexes on the `fs.files` collection (used by GridFS).

**Understanding Index Creation:**
*   The `FileRecord.java` entity is mapped to `fs.files` and contains `@Indexed` and `@CompoundIndex` annotations. If `spring.data.mongodb.auto-index-creation` is enabled (default is true), Spring Boot will attempt to create indexes based on these annotations.
*   **Important Note:** `FileServiceImpl` stores custom attributes (like `ownerId`, `sha256`, `token`, `originalFilename`, `tags`, `visibility`) in a `metadata` sub-document within `fs.files`. However, the `FileRecord.java` entity currently defines corresponding fields at its root level. This means Spring's automatic index creation based *solely* on `FileRecord`'s root-level annotations will **not** correctly create indexes on the crucial `metadata.*` paths.
*   Therefore, ensuring the correct `metadata.*` indexes (as detailed in `CONSIDERATIONS.md`, Point 13) may require programmatic setup (like `MongoTestIndexConfiguration` used for tests) or manual creation in a production environment. Indexes on top-level GridFS fields (e.g., `uploadDate`, `length`, `filename` (system UUID)) might be created automatically via `FileRecord` annotations.

**Verification:**
Upon application startup with a MongoDB connection, you might see logs from `org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexCreator`. However, due to the considerations above, manual verification is highly recommended:
```shell
use storage-db # Or your configured database name
db.fs.files.getIndexes()
```
**Refer to `CONSIDERATIONS.md` (Section 13) for a detailed list of required indexes and their correct paths (e.g., `metadata.ownerId`, `metadata.sha256`, `metadata.token`, etc.) to ensure optimal performance and correct constraint enforcement.**

Key functional indexes include those on:
*   `metadata.ownerId` combined with `metadata.sha256` (for content uniqueness per user).
*   `metadata.token` (for unique download links).
*   `metadata.visibility` and `metadata.tags` (for filtering and listing).
*   A unique index on `(metadata.ownerId, metadata.originalFilename)` is also recommended for robust user-provided filename uniqueness but is not currently enforced at the DB level by default annotations. 

## MongoDB Replica Set Requirement for Transactions

This application requires MongoDB to be running as a replica set (even if single-node) in order to support transactions (required for safe file uploads and duplicate prevention). If you are running MongoDB via Docker Compose, ensure your service uses the following:

```
services:
  mongo:
    image: mongo:8.0
    container_name: storage-app-mongo
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db
      - ./init-mongo.sh:/docker-entrypoint-initdb.d/init-mongo.sh:ro
    command: ["--replSet", "rs0", "--bind_ip_all"]

volumes:
  mongo-data:
```

And create a file named `init-mongo.sh` in your project root with the following contents:

```bash
#!/bin/bash

# Wait for MongoDB to be ready
until mongosh --eval "print(\"waited for connection\")"; do
  sleep 2
  echo "Waiting for MongoDB to be available..."
done

# Check if the replica set is already initialized
IS_INIT=$(mongosh --quiet --eval 'rs.status().ok' || echo "0")

if [ "$IS_INIT" != "1" ]; then
  echo "Initializing replica set..."
  mongosh --eval 'rs.initiate()'
else
  echo "Replica set already initialized."
fi
```

This will ensure the replica set is initialized on first startup. The application will not work correctly with a standalone (non-replica set) MongoDB instance. 

- Visit [http://localhost:8080](http://localhost:8080). 