package noticenow;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NoticeServer {

    private static final Gson gson = new Gson();
    private static JedisPool jedisPool;
    private static final Map<String, PrintWriter> sseClients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        // Redis 데이터베이스 초기화
        initializeRedis();

        // 1분마다 모든 사용자의 모든 사이트를 확인하는 백그라운드 작업 시작
        startBackgroundChecker();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/add-site", new AddSiteHandler());
        server.createContext("/api/delete-site", new DeleteSiteHandler());
        server.createContext("/api/events", new SseHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ 서버가 http://localhost:8080 에서 시작되었습니다.");
        System.out.println("Cloudtype 배포 주소로 접속해주세요.");
    }

    private static void initializeRedis() {
        // Cloudtype 환경 변수에서 Redis 연결 정보를 가져옵니다.
        String redisHost = System.getenv("REDIS_HOST");
        String redisPortStr = System.getenv("REDIS_PORT");
        String redisPassword = System.getenv("REDIS_PASSWORD");

        if (redisHost == null || redisPortStr == null || redisPassword == null) {
            System.err.println("❌ Redis 환경 변수(REDIS_HOST, REDIS_PORT, REDIS_PASSWORD)가 설정되지 않았습니다.");
            System.err.println("로컬 테스트를 위해 기본값(localhost:6379)으로 연결을 시도합니다.");
            // 로컬 테스트용 기본값
            redisHost = "localhost";
            redisPortStr = "6379";
            redisPassword = null; // 로컬 Redis에 비밀번호가 없다면 null
        }

        int redisPort = Integer.parseInt(redisPortStr);
        jedisPool = new JedisPool(redisHost, redisPort, null, redisPassword);
        System.out.println("✅ Redis DB가 성공적으로 연결되었습니다.");
    }

    private static void startBackgroundChecker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(NoticeServer::checkAllSites, 1, 1, TimeUnit.MINUTES);
    }

    private static void checkAllSites() {
        System.out.println("🔄 백그라운드 확인 작업 시작...");
        try (Jedis jedis = jedisPool.getResource()) {
            // "user:[학번]" 패턴을 가진 모든 키(모든 사용자)를 가져옵니다.
            for (String userKey : jedis.keys("user:*")) {
                String studentId = userKey.substring(5); // "user:" 부분 제거
                String userDataJson = jedis.get(userKey);
                UserData userData = gson.fromJson(userDataJson, UserData.class);

                boolean needsUpdate = false;
                for (MonitoredSite site : userData.getSites()) {
                    try {
                        Document doc = Jsoup.connect(site.getUrl()).get();
                        Elements currentTitles = doc.select("tr > td:nth-child(2)");
                        List<String> newTitlesList = new ArrayList<>();
                        for (Element titleElement : currentTitles) {
                            newTitlesList.add(titleElement.text());
                        }

                        List<String> oldTitles = site.getLastTitles();
                        if (oldTitles != null && !oldTitles.equals(newTitlesList)) {
                            List<String> addedTitles = new ArrayList<>(newTitlesList);
                            addedTitles.removeAll(oldTitles);

                            if (!addedTitles.isEmpty()) {
                                needsUpdate = true;
                                System.out.printf("🚨 새 공지 발견! [%s] %s%n", studentId, site.getName());
                                for (String newTitle : addedTitles) {
                                    String notificationJson = String.format(
                                            "{\"siteName\": \"%s\", \"title\": \"%s\"}",
                                            site.getName().replace("\"", "\\\""),
                                            newTitle.replace("\"", "\\\"")
                                    );
                                    sendSseEvent(studentId, notificationJson);
                                }
                            }
                        }
                        site.setLastTitles(newTitlesList);

                    } catch (IOException e) {
                        System.err.printf("❌ 사이트 확인 중 오류 [%s]: %s%n", site.getName(), e.getMessage());
                    }
                }
                // 변경된 lastTitles 정보를 DB에 다시 저장합니다.
                if (needsUpdate) {
                    jedis.set(userKey, gson.toJson(userData));
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Redis 작업 중 오류 발생: " + e.getMessage());
        }
    }

    // --- 핸들러들 및 유틸리티 메소드 (이하 코드) ---

    private static void sendSseEvent(String studentId, String data) {
        PrintWriter writer = sseClients.get(studentId);
        if (writer != null) {
            writer.write("data: " + data + "\n\n");
            writer.flush();
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            InputStream inputStream = NoticeServer.class.getResourceAsStream(path);

            if (inputStream == null) {
                String response = "404 (Not Found)\n";
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                final byte[] buffer = new byte[1024];
                int count;
                while ((count = inputStream.read(buffer)) >= 0) {
                    os.write(buffer, 0, count);
                }
                os.close();
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");
            String userKey = "user:" + studentId;
            UserData userData;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    userData = new UserData();
                    jedis.set(userKey, gson.toJson(userData));
                } else {
                    userData = gson.fromJson(userDataJson, UserData.class);
                }
            }
            sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
        }
    }

    static class AddSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");
            String siteName = params.get("siteName");
            String siteUrl = params.get("siteUrl");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"로그인 정보가 없습니다.\"}");
                    return;
                }
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                if (userData.getSites().size() >= 3) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"사이트는 최대 3개까지 등록 가능합니다.\"}");
                    return;
                }
                userData.addSite(new MonitoredSite(siteName, siteUrl));
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    static class DeleteSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");
            String siteUrl = params.get("siteUrl");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson == null) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"로그인 정보가 없습니다.\"}");
                    return;
                }
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                userData.removeSite(siteUrl);
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    static class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            Map<String, String> params = queryToMap(exchange.getRequestURI());
            String studentId = params.get("studentId");

            PrintWriter writer = new PrintWriter(exchange.getResponseBody());
            sseClients.put(studentId, writer);

            exchange.getRequestBody().close(); // Keep connection open
        }
    }

    private static Map<String, String> queryToMap(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } else {
                    result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
                }
            }
        }
        return result;
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
    }
}
