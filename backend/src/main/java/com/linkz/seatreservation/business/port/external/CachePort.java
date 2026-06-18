package com.tpthinh.seatreservation.business.port.external;

import java.util.List;

public interface CachePort {
    <T> T get(String key, Class<T> clazz);
    <T> List<T> multiGet(List<String> keys, Class<T> clazz);
    void put(String key, Object value, long ttlSeconds);
    void evict(String key);
    boolean setIfAbsent(String key, String value, long ttlSeconds);
}
