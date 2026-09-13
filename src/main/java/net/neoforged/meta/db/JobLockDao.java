package net.neoforged.meta.db;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * DAO for distributed job locking using PostgreSQL's SELECT FOR UPDATE SKIP LOCKED.
 * Only active in postgres (HA) mode.
 */
@Repository
@Profile("postgres")
public interface JobLockDao extends JpaRepository<JobLock, String> {

    /**
     * Try to acquire a lock for a job that is not currently locked or has an expired lock.
     * Uses SELECT FOR UPDATE SKIP LOCKED so competing replicas don't block each other.
     *
     * @param jobId       The job identifier
     * @param now         Current timestamp for comparing expiration
     * @return The lock row if acquired (with lock held), empty if another replica holds it
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT jl FROM JobLock jl WHERE jl.jobId = :jobId AND (jl.lockedBy IS NULL OR jl.expiresAt < :now)")
    Optional<JobLock> tryAcquire(String jobId, Instant now);

    /**
     * Release a lock if it was held by the specified instance.
     *
     * @param jobId       The job identifier
     * @param instanceId  The instance that wants to release
     * @return true if the lock was released, false if it wasn't held by this instance
     */
    @Modifying
    @Query("UPDATE JobLock jl SET jl.lockedBy = NULL, jl.lockedAt = NULL, jl.expiresAt = NULL WHERE jl.jobId = :jobId AND jl.lockedBy = :instanceId")
    int release(String jobId, String instanceId);

    /**
     * Renew a lock's expiration time (for long-running jobs).
     *
     * @param jobId       The job identifier
     * @param instanceId  The instance renewing the lock
     * @param newExpiry   New expiration time
     * @return true if renewed, false if lock was not held by this instance
     */
    @Modifying
    @Query("UPDATE JobLock jl SET jl.expiresAt = :newExpiry WHERE jl.jobId = :jobId AND jl.lockedBy = :instanceId")
    int renew(String jobId, String instanceId, Instant newExpiry);

    /**
     * Clean up locks that have expired (e.g., due to replica crash).
     *
     * @param now Current timestamp
     * @return Number of locks cleaned up
     */
    @Modifying
    @Query("UPDATE JobLock jl SET jl.lockedBy = NULL, jl.lockedAt = NULL, jl.expiresAt = NULL WHERE jl.lockedBy IS NOT NULL AND jl.expiresAt < :now")
    int cleanupExpired(Instant now);

    /**
     * Ensure lock rows exist for all specified job IDs (idempotent).
     */
    @Query("SELECT jl.jobId FROM JobLock jl")
    List<String> findAllJobIds();
}
