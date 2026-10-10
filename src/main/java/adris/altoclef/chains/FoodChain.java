package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Tuple;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class FoodChain extends SingleTaskChain {
    private static FoodChainConfig _config;
    private static boolean _hasFood;

    static {
        ConfigHelper.loadConfig("configs/food_chain_settings.json", FoodChainConfig::new, FoodChainConfig.class, newConfig -> _config = newConfig);
    }

    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private boolean _isTryingToEat = false;
    private boolean _requestFillup = false;
    private boolean _needsFood = false;
    private Optional<Item> _cachedPerfectFood = Optional.empty();
    private static final int FOOD_RECALC_TICKS = 10;
    private int _cachedFoodScore = 0;
    private int _foodCalcAge = FOOD_RECALC_TICKS;
    private int _lastFoodHunger = -1;
    private float _lastFoodSaturation = -1;
    private float _lastFoodHealth = -1;
    private boolean shouldStop = false;
    private boolean _eatBlocked = false;
    // what we are chewing and how things stood when the bite started, so the log line can say what a meal was for
    private Item _bite;
    private int _biteCount = -1;
    private int _biteHunger;
    private float _biteSaturation;
    private float _biteHealth;

    private final AltoClef _mod;

    public FoodChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Nothing.
    }

    private void startEat(AltoClef mod, Item food) {
        //Debug.logInternal("EATING " + toUse.getTranslationKey() + " : " + test);
        _isTryingToEat = true;
        _requestFillup = true;
        if (food != _bite) {
            snapshotBite(mod, food);
        }
        mod.getSlotHandler().forceEquipItem(new Item[]{food}, true); //"true" because it's food
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    private void stopEat(AltoClef mod) {
        if (_isTryingToEat) {
            if (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
                if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    _isTryingToEat = false;
                    _requestFillup = false;
                }
            } else {
                _isTryingToEat = false;
                _requestFillup = false;
            }
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
    }

    public boolean isTryingToEat() {
        return _isTryingToEat;
    }

    private void snapshotBite(AltoClef mod, Item food) {
        LocalPlayer player = mod.getPlayer();
        _bite = food;
        _biteCount = mod.getItemStorage().getItemCount(food);
        _biteHunger = player.getFoodData().getFoodLevel();
        _biteSaturation = player.getFoodData().getSaturationLevel();
        _biteHealth = player.getHealth();
    }

    // one line per item actually eaten, with the hunger it was eaten at. the stack going down is the bite landing: the hunger bar
    // can't tell a gapple at full hunger, and starting to chew is not eating (a fight spits it out). the gamer reads these to see
    // how fast a cave leg burns food
    private void watchBite(AltoClef mod) {
        if (_bite == null) {
            return;
        }
        int count = mod.getItemStorage().getItemCount(_bite);
        if (count < _biteCount) {
            Debug.logInternal(String.format("food: ate %s at hunger %d (saturation %.1f, hp %.1f)",
                    BuiltInRegistries.ITEM.getKey(_bite).getPath(), _biteHunger, _biteSaturation, _biteHealth));
            // the next one in a fillup starts from here
            snapshotBite(mod, _bite);
        } else if (count > _biteCount) {
            // picked one up mid bite, or the stack would have to drop twice to show the bite
            _biteCount = count;
        }
        if (!_isTryingToEat) {
            _bite = null;
            _biteCount = -1;
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        _dragonBreathTracker.updateBreath(mod);
        watchBite(mod);
        // one list of reasons we will not eat right now. the eat branch below and needsToEat() both go by it, so
        // everything that pauses for a meal (progress checkers, container waits) stops waiting when we refuse to eat.
        // before, "needs to eat" stayed true through a fall or a lava dip and no watchdog could fire
        _eatBlocked = eatingBlocked(mod);
        // blocking is NOT in the shared list: mob defense drops the shield when needsToEat() is true, so counting it
        // there would make a shielding bot unable to ever get hungry enough to put the shield down
        if (_eatBlocked) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        /*
        - Eats if:
        - We're hungry and have food that fits
            - We're low on health and maybe a little bit hungry
            - We're very low on health and are even slightly hungry
        - We're kind of hungry and have food that fits perfectly
         */
        if (shouldRecalculateFood(mod)) {
            Tuple<Integer, Optional<Item>> calculation = calculateFood(mod);
            _cachedFoodScore = calculation.getA();
            _cachedPerfectFood = calculation.getB();
            // a last resort food (rotten flesh while starving) counts as having food but adds no score
            _hasFood = _cachedPerfectFood.isPresent();
            _foodCalcAge = 0;
        } else {
            _foodCalcAge++;
        }
        // If we requested a fillup but we're full, stop.
        if (_requestFillup && mod.getPlayer().getFoodData().getFoodLevel() >= 20) {
            _requestFillup = false;
        }
        // If we no longer have food, we no longer can eat.
        if (!_hasFood) {
            _requestFillup = false;
        }
        // mid fight we do not start a meal, and one already going gets spat out. you cannot swing a sword while chewing
        // and the thing hitting us does not wait for the bar. once the fight is over _requestFillup picks it back up
        CombatRules.Stance stance = stance(mod);
        if (stance == CombatRules.Stance.EAT_GAPPLE && !mod.getPlayer().isBlocking()) {
            // not in the food list on purpose, this is the one fight where the gapple is the point
            Item gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) ? Items.GOLDEN_APPLE : Items.ENCHANTED_GOLDEN_APPLE;
            LookHelper.tryAvoidingInteractable(mod);
            startEat(mod, gapple);
        } else if (stance.mayEat() && _hasFood && (needsToEat() || _requestFillup) && _cachedPerfectFood.isPresent() &&
                !mod.getMLGBucketChain().isChorusFruiting() && !mod.getPlayer().isBlocking()) {
            Item toUse = _cachedPerfectFood.get();
            // Make sure we're not facing a container
            LookHelper.tryAvoidingInteractable(mod);
            startEat(mod, toUse);
        } else {
            stopEat(mod);
        }

        // read once so a #set in the middle of this can't make the checks below disagree with each other
        int minimumFood = Baritone.settings().altoMinimumFoodAllowed.value;
        int foodToCollect = Baritone.settings().altoFoodUnitsToCollect.value;

        if (_needsFood || _cachedFoodScore < minimumFood) {
            _needsFood = _cachedFoodScore < foodToCollect;

            // Only collect if we don't have enough food.
            // If the user inputs invalid settings, the bot would get stuck here.
            if (_cachedFoodScore < foodToCollect) {
                setTask(new CollectFoodTask(foodToCollect));
                return 55f;
            }
        }


        // Food eating is handled asynchronously.
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        // We're always checking for food.
        return true;
    }

    @Override
    public String getName() {
        return "Food";
    }

    @Override
    public String getHudName() {
        return "Eating";
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
        stopEat(mod);
    }

    // everything that makes the eat branch back off. cheap enough to read from getPriority, needsToEat() uses the cached answer
    private boolean eatingBlocked(AltoClef mod) {
        if (WorldHelper.isInNetherPortal(mod) || mod.getMobDefenseChain().isPuttingOutFire()) return true;
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) return true;
        }
        if (!Baritone.settings().altoAutoEat.value) return true;
        // do NOT eat while in lava if we are escaping it (spaghetti code dependencies go brrrr)
        if (mod.getPlayer().isInLava()) return true;
        // We're in danger, don't eat now!!
        return !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isFallingOhNo(mod) || shouldStop;
    }

    // this is also "are we busy chewing", half the codebase asks it to know whether it may swing or click. so it says no
    // when a fight stops us eating, and yes when the fight is the reason we are eating (the gapple)
    public boolean needsToEat() {
        if (shouldStop || _eatBlocked) return false;
        CombatRules.Stance stance = stance(_mod);
        if (stance == CombatRules.Stance.EAT_GAPPLE) return true;
        if (!stance.mayEat()) return false;
        return rawNeedsToEat();
    }

    // the chains are built one after another, so early on there may be no mob defense to ask yet
    private static CombatRules.Stance stance(AltoClef mod) {
        if (mod == null || mod.getMobDefenseChain() == null) return CombatRules.Stance.CALM;
        return mod.getMobDefenseChain().getCombatStance(mod);
    }

    private boolean rawNeedsToEat() {
        if (!hasFood()) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        assert player != null;
        int foodLevel = player.getFoodData().getFoodLevel();
        float health = player.getHealth();

        if (health <= 10 && foodLevel <= 19) {
            return true;
        }
        //Debug.logMessage("FOOD: " + foodLevel + " -- HEALTH: " + health);
        if (foodLevel >= 20) {
            // We can't eat.
            return false;
        } else {
            // Eat if we're desperate/need to heal ASAP
            if (player.isOnFire() || player.hasEffect(MobEffects.WITHER) || health < _config.alwaysEatWhenWitherOrFireAndHealthBelow) {
                return true;
            } else if (foodLevel > _config.alwaysEatWhenBelowHunger) {
                if (health < _config.alwaysEatWhenBelowHealth) {
                    return true;
                }
            } else {
                // We have half hunger
                return true;
            }
        }

        // Eat if we're  units hungry and we have a perfect fit.
        if (foodLevel < _config.alwaysEatWhenBelowHungerAndPerfectFit && _cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            Item best = _cachedPerfectFood.get();
            int fills = (best.components().get(DataComponents.FOOD) != null) ? Objects.requireNonNull(best.components().get(DataComponents.FOOD)).nutrition() : -1;
            return fills == need;
        }

        return false;
    }

    private Tuple<Integer, Optional<Item>> calculateFood(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        float health = player != null ? player.getHealth() : 20;
        float hunger = player != null ? player.getFoodData().getFoodLevel() : 20;
        float saturation = player != null ? player.getFoodData().getSaturationLevel() : 20;
        FoodSelector.Result result = FoodSelector.select(mod.getItemStorage().getItemStacksPlayerInventory(true),
                stack -> ItemHelper.canThrowAwayStack(mod, stack), health, hunger, saturation, _config);
        return new Tuple<>(result.foodTotal(), result.best());
    }

    // walking the whole inventory and scoring every stack 20 times a second is a lot of work for an answer
    // that only changes when we eat, pick something up or the bars move. so: recompute when the bars move,
    // while eating (the stack we're chewing on is about to run out) or every FOOD_RECALC_TICKS otherwise
    private boolean shouldRecalculateFood(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (player == null) return true;
        int hunger = player.getFoodData().getFoodLevel();
        float saturation = player.getFoodData().getSaturationLevel();
        float health = player.getHealth();
        boolean barsMoved = hunger != _lastFoodHunger || saturation != _lastFoodSaturation || health != _lastFoodHealth;
        if (barsMoved || _isTryingToEat || _foodCalcAge >= FOOD_RECALC_TICKS) {
            _lastFoodHunger = hunger;
            _lastFoodSaturation = saturation;
            _lastFoodHealth = health;
            return true;
        }
        return false;
    }

    // If we need to eat like, NOW.
    public boolean needsToEatCritical() {
        return false;
    }

    public boolean hasFood() {
        return _hasFood;
    }

    public void shouldStop(boolean shouldStopInput) {
        shouldStop = shouldStopInput;
    }

    public boolean isShouldStop() {
        return shouldStop;
    }

    static class FoodChainConfig {
        public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
        public int alwaysEatWhenBelowHunger = 10;
        public int alwaysEatWhenBelowHealth = 14;
        public int alwaysEatWhenBelowHungerAndPerfectFit = 20 - 5;
        public int prioritizeSaturationWhenBelowHealth = 8;
        public float foodPickPrioritizeSaturationSaturationMultiplier = 8;
        public float foodPickSaturationWastePenaltyMultiplier = 1;
        public float foodPickHungerWastePenaltyMultiplier = 2;
        public float foodPickHungerNotFilledPenaltyMultiplier = 1;
        public float foodPickRottenFleshPenalty = 100;
        public float runDontEatMaxHealth = 3;
        public int runDontEatMaxHunger = 3;
        public int canTankHitsAndEatArmor = 15;
        public int canTankHitsAndEatMaxHunger = 3;
    }
}
