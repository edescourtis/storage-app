package com.example.storage_app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document("fs.files")
@CompoundIndexes({
        @CompoundIndex(name="user_name_idx",
                def="{'metadata.userId': 1, filename: 1}", unique=true),
        @CompoundIndex(name="user_hash_idx",
                def="{'metadata.userId': 1, 'metadata.sha256': 1}", unique=true),
        @CompoundIndex(name="token_idx",
                def="{'metadata.token': 1}", unique=true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileRecord {
    @Id
    private String id;
    private String ownerId;
    private String filename;
    private Visibility visibility;
    private List<String> tags;
    private Date uploadDate;
    private String contentType;
    private long size;
    private String sha256;
    private String token;

    public enum Visibility { PUBLIC, PRIVATE }
}
