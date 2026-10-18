package com.custom.url.shortner.repository;

import com.custom.url.shortner.model.UrlEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UrlEntityRepository extends JpaRepository<UrlEntity, Long> {

    @Query("select u from UrlEntity u " +
            "where u.distributedId = :id and (u.expiresAt is null or u.expiresAt > :now)")
    Optional<UrlEntity> findActiveLongUrl(@Param("id") Long id, @Param("now") Instant now);

}
