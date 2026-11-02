package adris.altoclef.ui.gui.kit;

// one wire as the node canvas sees it. ran = the run went through here, dim = mid run and it didn't
public record NodeWire(String from, String port, String to, String toPort, boolean ran, boolean dim) {

    public NodeWire(String from, String port, String to, String toPort) {
        this(from, port, to, toPort, false, false);
    }

    public String key() {
        return from + ":" + port + ":" + to + ":" + toPort;
    }
}
