package adris.altoclef;

import baritone.Baritone;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

// everything altoclef prints to chat goes through soprano's own logger now, so it gets the same [Soprano] prefix, the
// logAsToast/useMessageTag settings and the same chat hooks as every other message. the verbosity switch
// (altoHideAllWarningLogs) is a normal soprano setting now
public class Debug {

    public static AltoClef jankModInstance;

    public static void logInternal(String message) {
        System.out.println("ALTO CLEF: " + message);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    // before there is a world there is nowhere to put chat, so it goes to the console like it always did
    private static boolean canChat() {
        return Minecraft.getInstance() != null && Minecraft.getInstance().player != null;
    }

    public static void logMessage(String message) {
        if (canChat()) {
            Helper.HELPER.logDirect(message);
        } else {
            logInternal(message);
        }
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    public static void logWarning(String message) {
        logInternal("WARNING: " + message);
        if (jankModInstance != null && !Baritone.settings().altoHideAllWarningLogs.value) {
            if (canChat()) {
                Helper.HELPER.logDirect(message, ChatFormatting.RED);
            }
        }
    }

    public static void logWarning(String format, Object... args) {
        logWarning(String.format(format, args));
    }

    public static void logError(String message) {
        String stacktrace = getStack(2);
        System.err.println(message);
        System.err.println("at:");
        System.err.println(stacktrace);
        if (canChat()) {
            Helper.HELPER.logDirect("[ERROR] " + message + "\nat:\n" + stacktrace, ChatFormatting.RED);
        }
    }

    public static void logError(String format, Object... args) {
        logError(String.format(format, args));
    }

    public static void logStack() {
        logInternal("STACKTRACE: \n" + getStack(2));
    }

    private static String getStack(int toSkip) {
        StringBuilder stacktrace = new StringBuilder();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (toSkip-- <= 0) {
                stacktrace.append(ste.toString()).append("\n");
            }
        }
        return stacktrace.toString();
    }
}
