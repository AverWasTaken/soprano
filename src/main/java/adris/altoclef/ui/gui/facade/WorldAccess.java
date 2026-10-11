package adris.altoclef.ui.gui.facade;

import java.util.List;

// the player's surroundings and the actions on them: go to, follow, waypoints. lists are snapshots the tabs
// refresh on tick, never live views
public interface WorldAccess {

    record Position(double x, double y, double z) {
    }

    // distance is -1 when the player is on the tab list but not loaded nearby
    record PlayerEntry(String name, double distance, boolean self) {
    }

    // typeId "minecraft:zombie", typeName "Zombie", displayName the entity's own name (a named pet, a player)
    record EntityEntry(int id, String typeId, String typeName, String displayName, double distance) {
    }

    record WaypointEntry(String name, String tag, int x, int y, int z, long createdMillis) {
    }

    // null when there is no player
    Position playerPos();

    // "overworld", "the_nether", "the_end" or whatever the dimension id's path is
    String dimension();

    List<PlayerEntry> players();

    List<EntityEntry> entities();

    Result followPlayer(String name);

    Result followEntity(int id);

    Result followType(String typeId);

    Result followAllPlayers();

    Result followAllEntities();

    Result stopFollowing();

    // "Steve", "every zombie", "all players", or null when nothing is followed
    String following();

    List<WaypointEntry> waypoints();

    List<String> waypointTags();

    Result addWaypointHere(String name, String tag);

    Result deleteWaypoint(WaypointEntry waypoint);

    // any of the three may be null: x and z only is a GoalXZ, y only is a GoalYLevel, all three a GoalBlock
    Result goTo(Integer x, Integer y, Integer z);

    Result setGoal(Integer x, Integer y, Integer z);
}
