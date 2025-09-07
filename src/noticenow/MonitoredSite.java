package noticenow;

import java.util.List;
import java.util.Objects;

public class MonitoredSite {
    private String name;
    private String url;
    private List<String> lastTitles;

    public MonitoredSite(String name, String url) {
        this.name = name;
        this.url = url;
        this.lastTitles = null;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public List<String> getLastTitles() {
        return lastTitles;
    }

    public void setLastTitles(List<String> lastTitles) {
        this.lastTitles = lastTitles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MonitoredSite that = (MonitoredSite) o;
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }
}