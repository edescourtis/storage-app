package com.example.storage_app.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.service.FileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;

@WebMvcTest(FileController.class)
@Import(ValidationAutoConfiguration.class)
public class FileControllerTest {

  private MockMvc mockMvc;

  @MockBean private FileService fileService;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private WebApplicationContext webApplicationContext;

  private String testUserId = "user-test-id";
  private FileUploadRequest fileUploadRequest;
  private String testFilename = "test.txt";

  @BeforeEach
  void setUp() {
    this.fileUploadRequest =
        new FileUploadRequest(testFilename, Visibility.PRIVATE, List.of("tag1"));
    this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext).build();
  }

  @Test
  void uploadFile_whenValidInput_shouldReturn201CreatedWithFileResponse() throws Exception {
    String fileId = new ObjectId().toHexString();
    String requestFilename = "test-upload.txt";
    String token = UUID.randomUUID().toString();
    String downloadLink = "/api/v1/files/download/" + token;

    FileUploadRequest currentUploadRequestDto =
        new FileUploadRequest(requestFilename, Visibility.PRIVATE, List.of("uploadtag"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original-filename.txt", MediaType.TEXT_PLAIN_VALUE, "Test content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(currentUploadRequestDto));

    FileResponse serviceResponse =
        new FileResponse(
            fileId,
            requestFilename,
            Visibility.PRIVATE,
            List.of("uploadtag"),
            new Date(),
            MediaType.TEXT_PLAIN_VALUE,
            filePart.getSize(),
            downloadLink);

    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), eq(currentUploadRequestDto)))
        .thenReturn(serviceResponse);

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", downloadLink))
        .andExpect(jsonPath("$.id").value(fileId))
        .andExpect(jsonPath("$.filename").value(requestFilename))
        .andExpect(jsonPath("$.visibility").value(Visibility.PRIVATE.name()));
  }

  @Test
  void uploadFile_whenServiceThrowsIOException_shouldReturn500() throws Exception {
    FileUploadRequest uploadRequestDto =
        new FileUploadRequest("io-error-test.txt", Visibility.PRIVATE, List.of("io"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    String exceptionMessage = "Simulated IO error during upload";
    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), any(FileUploadRequest.class)))
        .thenThrow(new IOException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void uploadFile_whenServiceThrowsNoSuchAlgorithmException_shouldReturn500() throws Exception {
    FileUploadRequest uploadRequestDto =
        new FileUploadRequest("algo-error-test.txt", Visibility.PRIVATE, List.of("algo"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    String exceptionMessage = "Simulated NoSuchAlgorithmException";
    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), any(FileUploadRequest.class)))
        .thenThrow(new NoSuchAlgorithmException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void uploadFile_whenDownloadLinkIsInvalidUri_shouldReturn500() throws Exception {
    FileUploadRequest uploadRequestDto =
        new FileUploadRequest("uri-error-test.txt", Visibility.PRIVATE, List.of("uri"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    FileResponse serviceResponseWithInvalidUri =
        new FileResponse(
            new ObjectId().toHexString(),
            "uri-error-test.txt",
            Visibility.PRIVATE,
            List.of("uri"),
            new Date(),
            MediaType.TEXT_PLAIN_VALUE,
            100L,
            "/api/v1/files/download/a bad uri with spaces");

    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), any(FileUploadRequest.class)))
        .thenReturn(serviceResponseWithInvalidUri);

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Error creating location URI from download link: Illegal character in path at index 24: /api/v1/files/download/a bad uri with spaces"));
  }

  @Test
  void updateFileDetails_whenValidInput_shouldReturn200AndUpdatedResponse() throws Exception {
    String fileToUpdateId = new ObjectId().toHexString();
    FileUpdateRequest updateRequestDto = new FileUpdateRequest("updated-filename.txt");
    FileResponse expectedServiceResponse =
        new FileResponse(
            fileToUpdateId,
            updateRequestDto.newFilename(),
            Visibility.PRIVATE,
            List.of("tag1"),
            new Date(),
            "text/plain",
            1234L,
            "/api/v1/files/download/some-token");

    when(fileService.updateFileDetails(
            eq(testUserId), eq(fileToUpdateId), any(FileUpdateRequest.class)))
        .thenReturn(expectedServiceResponse);

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileToUpdateId)
                .header("X-User-Id", testUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(fileToUpdateId))
        .andExpect(jsonPath("$.filename").value(updateRequestDto.newFilename()));
  }

  @Test
  void updateFileDetails_whenServiceThrowsResourceNotFound_shouldReturn404() throws Exception {
    String fileToUpdateId = "non-existent-id-for-update";
    FileUpdateRequest updateRequestDto = new FileUpdateRequest("any-filename.txt");
    String exceptionMessage = "File not found with id: " + fileToUpdateId;

    when(fileService.updateFileDetails(
            eq(testUserId), eq(fileToUpdateId), any(FileUpdateRequest.class)))
        .thenThrow(new ResourceNotFoundException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileToUpdateId)
                .header("X-User-Id", testUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  // Tests for downloadFile endpoint
  @Test
  void downloadFile_Success_WithContentType() throws Exception {
    String downloadToken = "test-token-123";
    String filename = "testfile.txt";
    String contentType = "text/plain";
    byte[] content = "Hello World".getBytes();

    GridFsResource mockResource = mock(GridFsResource.class);
    when(mockResource.getFilename()).thenReturn(filename);
    when(mockResource.getContentType()).thenReturn(contentType);
    when(mockResource.contentLength()).thenReturn((long) content.length);
    when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream(content));

    when(fileService.downloadFile(eq(downloadToken)))
        .thenReturn(
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(mockResource));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files/download/{token}", downloadToken)
                .accept(MediaType.ALL))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, contentType))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length)))
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\""))
        .andExpect(content().bytes(content));
  }

  @Test
  void downloadFile_Success_NullContentType() throws Exception {
    String downloadToken = "test-token-456";
    String filename = "anotherfile.dat";
    byte[] content = "Binary data".getBytes();

    GridFsResource mockResource = mock(GridFsResource.class);
    when(mockResource.getFilename()).thenReturn(filename);
    when(mockResource.getContentType()).thenReturn(null);
    when(mockResource.contentLength()).thenReturn((long) content.length);
    when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream(content));

    when(fileService.downloadFile(eq(downloadToken)))
        .thenReturn(
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(mockResource));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files/download/{token}", downloadToken)
                .accept(MediaType.ALL))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
        .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length)))
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\""))
        .andExpect(content().bytes(content));
  }

  @Test
  void downloadFile_whenServiceThrowsResourceNotFound_shouldReturn404() throws Exception {
    String downloadToken = "non-existent-token";
    String exceptionMessage = "File not found for token: " + downloadToken;

    when(fileService.downloadFile(eq(downloadToken)))
        .thenThrow(new ResourceNotFoundException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files/download/{token}", downloadToken)
                .accept(MediaType.ALL))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void downloadFile_whenResourceAccessThrowsIOException_shouldReturn500() throws Exception {
    String downloadToken = "token-for-io-exception";
    GridFsResource mockResource = mock(GridFsResource.class);
    String ioExceptionMessage = "Simulated IOException during resource access";

    String serviceLevelExceptionMessage =
        "Service-level issue preparing download for: " + downloadToken;
    when(fileService.downloadFile(eq(downloadToken)))
        .thenThrow(new StorageException(serviceLevelExceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files/download/{token}", downloadToken)
                .accept(MediaType.ALL))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value(serviceLevelExceptionMessage));
  }

  @Test
  void updateFileDetails_whenServiceThrowsStorageException_shouldReturn500() throws Exception {}

  @Test
  void updateFileDetails_whenServiceThrowsUnauthorizedOperation_shouldReturn403Forbidden()
      throws Exception {
    String fileToUpdateId = "unauthorized-update-id";
    FileUpdateRequest updateRequestDto = new FileUpdateRequest("any-new-name.txt");
    String exceptionMessage = "User not authorized to update fileId: " + fileToUpdateId;

    when(fileService.updateFileDetails(
            eq(testUserId), eq(fileToUpdateId), any(FileUpdateRequest.class)))
        .thenThrow(new UnauthorizedOperationException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileToUpdateId)
                .header("X-User-Id", testUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void deleteFile_whenServiceThrowsUnauthorizedOperation_shouldReturn403Forbidden()
      throws Exception {}

  @Test
  void uploadFile_whenServiceThrowsInvalidRequestForEmptyFile_shouldReturn400() throws Exception {
    FileUploadRequest uploadRequestDto =
        new FileUploadRequest("empty-file-test.txt", Visibility.PRIVATE, List.of("test"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original-empty.txt", MediaType.TEXT_PLAIN_VALUE, "".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    String exceptionMessage = "File is empty";
    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), any(FileUploadRequest.class)))
        .thenThrow(new InvalidRequestArgumentException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void uploadFile_whenDtoHasTooManyTags_shouldReturn400FromDtoValidation() throws Exception {
    List<String> tooManyTags = List.of("1", "2", "3", "4", "5", "6");
    FileUploadRequest uploadRequestDtoWithTooManyTags =
        new FileUploadRequest("valid-filename.txt", Visibility.PRIVATE, tooManyTags);

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original-tags.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDtoWithTooManyTags));

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0]").value("tags: A maximum of 5 tags are allowed"));
  }

  @Test
  void uploadFile_whenServiceThrowsFileAlreadyExists_shouldReturn409Conflict() throws Exception {
    FileUploadRequest uploadRequestDto =
        new FileUploadRequest("existing-file.txt", Visibility.PRIVATE, List.of("conflict"));

    MockMultipartFile filePart =
        new MockMultipartFile(
            "file", "original.txt", MediaType.TEXT_PLAIN_VALUE, "content".getBytes());
    MockMultipartFile propertiesPart =
        new MockMultipartFile(
            "properties",
            null,
            MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(uploadRequestDto));

    String exceptionMessage = "File 'existing-file.txt' already exists.";
    when(fileService.uploadFile(
            eq(testUserId), any(MultipartFile.class), any(FileUploadRequest.class)))
        .thenThrow(
            new com.example.storage_app.exception.FileAlreadyExistsException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/api/v1/files")
                .file(filePart)
                .file(propertiesPart)
                .header("X-User-Id", testUserId)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void listFiles_whenInvalidSortByParam_shouldReturn400() throws Exception {
    String invalidSortBy = "invalidSortField";
    String exceptionMessage = "Invalid sortBy field: " + invalidSortBy;

    when(fileService.listFiles(isNull(), isNull(), eq(invalidSortBy), eq("desc"), eq(0), eq(10)))
        .thenThrow(new IllegalArgumentException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .param("sortBy", invalidSortBy)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void listFiles_forUserWithTagAndSpecificSort_shouldReturnPage() throws Exception {
    String specificUserId = "user-filter-sort";
    String specificTag = "projectX";
    String sortByField = "filename";
    String sortDir = "asc";
    int pageNum = 0;
    int pageSize = 3;

    FileResponse file1 =
        new FileResponse(
            "id1",
            "alpha.doc",
            Visibility.PRIVATE,
            List.of(specificTag),
            new Date(),
            "app/doc",
            100L,
            "/dl/t1");
    FileResponse file2 =
        new FileResponse(
            "id2",
            "beta.doc",
            Visibility.PRIVATE,
            List.of(specificTag),
            new Date(),
            "app/doc",
            200L,
            "/dl/t2");
    List<FileResponse> responseList = Arrays.asList(file1, file2);
    Page<FileResponse> serviceResponsePage =
        new PageImpl<>(
            responseList,
            PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, sortByField)),
            responseList.size());

    when(fileService.listFiles(
            eq(specificUserId),
            eq(specificTag),
            eq(sortByField),
            eq(sortDir),
            eq(pageNum),
            eq(pageSize)))
        .thenReturn(serviceResponsePage);

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/files")
                .header("X-User-Id", specificUserId)
                .param("tag", specificTag)
                .param("sortBy", sortByField)
                .param("sortDir", sortDir)
                .param("page", String.valueOf(pageNum))
                .param("size", String.valueOf(pageSize))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(responseList.size()))
        .andExpect(jsonPath("$.content[0].filename").value("alpha.doc"))
        .andExpect(jsonPath("$.totalElements").value(responseList.size()));
  }

  @Test
  void updateFileDetails_whenServiceReportsNoModification_shouldReturn500() throws Exception {
    String fileToUpdateId = "id-for-no-modification-test";
    FileUpdateRequest updateRequestDto = new FileUpdateRequest("some-new-name.txt");
    String exceptionMessage =
        "File update for filename failed for fileId: "
            + fileToUpdateId
            + ". Zero documents modified despite expecting a change.";

    when(fileService.updateFileDetails(
            eq(testUserId), eq(fileToUpdateId), any(FileUpdateRequest.class)))
        .thenThrow(new StorageException(exceptionMessage));

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/files/{fileId}", fileToUpdateId)
                .header("X-User-Id", testUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequestDto))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value(exceptionMessage));
  }

  @Test
  void testListFiles_DefaultSort() throws Exception {
    FileResponse mockResponse1 =
        new FileResponse(
            "uuid1",
            "file1.txt",
            Visibility.PUBLIC,
            List.of("tagA"),
            new Date(),
            "text/plain",
            100L,
            "/dl/token1");
    Page<FileResponse> mockPage = new PageImpl<>(List.of(mockResponse1));

    when(fileService.listFiles(isNull(), isNull(), eq("uploadDate"), eq("desc"), eq(0), eq(10)))
        .thenReturn(mockPage);

    mockMvc
        .perform(get("/api/v1/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("uuid1"))
        .andExpect(jsonPath("$.totalElements").value(1));

    verify(fileService).listFiles(isNull(), isNull(), eq("uploadDate"), eq("desc"), eq(0), eq(10));
  }
}
