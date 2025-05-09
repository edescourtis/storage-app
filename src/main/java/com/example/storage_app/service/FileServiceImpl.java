package com.example.storage_app.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.multipart.MultipartFile;

import com.example.storage_app.controller.dto.FileResponse;
import com.example.storage_app.controller.dto.FileUpdateRequest;
import com.example.storage_app.controller.dto.FileUploadRequest;
import com.example.storage_app.exception.FileAlreadyExistsException;
import com.example.storage_app.exception.InvalidRequestArgumentException;
import com.example.storage_app.exception.ResourceNotFoundException;
import com.example.storage_app.exception.StorageException;
import com.example.storage_app.model.Visibility;
import com.example.storage_app.util.MimeUtil;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.UpdateResult;

@Service
public class FileServiceImpl implements FileService {
    public final int MAX_TAGS = 5;
    public final String HASH_ALGO = "SHA-256";

    @Autowired
    private GridFsTemplate gridFs;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public FileResponse uploadFile(String userId, MultipartFile file, FileUploadRequest request) throws NoSuchAlgorithmException, IOException {
        Visibility visibility = request.visibility();
        String filenameForStorage = request.filename();
        if (filenameForStorage == null || filenameForStorage.isBlank()) {
            filenameForStorage = file.getOriginalFilename();
        }

        List<String> originalTags = request.tags();
        List<String> lowercaseTags = new java.util.ArrayList<>();
        if (originalTags != null) {
            for (String tag : originalTags) {
                if (tag != null) {
                    lowercaseTags.add(tag.toLowerCase());
                }
            }
        }

        if(lowercaseTags.size() > MAX_TAGS) {
            throw new InvalidRequestArgumentException("Cannot have more than " + MAX_TAGS + " tags");
        }

        if(file.isEmpty()) {
            throw new InvalidRequestArgumentException("File is empty");
        }

        MimeUtil.Detected detected = MimeUtil.detect(file.getInputStream());
        String mimeType = detected.contentType;
        MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
        String objectIdHex = null;
        String token = UUID.randomUUID().toString();

        try (InputStream actualStream = detected.stream;
             DigestInputStream digestIn = new DigestInputStream(actualStream, md)) {

            Document initialMetadata = new Document()
                    .append("ownerId", userId)
                    .append("visibility", visibility.name())
                    .append("tags", lowercaseTags)
                    .append("token", token)
                    .append("originalFilename", filenameForStorage)
                    .append("uploadDate", new Date())
                    .append("contentType", mimeType)
                    .append("size", file.getSize());

            Query filenameQuery = new Query(
                    Criteria.where("filename").is(filenameForStorage)
                            .and("metadata.ownerId").is(userId)
            );
            if (mongoTemplate.exists(filenameQuery, "fs.files")) {
                throw new FileAlreadyExistsException("Filename '" + filenameForStorage + "' already exists for this user.");
            }

            objectIdHex = gridFs.store(digestIn, filenameForStorage, mimeType, initialMetadata).toHexString();

            String hash = HexFormat.of().formatHex(digestIn.getMessageDigest().digest());

            Query contentQuery = new Query(
                    Criteria.where("metadata.ownerId").is(userId)
                            .and("metadata.sha256").is(hash)
            );
            if (mongoTemplate.exists(contentQuery, "fs.files")) {
                gridFs.delete(new Query(Criteria.where("_id").is(new ObjectId(objectIdHex))));
                throw new FileAlreadyExistsException("Content already exists for this user. File upload aborted.");
            }

            Query queryForUpdate = Query.query(Criteria.where("_id").is(new ObjectId(objectIdHex)));
            Update update = new Update().set("metadata.sha256", hash);
            mongoTemplate.updateFirst(queryForUpdate, update, "fs.files");
            
            String downloadLink = "/api/v1/files/" + objectIdHex + "/download";

            return new FileResponse(
                    objectIdHex,
                    filenameForStorage,
                    visibility,
                    lowercaseTags,
                    (Date) initialMetadata.get("uploadDate"), // Use date from metadata
                    mimeType,
                    file.getSize(),
                    downloadLink
            );
        }
    }

    private String mapSortField(String apiSortField) {
        if (apiSortField == null) {
            return "uploadDate"; // Default sort field if null is passed
        }
        return switch (apiSortField.toLowerCase()) {
            case "filename" -> "filename";
            case "uploaddate" -> "uploadDate";
            case "contenttype" -> "contentType";
            case "size" -> "length";
            case "tag", "tags" -> "metadata.tags";
            default -> "uploadDate";
        };
    }

    @Override
    public Page<FileResponse> listFiles(String userId, String filterTag, String sortBy, String sortDir, int pageNum, int pageSize) {
        Sort.Direction direction = Sort.Direction.ASC; // Default to ASC
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
        
        Query countQuery = Query.query(criteria); // Count based on the same criteria, without pagination/sorting
        long total = mongoTemplate.count(countQuery, Document.class, "fs.files");

        List<FileResponse> fileResponses = fileDocuments.stream().map(doc -> {
            ObjectId id = doc.getObjectId("_id");
            String filename = doc.getString("filename");
            Date uploadDate = doc.getDate("uploadDate");
            String contentType = doc.getString("contentType");
            Long length = doc.getLong("length"); // Use Long to handle potential null from DB if field absent
            if (length == null) length = 0L;

            Document metadata = doc.get("metadata", Document.class);
            Visibility visibility = Visibility.PUBLIC; // Default or handle null
            List<String> tags = Collections.emptyList();
            String token = null;
            
            if (metadata != null) {
                String visibilityStr = metadata.getString("visibility");
                if (visibilityStr != null) {
                    try {
                        visibility = Visibility.valueOf(visibilityStr);
                    } catch (IllegalArgumentException e) {
                        // Handle or log invalid visibility string, default already set
                    }
                }
                tags = metadata.getList("tags", String.class, Collections.emptyList());
                token = metadata.getString("token");
            }

            String downloadLink = (token != null) ? "/api/v1/files/download/" + token : null;
            
            return new FileResponse(
                id != null ? id.toHexString() : null, // Handle if _id is somehow null from projection
                filename,
                visibility,
                tags,
                uploadDate,
                contentType,
                length,
                downloadLink
            );
        }).collect(Collectors.toList());

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
        String newFilename = request.newFilename();
        ObjectId objectFileId = new ObjectId(fileId);

        Query ownershipQuery = Query.query(
            Criteria.where("_id").is(objectFileId)
                    .and("metadata.ownerId").is(userId)
        );
        Document existingFileDoc = mongoTemplate.findOne(ownershipQuery, Document.class, "fs.files");

        if (existingFileDoc == null) {
            throw new ResourceNotFoundException("File not found with id: " + fileId + " for user: " + userId);
        }

        Query conflictQuery = Query.query(
            Criteria.where("filename").is(newFilename)
                    .and("metadata.ownerId").is(userId)
                    .and("_id").ne(objectFileId) 
        );
        if (mongoTemplate.exists(conflictQuery, "fs.files")) {
            throw new FileAlreadyExistsException("Filename '" + newFilename + "' already exists for this user.");
        }

        Query updateQuery = Query.query(Criteria.where("_id").is(objectFileId));
        Update updateDefinition = new Update().set("filename", newFilename);

        UpdateResult updateResult = mongoTemplate.updateFirst(updateQuery, updateDefinition, "fs.files");

        if (updateResult.getModifiedCount() == 0 && !existingFileDoc.getString("filename").equals(newFilename)) {
            throw new StorageException("File update failed for fileId: " + fileId + ". The file was not modified.");
        }

        Document metadata = existingFileDoc.get("metadata", Document.class);
        if (metadata == null) { // Should not happen if ownership query worked based on metadata.ownerId
            throw new IllegalStateException("Metadata not found for file: " + fileId);
        }
        
        String responseId = objectFileId.toHexString();
        Visibility visibility = Visibility.valueOf(metadata.getString("visibility")); 
        List<String> tags = metadata.getList("tags", String.class);
        Date uploadDate = existingFileDoc.getDate("uploadDate"); 
        String contentType = existingFileDoc.getString("contentType"); 
        long size = existingFileDoc.getLong("length"); 
        String token = metadata.getString("token");
        String downloadLink = "/api/v1/files/download/" + token; 

        return new FileResponse(
                responseId,
                newFilename, 
                visibility,
                tags,
                uploadDate,
                contentType,
                size,
                downloadLink
        );
    }

    @Override
    public void deleteFile(String userId, String fileId) {
        ObjectId objectFileId = new ObjectId(fileId);

        Query existenceAndOwnershipQuery = Query.query(
            Criteria.where("_id").is(objectFileId)
                    .and("metadata.ownerId").is(userId)
        );

        if (!mongoTemplate.exists(existenceAndOwnershipQuery, "fs.files")) {
            throw new ResourceNotFoundException("File not found with id: " + fileId + " for user: " + userId + ", or user not authorized to delete.");
        }

        Query deleteQuery = Query.query(Criteria.where("_id").is(objectFileId));
        gridFs.delete(deleteQuery);
    }
} 