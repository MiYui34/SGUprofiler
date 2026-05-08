package xin.sgu_server.sguprofiler.profiler;

public enum LagType {
    AI("AI"),
    PLAYER_ACTION("PLAYER_ACTION"),
    TICK_MOVEMENT("MOVEMENT"),
    TICK("TICK"),
    COLLISIONS("COLLISIONS");

    public final String name;

    LagType(String name) {
        this.name = name;
    }
}
