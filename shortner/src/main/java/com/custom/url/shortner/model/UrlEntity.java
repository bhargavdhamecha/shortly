package com.custom.url.shortner.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class UrlEntity {
    @Id
    private Long distributedId;

    @Setter
    @Column(nullable = false)
    private String longUrl;

    @Setter
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    private Instant expiresAt;

    public UrlEntity(Long distributedId){
        this.distributedId = distributedId;
    }

}
