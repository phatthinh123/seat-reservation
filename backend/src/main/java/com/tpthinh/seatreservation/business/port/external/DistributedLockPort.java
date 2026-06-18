package com.tpthinh.seatreservation.business.port.external;

public interface DistributedLockPort {
    boolean tryLock(String key, long waitMs, long ttlMs);
    void unlock(String key);
}
