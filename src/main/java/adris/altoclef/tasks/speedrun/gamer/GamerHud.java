package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.CoalRow;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.FurnaceRow;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState.KitRow;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// fills a GamerHudState from the engine once per tick (GamerTask.updateHud). one per GamerTask: it remembers the rows it
// showed last tick so a need that just got satisfied can linger on the card as done instead of vanishing the moment it
// is no longer a need. everything the world is asked happens in here, on the engine's tick, never on the render thread
final class GamerHud {
    private record Done(KitRow row, long until) {
    }

    // display names and icons per catalogue name, they are registry lookups and a plan asks for the same dozen all run
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Item> icons = new HashMap<>();
    private final Map<String, Done> lingering = new HashMap<>();
    private List<KitRow> lastRows = List.of();
    private KitRunner lastRunner;

    GamerHudState build(AltoClef mod, PhaseMachine machine, RunState state, GamerFacts facts, GamerConfig cfg, PhaseHandler h) {
        long now = facts.gameTime();
        String sub = h.hudState();
        String action = sub == null ? h.hud() : sub;
        // the card shows the number the food gates act on (FoodPlan.held), not the bag, or it says 62/70 while the gate sees 40
        FoodPlan food = machine.food();
        List<KitRow> rows = kitRows(facts, food, h.kitRunner(), now);
        List<FurnaceRow> furnaces = furnaceRows(facts.furnaceJobs());
        CoalRow coal = coalRow(mod, h.support(), cfg);
        return new GamerHudState(state.phase, machine.secondsInPhase(), cfg.budgets.minutes(state.phase), machine.attempt(), action,
                rows, furnaces, coal, food.held(), food.overworldMinimum());
    }

    private List<KitRow> kitRows(GamerFacts f, FoodPlan food, KitRunner runner, long now) {
        if (runner != lastRunner) {
            // another phase's kit, or none: what the old one had finished is not news on this one
            lastRunner = runner;
            lastRows = List.of();
            lingering.clear();
        }
        if (runner == null) {
            return List.of();
        }
        List<KitNeed> needs = runner.needs();
        KitNeed current = runner.current();
        List<KitRow> live = new ArrayList<>(needs.size());
        for (KitNeed need : needs) {
            live.add(row(f, food, need, need.equals(current)));
        }
        // a counted row that was live last tick, is not a need any more and whose number is really there got satisfied:
        // keep it a while, dim and green. the number is checked because the list is sometimes the runnable subset (a
        // need waiting on the iron drops out of it while a batch cooks) and that is not the need being done. the
        // uncounted ones (armor on, meat to cook) leave for too many reasons to call any of them done
        for (KitRow was : lastRows) {
            if (was.counted() && !hasName(needs, was.catalogueName()) && haveOf(f, food, was.catalogueName()) >= was.want()) {
                lingering.put(was.catalogueName(), new Done(new KitRow(was.catalogueName(), was.name(), was.icon(), was.want(), was.want(), false, true),
                        now + HudRules.DONE_LINGER_TICKS));
            }
        }
        List<KitRow> done = new ArrayList<>();
        for (Iterator<Done> it = lingering.values().iterator(); it.hasNext(); ) {
            Done d = it.next();
            // back in the plan (a pick wore out, say) or lingered long enough
            if (d.until() <= now || hasName(needs, d.row().catalogueName())) {
                it.remove();
                continue;
            }
            done.add(d.row());
        }
        KitRow head = null;
        for (KitRow r : live) {
            if (r.working()) {
                head = r;
                break;
            }
        }
        lastRows = live;
        return HudRules.rows(live, head, done, HudRules.MAX_ROWS);
    }

    private static boolean hasName(List<KitNeed> needs, String name) {
        for (KitNeed n : needs) {
            if (n.catalogueName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private KitRow row(GamerFacts f, FoodPlan food, KitNeed need, boolean working) {
        String name = need.catalogueName();
        return switch (name) {
            case KitNeed.FOOD -> new KitRow(name, "Food", Items.COOKED_BEEF, food.held(), need.count(), working, false);
            case KitNeed.BUILD_BLOCKS -> new KitRow(name, "Building blocks", Items.COBBLESTONE, f.buildBlocks(), need.count(), working, false);
            case KitNeed.EQUIP_ARMOR -> new KitRow(name, "Armor on", Items.IRON_CHESTPLATE, 0, -1, working, false);
            case KitNeed.COOK_SMOKER -> new KitRow(name, "Raw meat to cook", Items.SMOKER, CookGate.raw(f), -1, working, false);
            case KitNeed.COOK_FURNACE -> new KitRow(name, "Raw meat to cook", Items.FURNACE, CookGate.raw(f), -1, working, false);
            default -> new KitRow(name, nameOf(name), iconOf(name), haveOf(f, food, name), need.count(), working, false);
        };
    }

    // the number on the left of the slash, same source for a live row and for the done check
    private static int haveOf(GamerFacts f, FoodPlan food, String name) {
        return switch (name) {
            case KitNeed.FOOD -> food.held();
            case KitNeed.BUILD_BLOCKS -> f.buildBlocks();
            default -> KitPlanner.have(f, name);
        };
    }

    // the first item the catalogue would collect for this name is the icon: oak log for log, white wool for wool
    private Item iconOf(String name) {
        return icons.computeIfAbsent(name, n -> {
            Item[] exact = KitPlanner.exact(n);
            return exact.length == 0 ? null : exact[0];
        });
    }

    // "Iron ingot", not "Iron Ingot": the game's title case is for tooltips, this is a list. the families get a plain word
    private String nameOf(String name) {
        return names.computeIfAbsent(name, n -> switch (n) {
            case "log" -> "Logs";
            case "planks" -> "Planks";
            case "wool" -> "Wool";
            case "bed" -> "Beds";
            default -> {
                Item icon = iconOf(n);
                if (icon == null) {
                    yield n.replace('_', ' ');
                }
                String title = new ItemStack(icon).getHoverName().getString();
                yield title.isEmpty() ? n : title.charAt(0) + title.substring(1).toLowerCase(Locale.ROOT);
            }
        });
    }

    private static List<FurnaceRow> furnaceRows(List<RunState.FurnaceJob> jobs) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        List<FurnaceRow> out = new ArrayList<>(HudRules.MAX_FURNACES);
        for (RunState.FurnaceJob job : jobs) {
            if (out.size() >= HudRules.MAX_FURNACES) {
                break;
            }
            // meat left in a station unlit is a pickup, nothing is cooking (FurnaceJobs skips these too)
            if (job.stranded) {
                continue;
            }
            Item icon = switch (job.kind == null ? "" : job.kind) {
                case "smoker" -> Items.SMOKER;
                case "blast_furnace" -> Items.BLAST_FURNACE;
                default -> Items.FURNACE;
            };
            out.add(new FurnaceRow(HudRules.furnaceWords(job.kind, job.output, job.count, job.unitsEach), icon, job.startTick, job.doneTick));
        }
        return out;
    }

    private static CoalRow coalRow(AltoClef mod, PrepSupport support, GamerConfig cfg) {
        CoalDetour coal = support == null ? null : support.coal();
        if (coal == null || !coal.active()) {
            return null;
        }
        BlockPos ore = coal.ore();
        // -1 = no ore in sight right now, the row says so without a number
        int blocks = ore == null ? -1 : (int) Math.round(Math.sqrt(mod.getPlayer().blockPosition().distSqr(ore)));
        return new CoalRow(blocks, coal.startTick(), cfg.overworld.coalSideSeconds);
    }
}
