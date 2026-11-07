package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.SuggestList;
import adris.altoclef.ui.gui.kit.SuggestList.Entry;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.model.ItemSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// what the commands tab's id boxes suggest as you type: items for get and equip, blocks for mine and go to, mobs and
// players for the conditions. ranked by the get tab's own ItemSearch so both places agree on what "iron" means
final class IdLists {

    // for hosts that never got a facade (test fakes): every list is empty, so no box ever opens one
    static final IdLists NONE = new IdLists(null);

    private final BotFacade facade;

    IdLists(BotFacade facade) {
        this.facade = facade;
    }

    SuggestList.Source items() {
        return (q, n) -> rank(list(f -> f.items().gettable()), q, n);
    }

    SuggestList.Source blocks() {
        return (q, n) -> rank(list(f -> f.items().mineable()), q, n);
    }

    SuggestList.Source mobs() {
        return (q, n) -> rank(list(f -> f.items().mobs()), q, n);
    }

    // asked fresh every key: people join and leave, and the tab list is a short read. empty in singleplayer, which
    // just means the box is a plain box
    SuggestList.Source players() {
        return (q, n) -> {
            List<ItemEntry> names = new ArrayList<>();
            List<PlayerEntry> ps = facade == null ? null : safe(() -> facade.world().players());
            if (ps != null) {
                for (PlayerEntry p : ps) {
                    if (!p.self() && p.name() != null && !p.name().isBlank()) {
                        names.add(new ItemEntry(p.name(), p.name(), null, 1));
                    }
                }
            }
            List<Entry> out = new ArrayList<>();
            for (ItemEntry e : ItemSearch.filter(names, q)) {
                if (out.size() >= n) {
                    break;
                }
                out.add(Entry.player(e.name()));
            }
            return out;
        };
    }

    // a FieldSpec kind to its list, null for kinds that have nothing to suggest
    SuggestList.Source forKind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "ITEM" -> items();
            case "BLOCK" -> blocks();
            case "MOB" -> mobs();
            case "PLAYER" -> players();
            default -> null;
        };
    }

    static SuggestList attach(Theme theme, TextField box, ShellContext ctx, SuggestList.Source source, Consumer<String> onPick) {
        return new SuggestList(theme, box, ctx, source, onPick);
    }

    private List<ItemEntry> list(Function<BotFacade, List<ItemEntry>> get) {
        if (facade == null) {
            return List.of();
        }
        List<ItemEntry> l = safe(() -> get.apply(facade));
        return l == null ? List.of() : l;
    }

    private static <T> T safe(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            Safe.report("id lists", t);
            return null;
        }
    }

    static List<Entry> rank(List<ItemEntry> all, String query, int limit) {
        List<Entry> out = new ArrayList<>();
        for (ItemEntry e : ItemSearch.filter(all, query)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(Entry.item(e.name(), e.displayName(), e.iconItemId()));
        }
        return out;
    }
}
