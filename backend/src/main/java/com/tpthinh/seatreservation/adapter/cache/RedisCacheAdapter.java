package com.tpthinh.seatreservation.adapter.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpthinh.seatreservation.business.port.external.CachePort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisCacheAdapter implements CachePort {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheAdapter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T get(String key, Class<T> clazz) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize cache key: " + key, e);
        }
    }

    @Override
    public <T> List<T> multiGet(List<String> keys, Class<T> clazz) {
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return new ArrayList<>();
        }
        List<T> results = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                results.add(null);
            } else {
                try {
                    results.add(objectMapper.readValue(value, clazz));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize multiGet value", e);
                }
            }
        }
        return results;
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize cache key: " + key, e);
        }
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }
}
