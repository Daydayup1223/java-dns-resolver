package com.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ServerPerformance {
    private static final Logger logger = LoggerFactory.getLogger(ServerPerformance.class);
    
    // BIND样式的配置参数
    private static final int BUCKET_COUNT = 64;           // RTT桶数量
    private static final double RTT_MAX = 4000.0;        // 最大RTT (ms)
    private static final double RTT_INITIAL = 2000.0;    // 初始RTT (ms)
    private static final double RTT_ALPHA = 0.875;       // RTT平滑因子
    private static final double RTT_BETA = 0.25;         // RTT变化因子
    private static final double FAILURE_PENALTY = 2.0;   // 失败惩罚因子
    private static final int MAX_FAILURES = 3;           // 最大失败次数
    private static final long RETRY_INTERVAL = 30000;    // 重试间隔 (ms)
    private static final int UNTESTED_CHANCE = 10;       // 未测试服务器被选中的概率 (10%)
    
    // 服务器状态
    private enum ServerStatus {
        AVAILABLE,    // 可用
        NEGATIVE,     // 暂时失败
        EXPIRED,      // 已过期
        UNTESTED     // 未测试
    }
    
    // 服务器统计信息
    private static class ServerStats {
        private volatile double srtt = RTT_INITIAL;  // 平滑往返时间
        private volatile double rttvar = RTT_INITIAL / 2;  // RTT变化
        private final AtomicInteger failures = new AtomicInteger(0);
        private final AtomicInteger activeQueries = new AtomicInteger(0);
        private volatile ServerStatus status = ServerStatus.UNTESTED;  // 初始状态为未测试
        private volatile long lastQueryTime = 0;
        private volatile long expireTime = 0;
        
        public void updateRTT(long rtt) {
            // 如果是第一次测试
            if (status == ServerStatus.UNTESTED) {
                srtt = rtt;
                rttvar = rtt / 2;
                status = ServerStatus.AVAILABLE;
            } else {
                // 使用BIND的RTT计算方法
                double diff = srtt - rtt;
                rttvar = (1 - RTT_BETA) * rttvar + RTT_BETA * Math.abs(diff);
                srtt = (1 - RTT_ALPHA) * srtt + RTT_ALPHA * rtt;
            }
            
            failures.set(0);
            lastQueryTime = System.currentTimeMillis();
            logger.debug("服务器RTT更新 - SRTT: {}, RTTVAR: {}", srtt, rttvar);
        }
        
        public void recordFailure() {
            int currentFailures = failures.incrementAndGet();
            srtt *= FAILURE_PENALTY;  // 增加RTT作为惩罚
            
            if (currentFailures >= MAX_FAILURES) {
                status = ServerStatus.NEGATIVE;
                expireTime = System.currentTimeMillis() + RETRY_INTERVAL;
            }
            
            lastQueryTime = System.currentTimeMillis();
        }
        
        public boolean isAvailable() {
            if (status == ServerStatus.NEGATIVE) {
                if (System.currentTimeMillis() >= expireTime) {
                    status = ServerStatus.AVAILABLE;
                    failures.set(0);
                    return true;
                }
                return false;
            }
            return true;
        }
        
        public void startQuery() {
            activeQueries.incrementAndGet();
        }
        
        public void endQuery() {
            activeQueries.decrementAndGet();
        }
        
        public double getEffectiveRTT() {
            // 计算有效RTT，考虑RTT变化
            double effectiveRTT = srtt + 4 * rttvar;
            
            // 考虑活跃查询的影响
            effectiveRTT *= (1.0 + activeQueries.get() * 0.1);
            
            // 考虑最后查询时间
            long timeSinceLastQuery = System.currentTimeMillis() - lastQueryTime;
            if (timeSinceLastQuery > 60000) {  // 1分钟
                effectiveRTT *= (1.0 + timeSinceLastQuery / 60000.0 * 0.1);
            }
            
            return effectiveRTT;
        }
        
        public boolean isUntested() {
            return status == ServerStatus.UNTESTED;
        }
    }
    
    private final ConcurrentHashMap<String, ServerStats> stats = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    public List<String> selectServers(List<String> nameservers) {
        List<String> selected = new ArrayList<>();
        int neededServers = 2;  // 目标服务器数量
        
        // 1. 分离未测试和已测试的服务器
        List<String> untestedServers = new ArrayList<>();
        List<String> testedServers = new ArrayList<>();
        
        for (String server : nameservers) {
            ServerStats serverStats = stats.get(server);
            if (serverStats == null || serverStats.isUntested()) {
                untestedServers.add(server);
            } else if (serverStats.isAvailable()) {
                testedServers.add(server);
            }
        }
        
        // 2. 首先尝试从已测试的服务器中选择
        if (!testedServers.isEmpty()) {
            // 创建RTT桶
            RttBucket[] buckets = new RttBucket[BUCKET_COUNT];
            double bucketSize = RTT_MAX / BUCKET_COUNT;
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = new RttBucket(i * bucketSize, (i + 1) * bucketSize);
            }
            
            // 将已测试的服务器分配到桶中
            for (String server : testedServers) {
                ServerStats serverStats = stats.get(server);
                double effectiveRTT = serverStats.getEffectiveRTT();
                int bucketIndex = (int) (effectiveRTT * BUCKET_COUNT / RTT_MAX);
                bucketIndex = Math.min(bucketIndex, BUCKET_COUNT - 1);
                buckets[bucketIndex].addServer(server);
            }
            
            // 从低RTT桶开始选择
            for (int i = 0; i < BUCKET_COUNT && selected.size() < neededServers; i++) {
                RttBucket bucket = buckets[i];
                if (bucket.isEmpty()) {
                    continue;
                }
                
                List<String> bucketServers = bucket.getServers();
                while (!bucketServers.isEmpty() && selected.size() < neededServers) {
                    int index = random.nextInt(bucketServers.size());
                    selected.add(bucketServers.get(index));
                    bucketServers.remove(index);
                }
            }
        }
        
        // 3. 如果还需要更多服务器，考虑使用未测试的服务器
        if (selected.size() < neededServers && !untestedServers.isEmpty()) {
            // 给未测试服务器10%的机会
            if (selected.isEmpty() || random.nextInt(100) < UNTESTED_CHANCE) {
                Collections.shuffle(untestedServers);
                selected.add(untestedServers.get(0));
                logger.debug("选择未测试服务器: {}", untestedServers.get(0));
            }
        }
        
        // 4. 如果还是没有足够的服务器，使用任何可用的服务器
        if (selected.isEmpty()) {
            selected.add(nameservers.get(random.nextInt(nameservers.size())));
            logger.debug("使用随机服务器作为后备: {}", selected.get(0));
        }
        
        logger.debug("选择的服务器: {} (从 {} 个候选服务器中选择)", 
                    selected, nameservers.size());
        return selected;
    }
    
    // RTT桶，用于服务器分组
    private static class RttBucket {
        private final List<String> servers = new ArrayList<>();
        private final double minRtt;
        private final double maxRtt;
        
        public RttBucket(double minRtt, double maxRtt) {
            this.minRtt = minRtt;
            this.maxRtt = maxRtt;
        }
        
        public void addServer(String server) {
            servers.add(server);
        }
        
        public List<String> getServers() {
            return servers;
        }
        
        public boolean isEmpty() {
            return servers.isEmpty();
        }
    }
    
    public void recordSuccess(String server, long rtt) {
        stats.computeIfAbsent(server, k -> new ServerStats()).updateRTT(rtt);
    }
    
    public void recordFailure(String server) {
        stats.computeIfAbsent(server, k -> new ServerStats()).recordFailure();
    }
    
    public void startQuery(String server) {
        stats.computeIfAbsent(server, k -> new ServerStats()).startQuery();
    }
    
    public void endQuery(String server) {
        stats.computeIfAbsent(server, k -> new ServerStats()).endQuery();
    }
}
