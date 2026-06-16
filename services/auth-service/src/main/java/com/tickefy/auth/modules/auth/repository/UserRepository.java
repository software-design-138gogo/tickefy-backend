package com.tickefy.auth.modules.auth.repository;

import com.tickefy.auth.modules.auth.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    // Only enabled users count toward the LAST_ADMIN guard — a disabled admin must not
    // keep the system "covered" and allow revoking the last active admin (lockout).
    @Query("SELECT COUNT(u) FROM UserEntity u JOIN u.roles r WHERE r.code = :roleCode AND u.enabled = true")
    long countByRoleCode(String roleCode);

    @Query("SELECT u.id FROM UserEntity u ORDER BY u.createdAt ASC")
    Page<UUID> findUserIds(Pageable pageable);

    @Query("SELECT u FROM UserEntity u WHERE u.id IN :ids")
    List<UserEntity> findAllByIdIn(List<UUID> ids);
}
