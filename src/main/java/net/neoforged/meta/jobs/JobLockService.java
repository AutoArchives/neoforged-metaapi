package net.neoforged.meta.jobs;

import net.neoforged.meta.db.JobLock;
import net.neoforged.meta.db.JobLockDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for acquiring, renewing, and releasing distributed job locks.
 * Ensures only one replica executes a given job at a time.
 * Only active in postgres (HA) mode.
 */
@Service
@Profile("postgres")
public class JobLockService {

    private static final Logger logger = LoggerFactory.getLogger(JobLockService.class);

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofMinutes(15);

    private final JobLockDao dao;
    private final String instanceId;

    public JobLockService(JobLockDao dao, String instanceId) {
        this.dao = dao;
        this.instanceId = instanceId;
    }

    /**
     * Try to acquire a lock for the specified job.
     *
     * @param jobId The job identifier
     * @return true if lock was acquired, false if another replica holds it
     */
    @Transactional
    public boolean tryAcquire(String jobId) {
        return tryAcquire(jobId, DEFAULT_LOCK_TTL);
    }

    /**
     * Try to acquire a lock for the specified job with a custom TTL.
     *
     * @param jobId The job identifier
     * @param ttl   Time-to-live for the lock
     * @return true if lock was acquired, false if another replica holds it
     */
    @Transactional
    public boolean tryAcquire(String jobId, Duration ttl) {
        Instant now = Instant.now();

        // Ensure the lock row exists
        JobLock lock = dao.findById(jobId).orElseGet(() -> {
            JobLock newLock = new JobLock();
            newLock.setJobId(jobId);
            return dao.save(newLock);
        });

        // Try to acquire with SELECT FOR UPDATE SKIP LOCKED
        Optional<JobLock> acquired = dao.tryAcquire(jobId, now);
        if (acquired.isEmpty()) {
            // Check if another replica holds it (for logging)
            if (lock.getLockedBy() != null && !lock.getLockedBy().equals(instanceId)) {
                logger.debug("Job '{}' is already running by instance '{}' (expires at {})",
                        jobId, lock.getLockedBy(), lock.getExpiresAt());
            }
            return false;
        }

        // Mark as locked by this instance
        JobLock locked = acquired.get();
        locked.setLockedAt(now);
        locked.setLockedBy(instanceId);
        locked.setExpiresAt(now.plus(ttl));
        dao.saveAndFlush(locked);

        logger.info("Acquired lock for job '{}' (expires at {})", jobId, locked.getExpiresAt());
        return true;
    }

    /**
     * Release a lock for the specified job.
     * Idempotent: safe to call even if lock wasn't held.
     *
     * @param jobId The job identifier
     */
    @Transactional
    public void release(String jobId) {
        int released = dao.release(jobId, instanceId);
        if (released > 0) {
            logger.info("Released lock for job '{}'", jobId);
        } else {
            logger.debug("Lock for job '{}' was not held by this instance", jobId);
        }
    }

    /**
     * Renew a lock's expiration time. Used by long-running jobs to prevent
     * lock expiry while they're still working.
     *
     * @param jobId The job identifier
     * @param ttl   New TTL from now
     * @return true if renewed, false if lock was lost (another replica took it)
     */
    @Transactional
    public boolean renew(String jobId, Duration ttl) {
        int renewed = dao.renew(jobId, instanceId, Instant.now().plus(ttl));
        if (renewed == 0) {
            logger.warn("Failed to renew lock for job '{}' - lock may have been lost", jobId);
            return false;
        }
        return true;
    }

    /**
     * Clean up expired locks from crashed replicas.
     * Should be called periodically.
     */
    @Transactional
    public void cleanupExpired() {
        int cleaned = dao.cleanupExpired(Instant.now());
        if (cleaned > 0) {
            logger.info("Cleaned up {} expired job locks", cleaned);
        }
    }
}
