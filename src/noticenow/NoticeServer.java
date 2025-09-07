package noticenow;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NoticeServer {

    private static final Gson gson = new Gson();
    private static JedisPool jedisPool;

    public static void main(String[] args) throws IOException {
        initializeRedis();
        startBackgroundChecker();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/add-site", new AddSiteHandler());
        server.createContext("/api/delete-site", new DeleteSiteHandler());
        server.createContext("/api/get-notifications", new GetNotificationsHandler()); // 새 알림 확인 API
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ 서버가 http://localhost:8080 에서 시작되었습니다.");
    }

    private static void initializeRedis() {
        String redisHost = System.getenv("REDIS_HOST");
        String redisPortStr = System.getenv("REDIS_PORT");
        String redisPassword = System.getenv("REDIS_PASSWORD");

        if (redisHost == null || redisPortStr == null) {
            redisHost = "localhost";
            redisPortStr = "6379";
            redisPassword = null;
        }
        int redisPort = Integer.parseInt(redisPortStr);
        jedisPool = new JedisPool(redisHost, redisPort, null, redisPassword);
        System.out.println("✅ Redis DB가 성공적으로 연결되었습니다.");
    }

    private static void startBackgroundChecker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(NoticeServer::checkAllSites, 0, 1, TimeUnit.MINUTES);
    }

    private static void checkAllSites() {
        System.out.println("🔄 백그라운드 확인 작업 시작...");
        try (Jedis jedis = jedisPool.getResource()) {
            for (String userKey : jedis.keys("user:*")) {
                String userDataJson = jedis.get(userKey);
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                boolean needsDbUpdate = false;

                for (MonitoredSite site : userData.getSites()) {
                    try {
                        Document doc = Jsoup.connect(site.getUrl()).get();
                        Elements currentTitlesElements = doc.select("tr > td:nth-child(2)");
                        List<String> newTitlesList = new ArrayList<>();
                        currentTitlesElements.forEach(el -> newTitlesList.add(el.text()));

                        List<String> oldTitles = site.getLastTitles();
                        if (oldTitles == null) {
                            site.setLastTitles(newTitlesList);
                            needsDbUpdate = true;
                            continue;
                        }

                        if (!newTitlesList.equals(oldTitles)) {
                            List<String> addedTitles = new ArrayList<>(newTitlesList);
                            addedTitles.removeAll(oldTitles);

                            if (!addedTitles.isEmpty()) {
                                System.out.printf("🚨 새 공지 발견! [%s] %s%n", userKey, site.getName());
                                for (String newTitle : addedTitles) {
                                    String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                                    Map<String, String> newLog = Map.of("siteName", site.getName(), "title", newTitle, "time", time);
                                    userData.addMissedNotification(newLog); // "부재중 알림"으로 저장
                                    needsDbUpdate = true;
                                }
                            }
                            site.setLastTitles(newTitlesList);
                        }
                    } catch (IOException e) {
                        System.err.printf("❌ 사이트 확인 오류 [%s]: %s%n", site.getName(), e.getMessage());
                    }
                }
                if (needsDbUpdate) {
                    jedis.set(userKey, gson.toJson(userData));
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Redis 작업 오류: " + e.getMessage());
        }
    }

    // --- 핸들러들 ---

    static class GetNotificationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String userKey = "user:" + studentId;
            List<Map<String, String>> missedNotifications = new ArrayList<>();

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                if (userDataJson != null) {
                    UserData userData = gson.fromJson(userDataJson, UserData.class);
                    missedNotifications.addAll(userData.getMissedNotifications());
                    if (!missedNotifications.isEmpty()) {
                        userData.clearMissedNotifications();
                        jedis.set(userKey, gson.toJson(userData));
                    }
                }
            }
            sendJsonResponse(exchange, 200, gson.toJson(missedNotifications));
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            try (InputStream inputStream = NoticeServer.class.getResourceAsStream(path)) {
                if (inputStream == null) {
                    sendResponse(exchange, 404, "404 Not Found");
                } else {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        inputStream.transferTo(os);
                    }
                }
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
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
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String siteName = params.get("siteName");
            String siteUrlB64 = params.get("siteUrlB64");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                if (userData.getSites().size() >= 3) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"사이트는 최대 3개까지 등록 가능합니다.\"}");
                    return;
                }
                String siteUrl = new String(Base64.getDecoder().decode(siteUrlB64), StandardCharsets.UTF_8);
                userData.addSite(new MonitoredSite(siteName, siteUrl));
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    static class DeleteSiteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            String studentId = params.get("studentId");
            String siteUrlB64 = params.get("siteUrlB64");
            String userKey = "user:" + studentId;

            try (Jedis jedis = jedisPool.getResource()) {
                String userDataJson = jedis.get(userKey);
                UserData userData = gson.fromJson(userDataJson, UserData.class);
                String siteUrl = new String(Base64.getDecoder().decode(siteUrlB64), StandardCharsets.UTF_8);
                userData.removeSite(siteUrl);
                jedis.set(userKey, gson.toJson(userData));
                sendJsonResponse(exchange, 200, gson.toJson(userData.getSites()));
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        exchange.sendResponseHeaders(code, body.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int code, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        sendResponse(exchange, code, json);
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1) {
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
