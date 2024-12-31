package com.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DNS解析器实现，结合BIND风格的性能统计
 * 特点：
 * 1. 多级缓存（记录缓存、NS缓存、否定缓存）
 * 2. 服务器性能统计和智能选择
 * 3. 并行查询策略
 * 4. 完善的错误处理
 */
public class DNSResolver {
    private static final Logger logger = LoggerFactory.getLogger(DNSResolver.class);
    
    private final DNSCache cache;
    private final List<String> rootServers;
    private final ServerPerformance serverPerformance;
    
    // 查询相关的配置参数
    private static final int DEFAULT_TIMEOUT = 3000;     // 默认超时时间（毫秒）
    private static final int MAX_RETRIES = 2;           // 最大重试次数
    private static final int UDP_SIZE = 512;            // UDP包大小限制
    private static final int PRIMARY_SERVERS = 2;       // 优先查询的服务器数量
    private static final int BACKUP_SERVERS = 1;        // 备份服务器数量
    
    public DNSResolver() {
        this.cache = new DNSCache();
        this.serverPerformance = new ServerPerformance();
        this.rootServers = Arrays.asList(
            "198.41.0.4",     // a.root-servers.net
            "199.9.14.201",   // b.root-servers.net
            "192.33.4.12",    // c.root-servers.net
            "199.7.91.13",    // d.root-servers.net
            "192.203.230.10", // e.root-servers.net
            "192.5.5.241",    // f.root-servers.net
            "192.112.36.4",   // g.root-servers.net
            "198.97.190.53",  // h.root-servers.net
            "192.36.148.17",  // i.root-servers.net
            "192.58.128.30",  // j.root-servers.net
            "193.0.14.129",   // k.root-servers.net
            "199.7.83.42",    // l.root-servers.net
            "202.12.27.33"    // m.root-servers.net
        );
    }
    
    /**
     * 解析指定域名的指定类型记录
     * @param domain 要解析的域名
     * @param recordType 记录类型（A, AAAA, CNAME, MX, NS等）
     * @return 解析结果列表
     */
    public List<String> resolve(String domain, String recordType) {
        try {
            // 确保域名是绝对域名（以点结尾）
            String absoluteDomain = domain;
            if (!absoluteDomain.endsWith(".")) {
                absoluteDomain = absoluteDomain + ".";
            }
            
            int type = convertRecordType(recordType);
            return recursiveResolve(absoluteDomain, type);
        } catch (Exception e) {
            logger.error("解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 递归解析域名
     * @param domain 要解析的域名
     * @param type 记录类型
     * @return 解析结果列表
     */
    private List<String> recursiveResolve(String domain, int type) {
        // 1. 检查缓存
        List<String> cachedResults = cache.get(domain, Type.string(type));
        if (cachedResults != null) {
            logger.debug("命中缓存: {} ({})", domain, Type.string(type));
            return cachedResults;
        }
        
        try {
            // 2. 从根服务器开始查询
            List<String> nameservers = new ArrayList<>(rootServers);
            String qname = domain;
            
            while (!nameservers.isEmpty()) {
                // 发送优化的查询（同时查询记录和NS）
                Message response = queryOptimized(nameservers, qname, type);
                if (response == null) {
                    return Collections.emptyList();
                }
                
                // 先检查是否有目标记录
                List<String> results = extractRecordsFromSection(response.getSectionArray(Section.ANSWER), type);
                if (!results.isEmpty()) {
                    cache.put(domain, Type.string(type), results, 300);
                    return results;
                }
                
                // 检查授权部分的NS记录
                List<String> nextNameservers = extractNameserversFromResponse(response);
                if (nextNameservers.isEmpty()) {
                    break;
                }
                
                nameservers = nextNameservers;
                logger.debug("下一层名称服务器: {}", nameservers);
            }
            
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("递归解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 发送优化的查询，同时获取记录和NS信息
     */
    private Message queryOptimized(List<String> nameservers, String domain, int type) throws TextParseException {
        Record question = Record.newRecord(Name.fromString(domain), type, DClass.IN);
        Message lastResponse = null;
        Exception lastException = null;
        
        // 重试策略
        int maxRetries = 2;  // 最大重试次数
        int currentTry = 0;
        
        while (currentTry <= maxRetries && !nameservers.isEmpty()) {
            // 每次重试都重新选择服务器
            List<String> selectedServers = serverPerformance.selectServers(nameservers);
            if (selectedServers.isEmpty()) {
                break;
            }
            
            for (String nameserver : selectedServers) {
                try {
                    long startTime = System.currentTimeMillis();
                    
                    // 创建支持EDNS的消息
                    Message query = Message.newQuery(question);
                    OPTRecord opt = new OPTRecord(4096, 0, 0);  // 支持更大的响应
                    query.addRecord(opt, Section.ADDITIONAL);
                    
                    // 发送查询
                    serverPerformance.startQuery(nameserver);
                    SimpleResolver resolver = new SimpleResolver(nameserver);
                    resolver.setTimeout(DEFAULT_TIMEOUT / 1000);  // 设置超时（秒）
                    
                    Message response = resolver.send(query);
                    long rtt = System.currentTimeMillis() - startTime;
                    
                    // 更新服务器统计
                    serverPerformance.recordSuccess(nameserver, rtt);
                    serverPerformance.endQuery(nameserver);
                    
                    // 检查响应码
                    int rcode = response.getRcode();
                    if (rcode == Rcode.NOERROR) {
                        return response;  // 成功获取响应
                    } else if (rcode == Rcode.NXDOMAIN) {
                        // 域名不存在，这是一个明确的响应
                        return response;
                    } else if (rcode == Rcode.SERVFAIL) {
                        // 服务器失败，可能需要重试其他服务器
                        lastResponse = response;
                        continue;
                    } else {
                        // 其他错误码，记录响应
                        lastResponse = response;
                        logger.warn("服务器 {} 返回错误码: {}", nameserver, Rcode.string(rcode));
                    }
                    
                } catch (SocketTimeoutException e) {
                    // 超时，记录失败并继续尝试下一个服务器
                    logger.debug("查询 {} 超时", nameserver);
                    serverPerformance.recordFailure(nameserver);
                    lastException = e;
                    
                } catch (Exception e) {
                    // 其他错误
                    logger.warn("查询 {} 失败: {}", nameserver, e.getMessage());
                    serverPerformance.recordFailure(nameserver);
                    lastException = e;
                    
                } finally {
                    serverPerformance.endQuery(nameserver);
                }
            }
            
            // 一轮查询完成后，如果需要重试
            currentTry++;
            if (currentTry <= maxRetries) {
                logger.debug("第 {} 次重试查询 {}", currentTry, domain);
                // 短暂等待后重试
                try {
                    Thread.sleep(100 * currentTry);  // 递增等待时间
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // 所有尝试都失败后
        if (lastResponse != null) {
            return lastResponse;  // 返回最后一个有效响应
        } else if (lastException != null) {
            logger.error("所有查询尝试都失败: {}", lastException.getMessage());
        }
        
        // 构造一个错误响应
        Message errorResponse = new Message();
        errorResponse.getHeader().setRcode(Rcode.SERVFAIL);
        return errorResponse;
    }
    
    /**
     * 从响应的指定部分提取记录，处理CNAME链
     */
    private List<String> extractRecordsFromSection(Record[] records, int type) {
        if (records == null) {
            return Collections.emptyList();
        }

        List<String> results = new ArrayList<>();
        Set<String> seenCnames = new HashSet<>();  // 防止CNAME循环

        // 首先收集所有记录
        for (Record r : records) {
            if (r.getType() == type) {
                results.add(r.rdataToString());
            } else if (r.getType() == Type.CNAME) {
                CNAMERecord cname = (CNAMERecord) r;
                String target = cname.getTarget().toString();
                // 移除末尾的点
                if (target.endsWith(".")) {
                    target = target.substring(0, target.length() - 1);
                }
                
                // 如果是查询A记录且遇到CNAME，需要解析CNAME目标
                if (type == Type.A && !seenCnames.contains(target)) {
                    seenCnames.add(target);
                    try {
                        List<String> resolvedAddresses = recursiveResolve(target, Type.A);
                        if (!resolvedAddresses.isEmpty()) {
                            results.addAll(resolvedAddresses);
                        } else {
                            // 如果解析失败，至少返回CNAME记录
                            results.add(target);
                        }
                    } catch (Exception e) {
                        logger.warn("解析CNAME目标 {} 失败: {}", target, e.getMessage());
                        results.add(target);
                    }
                } else {
                    // 对于非A记录查询，直接返回CNAME目标
                    results.add(target);
                }
            }
        }

        return results;
    }
    
    /**
     * 从响应中提取名称服务器信息
     */
    private List<String> extractNameserversFromResponse(Message response) {
        Set<String> nameservers = new LinkedHashSet<>();
        
        // 1. 检查授权部分的NS记录
        Record[] authority = response.getSectionArray(Section.AUTHORITY);
        if (authority != null) {
            for (Record r : authority) {
                if (r.getType() == Type.NS) {
                    NSRecord ns = (NSRecord) r;
                    String nsName = ns.getTarget().toString();
                    // 移除末尾的点
                    if (nsName.endsWith(".")) {
                        nsName = nsName.substring(0, nsName.length() - 1);
                    }
                    nameservers.add(nsName);
                }
            }
        }
        
        // 2. 检查附加部分的A记录
        Map<String, String> glueRecords = new HashMap<>();
        Record[] additional = response.getSectionArray(Section.ADDITIONAL);
        if (additional != null) {
            for (Record r : additional) {
                if (r.getType() == Type.A) {
                    ARecord a = (ARecord) r;
                    String name = a.getName().toString();
                    if (name.endsWith(".")) {
                        name = name.substring(0, name.length() - 1);
                    }
                    
                    if (nameservers.contains(name)) {
                        String ip = a.getAddress().getHostAddress();
                        glueRecords.put(name, ip);
                        nameservers.add(ip);
                    }
                }
            }
        }
        
        // 3. 优先使用粘合记录
        List<String> results = new ArrayList<>();
        for (String ns : nameservers) {
            String ip = glueRecords.get(ns);
            if (ip != null) {
                results.add(ip);
            } else {
                // 如果没有粘合记录，需要解析NS记录
                try {
                    List<String> resolved = recursiveResolve(ns, Type.A);
                    if (!resolved.isEmpty()) {
                        results.addAll(resolved);
                    }
                } catch (Exception e) {
                    logger.warn("解析NS {} 失败: {}", ns, e.getMessage());
                }
            }
        }
        
        return results;
    }
    
    /**
     * 向单个服务器发送查询
     * @param domain 要查询的域名
     * @param nameserver 要查询的服务器
     * @param type 记录类型
     * @return 查询结果列表
     */
    private List<String> query(String domain, String nameserver, int type) throws IOException {
        // 确保域名是绝对域名（以点结尾）
        String absoluteDomain = domain;
        if (!absoluteDomain.endsWith(".")) {
            absoluteDomain = absoluteDomain + ".";
        }
        
        // 1. 创建查询消息
        Message query = Message.newQuery(Record.newRecord(Name.fromString(absoluteDomain), type, DClass.IN));
        
        // 2. 发送查询并获取响应
        SimpleResolver resolver = new SimpleResolver(nameserver);
        resolver.setTCP(false);  // 使用UDP
        resolver.setTimeout(DEFAULT_TIMEOUT);
        
        Message response = resolver.send(query);
        
        // 3. 处理响应
        if (response.getRcode() != Rcode.NOERROR) {
            return Collections.emptyList();
        }
        
        // 4. 提取记录
        if (type == Type.NS) {
            return extractNameservers(response);
        } else {
            return extractRecords(response.getSectionArray(Section.ANSWER));
        }
    }
    
    /**
     * 从响应中提取名称服务器地址
     * 优先使用A记录中的IP地址，如果没有A记录，则解析NS记录中的域名
     */
    private List<String> extractNameservers(Message response) {
        Set<String> nameservers = new HashSet<>();
        Map<String, String> nsNameToIP = new HashMap<>();
        
        // 1. 首先收集所有NS记录的域名
        for (Record record : response.getSectionArray(Section.AUTHORITY)) {
            if (record instanceof NSRecord) {
                NSRecord ns = (NSRecord) record;
                String nsName = ns.getTarget().toString();
                if (nsName.endsWith(".")) {
                    nsName = nsName.substring(0, nsName.length() - 1);
                }
                nsNameToIP.put(nsName, null);  // 初始化为null，后面填充IP
            }
        }
        
        // 2. 从Additional部分获取NS服务器的IP地址
        for (Record record : response.getSectionArray(Section.ADDITIONAL)) {
            if (record instanceof ARecord) {
                ARecord a = (ARecord) record;
                String hostname = a.getName().toString();
                if (hostname.endsWith(".")) {
                    hostname = hostname.substring(0, hostname.length() - 1);
                }
                
                if (nsNameToIP.containsKey(hostname)) {
                    String ip = a.getAddress().getHostAddress();
                    nsNameToIP.put(hostname, ip);
                    nameservers.add(ip);
                }
            }
        }
        
        // 3. 对于没有IP的NS记录，尝试解析它们的IP
        for (Map.Entry<String, String> entry : nsNameToIP.entrySet()) {
            if (entry.getValue() == null) {
                try {
                    // 使用系统DNS解析器解析NS域名
                    InetAddress[] addresses = InetAddress.getAllByName(entry.getKey());
                    for (InetAddress addr : addresses) {
                        String ip = addr.getHostAddress();
                        nameservers.add(ip);
                        logger.debug("解析NS域名 {} 为IP: {}", entry.getKey(), ip);
                    }
                } catch (UnknownHostException e) {
                    logger.warn("无法解析NS域名: {}", entry.getKey());
                }
            }
        }
        
        // 4. 如果还是没有找到任何IP，返回一个根服务器作为后备
        if (nameservers.isEmpty()) {
            nameservers.add(rootServers.get(0));
            logger.debug("使用根服务器作为后备: {}", rootServers.get(0));
        }
        
        List<String> result = new ArrayList<>(nameservers);
        logger.debug("提取的NS服务器: {}", result);
        return result;
    }
    
    /**
     * 从记录数组中提取记录值
     */
    private List<String> extractRecords(Record[] records) {
        List<String> results = new ArrayList<>();
        for (Record record : records) {
            if (record instanceof ARecord) {
                results.add(((ARecord) record).getAddress().getHostAddress());
            } else if (record instanceof AAAARecord) {
                results.add(((AAAARecord) record).getAddress().getHostAddress());
            } else if (record instanceof CNAMERecord) {
                results.add(((CNAMERecord) record).getTarget().toString());
            } else if (record instanceof MXRecord) {
                MXRecord mx = (MXRecord) record;
                results.add(String.format("%d %s", mx.getPriority(), mx.getTarget()));
            } else if (record instanceof NSRecord) {
                results.add(((NSRecord) record).getTarget().toString());
            }
        }
        return results;
    }
    
    private int convertRecordType(String recordType) {
        switch (recordType.toUpperCase()) {
            case "A": return Type.A;
            case "AAAA": return Type.AAAA;
            case "CNAME": return Type.CNAME;
            case "MX": return Type.MX;
            case "NS": return Type.NS;
            default:
                throw new IllegalArgumentException("Unsupported record type: " + recordType);
        }
    }
    
    /**
     * 获取根服务器列表
     */
    public List<String> getRootServers() {
        return new ArrayList<>(rootServers);
    }
    
    /**
     * 获取服务器性能统计实例
     */
    public ServerPerformance getServerPerformance() {
        return serverPerformance;
    }
}
