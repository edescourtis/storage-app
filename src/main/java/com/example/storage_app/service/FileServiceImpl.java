package com.example.storage_app.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.tika.mime.MediaType;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.exception.UnauthorizedOperationException;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.util.MimeUtil;
import com.mongodb.MongoWriteException;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.UpdateResult;

@Service
public class FileServiceImpl implements FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileServiceImpl.class);
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    public final int MAX_TAGS = 5;
    public final String HASH_ALGO = "SHA-256";


    @Autowired
    private GridFsTemplate gridFs;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    @Transactional(rollbackFor = {IOException.class, NoSuchAlgorithmException.class, StorageException.class, FileAlreadyExistsException.class, IllegalArgumentException.class})
    public FileResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request) throws NoSuchAlgorithmException, IOException {
        Visibility visibility = request.visibility();

        String userProvidedFilename = request.filename();
        if (userProvidedFilename == null || userProvidedFilename.isBlank()) {
            userProvidedFilename = file.getOriginalFilename();
        }

        if (userProvidedFilename == null || userProvidedFilename.isBlank()) {
            throw new InvalidRequestArgumentException("Filename cannot be empty.");
        }

        Query filenameConflictQuery = new Query(
                Criteria.where("metadata.originalFilename").is(userProvidedFilename)
                        .and("metadata.ownerId").is(userId)
        );
        if (mongoTemplate.exists(filenameConflictQuery, "fs.files")) {
            throw new FileAlreadyExistsException("Filename '" + userProvidedFilename + "' already exists for this user.");
        }

        String systemFilenameUUID = UUID.randomUUID().toString();
        List<String> lowercaseTags = (request.tags() == null ? new ArrayList<String>() : request.tags())
                .stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        if(lowercaseTags.size() > MAX_TAGS) {
            throw new InvalidRequestArgumentException("Cannot have more than " + MAX_TAGS + " tags");
        }
        if(file.isEmpty()) {
            throw new InvalidRequestArgumentException("File is empty");
        }

        MimeUtil.Detected detectedMime = MimeUtil.detect(file.getInputStream());
        String mimeType = detectedMime.contentType;
        if(mimeType == null && file.getContentType() != null && MediaType.parse(file.getContentType()) != null) {
            mimeType = file.getContentType();
        }
        if(mimeType == null || mimeType.isBlank()) {
            mimeType = DEFAULT_CONTENT_TYPE;
        }

        MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
        String token = UUID.randomUUID().toString();
        ObjectId storedFileObjectId;

        try (InputStream actualStream = detectedMime.stream;
             DigestInputStream digestIn = new DigestInputStream(actualStream, md)) {
            
            Document initialMetadata = new Document()
                    .append("ownerId", userId)
                    .append("visibility", visibility.name())
                    .append("tags", lowercaseTags)
                    .append("token", token)
                    .append("originalFilename", userProvidedFilename)
                    .append("uploadDate", new Date())
                    .append("contentType", mimeType)
                    .append("size", file.getSize());
            
            try {
                storedFileObjectId = gridFs.store(digestIn, systemFilenameUUID, mimeType, initialMetadata);
                if (storedFileObjectId == null) { 
                    throw new StorageException("Failed to store file, GridFS store operation returned null ID.");
                }
            } catch (Exception e) { 
                if (e instanceof DuplicateKeyException || (e instanceof MongoWriteException && ((MongoWriteException) e).getError().getCode() == 11000)) {
                    String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    logger.error("MongoDB DuplicateKeyException or E11000 WriteException during GridFS store for systemFilenameUUID: {}. Message: {}",
                                systemFilenameUUID, e.getMessage(), e);
                    if (errorMessage.contains("metadata.originalfilename")) {
                        throw new FileAlreadyExistsException("Filename '" + userProvidedFilename + "' already exists for this user (DB conflict during store).", e);
                    } else if (errorMessage.contains("metadata.sha256")) { 
                        throw new FileAlreadyExistsException("Content already exists for this user (DB conflict during store).", e);
                    }
                    throw new StorageException("Failed to store file due to a data conflict during initial storage (GridFS store).", e);
                } else if (e instanceof IOException) { 
                    throw (IOException)e;
                } else if (e instanceof NoSuchAlgorithmException) { 
                    throw (NoSuchAlgorithmException)e;
                }
                logger.error("Unexpected exception during GridFS store for systemFilenameUUID: {}. Exception: {}", systemFilenameUUID, e.getMessage(), e);
                throw new StorageException("Failed to store file due to an unexpected issue during initial storage.", e);
            }

            String hash = HexFormat.of().formatHex(digestIn.getMessageDigest().digest());

            Query queryForHashUpdate = Query.query(Criteria.where("_id").is(storedFileObjectId));
            Update updateWithHash = new Update().set("metadata.sha256", hash);
            
            try {
                UpdateResult updateResult = mongoTemplate.updateFirst(queryForHashUpdate, updateWithHash, "fs.files");
                if (updateResult.getModifiedCount() == 0) {
                    logger.warn("File metadata update for hash failed for ID: {}. Zero documents modified. Potential orphan if transaction doesn't roll back GridFS store.", storedFileObjectId);
                    throw new StorageException("File metadata update for hash failed for ID: " + storedFileObjectId + ". Zero documents modified.");
                }
            } catch (org.springframework.dao.DuplicateKeyException e) { 
                logger.warn("Content duplicate detected for user {} (hash: {}) upon metadata update. SystemFileUUID: {}. Attempting to delete orphaned GridFS file.", userId, hash, systemFilenameUUID);
                try {
                    gridFs.delete(Query.query(Criteria.where("_id").is(storedFileObjectId)));
                    logger.info("Successfully deleted orphaned GridFS file (id: {}) after hash conflict.", storedFileObjectId);
                } catch (Exception deleteEx) {
                    logger.error("Failed to delete orphaned GridFS file (id: {}) after hash conflict. Manual cleanup may be required.", storedFileObjectId, deleteEx);
                }
                throw new FileAlreadyExistsException("Content already exists for this user (hash conflict: " + e.getMessage() + ")", e);
            }

            String downloadLink = "/api/v1/files/download/" + token;
            Date uploadDateFromMeta = initialMetadata.getDate("uploadDate");

            return new FileResponse(
                    systemFilenameUUID,
                    userProvidedFilename,
                    visibility,
                    lowercaseTags,
                    uploadDateFromMeta,
                    mimeType,
                    file.getSize(),
                    downloadLink
            );
        }
    }

    private String mapSortField(String apiSortField) {
        if (apiSortField == null) {
            return "uploadDate";
        }
        return switch (apiSortField.toLowerCase()) {
            case "filename" -> "metadata.originalFilename";
            case "uploaddate" -> "uploadDate";
            case "contenttype" -> "contentType";
            case "size" -> "length";
            case "tag", "tags" -> "metadata.tags";
            default -> throw new IllegalArgumentException("Invalid sortBy field: " + apiSortField);
        };
    }

    @Override
    public Page<FileResponse> listFiles(String userId, String filterTag, String sortBy, String sortDir, int pageNum, int pageSize) {
        Sort.Direction direction = Sort.Direction.ASC;
        if (sortDir != null && sortDir.equalsIgnoreCase("desc")) {
            direction = Sort.Direction.DESC;
        }
        String sortField = mapSortField(sortBy);
        Pageable pageable = PageRequest.of(pageNum, pageSize, Sort.by(direction, sortField));

        Criteria criteria = new Criteria();
        if (userId != null && !userId.isBlank()) {
            criteria.and("metadata.ownerId").is(userId);
        } else {
            criteria.and("metadata.visibility").is(Visibility.PUBLIC.name());
        }

        if (filterTag != null && !filterTag.isBlank()) {
            criteria.and("metadata.tags").is(filterTag.toLowerCase());
        }

        Query query = new Query(criteria).with(pageable);
        
        List<Document> fileDocuments = mongoTemplate.find(query, Document.class, "fs.files");
        
        Query countQuery = Query.query(criteria);
        long total = mongoTemplate.count(countQuery, Document.class, "fs.files");

        List<FileResponse> fileResponses = fileDocuments.stream()
                .map(this::mapDocToFileResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(fileResponses, pageable, total);
    }

    @Override
    public GridFsResource downloadFile(String token) {
        Query query = Query.query(Criteria.where("metadata.token").is(token));
        GridFSFile gridFSFile = gridFs.findOne(query);

        if (gridFSFile == null) {
            throw new ResourceNotFoundException("File not found for token: " + token);
        }
        return gridFs.getResource(gridFSFile);
    }

    @Override
    public FileResponse updateFileDetails(String userId, String fileId, FileUpdateRequest request) {
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(fileId));
        Document existingFileDoc = mongoTemplate.findOne(findBySystemUUIDQuery, Document.class, "fs.files");

        if (existingFileDoc == null) {
            throw new ResourceNotFoundException("File not found with id: " + fileId);
        }

        Document metadata = existingFileDoc.get("metadata", Document.class);
        if (metadata == null) {
            throw new StorageException("File metadata is missing for fileId: " + fileId);
        }
        String ownerId = metadata.getString("ownerId");
        if (!userId.equals(ownerId)) {
            throw new UnauthorizedOperationException("User '" + userId + "' not authorized to update fileId: " + fileId);
        }

        String currentOriginalFilename = metadata.getString("originalFilename");
        String newFilenameFromRequest = request.newFilename();

        if (!StringUtils.hasText(newFilenameFromRequest) || newFilenameFromRequest.equals(currentOriginalFilename)) {
            return mapDocToFileResponse(existingFileDoc);
        }

        Query conflictQuery = Query.query(
            Criteria.where("metadata.originalFilename").is(newFilenameFromRequest)
                    .and("metadata.ownerId").is(userId)
                    .and("filename").ne(fileId)
        );
        if (mongoTemplate.exists(conflictQuery, "fs.files")) {
            throw new FileAlreadyExistsException("Filename '" + newFilenameFromRequest + "' already exists for this user.");
        }

        Update updateDefinition = new Update().set("metadata.originalFilename", newFilenameFromRequest);
        com.mongodb.client.result.UpdateResult updateResult = mongoTemplate.updateFirst(findBySystemUUIDQuery, updateDefinition, "fs.files");

        if (updateResult.getModifiedCount() == 0) {
            throw new StorageException("File update for original filename failed for system ID: " + fileId + ". Zero documents modified.");
        }

        Document updatedFileDoc = mongoTemplate.findOne(findBySystemUUIDQuery, Document.class, "fs.files");
        if (updatedFileDoc == null) {
             throw new StorageException("Failed to retrieve file after update for system ID: " + fileId);
        }
        return mapDocToFileResponse(updatedFileDoc);
    }

    @Override
    public void deleteFile(String userId, String fileId) {
        Query findBySystemUUIDQuery = Query.query(Criteria.where("filename").is(fileId));
        
        Document fileDoc = mongoTemplate.findOne(findBySystemUUIDQuery, Document.class, "fs.files");

        if (fileDoc == null) {
            throw new ResourceNotFoundException("File not found with id: " + fileId);
        }

        Document metadata = fileDoc.get("metadata", Document.class);
        String ownerId = (metadata != null) ? metadata.getString("ownerId") : null;

        if (!userId.equals(ownerId)) {
            throw new UnauthorizedOperationException("User '" + userId + "' not authorized to delete fileId: " + fileId);
        }

        gridFs.delete(findBySystemUUIDQuery);
    }

    private FileResponse mapDocToFileResponse(Document fsFileDoc) {
        if (fsFileDoc == null) return null;
        Document metadata = fsFileDoc.get("metadata", Document.class);
        
        String systemFileId = fsFileDoc.getString("filename");

        if (metadata == null) {
            logger.error("Corrupt file record: metadata missing for system ID: {}", systemFileId);
            throw new StorageException("File metadata is missing for system ID: " + systemFileId);
        }

        String originalFilename = metadata.getString("originalFilename");
        
        String visString = metadata.getString("visibility");
        Visibility visibilityEnum;
        try {
            visibilityEnum = StringUtils.hasText(visString) ? Visibility.valueOf(visString.toUpperCase()) : Visibility.PRIVATE;
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid visibility string '{}' in metadata for system ID {}. Defaulting to PRIVATE.", visString, systemFileId);
            visibilityEnum = Visibility.PRIVATE;
        }
        
        List<String> tags = metadata.getList("tags", String.class, java.util.Collections.emptyList());
        Date uploadDate = fsFileDoc.getDate("uploadDate");
        String contentType = fsFileDoc.getString("contentType"); 
        Long lengthFromDb = fsFileDoc.getLong("length");
        long size = (lengthFromDb != null) ? lengthFromDb : 0L;
        
        String token = metadata.getString("token");
        String downloadLink = (token != null) ? "/api/v1/files/download/" + token : null;

        return new FileResponse(
                systemFileId,
                originalFilename,
                visibilityEnum,
                tags,
                uploadDate, 
                contentType, 
                size,        
                downloadLink 
        );
    }
} 