package noticenow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class UserData {
    private List<MonitoredSite> sites;
    private List<Map<String, String>> missedNotifications; // 부재중 알림 저장 리스트

    public UserData() {
        this.sites = new ArrayList<>();
        this.missedNotifications = new ArrayList<>();
    }

    public List<MonitoredSite> getSites() {
        return sites;
    }

    public void addSite(MonitoredSite site) {
        if (this.sites.size() < 3) {
            this.sites.add(site);
        }
    }

    public void removeSite(String url) {
        this.sites.removeIf(site -> site.getUrl().equals(url));
    }

    // --- 부재중 알림 관련 메소드 ---
    public List<Map<String, String>> getMissedNotifications() {
        return missedNotifications;
    }

    public void addMissedNotification(Map<String, String> notification) {
        if (this.missedNotifications == null) {
            this.missedNotifications = new ArrayList<>();
        }
        this.missedNotifications.add(notification);
    }

    public void clearMissedNotifications() {
        this.missedNotifications.clear();
    }
}