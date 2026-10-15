package adris.altoclef.ui.gui.mc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// the os's own "open files" dialog, run as a child process. minecraft 26.3 ships neither lwjgl-tinyfd nor glfw any more
// (it moved to sdl), so there is no in-process dialog to call, and a dependency just for one button isn't worth bundling
// natives for. a child process also means the game keeps drawing while the dialog is open, and a dialog that hangs
// can't take the jvm with it. windows uses the winforms dialog through powershell, mac uses osascript, linux tries
// zenity and then kdialog. blocking: call it from a helper thread
final class FilePicker {

    // files empty and error null: the dialog was closed without choosing. error set: no dialog could be opened at all
    record Choice(List<Path> files, String error) {

        static Choice cancelled() {
            return new Choice(List.of(), null);
        }

        static Choice failed(String error) {
            return new Choice(List.of(), error);
        }

        boolean wasCancelled() {
            return files.isEmpty() && error == null;
        }
    }

    private FilePicker() {
    }

    static Choice pick(List<String> extensions, String title) {
        try {
            Choice choice = platform(extensions, title);
            if (choice.error != null && choice.error.startsWith(MISSING)) {
                return Choice.failed("No file picker found on this system. Use drag and drop or Open folder.");
            }
            return choice;
        } catch (Throwable t) {
            return Choice.failed("Couldn't open the file picker (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static Choice platform(List<String> extensions, String title) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return windows(extensions, title);
        }
        if (os.contains("mac")) {
            return mac(title);
        }
        return linux(extensions, title);
    }

    // ----- windows

    // single quotes in a powershell string are doubled, and the title is ours anyway
    static String windowsScript(List<String> extensions, String title) {
        String patterns = String.join(";", extensions.stream().map(e -> "*." + e).toList());
        return String.join("\n",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Windows.Forms",
            // a hidden topmost owner, or the dialog opens behind the game window and just flashes the taskbar
            "$owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }",
            "$d = New-Object System.Windows.Forms.OpenFileDialog",
            "$d.Multiselect = $true",
            "$d.Title = '" + title.replace("'", "''") + "'",
            "$d.Filter = 'Schematics (" + patterns + ")|" + patterns + "|All files (*.*)|*.*'",
            "if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {",
            "  foreach ($f in $d.FileNames) { [Console]::Out.WriteLine($f) }",
            "}");
    }

    private static Choice windows(List<String> extensions, String title) throws IOException, InterruptedException {
        // -EncodedCommand is utf-16le base64, which dodges every quoting rule there is
        String encoded = Base64.getEncoder().encodeToString(windowsScript(extensions, title).getBytes(StandardCharsets.UTF_16LE));
        // hidden, because a game started with javaw has no console and powershell would open one next to the dialog
        return run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded));
    }

    // ----- mac

    private static Choice mac(String title) throws IOException, InterruptedException {
        return run(List.of("osascript",
            "-e", "set theFiles to choose file with prompt \"" + title.replace("\"", "") + "\" with multiple selections allowed",
            "-e", "set out to \"\"",
            "-e", "repeat with f in theFiles",
            "-e", "set out to out & POSIX path of f & linefeed",
            "-e", "end repeat",
            "-e", "return out"));
    }

    // ----- linux

    private static Choice linux(List<String> extensions, String title) throws IOException, InterruptedException {
        String patterns = String.join(" ", extensions.stream().map(e -> "*." + e).toList());
        Choice zenity = run(List.of("zenity", "--file-selection", "--multiple", "--separator=\n",
            "--title=" + title, "--file-filter=Schematics | " + patterns, "--file-filter=All files | *"));
        if (zenity.error == null || !zenity.error.startsWith(MISSING)) {
            return zenity;
        }
        Choice kdialog = run(List.of("kdialog", "--getopenfilename", ".", patterns + "|Schematics", "--multiple",
            "--separate-output", "--title", title));
        if (kdialog.error != null && kdialog.error.startsWith(MISSING)) {
            return Choice.failed("No file picker found. Install zenity or kdialog, or use drag and drop.");
        }
        return kdialog;
    }

    // ----- the child process

    private static final String MISSING = "missing:";

    private static Choice run(List<String> command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            // not installed. linux tries the next one, the others report it as a failure
            return Choice.failed(MISSING + command.get(0));
        }
        try {
            // the dialog is on screen for as long as the player wants, so no timeout here. stdout is read on this
            // thread, which only ends when the process closes it
            List<String> lines = readLines(process.getInputStream());
            int code = process.waitFor();
            if (code != 0 && lines.isEmpty()) {
                // osascript says -128 for cancel, zenity and kdialog use 1. nobody prints anything else useful
                return Choice.cancelled();
            }
            return parse(lines);
        } finally {
            if (process.isAlive()) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    // one path per line. blank lines are the trailing newline
    static Choice parse(List<String> lines) {
        List<Path> files = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                files.add(Path.of(trimmed));
            }
        }
        return files.isEmpty() ? Choice.cancelled() : new Choice(List.copyOf(files), null);
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
