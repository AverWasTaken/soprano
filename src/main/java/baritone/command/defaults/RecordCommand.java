/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.behavior.RecordingBehavior;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RecordCommand extends Command {

    public RecordCommand(IBaritone baritone) {
        super(baritone, "record");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        RecordingBehavior rec = ((Baritone) baritone).getRecordingBehavior();
        String action = args.hasAny() ? args.getString().toLowerCase() : "status";
        try {
            switch (action) {
                case "start": {
                    String name = args.hasAny() ? args.getString() : null;
                    int radius = args.hasAny() ? args.getAs(Integer.class) : 4;
                    int height = args.hasAny() ? args.getAs(Integer.class) : 48;
                    args.requireMax(0);
                    Path dir = rec.start(name, Math.max(1, Math.min(radius, 12)), height, height);
                    logDirect("Recording \"" + rec.name() + "\": the blocks " + radius + " chunks round you (" + height
                            + " up and down), you every tick, and the chat. #record mark <note> to flag a spot, #record stop to finish.");
                    break;
                }
                case "stop": {
                    args.requireMax(0);
                    long ticks = rec.ticks();
                    int blocks = rec.blockCount();
                    Path zip = rec.stop();
                    if (zip == null) {
                        throw new CommandInvalidStateException("Not recording");
                    }
                    MutableComponent msg = Component.literal("Saved " + ticks + " ticks and " + blocks + " blocks to " + zip.getFileName()
                            + " (click to open the folder)");
                    msg.setStyle(msg.getStyle().withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.OpenFile(zip.getParent().toFile())));
                    logDirect(msg);
                    break;
                }
                case "mark": {
                    if (!rec.recording()) {
                        throw new CommandInvalidStateException("Not recording");
                    }
                    String note = args.hasAny() ? args.rawRest() : "";
                    rec.mark(note);
                    logDirect("Marked" + (note.isEmpty() ? "" : ": " + note));
                    break;
                }
                case "status": {
                    if (rec.recording()) {
                        logDirect("Recording \"" + rec.name() + "\": " + rec.ticks() + " ticks, " + rec.blockCount() + " blocks so far");
                    } else {
                        logDirect("Not recording. #record start [name] [radius in chunks] [height]");
                    }
                    break;
                }
                default:
                    throw new CommandInvalidStateException("#record start [name] [radius] [height], #record mark [note], #record stop");
            }
        } catch (IOException e) {
            throw new CommandInvalidStateException("Couldn't write the recording: " + e.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String typed = args.peekString().toLowerCase();
            return Stream.of("start", "stop", "mark", "status").filter(s -> s.startsWith(typed));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Record a run: the blocks round you, your movement and the chat";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Records the map round you (every block, as you get near it), where you are and which keys are down every tick,",
                "what Soprano is doing, and the chat. #record stop saves it all as one zip in baritone/recordings, to send to",
                "someone who wants to see (or rebuild) the map and the run.",
                "",
                "Usage:",
                "> record start [name] [radius] [height] - radius in chunks round you (default 4), height in blocks up and down (default 48)",
                "> record mark [note] - flag this moment and spot (\"it fell here\")",
                "> record stop - finish and save the zip",
                "> record - is it recording"
        );
    }
}
