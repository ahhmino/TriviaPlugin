package com.ahhmino.trivia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Base64;

public final class TriviaPlugin extends JavaPlugin implements TabExecutor, Listener {

    private final HttpClient http = HttpClient.newHttpClient();
    private final Deque<TriviaQuestion> queue = new ArrayDeque<>();
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private String chatPrefix;
    private int answerDelayTicks;
    private int betweenQuestionsDelayTicks;
    private int fetchBatchSize;
    private boolean triviaEnabled;

    // Loop control
    private boolean cycleActive = false;
    private int loopGeneration = 0; // increments when loop starts/stops/restarts

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings(getConfig());

        if (getCommand("trivia") != null) {
            getCommand("trivia").setExecutor(this);
            getCommand("trivia").setTabCompleter(this);
        }

        // Listen for first-player-join so we can resume trivia when people come back
        Bukkit.getPluginManager().registerEvents(this, this);

        // Initial fetch + loop start if enabled
        fetchQuestionsAsync().thenRun(() -> {
            if (triviaEnabled) startLoopIfNeeded();
        });

        getLogger().info("Trivia loaded; start_enabled=" + triviaEnabled);
    }

    @Override
    public void onDisable() {
        stopLoop();
        queue.clear();
    }

    /* ------------------ Player Events ------------------ */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // If trivia is enabled and this is the *first* player online, restart fresh
        if (!triviaEnabled) return;

        // By the time the event fires, the joining player is counted in getOnlinePlayers()
        if (Bukkit.getOnlinePlayers().size() == 1) {
            getLogger().info("First player joined; restarting trivia loop fresh.");
            restartLoopFresh(true);
        }
    }

    /* ------------------ Commands ------------------ */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("trivia")) return false;

        if (!sender.hasPermission("trivia.manage")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage trivia.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "enable" -> {
                triviaEnabled = true;
                startLoopIfNeeded();
                sender.sendMessage(ChatColor.GREEN + "Trivia enabled.");
            }
            case "disable" -> {
                triviaEnabled = false;
                stopLoop();
                sender.sendMessage(ChatColor.RED + "Trivia disabled.");
            }
            case "status" -> {
                sender.sendMessage(ChatColor.AQUA + "Trivia is " + (triviaEnabled ? "ENABLED" : "DISABLED")
                        + ChatColor.GRAY + " | Queue=" + queue.size()
                        + " | Active=" + cycleActive
                        + " | Gen=" + loopGeneration);
            }
            case "reload" -> {
                reloadConfig();
                loadSettings(getConfig());
                sender.sendMessage(ChatColor.GREEN + "Trivia config reloaded.");

                // Start completely fresh with new config (new category/difficulty/etc)
                if (triviaEnabled) {
                    restartLoopFresh(true);
                } else {
                    queue.clear();
                    fetching.set(false);
                    stopLoop();
                }
            }
            case "now" -> {
                if (!triviaEnabled) {
                    sender.sendMessage(ChatColor.YELLOW + "Trivia is disabled. Use /trivia enable first.");
                } else {
                    loopGeneration++;
                    cycleActive = true;
                    int myGen = loopGeneration;
                    runOneCycleOrWait(myGen);
                    sender.sendMessage(ChatColor.GREEN + "Triggered next trivia cycle (reset loop).");
                }
            }

            // --- CONFIG COMMANDS ---

            case "config" -> {
                showConfig(sender);
            }
            case "amount" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia amount <number>");
                    return true;
                }
                try {
                    int amount = Integer.parseInt(args[1]);
                    if (amount < 1) {
                        sender.sendMessage(ChatColor.RED + "Amount must be >= 1.");
                        return true;
                    }
                    getConfig().set("amount", amount);
                    saveConfig();
                    sender.sendMessage(ChatColor.GREEN + "Set amount to " + amount + ".");
                    restartLoopFresh(true);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
                }
            }
            case "category" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia category <id|any>");
                    return true;
                }
                String arg = args[1];
                String value;
                if (arg.equalsIgnoreCase("any") || arg.equalsIgnoreCase("none") || arg.equals("-")) {
                    value = "";
                } else {
                    value = arg;
                }
                getConfig().set("category", value);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Set category to " + (value.isEmpty() ? "any" : value) + ".");
                restartLoopFresh(true);
            }
            case "difficulty" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia difficulty <easy|medium|hard|any>");
                    return true;
                }
                String diff = args[1].toLowerCase(Locale.ROOT);
                String stored;
                switch (diff) {
                    case "easy", "medium", "hard" -> stored = diff;
                    case "any", "none", "-" -> stored = "";
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Invalid difficulty. Use easy, medium, hard, or any.");
                        return true;
                    }
                }
                getConfig().set("difficulty", stored);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Set difficulty to " + (stored.isEmpty() ? "any" : stored) + ".");
                restartLoopFresh(true);
            }
            case "type" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia type <multiple|boolean|any>");
                    return true;
                }
                String t = args[1].toLowerCase(Locale.ROOT);
                String stored;
                switch (t) {
                    case "multiple", "boolean" -> stored = t;
                    case "any", "none", "-" -> stored = "";
                    default -> {
                        sender.sendMessage(ChatColor.RED + "Invalid type. Use multiple, boolean, or any.");
                        return true;
                    }
                }
                getConfig().set("type", stored);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Set question type to " + (stored.isEmpty() ? "any" : stored) + ".");
                restartLoopFresh(true);
            }
            case "encode" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia encode <base64|url3986|...>");
                    return true;
                }
                String enc = args[1];
                getConfig().set("encode", enc);
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Set encode to " + enc + ".");
                restartLoopFresh(true);
            }
            case "delay" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia delay <answerSeconds> <betweenSeconds>");
                    return true;
                }
                try {
                    int answerSec = Integer.parseInt(args[1]);
                    int betweenSec = Integer.parseInt(args[2]);
                    if (answerSec < 1 || betweenSec < 0) {
                        sender.sendMessage(ChatColor.RED + "answerSeconds must be >=1 and betweenSeconds >=0.");
                        return true;
                    }
                    getConfig().set("answer_delay_seconds", answerSec);
                    getConfig().set("between_questions_delay_seconds", betweenSec);
                    saveConfig();
                    loadSettings(getConfig());
                    sender.sendMessage(ChatColor.GREEN + "Set delays: answer=" + answerSec +
                            "s, between=" + betweenSec + "s.");
                    restartLoopFresh(false);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid numbers: " + args[1] + " " + args[2]);
                }
            }
            case "fetchbatch" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia fetchbatch <number>");
                    return true;
                }
                try {
                    int n = Integer.parseInt(args[1]);
                    if (n < 1) {
                        sender.sendMessage(ChatColor.RED + "fetch_batch_size must be >=1.");
                        return true;
                    }
                    getConfig().set("fetch_batch_size", n);
                    saveConfig();
                    loadSettings(getConfig());
                    sender.sendMessage(ChatColor.GREEN + "Set fetch_batch_size to " + n + ".");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
                }
            }
            case "prefix" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia prefix <chat prefix text>");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) sb.append(' ');
                    sb.append(args[i]);
                }
                String prefix = sb.toString();
                getConfig().set("chat_prefix", prefix);
                saveConfig();
                loadSettings(getConfig());
                sender.sendMessage(ChatColor.GREEN + "Set chat prefix to: " +
                        ChatColor.translateAlternateColorCodes('&', prefix) +
                        ChatColor.GRAY + " (raw: \"" + prefix + "\")");
            }

            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Trivia commands:");
        sender.sendMessage(ChatColor.GRAY + "  /trivia enable|disable|status|reload|now");
        sender.sendMessage(ChatColor.GRAY + "  /trivia config");
        sender.sendMessage(ChatColor.GRAY + "  /trivia amount <n>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia category <id|any>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia difficulty <easy|medium|hard|any>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia type <multiple|boolean|any>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia encode <base64|url3986|...>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia delay <answerSeconds> <betweenSeconds>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia fetchbatch <n>");
        sender.sendMessage(ChatColor.GRAY + "  /trivia prefix <text...>");
    }

    private void showConfig(CommandSender sender) {
        FileConfiguration cfg = getConfig();
        sender.sendMessage(ChatColor.AQUA + "Trivia configuration:");
        sender.sendMessage(ChatColor.GRAY + "  enabled: " + triviaEnabled);
        sender.sendMessage(ChatColor.GRAY + "  amount: " + cfg.getInt("amount", 50));
        sender.sendMessage(ChatColor.GRAY + "  category: " + cfg.getString("category", ""));
        sender.sendMessage(ChatColor.GRAY + "  difficulty: " + cfg.getString("difficulty", ""));
        sender.sendMessage(ChatColor.GRAY + "  type: " + cfg.getString("type", ""));
        sender.sendMessage(ChatColor.GRAY + "  encode: " + cfg.getString("encode", "base64"));
        sender.sendMessage(ChatColor.GRAY + "  answer_delay_seconds: " + cfg.getInt("answer_delay_seconds", 15));
        sender.sendMessage(ChatColor.GRAY + "  between_questions_delay_seconds: " +
                cfg.getInt("between_questions_delay_seconds", 10));
        sender.sendMessage(ChatColor.GRAY + "  fetch_batch_size: " + cfg.getInt("fetch_batch_size", 50));
        sender.sendMessage(ChatColor.GRAY + "  chat_prefix: \"" + cfg.getString("chat_prefix", "&dTrivia:&r ") + "\"");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("trivia")) return Collections.emptyList();
        if (!sender.hasPermission("trivia.manage")) return Collections.emptyList();

        List<String> root = Arrays.asList(
                "enable", "disable", "status", "reload", "now",
                "config", "amount", "category", "difficulty", "type",
                "encode", "delay", "fetchbatch", "prefix"
        );

        if (args.length == 0) return root;
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : root) if (s.startsWith(p)) out.add(s);
            return out;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "difficulty" -> filterStarts(args[1], List.of("easy", "medium", "hard", "any"));
                case "type" -> filterStarts(args[1], List.of("multiple", "boolean", "any"));
                case "encode" -> filterStarts(args[1], List.of("base64", "url3986"));
                case "category" -> filterStarts(args[1], List.of("any"));
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }

    private List<String> filterStarts(String prefix, List<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) if (s.startsWith(p)) out.add(s);
        return out;
    }

    /* ------------------ Loop & Generation Control ------------------ */

    private void startLoopIfNeeded() {
        if (!triviaEnabled) return;
        if (cycleActive) return; // already running

        cycleActive = true;
        loopGeneration++;          // new generation
        int myGen = loopGeneration;
        runOneCycleOrWait(myGen);
    }

    private void stopLoop() {
        cycleActive = false;
        loopGeneration++; // invalidate scheduled tasks from previous generations
    }

    /**
     * Completely restart the trivia loop with a fresh generation and (optionally) fresh fetch.
     * Used by /trivia reload, config-changing commands, and on first player join.
     */
    private void restartLoopFresh(boolean fetchImmediately) {
        cycleActive = false;
        loopGeneration++;
        queue.clear();
        fetching.set(false);

        if (fetchImmediately) {
            fetchQuestionsAsync();
        }

        cycleActive = true;
        loopGeneration++;
        int myGen = loopGeneration;
        runOneCycleOrWait(myGen);
    }

    private void runOneCycleOrWait(int gen) {
        if (!triviaEnabled || !cycleActive || gen != loopGeneration) return;

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this, () -> runOneCycleOrWait(gen), 20L * 10);
            return;
        }

        if (queue.size() < Math.max(5, fetchBatchSize / 4) && !fetching.get()) {
            fetchQuestionsAsync();
        }

        TriviaQuestion q = queue.pollFirst();
        if (q == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> runOneCycleOrWait(gen), 20L * 5);
            return;
        }

        broadcastPrefixed(ChatColor.LIGHT_PURPLE + "Question: " + ChatColor.RESET + q.question());
        List<String> choices = q.choices();
        char[] letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        for (int i = 0; i < choices.size(); i++) {
            broadcastPrefixed(ChatColor.GRAY + "  " + letters[i] + ") " + ChatColor.WHITE + choices.get(i));
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!triviaEnabled || !cycleActive || gen != loopGeneration) return;
            if (Bukkit.getOnlinePlayers().isEmpty()) return;

            int idx = q.correctIndex();
            String answerLine = ChatColor.GREEN + "Answer: " + ChatColor.RESET
                    + letters[idx] + ") " + choices.get(idx);
            broadcastPrefixed(answerLine);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!triviaEnabled || !cycleActive || gen != loopGeneration) return;
                runOneCycleOrWait(gen);
            }, betweenQuestionsDelayTicks);

        }, answerDelayTicks);
    }

    private void broadcastPrefixed(String message) {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        String prefix = ChatColor.translateAlternateColorCodes('&', chatPrefix);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(prefix + message);
        }
        getServer().getConsoleSender().sendMessage(prefix + ChatColor.stripColor(message));
    }

    /* ------------------ Fetching ------------------ */

    private CompletableFuture<Void> fetchQuestionsAsync() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().fine("Skipping fetch: no players online.");
            return CompletableFuture.completedFuture(null);
        }

        if (!fetching.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        FileConfiguration cfg = getConfig();

        int amount = Math.max(1, cfg.getInt("amount", 50));
        String baseUrl = cfg.getString("opentdb.url", "https://opentdb.com/api.php");
        String category = cfg.getString("category", "");
        String difficulty = cfg.getString("difficulty", "");
        String type = cfg.getString("type", "");
        String encode = cfg.getString("encode", "base64");

        StringBuilder url = new StringBuilder(baseUrl)
                .append("?amount=").append(amount);

        if (category != null && !category.isBlank()) {
            url.append("&category=").append(category);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            url.append("&difficulty=").append(difficulty);
        }
        if (type != null && !type.isBlank()) {
            url.append("&type=").append(type);
        }
        if (encode != null && !encode.isBlank()) {
            url.append("&encode=").append(encode);
        }

        getLogger().info("Fetching trivia from: " + url);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).GET().build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(HttpResponse::body)
                .handle((body, err) -> {
                    if (err != null) {
                        getLogger().warning("Failed to fetch trivia: " + err.getMessage());
                        return null;
                    }
                    try {
                        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                        if (!root.has("response_code")) {
                            getLogger().warning("OpenTriviaDB: missing response_code");
                            return null;
                        }
                        int code = root.get("response_code").getAsInt();
                        if (code != 0) {
                            getLogger().warning("OpenTriviaDB response_code=" + code + " (no questions added)");
                            return null;
                        }

                        JsonArray results = root.getAsJsonArray("results");
                        int added = 0;
                        for (JsonElement element : results) {
                            JsonObject o = element.getAsJsonObject();

                            String qText = decodeB64(o.get("question").getAsString());
                            String correct = decodeB64(o.get("correct_answer").getAsString());
                            if (qText == null || qText.isBlank() || correct == null || correct.isBlank()) {
                                continue;
                            }

                            List<String> options = new ArrayList<>();
                            if (o.has("incorrect_answers")) {
                                for (JsonElement je : o.getAsJsonArray("incorrect_answers")) {
                                    options.add(decodeB64(je.getAsString()));
                                }
                            }
                            options.add(correct);

                            Collections.shuffle(options, ThreadLocalRandom.current());

                            int correctIdx = options.indexOf(correct);
                            if (correctIdx < 0) continue;

                            queue.addLast(new TriviaQuestion(qText, options, correctIdx));
                            added++;
                        }

                        getLogger().info("Fetched " + added + " questions (queue=" + queue.size() + ")");
                    } catch (Exception e) {
                        getLogger().warning("Error parsing trivia: " + e.getMessage());
                    }
                    return null;
                })
                .whenComplete((v, t) -> fetching.set(false))
                .thenApply(v -> null);
    }

    private static String decodeB64(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // fallback if not actually base64
        }
    }

    /* ------------------ Config ------------------ */

    private void loadSettings(FileConfiguration cfg) {
        this.answerDelayTicks = Math.max(1, cfg.getInt("answer_delay_seconds", 15)) * 20;
        this.betweenQuestionsDelayTicks =
                Math.max(0, cfg.getInt("between_questions_delay_seconds", 10)) * 20;
        this.fetchBatchSize = Math.max(5, cfg.getInt("fetch_batch_size", 50));
        this.chatPrefix = Objects.requireNonNullElse(cfg.getString("chat_prefix"), "&dTrivia:&r ");
        this.triviaEnabled = cfg.getBoolean("start_enabled", true);
    }
}
