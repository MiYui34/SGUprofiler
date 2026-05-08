package xin.sgu_server.sguprofiler.profiler;

/** 游戏刻级采样策略（与 minProfileNanoseconds 切片阈值独立）。 */
public enum TickSampleMode {
    /** 仅按 everyNTicks 取刻 */
    STRIDE_ONLY,
    /** 仅当上一整刻墙钟耗时不低于 heavyLastTickMsThreshold 时取本刻（阈值须为正数） */
    HEAVY_ONLY,
    /** 满足步长或重刻条件之一即采样 */
    STRIDE_OR_HEAVY,
    /** 同时满足步长与重刻条件 */
    STRIDE_AND_HEAVY
}
