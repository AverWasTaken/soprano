package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.PlayerCollidedWithEntityEvent;
import baritone.Baritone;
import baritone.utils.accessor.IPersistentProjectile;
import adris.altoclef.trackers.blacklisting.EntityLocateBlacklist;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps track of entities so we can search/grab them.
 */
@SuppressWarnings("rawtypes")
public class EntityTracker extends Tracker {

    private final HashMap<Item, List<ItemEntity>> _itemDropLocations = new HashMap<>();
    private final HashMap<Class, List<Entity>> _entityMap = new HashMap<>();

    private final List<Entity> _closeEntities = new ArrayList<>();
    private final List<Entity> _hostiles = new ArrayList<>();

    private final List<CachedProjectile> _projectiles = new ArrayList<>();

    private final HashMap<String, Player> _playerMap = new HashMap<>();
    private final HashMap<String, Vec3> _playerLastCoordinates = new HashMap<>();

    private final EntityLocateBlacklist _entityBlacklist = new EntityLocateBlacklist();
    private final MobReachability _mobReach = new MobReachability();
    // who we hit and who hit us lately, the only honest "it is angry at us" the client can get (see NeutralMobs)
    private final Provocations _provocations = new Provocations();
    // entity ids we already said we were skipping, so the log gets one line per drop and not one per tick
    private final Set<Integer> _skippedWetDrops = new HashSet<>();
    // isPickupSafe can be ~45 block lookups per drop, so each drop's verdict lives a few ticks instead of being redone
    // every tick. rebuilt each update from the drops that were actually asked about, so dead ones fall out by themselves
    private HashMap<Integer, PickupVerdict> _pickupVerdicts = new HashMap<>();
    private static final int PICKUP_RECHECK_TICKS = 10;

    private record PickupVerdict(long pos, long tick, boolean safe) {
    }

    private final HashMap<Player, Set<Entity>> _entitiesCollidingWithPlayerAccumulator = new HashMap<>();
    private final HashMap<Player, HashSet<Entity>> _entitiesCollidingWithPlayer = new HashMap<>();

    public EntityTracker(TrackerManager manager) {
        super(manager);

        // Listen for player collisions
        EventBus.subscribe(PlayerCollidedWithEntityEvent.class, evt -> registerPlayerCollision(evt.player, evt.other));
    }

    /**
     * Squash a class that may have sub classes into one distinguishable class type.
     * For ease of use.
     *
     * @param type: An entity class that may have a 'simpler' class to squash to
     * @return what the given entity class should be read as/catalogued as.
     */
    private static Class squashType(Class type) {
        // Squash types for ease of use
        if (Player.class.isAssignableFrom(type)) {
            return Player.class;
        }
        return type;
    }

    private void registerPlayerCollision(Player player, Entity entity) {
        // only updateState drains this and it only runs while altoclef drives, so nothing gets to pile up while idle
        if (!AltoClef.isRunning()) {
            return;
        }
        if (!_entitiesCollidingWithPlayerAccumulator.containsKey(player)) {
            _entitiesCollidingWithPlayerAccumulator.put(player, new HashSet<>());
        }
        _entitiesCollidingWithPlayerAccumulator.get(player).add(entity);
    }

    public boolean isCollidingWithPlayer(Player player, Entity entity) {
        return _entitiesCollidingWithPlayer.containsKey(player) && _entitiesCollidingWithPlayer.get(player).contains(entity);
    }

    public boolean isCollidingWithPlayer(Entity entity) {
        return isCollidingWithPlayer(_mod.getPlayer(), entity);
    }

    public Optional<ItemEntity> getClosestItemDrop(Item... items) {
        return getClosestItemDrop(_mod.getPlayer().position(), items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Item... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, ItemTarget... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Predicate<ItemEntity> acceptPredicate, Item... items) {
        return getClosestItemDrop(_mod.getPlayer().position(), acceptPredicate, items);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Predicate<ItemEntity> acceptPredicate, Item... items) {
        ensureUpdated();
        ItemTarget[] tempTargetList = new ItemTarget[items.length];
        for (int i = 0; i < items.length; ++i) {
            tempTargetList[i] = new ItemTarget(items[i], 9999999);
        }
        return getClosestItemDrop(position, acceptPredicate, tempTargetList);
    }

    public Optional<ItemEntity> getClosestItemDrop(Vec3 position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return Optional.empty();
        }
        if (!itemDropped(targets)) {
            return Optional.empty();
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (_entityBlacklist.unreachable(entity)) continue;
                    if (!entity.getItem().getItem().equals(item)) continue;
                    if (!acceptPredicate.test(entity)) continue;

                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, entity.position());
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = entity;
                    }
                }
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public Optional<Entity> getClosestEntity(Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().position(), entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3 position, Class... entityTypes) {
        return this.getClosestEntity(position, (entity) -> true, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Predicate<Entity> acceptPredicate, Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().position(), acceptPredicate, entityTypes);
    }

    public Optional<Entity> getClosestEntity(Vec3 position, Predicate<Entity> acceptPredicate, Class... entityTypes) {
        Entity closestEntity = null;
        double minCost = Float.POSITIVE_INFINITY;
        for (Class toFind : entityTypes) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                if (_entityMap.containsKey(toFind)) {
                    for (Entity entity : _entityMap.get(toFind)) {
                        // Don't accept entities that no longer exist
                        if (_entityBlacklist.unreachable(entity)) continue;
                        if (!entity.isAlive()) continue;
                        if (!acceptPredicate.test(entity)) continue;
                        double cost = entity.distanceToSqr(position);
                        if (cost < minCost) {
                            minCost = cost;
                            closestEntity = entity;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(closestEntity);
    }

    public boolean itemDropped(Item... items) {
        ensureUpdated();
        for (Item item : items) {
            if (_itemDropLocations.containsKey(item)) {
                // Find a non-blacklisted item
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (!_entityBlacklist.unreachable(entity)) return true;
                }
            }
        }
        return false;
    }

    public boolean itemDropped(ItemTarget... targets) {
        ensureUpdated();
        for (ItemTarget target : targets) {
            if (itemDropped(target.getMatches())) return true;
        }
        return false;
    }

    public List<ItemEntity> getDroppedItems() {
        ensureUpdated();
        return _itemDropLocations.values().stream().reduce(new ArrayList<>(), (result, drops) -> {
            result.addAll(drops);
            return result;
        });
    }

    public boolean entityFound(Predicate<Entity> shouldAccept, Class... types) {
        ensureUpdated();
        for (Class type : types) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (Entity entity : _entityMap.getOrDefault(type, Collections.emptyList())) {
                    if (shouldAccept.test(entity))
                        return true;
                }
            }
        }
        return false;
    }

    public boolean entityFound(Class... types) {
        return entityFound(check -> true, types);
    }

    public <T extends Entity> List<T> getTrackedEntities(Class<T> type) {
        ensureUpdated();
        if (!entityFound(type)) {
            return Collections.emptyList();
        }
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            //noinspection unchecked
            return (List<T>) _entityMap.get(type);
        }
    }

    /**
     * Gets all entities that are within our interact range
     */
    public List<Entity> getCloseEntities() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _closeEntities;
        }
    }

    /**
     * Gets a list of projectiles that we've cached/stored information about.
     */
    public List<CachedProjectile> getProjectiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _projectiles;
        }
    }

    public List<Entity> getHostiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _hostiles;
        }
    }

    /**
     * Is a player loaded/within render distance?
     *
     * @param name Username on a multiplayer server
     */
    public boolean isPlayerLoaded(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _playerMap.containsKey(name);
        }
    }

    /**
     * Get where we last saw a player, if we saw them at all.
     *
     * @return Username on a multiplayer server.
     */
    public Optional<Vec3> getPlayerMostRecentPosition(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return Optional.ofNullable(_playerLastCoordinates.getOrDefault(name, null));
        }
    }

    /**
     * Gets the player entity corresponding to a username, if they're loaded/within render distance.
     *
     * @param name Username on a multiplayer server.
     */
    public Optional<Player> getPlayerEntity(String name) {
        if (isPlayerLoaded(name)) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                return Optional.of(_playerMap.get(name));
            }
        }
        return Optional.empty();
    }

    /**
     * Tells the entity tracker that we were unable to reach this entity.
     */
    public void requestEntityUnreachable(Entity entity) {
        _entityBlacklist.blackListItem(_mod, entity, 3);
    }

    /**
     * Gives up on this entity for good (until the world changes), no retries.
     */
    public void banEntity(Entity entity) {
        _entityBlacklist.banItem(entity);
    }

    /**
     * Whether we have decided that this entity is unreachable.
     */
    public boolean isEntityReachable(Entity entity) {
        return !_entityBlacklist.unreachable(entity);
    }

    // not baked into getHostiles on purpose: "it is angry" is still true for the run away and eating checks even when it
    // cannot get to us. only the decisions about fighting ask this
    public MobReachability getMobReachability() {
        return _mobReach;
    }

    public Provocations getProvocations() {
        return _provocations;
    }

    // we just hit this one (or it just hit us). anything neutral stops being calm for a while
    public void noteProvoked(Entity entity) {
        if (entity instanceof Mob && Minecraft.getInstance().level != null) {
            _provocations.mark(entity.getId(), Minecraft.getInstance().level.getGameTime());
        }
    }

    private void noteAttackers() {
        if (_mod.getPlayer() == null) return;
        DamageSource source = _mod.getPlayer().getLastDamageSource();
        if (source == null) return;
        // the attacker for melee and a shot's shooter, and the thing that actually touched us
        noteProvoked(source.getEntity());
        noteProvoked(source.getDirectEntity());
    }

    @Override
    protected synchronized void updateState() {
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            _itemDropLocations.clear();
            _entityMap.clear();
            _closeEntities.clear();
            _projectiles.clear();
            _hostiles.clear();
            _playerMap.clear();
            if (Minecraft.getInstance().level == null) return;

            // whatever hurt us last (the client keeps the source for two seconds) is after us, neutral or not. goes before
            // the hostile pass below so a piglin that just hit us is a hostile on the same tick
            noteAttackers();

            // Store/Register All accumulated player collisions for this frame.
            _entitiesCollidingWithPlayer.clear();
            for (Map.Entry<Player, Set<Entity>> collisions : _entitiesCollidingWithPlayerAccumulator.entrySet()) {
                _entitiesCollidingWithPlayer.put(collisions.getKey(), new HashSet<>());
                _entitiesCollidingWithPlayer.get(collisions.getKey()).addAll(collisions.getValue());
            }
            _entitiesCollidingWithPlayerAccumulator.clear();

            boolean allowWetDrops = Baritone.settings().altoPickupItemsInWater.value;
            long now = Minecraft.getInstance().level.getGameTime();
            HashMap<Integer, PickupVerdict> verdicts = new HashMap<>();

            // Loop through all entities and track 'em
            for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {

                // Catalogue based on type. Some types may get "squashed" or combined into one.
                Class type = entity.getClass();
                type = squashType(type);

                //noinspection ConstantConditions
                if (entity == null || !entity.isAlive()) continue;

                // Don't catalogue our own player.
                if (type == Player.class && entity.equals(_mod.getPlayer())) continue;

                if (!_entityMap.containsKey(type)) {
                    _entityMap.put(type, new ArrayList<>());
                }
                _entityMap.get(type).add(entity);

                if (_mod.getControllerExtras().inRange(entity)) {
                    _closeEntities.add(entity);
                }

                if (entity instanceof ItemEntity ientity) {
                    Item droppedItem = ientity.getItem().getItem();

                    // Only cared about GROUNDED item entities
                    if (ientity.onGround() || ientity.isInWater() || WorldHelper.isSolid(_mod, ientity.blockPosition().below(2)) || WorldHelper.isSolid(_mod, ientity.blockPosition().below(3))) {
                        // every drop lookup in altoclef goes through this map, so skipping wet items here covers all
                        // of them at once, and updateState only runs once a tick so it doubles as the cache
                        if (!allowWetDrops && !isPickupSafeCached(ientity, now, verdicts)) {
                            noteSkippedWetDrop(ientity);
                            continue;
                        }
                        if (!_itemDropLocations.containsKey(droppedItem)) {
                            _itemDropLocations.put(droppedItem, new ArrayList<>());
                        }
                        _itemDropLocations.get(droppedItem).add(ientity);
                    }
                }
                if (entity instanceof Mob) {
                    // distance first: isAngryAtPlayer does a line of sight raycast for endermen, and most mobs
                    // in render distance are nowhere near close enough to care
                    if (entity.closerThan(_mod.getPlayer(), 16) && EntityHelper.isAngryAtPlayer(_mod, entity)) {
                        _hostiles.add(entity);
                    }
                } else if (entity instanceof Projectile projEntity) {
                    if (!_mod.getBehaviour().shouldAvoidDodgingProjectile(entity)) {
                        CachedProjectile proj = new CachedProjectile();

                        boolean inGround = false;
                        // Get projectile "inGround" variable
                        if (entity instanceof AbstractArrow) {
                            inGround = ((IPersistentProjectile) entity).callIsInGround();
                        }

                        // Ignore some of the harlmess projectiles
                        if (projEntity instanceof FishingHook || projEntity instanceof ThrownEnderpearl || projEntity instanceof ThrownExperienceBottle)
                            continue;

                        if (!inGround) {
                            proj.position = projEntity.position();
                            proj.velocity = projEntity.getDeltaMovement();
                            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.ARROW_GRAVITY_ACCEL : 0;
                            proj.projectileType = projEntity.getClass();
                            _projectiles.add(proj);
                        }
                    }
                } else if (entity instanceof Player player) {
                    String name = player.getName().getString();
                    _playerMap.put(name, player);
                    _playerLastCoordinates.put(name, player.position());
                }
            }
            _pickupVerdicts = verdicts;
        }
    }

    // redo the check when the drop moves to another block or the verdict is old enough that the water may have changed
    private boolean isPickupSafeCached(ItemEntity drop, long now, HashMap<Integer, PickupVerdict> verdicts) {
        long pos = drop.blockPosition().asLong();
        PickupVerdict verdict = _pickupVerdicts.get(drop.getId());
        if (verdict == null || verdict.pos() != pos || now - verdict.tick() >= PICKUP_RECHECK_TICKS) {
            verdict = new PickupVerdict(pos, now, ItemPickupRules.isPickupSafe(drop));
        }
        verdicts.put(drop.getId(), verdict);
        return verdict.safe();
    }

    private void noteSkippedWetDrop(ItemEntity drop) {
        if (_skippedWetDrops.add(drop.getId())) {
            Debug.logInternal("Skipping " + drop.getItem().getItem().getDescriptionId() + " in water at " + drop.blockPosition().toShortString());
        }
    }

    @Override
    protected void reset() {
        // Dirty clears everything else.
        _entityBlacklist.clear();
        _mobReach.reset();
        _skippedWetDrops.clear();
        _pickupVerdicts.clear();
        // these are keyed by LocalPlayer and hold entities, so each of them pins a whole ClientLevel. gone with the world
        _entitiesCollidingWithPlayerAccumulator.clear();
        _entitiesCollidingWithPlayer.clear();
    }
}
