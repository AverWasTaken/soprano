package adris.altoclef.tasks.speedrun.gamer.end;

// the bed errand's throws out of a full bag, one visit's worth. a thrown stack lands at our feet and the errand may walk straight
// back over it, so there's a ceiling. the throw itself reports in: the food step can take the tick a throw was asked for, and
// asking shouldn't cost anything
public final class ShedTally {
    public static final int CAP = 12;
    // two, not one: the errand walks and digs to its logs, and the first thing it picks up on the way fills a lone free slot
    // right back up. the bed then has nowhere to land again
    public static final int FREE = 2;

    private int _thrown;
    private boolean _firstSaid;
    private boolean _capSaid;
    private boolean _stuckSaid;

    // does a bag with this many free slots want a throw
    public static boolean wanted(int free) {
        return free < FREE;
    }

    public void thrown() {
        _thrown++;
        _stuckSaid = false;
    }

    public boolean spent() {
        return _thrown >= CAP;
    }

    // true once a visit, that's the one worth a bag dump
    public boolean first() {
        boolean first = !_firstSaid;
        _firstSaid = true;
        return first;
    }

    // once, this runs every tick the bag stays full
    public boolean sayCap() {
        boolean say = !_capSaid;
        _capSaid = true;
        return say;
    }

    // once until a throw works again, same reason
    public boolean sayStuck() {
        boolean say = !_stuckSaid;
        _stuckSaid = true;
        return say;
    }
}
