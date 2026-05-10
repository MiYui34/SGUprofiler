package xin.sgu_server.sguprofiler.profiler;

public enum LagType {
    AI("AI"),
    PLAYER_ACTION("PLAYER_ACTION"),
    /** Carpet 假人 ActionType.ATTACK（挖掘 / 攻击实体等） */
    PLAYER_ACTION_ATTACK("PLAYER_ACTION_ATTACK"),
    /** Carpet 假人 ActionType.USE（交互方块 / 使用物品等） */
    PLAYER_ACTION_USE("PLAYER_ACTION_USE"),
    TICK_MOVEMENT("MOVEMENT"),
    TICK("TICK"),
    COLLISIONS("COLLISIONS");

    public final String name;

    LagType(String name) {
        this.name = name;
    }
}
