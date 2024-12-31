package com.dns;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNS多级缓存实现
 * 包含：记录缓存、NS服务器缓存、否定缓存
 */
public class DNSCache {
    // 记录缓存：domain+type -> CacheEntry
    private final Map<String, CacheEntry> recordCache = new ConcurrentHashMap<>();
    // NS服务器缓存：domain -> NSCacheEntry
    private final Map<String, NSCacheEntry> nsCache = new ConcurrentHashMap<>();
    // 否定缓存：domain+type -> CacheEntry
    private final Map<String, CacheEntry> negativeCache = new ConcurrentHashMap<>();
    
    // 缓存清理的时间间隔（毫秒）
    private static final long CLEANUP_INTERVAL = 300_000; // 5分钟
    
    public DNSCache() {
        // 启动定期清理任务
        startCleanupTask();
    }
    
    /**
     * 获取DNS记录缓存
     */
    public List<String> get(String domain, String recordType) {
        String key = getCacheKey(domain, recordType);
        CacheEntry entry = recordCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.getRecords();
        }
        return null;
    }
    
    /**
     * 缓存DNS记录
     */
    public void put(String domain, String recordType, List<String> records, long ttl) {
        String key = getCacheKey(domain, recordType);
        recordCache.put(key, new CacheEntry(records, ttl));
    }
    
    /**
     * 获取NS服务器缓存
     */
    public List<String> getNSServers(String domain) {
        NSCacheEntry entry = nsCache.get(domain);
        if (entry != null && !entry.isExpired()) {
            return entry.getNameservers();
        }
        return null;
    }
    
    /**
     * 缓存NS服务器信息
     */
    public void putNSServers(String domain, List<String> nameservers, long ttl) {
        nsCache.put(domain, new NSCacheEntry(nameservers, ttl));
    }
    
    /**
     * 获取否定缓存（NXDOMAIN等）
     */
    public boolean isNegativeCached(String domain, String recordType) {
        String key = getCacheKey(domain, recordType);
        CacheEntry entry = negativeCache.get(key);
        return entry != null && !entry.isExpired();
    }
    
    /**
     * 添加否定缓存
     */
    public void putNegative(String domain, String recordType, long ttl) {
        String key = getCacheKey(domain, recordType);
        negativeCache.put(key, new CacheEntry(null, ttl));
    }
    
    private String getCacheKey(String domain, String recordType) {
        return domain.toLowerCase() + ":" + recordType;
    }
    
    private void startCleanupTask() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL);
                    cleanup();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        // 清理记录缓存
        recordCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // 清理NS缓存
        nsCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // 清理否定缓存
        negativeCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * 缓存条目基类
     */
    private static class CacheEntry {
        private final List<String> records;
        private final long expirationTime;
        
        public CacheEntry(List<String> records, long ttl) {
            this.records = records;
            this.expirationTime = System.currentTimeMillis() + (ttl * 1000);
        }
        
        public List<String> getRecords() {
            return records;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * NS服务器缓存条目
     */
    private static class NSCacheEntry {
        private final List<String> nameservers;
        private final long expirationTime;
        
        public NSCacheEntry(List<String> nameservers, long ttl) {
            this.nameservers = nameservers;
            this.expirationTime = System.currentTimeMillis() + (ttl * 1000);
        }
        
        public List<String> getNameservers() {
            return nameservers;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
}
