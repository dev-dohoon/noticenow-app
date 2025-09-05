package noticenow;

import java.util.HashSet;
import java.util.Set;

/**
 * 사용자가 등록한 개별 사이트 정보를 저장하는 클래스
 */
public class MonitoredSite {
    private final String siteName;
    private final String siteUrl;
    private Set<String> lastKnownTitles;

    public MonitoredSite(String siteName, String siteUrl) {
        this.siteName = siteName;
        this.siteUrl = siteUrl;
        this.lastKnownTitles = new HashSet<>();
    }

    // Getter and Setter methods
    public String getSiteName() {
        return siteName;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public Set<String> getLastKnownTitles() {
        return lastKnownTitles;
    }

    public void setLastKnownTitles(Set<String> titles) {
        this.lastKnownTitles = titles;
    }
}
