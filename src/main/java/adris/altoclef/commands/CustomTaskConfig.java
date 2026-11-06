package adris.altoclef.commands;

// configs/CustomTasks.json. gson fills it in by field name, so the old fields stay as they were. CommandsStore is the
// only thing that reads or writes it
class CustomTaskConfig {

    // null in every file from before the commands tab. those get read the old way and migrated on the first save
    public Integer version;
    // the command is always called custom now, this only stays so old files still load
    public String prefix = "custom2";
    public CustomTaskEntry[] customTasks = new CustomTaskEntry[0];

    static class CustomTaskEntry {
        // the one word #custom takes
        public String name;
        // what the tab shows, spaces and all. missing = same as name
        public String displayName;
        public String description;
        public CustomSubTaskEntry[] tasks;

        static class CustomSubTaskEntry {
            // get / equip / goto / wait / raw. missing = worked out from the line
            public String type;
            // the whole command line, the one that wins. older files only have command + parameters
            public String line;
            public String command;
            public String[][] parameters;
        }
    }
}
