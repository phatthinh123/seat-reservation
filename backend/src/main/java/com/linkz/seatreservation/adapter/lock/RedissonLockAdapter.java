package com.linkz.seatreservation.adapter.lock;

import com.linkz.seatreservation.business.port.out.DistributedLockPort;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class RedissonLockAdapter implements DistributedLockPort {
    private final RedissonClient redisson;

    public RedissonLockAdapter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public boolean tryLock(String key, long waitMs, long ttlMs) {
        RLock lock = redisson.getLock("seat-lock:" + key);
        try {
            return lock.tryLock(waitMs, ttlMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        RLock lock = redisson.getLock("seat-lock:" + key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
