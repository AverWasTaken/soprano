package adris.altoclef.ui.gui.facade;

import adris.altoclef.ui.gui.kit.Clipboard;

// everything the gui knows about the game, behind interfaces the preview and the tests can fake
public interface BotFacade {

    SettingsAccess settings();

    CommandsAccess commands();

    ItemsAccess items();

    WorldAccess world();

    StatusAccess status();

    KeyNames keyNames();

    Clipboard clipboard();

    // the schematics tab's folder, costs and import buttons. the default keeps older fakes compiling until they bother
    default SchematicsAccess schematics() {
        return SchematicsAccess.NONE;
    }

    // the commands tab's saved routines and the run. same deal again
    default RoutinesAccess routines() {
        return RoutinesAccess.NONE;
    }

    // the effortless tab's queue. an empty one for fakes that don't care
    default EffortlessAccess effortless() {
        return EffortlessAccess.NONE;
    }
}
