package com.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DNSServer {
    private static final Logger logger = LoggerFactory.getLogger(DNSServer.class);
    private static final int DEFAULT_PORT = 53;
    private static final int MAX_UDP_SIZE = 512;
    private static final int THREAD_POOL_SIZE = 32;

    private final DNSResolver resolver;
    private final int port;
    private final ExecutorService executorService;
    private final AtomicBoolean running;
    private DatagramSocket socket;

    public DNSServer(DNSResolver resolver) {
        this(resolver, DEFAULT_PORT);
    }

    public DNSServer(DNSResolver resolver, int port) {
        this.resolver = resolver;
        this.port = port;
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.running = new AtomicBoolean(false);
    }

    public void start() throws SocketException {
        if (running.get()) {
            return;
        }

        socket = new DatagramSocket(port);
        running.set(true);
        logger.info("DNS服务器启动在端口 {}", port);

        // 启动主接收循环
        Thread receiverThread = new Thread(this::receiveLoop);
        receiverThread.setName("DNS-Receiver");
        receiverThread.start();
    }

    public void stop() {
        if (!running.get()) {
            return;
        }

        running.set(false);
        executorService.shutdown();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        logger.info("DNS服务器已停止");
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_UDP_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running.get()) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                // 提交到线程池处理
                executorService.submit(() -> handleQuery(data, packet.getAddress(), packet.getPort()));
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("接收查询失败: {}", e.getMessage());
                }
            }
        }
    }

    private void handleQuery(byte[] queryData, InetAddress clientAddress, int clientPort) {
        try {
            // 解析查询消息
            Message query = new Message(queryData);
            Record question = query.getQuestion();

            if (question == null) {
                logger.warn("收到无效查询");
                return;
            }

            String domain = question.getName().toString();
            int type = question.getType();

            // 使用解析器获取结果
            List<String> results = resolver.resolve(domain, Type.string(type));

            // 构建响应
            Message response = buildResponse(query, results);

            // 发送响应
            byte[] responseData = response.toWire();
            DatagramPacket responsePacket = new DatagramPacket(
                responseData, responseData.length, clientAddress, clientPort);
            socket.send(responsePacket);

            logger.debug("响应发送到 {}:{} - {} {}", 
                clientAddress.getHostAddress(), clientPort, domain, Type.string(type));

        } catch (IOException e) {
            logger.error("处理查询失败: {}", e.getMessage());
        }
    }

    private Message buildResponse(Message query, List<String> results) throws IOException {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);      // 这是一个响应
        response.getHeader().setFlag(Flags.RA);      // 递归可用
        response.addRecord(query.getQuestion(), Section.QUESTION);

        Record question = query.getQuestion();
        if (results.isEmpty()) {
            response.getHeader().setRcode(Rcode.NXDOMAIN);
        } else {
            response.getHeader().setRcode(Rcode.NOERROR);
            for (String result : results) {
                Record record = null;
                switch (question.getType()) {
                    case Type.A:
                        record = new ARecord(question.getName(), question.getDClass(),
                            3600, InetAddress.getByName(result));
                        break;
                    case Type.AAAA:
                        record = new AAAARecord(question.getName(), question.getDClass(),
                            3600, InetAddress.getByName(result));
                        break;
                    case Type.CNAME:
                        record = new CNAMERecord(question.getName(), question.getDClass(),
                            3600, Name.fromString(result));
                        break;
                    case Type.NS:
                        record = new NSRecord(question.getName(), question.getDClass(),
                            3600, Name.fromString(result));
                        break;
                    case Type.MX:
                        String[] parts = result.split(" ");
                        if (parts.length == 2) {
                            record = new MXRecord(question.getName(), question.getDClass(),
                                3600, Integer.parseInt(parts[0]), Name.fromString(parts[1]));
                        }
                        break;
                    default:
                        logger.warn("不支持的记录类型: {}", Type.string(question.getType()));
                }
                
                if (record != null) {
                    response.addRecord(record, Section.ANSWER);
                }
            }
        }

        return response;
    }
}
