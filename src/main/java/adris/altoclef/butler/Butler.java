package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.ui.MessagePriority;
import baritone.Baritone;
import java.util.Objects;
import net.minecraft.network.chat.ChatType;

/**
 * The butler system lets authorized players send commands to the bot to execute.
 * <p>
 * This effectively makes the bot function as a servant, or butler.
 * <p>
 * Authorization is defined in "altoclef_butler_whitelist.txt" and "altoclef_butler_blacklist.txt".
 * The whitelist is required (empty means nobody can command the bot), the blacklist is applied on top of it
 * and wins, unless "useButlerBlacklist" in "configs/butler.json" is off. Names are case-insensitive.
 * The whole thing is switched by the altoButler setting (#set altoButler true).
 */
public class Butler {

    private static final String BUTLER_MESSAGE_START = "` ";

    private final AltoClef _mod;

    private final WhisperChecker _whisperChecker = new WhisperChecker();

    private final UserAuth _userAuth;

    private String _currentUser = null;

    // Utility variables for command logic
    private boolean _commandInstantRan = false;
    private boolean _commandFinished = false;

    public Butler(AltoClef mod) {
        _mod = mod;
        _userAuth = new UserAuth(mod);

        // Revoke our current user whenever a task finishes.
        EventBus.subscribe(TaskFinishedEvent.class, evt -> {
            if (_currentUser != null) {
                _currentUser = null;
            }
        });

        // Receive system events
        EventBus.subscribe(ChatMessageEvent.class, evt -> {
            if (!Baritone.settings().altoButler.value) {
                return;
            }
            boolean debug = ButlerConfig.getInstance().whisperFormatDebug;
            String message = evt.messageContent();
            String sender = evt.senderName();
            ChatType messageType = evt.messageType();
            String receiver = mod.getPlayer().getName().getString();
            if (sender != null && !Objects.equals(sender, receiver) && messageType.chat().style().isItalic()
                    && messageType.chat().style().getColor() != null
                    && Objects.equals(messageType.chat().style().getColor().serialize(), "gray")) {
                if (debug) {
                    Debug.logMessage("RECEIVED WHISPER: \"" + sender + " " + receiver + " " + message + "\".");
                }
                _mod.getButler().receiveMessage(sender, receiver, message);
            }
        });
    }

    private void receiveMessage(String sender, String receiver, String msg) {
        // the sender comes from the packet, not from anything in the text, see WhisperChecker#parseWhisper
        WhisperChecker.MessageResult result = this._whisperChecker.receiveMessage(sender, receiver, msg);
        if (result != null) {
            this.receiveWhisper(result.from, result.message);
        } else if (ButlerConfig.getInstance().whisperFormatDebug) {
            Debug.logMessage("    Not Parsing: MSG format not found.");
        }
    }

    private void receiveWhisper(String username, String message) {

        boolean debug = ButlerConfig.getInstance().whisperFormatDebug;
        // Ignore messages from other bots.
        if (message.startsWith(BUTLER_MESSAGE_START)) {
            if (debug) {
                Debug.logMessage("    Rejecting: MSG is detected to be sent from another bot.");
            }
            return;
        }

        if (_userAuth.isUserAuthorized(username)) {
            executeWhisper(username, message);
        } else {
            if (debug) {
                Debug.logMessage("    Rejecting: User \"" + username + "\" is not authorized.");
            }
            if (ButlerConfig.getInstance().sendAuthorizationResponse) {
                sendWhisper(username, ButlerConfig.getInstance().failedAuthorizationResposne.replace("{from}", username), MessagePriority.UNAUTHORIZED);
            }
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isUserAuthorized(String username) {
        return _userAuth.isUserAuthorized(username);
    }

    // the force field and the terminator ask this one, see UserAuth#isUserSpared
    public boolean isUserSpared(String username) {
        return _userAuth.isUserSpared(username);
    }

    public void onLog(String message, MessagePriority priority) {
        if (_currentUser != null) {
            sendWhisper(message, priority);
        }
    }

    public void onLogWarning(String message, MessagePriority priority) {
        if (_currentUser != null) {
            sendWhisper("[WARNING:] " + message, priority);
        }
    }

    public void tick() {
        // Nothing for now.
    }

    public String getCurrentUser() {
        return _currentUser;
    }

    public boolean hasCurrentUser() {
        return _currentUser != null;
    }

    private void executeWhisper(String username, String message) {
        // with requirePrefixMsg only messages that start with soprano's command prefix count as commands, the rest is
        // just somebody chatting at us
        if (ButlerConfig.getInstance().requirePrefixMsg && !message.startsWith(AltoClefCommands.prefix())) {
            return;
        }
        String prevUser = _currentUser;
        _commandInstantRan = true;
        _commandFinished = false;
        _currentUser = username;
        sendWhisper("Command Executing: " + message, MessagePriority.TIMELY);
        // the last flag is the allow-list: a whisper can run altoclef's commands and nothing else of soprano's
        AltoClefCommands.execute(message, () -> {
            // On finish
            sendWhisper("Command Finished: " + message, MessagePriority.TIMELY);
            if (!_commandInstantRan) {
                _currentUser = null;
            }
            _commandFinished = true;
        }, error -> {
            sendWhisper("TASK FAILED: " + error, MessagePriority.ASAP);
            _currentUser = null;
            _commandInstantRan = false;
        }, true);
        _commandInstantRan = false;
        // Only set the current user if we're still running.
        if (_commandFinished) {
            _currentUser = prevUser;
        }
    }

    private void sendWhisper(String message, MessagePriority priority) {
        if (_currentUser != null) {
            sendWhisper(_currentUser, message, priority);
        } else {
            Debug.logWarning("Failed to send butler message as there are no users present: " + message);
        }
    }

    private void sendWhisper(String username, String message, MessagePriority priority) {
        _mod.getMessageSender().enqueueWhisper(username, BUTLER_MESSAGE_START + message, priority);
    }
}
