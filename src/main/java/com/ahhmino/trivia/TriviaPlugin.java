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

public final class TriviaPlugin extends JavaPlugin implements TabExecutor {

    private final HttpClient http = HttpClient.newHttpClient();
    private final Deque<TriviaQuestion> queue = new ArrayDeque<>();
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private String chatPrefix;
    private int answerDelayTicks;
    private int betweenQuestionsDelayTicks;
    private int fetchBatchSize;
    private boolean triviaEnabled;
    private boolean cycleActive = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings(getConfig());

        if (getCommand("trivia") != null) {
            getCommand("trivia").setExecutor(this);
            getCommand("trivia").setTabCompleter(this);
        }

        fetchQuestionsAsync(fetchBatchSize).thenRun(() -> {
            if (triviaEnabled) startLoopIfNeeded();
        });

        getLogger().info("Trivia loaded; start_enabled=" + triviaEnabled);
    }

    @Override
    public void onDisable() {
        stopLoop();
        queue.clear();
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
            sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia <enable|disable|status|reload|now>");
            return true;
        }

        switch (args[0].toLowerCase()) {
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
                        + " | Active=" + cycleActive);
            }
            case "reload" -> {
                reloadConfig();
                loadSettings(getConfig());
                sender.sendMessage(ChatColor.GREEN + "Trivia config reloaded.");
                if (queue.isEmpty() && !fetching.get()) fetchQuestionsAsync(fetchBatchSize);
                if (triviaEnabled) startLoopIfNeeded();
            }
            case "now" -> {
                if (!triviaEnabled) {
                    sender.sendMessage(ChatColor.YELLOW + "Trivia is disabled. Use /trivia enable first.");
                } else {
                    runOneCycleOrWait();
                    sender.sendMessage(ChatColor.GREEN + "Triggered next trivia cycle.");
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia <enable|disable|status|reload|now>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("trivia")) return Collections.emptyList();
        List<String> options = Arrays.asList("enable", "disable", "status", "reload", "now");
        if (args.length == 0) return options;
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : options) if (s.startsWith(p)) out.add(s);
            return out;
        }
        return Collections.emptyList();
    }

    /* ------------------ Loop ------------------ */

    private void startLoopIfNeeded() {
        if (!triviaEnabled || cycleActive) return;
        cycleActive = true;
        runOneCycleOrWait();
    }

    private void stopLoop() {
        cycleActive = false;
    }

    private void runOneCycleOrWait() {
        if (!triviaEnabled || !cycleActive) return;

        // Skip if no players online
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().fine("No players online; pausing trivia cycle...");
            Bukkit.getScheduler().runTaskLater(this, this::runOneCycleOrWait, 20L * 10); // retry in 10s
            return;
        }

        // Top up queue if needed
        if (queue.size() < Math.max(5, fetchBatchSize / 4) && !fetching.get()) {
            fetchQuestionsAsync(fetchBatchSize);
        }

        TriviaQuestion q = queue.pollFirst();
        if (q == null) {
            Bukkit.getScheduler().runTaskLater(this, this::runOneCycleOrWait, 20L * 5);
            return;
        }

        // Post question + choices
        broadcastPrefixed(ChatColor.LIGHT_PURPLE + "Question: " + ChatColor.RESET + q.question());
        List<String> choices = q.choices();
        char[] letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        for (int i = 0; i < choices.size(); i++) {
            broadcastPrefixed(ChatColor.GRAY + "  " + letters[i] + ") " + ChatColor.WHITE + choices.get(i));
        }

        // Schedule answer reveal
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!triviaEnabled || !cycleActive || Bukkit.getOnlinePlayers().isEmpty()) return;

            int idx = q.correctIndex();
            broadcastPrefixed(ChatColor.GREEN + "Answer: " + ChatColor.RESET +
                    letters[idx] + ") " + choices.get(idx));

            // Wait before next
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!triviaEnabled || !cycleActive) return;
                runOneCycleOrWait();
            }, betweenQuestionsDelayTicks);

        }, answerDelayTicks);
    }

    private void broadcastPrefixed(String message) {
        if (Bukkit.getOnlinePlayers().isEmpty()) return; // skip silent broadcast
        String prefix = ChatColor.translateAlternateColorCodes('&', chatPrefix);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(prefix + message);
        }
        getServer().getConsoleSender().sendMessage(prefix + ChatColor.stripColor(message));
    }

    /* ------------------ Fetching ------------------ */

    private CompletableFuture<Void> fetchQuestionsAsync(int amount) {
        // Don't fetch if no players online (to conserve API calls)
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().fine("Skipping fetch: no players online.");
            return CompletableFuture.completedFuture(null);
        }

        if (!fetching.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        String baseUrl = getConfig().getString("opentdb.url", "https://opentdb.com/api.php");
        String type = Objects.toString(getConfig().getString("opentdb.type", ""), "");
        String encode = Objects.toString(getConfig().getString("opentdb.encode", "base64"), "base64");

        String url = baseUrl + "?amount=" + amount
                + (type.isBlank() ? "" : "&type=" + type)
                + (encode.isBlank() ? "" : "&encode=" + encode);

        getLogger().info("Fetching trivia from: " + url);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(HttpResponse::body)
                .handle((body, err) -> {
                    if (err != null) {
                        getLogger().warning("Failed to fetch trivia: " + err.getMessage());
                        return null;
                    }
                    try {
                        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
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

                            List<String> options = new ArrayList<>();
                            if (o.has("incorrect_answers")) {
                                for (JsonElement je : o.getAsJsonArray("incorrect_answers")) {
                                    options.add(decodeB64(je.getAsString()));
                                }
                            }
                            options.add(correct);
                            Collections.shuffle(options, ThreadLocalRandom.current());

                            int correctIdx = options.indexOf(correct);
                            if (qText == null || qText.isBlank() || correctIdx < 0) continue;

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
            return s;
        }
    }

    /* ------------------ Config ------------------ */

    private void loadSettings(FileConfiguration cfg) {
        this.answerDelayTicks = Math.max(1, cfg.getInt("answer_delay_seconds", 15)) * 20;
        this.betweenQuestionsDelayTicks = Math.max(0, cfg.getInt("between_questions_delay_seconds", 10)) * 20;
        this.fetchBatchSize = Math.max(5, cfg.getInt("fetch_batch_size", 50));
        this.chatPrefix = Objects.requireNonNullElse(cfg.getString("chat_prefix"), "&dTrivia:&r ");
        this.triviaEnabled = cfg.getBoolean("start_enabled", true);
    }
}
