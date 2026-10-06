package adris.altoclef.chains;

import baritone.Baritone;
import adris.altoclef.AltoClef;
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

    public FoodChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Nothing.
    }

    private void startEat(AltoClef mod, Item food) {
        //Debug.logInternal("EATING " + toUse.getTranslationKey() + " : " + test);
        _isTryingToEat = true;
        _requestFillup = true;
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

    @Override
    public float getPriority(AltoClef mod) {
        if (WorldHelper.isInNetherPortal(mod)) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }
        if (mod.getMobDefenseChain().isPuttingOutFire()) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }
        _dragonBreathTracker.updateBreath(mod);
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(mod)) {
            if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                stopEat(mod);
                return Float.NEGATIVE_INFINITY;
            }
        }
        if (!Baritone.settings().altoAutoEat.value) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // do NOT eat while in lava if we are escaping it (spaghetti code dependencies go brrrr)
        if (mod.getPlayer().isInLava()) {
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
        // We're in danger, don't eat now!!
        if (!mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isFallingOhNo(mod) ||
                mod.getPlayer().isBlocking() || shouldStop) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }
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
        if (_hasFood && (needsToEat() || _requestFillup) && _cachedPerfectFood.isPresent() &&
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

    public boolean needsToEat() {
        if (!hasFood() || shouldStop) {
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
