package noticenow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 한 명의 사용자에 대한 모든 데이터를 관리하는 클래스
 */
public class UserData {
    private final String studentId;
    private final List<MonitoredSite> monitoredSites;
    private static final int MAX_SITES = 3;

    public UserData(String studentId) {
        this.studentId = studentId;
        this.monitoredSites = new ArrayList<>();
    }

    public List<MonitoredSite> getMonitoredSites() {
        return monitoredSites;
    }

    public boolean addSite(String siteName, String siteUrl) {
        if (monitoredSites.size() < MAX_SITES) {
            monitoredSites.add(new MonitoredSite(siteName, siteUrl));
            return true; // 추가 성공
        }
        return false; // 추가 실패 (개수 초과)
    }

    public void removeSite(String siteUrl) {
        monitoredSites.removeIf(site -> site.getSiteUrl().equals(siteUrl));
    }

    public Optional<MonitoredSite> findSiteByUrl(String url) {
        return monitoredSites.stream().filter(site -> site.getSiteUrl().equals(url)).findFirst();
    }
}
