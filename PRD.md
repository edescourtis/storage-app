# Product Requirements Document (PRD) - STORAGE Application

## 1. Introduction

The primary objective of the "STORAGE" application is to provide users with a straightforward platform for storing and sharing files. This document outlines the functional and non-functional requirements for the application.

## 2. Functional Requirements

### 2.1. File Upload
*   Users should be able to upload a file with a filename.
*   Users should be able to provide a visibility setting (PUBLIC/PRIVATE) for each file.
*   Users should be able to set a set of tags (up to 5 per file).
*   The application should support uploading files without a limit on the file size, ranging from a few KB to hundreds of GB.
*   After successful upload, the application should identify the type of the file (if not provided by the user).
*   After successful upload, the user should be provided with a unique download link for the file. Links should not be guessable.
*   The application should prevent users from uploading the same file multiple times *for that specific user*. Uniqueness is based on:
    *   File content (e.g., SHA256 hash).
    *   Filename.
    (If either content is the same OR filename already exists for the user, it's a duplicate).

### 2.2. File Management
*   Users should be able to change the filename of files they have uploaded without re-uploading the file content.
*   Users should only be able to delete files that they uploaded.

### 2.3. File Listing
*   Users should be able to list all PUBLIC files.
*   Users should be able to list all files that belong to them (both PUBLIC and PRIVATE).
*   All file lists should:
    *   Have a filter by TAG. Tags should be selected from a list (implying tags are pre-defined or created on-the-fly and then available). "TAG" and "tAg" should be treated as the same tag (case-insensitive matching).
    *   Be sortable by FILENAME, UPLOAD DATE, TAG, CONTENT TYPE, and FILE SIZE.
    *   Be divided into result pages containing a defined number of results (pagination).

### 2.4. File Download
*   All files (both PRIVATE and PUBLIC) can be downloaded by their unique, non-guessable link.

### 2.5. User Handling
*   The application should not have any session or user management endpoints.
*   User identity (e.g., `userId`) will be provided with each request to the API.

### 2.6. Tags
*   Tags should be provided by the user during upload.
*   If a tag does not exist, it should be created.
*   The application does not have to provide a list of all available tags (though listing files by tag implies some tag discovery/filtering).

## 3. Non-Functional Requirements

### 3.1. API
*   Do NOT create a UI. Provide a RESTful API that fulfills the given requirements.
*   Download links should be relative to the service.

### 3.2. Technology Stack
*   Use SpringBoot as the backend framework.
*   Use MongoDb as a database (GridFS for file storage).
*   Use one of your preferred build systems like Gradle or Maven (Maven is currently used).

### 3.3. Performance & Resource Constraints
*   The application container must have a memory limit of 1GB.
*   The application container must have a hard disk limit of 200MB for the application itself (temporary storage like `/tmp`).

### 3.4. Testing
*   Add Tests for REST controllers and services.
*   Specific test scenarios to cover (see `CHECKLIST.md`).

### 3.5. Deployment
*   Ship your app as a Docker image.
*   Host your project on GitHub.
*   Setup CI that builds a target Docker image.

### 3.6. Documentation
*   Add a README file in the repository root describing the project, API, and usage. 