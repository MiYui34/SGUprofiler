package xin.sgu_server.sguprofiler.profiler;

import java.util.HashMap;
import java.util.Map;

public class LagSource {
    public final ProfileKey key;
    public Map<LagType, Long> lags = new HashMap<>();

    public LagSource(ProfileKey key, LagType lagType, long timeCost) {
        this.key = key;
        lags.put(lagType, timeCost);
    }

    public void merge(LagSource source) {
        for (LagType lagType : source.lags.keySet()) {
            lags.put(lagType, lags.getOrDefault(lagType, 0L) + source.lags.get(lagType));
        }
    }

    public long sum() {
        return lags.values().stream().mapToLong(l -> l).sum();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof LagSource source) {
            return source.key.equals(this.key);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.key.hashCode();
    }
}
