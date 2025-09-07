package noticenow;

import com.google.gson.Gson;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static spark.Spark.*;

public class NoticeServer {

    private static final Gson gson = new Gson();
    private static JedisPool jedisPool;

    public static void main(String[] args) {
        initializeRedis();
        startBackgroundChecker();

        // Spark Port Setting
        port(8080);

        // --- Static File Server ---
        // Serve index.html from resources
        get("/", (req, res) -> {
            res.type("text/html; charset=UTF-8");
            try (InputStream is = NoticeServer.class.getResourceAsStream("/index.html")) {
                if (is == null) {
                    res.status(404);
                    return "404 Not Found: index.html not found in resources folder";
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        });

        // --- API Routes ---
        path("/api", () -> {
            before("/*", (req, res) -> res.type("application/json; charset=UTF-8"));

            // Login
            get("/login", (req, res) -> {
                String studentId = req.queryParams("studentId");
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
                return gson.toJson(userData.getSites());
            });

            // Add Site
            get("/add-site", (req, res) -> {
                String studentId = req.queryParams("studentId");
                String siteName = req.queryParams("siteName");
                String siteUrl = req.queryParams("siteUrl");
                String userKey = "user:" + studentId;
                try (Jedis jedis = jedisPool.getResource()) {
                    String userDataJson = jedis.get(userKey);
                    UserData userData = gson.fromJson(userDataJson, UserData.class);
                    if (userData.getSites().size() >= 3) {
                        res.status(400);
                        return "{\"error\":\"사이트는 최대 3개까지 등록 가능합니다.\"}";
                    }
                    userData.addSite(new MonitoredSite(siteName, siteUrl));
                    jedis.set(userKey, gson.toJson(userData));
                    return gson.toJson(userData.getSites());
                }
            });

            // Delete Site
            get("/delete-site", (req, res) -> {
                String studentId = req.queryParams("studentId");
                String siteUrl = req.queryParams("siteUrl");
                String userKey = "user:" + studentId;
                try (Jedis jedis = jedisPool.getResource()) {
                    String userDataJson = jedis.get(userKey);
                    UserData userData = gson.fromJson(userDataJson, UserData.class);
                    userData.removeSite(siteUrl);
                    jedis.set(userKey, gson.toJson(userData));
                    return gson.toJson(userData.getSites());
                }
            });

            // Get Notifications
            get("/get-notifications", (req, res) -> {
                String studentId = req.queryParams("studentId");
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
                return gson.toJson(missedNotifications);
            });
        });

        System.out.println("✅ Spark 서버가 http://localhost:8080 에서 시작되었습니다.");
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
                                    Map<String, String> newLog = new HashMap<>();
                                    newLog.put("siteName", site.getName());
                                    newLog.put("title", newTitle);
                                    newLog.put("time", time);
                                    userData.addMissedNotification(newLog);
                                    needsDbUpdate = true;
                                }
                            }
                            site.setLastTitles(newTitlesList);
                        }
                    } catch (Exception e) {
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
}