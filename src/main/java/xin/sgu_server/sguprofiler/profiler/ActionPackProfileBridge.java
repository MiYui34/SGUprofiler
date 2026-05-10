package xin.sgu_server.sguprofiler.profiler;

/** {@link xin.sgu_server.sguprofiler.mixin.EntityPlayerActionPack_Mixin} 从整段 onUpdate 中扣除 Action.tick/retry 已计时的部分。 */
public final class ActionPackProfileBridge {
    private static final ThreadLocal<long[]> ACC = ThreadLocal.withInitial(() -> new long[1]);

    private ActionPackProfileBridge() {
    }

    public static void clear() {
        ACC.get()[0] = 0L;
    }

    public static void addActionNs(long ns) {
        ACC.get()[0] += ns;
    }

    public static long takeAccumulatedNs() {
        long[] slot = ACC.get();
        long v = slot[0];
        slot[0] = 0L;
        return v;
    }
}
