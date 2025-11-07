package com.ahhmino.trivia;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TriviaPlugin extends JavaPlugin {

    private final HttpClient http = HttpClient.newHttpClient();
    private final Deque<TriviaQuestion> queue = new ArrayDeque<>();
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private String chatPrefix;
    private int answerDelayTicks;
    private int betweenQuestionsDelayTicks;
    private int fetchBatchSize;
    private boolean triviaEnabled;
    private BukkitTask loopGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings(getConfig());

        fetchQuestionsAsync(fetchBatchSize).thenRun(() -> {
            if (triviaEnabled) startLoopIfNeeded();
        });

        getLogger().info("Trivia enabled; start_enabled=" + triviaEnabled);
    }

    @Override
    public void onDisable() {
        stopLoop();
        queue.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("trivia.manage")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia <enable|disable|status|reload>");
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
                sender.sendMessage(ChatColor.AQUA + "Trivia is " +
                        (triviaEnabled ? "ENABLED" : "DISABLED") +
                        ChatColor.GRAY + " | Queue=" + queue.size());
            }
            case "reload" -> {
                reloadConfig();
                loadSettings(getConfig());
                sender.sendMessage(ChatColor.GREEN + "Trivia config reloaded.");
                if (queue.isEmpty()) fetchQuestionsAsync(fetchBatchSize);
                if (triviaEnabled) startLoopIfNeeded();
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /trivia <enable|disable|status|reload>");
        }
        return true;
    }

    private void startLoopIfNeeded() {
        if (loopGuard != null && !loopGuard.isCancelled()) return;

        loopGuard = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!triviaEnabled) return;

            if (queue.size() < Math.max(5, fetchBatchSize / 4)) {
                fetchQuestionsAsync(fetchBatchSize);
            }

            if (!Bukkit.getScheduler().isCurrentlyRunning(loopGuard.getTaskId())) {
                runOneCycle();
            }
        }, 1L, 20L);
    }

    private void stopLoop() {
        if (loopGuard != null) {
            loopGuard.cancel();
            loopGuard = null;
        }
    }

    private void runOneCycle() {
        if (!triviaEnabled) return;

        TriviaQuestion q = queue.pollFirst();
        if (q == null) return;

        broadcastPrefixed(ChatColor.LIGHT_PURPLE + "Question: " + ChatColor.RESET + q.question());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            broadcastPrefixed(ChatColor.GREEN + "Answer: " + ChatColor.RESET + q.answer());

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (triviaEnabled) runOneCycle();
            }, betweenQuestionsDelayTicks);

        }, answerDelayTicks);
    }

    private void broadcastPrefixed(String message) {
        String prefix = ChatColor.translateAlternateColorCodes('&', chatPrefix);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(prefix + message);
        }
        getServer().getConsoleSender().sendMessage(prefix + ChatColor.stripColor(message));
    }

    private CompletableFuture<Void> fetchQuestionsAsync(int amount) {
        if (!fetching.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);

        String baseUrl = getConfig().getString("opentdb.url", "https://opentdb.com/api.php");
        String type = Objects.toString(getConfig().getString("opentdb.type", ""), "");
        String difficulty = Objects.toString(getConfig().getString("opentdb.difficulty", "easy"), "easy");
        String category = Objects.toString(getConfig().getString("opentdb.category", ""), "");
        String encode = Objects.toString(getConfig().getString("opentdb.encode", "base64"), "base64");

        String url = baseUrl + "?amount=" + amount +
                (type.isBlank() ? "" : "&type=" + type) +
                (difficulty.isBlank() ? "" : "&difficulty=" + difficulty) +
                (category.isBlank() ? "" : "&category=" + category) +
                (encode.isBlank() ? "" : "&encode=" + encode);

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
                        if (root.get("response_code").getAsInt() != 0) return null;
                        JsonArray results = root.getAsJsonArray("results");
                        for (int i = 0; i < results.size(); i++) {
                            JsonObject o = results.get(i).getAsJsonObject();
                            String q = decodeB64(o.get("question").getAsString());
                            String a = decodeB64(o.get("correct_answer").getAsString());
                            if (q != null && !q.isBlank() && a != null && !a.isBlank())
                                queue.addLast(new TriviaQuestion(q, a));
                        }
                        getLogger().info("Fetched " + results.size() + " questions (queue=" + queue.size() + ")");
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

    private void loadSettings(FileConfiguration cfg) {
        this.answerDelayTicks = Math.max(1, cfg.getInt("answer_delay_seconds", 15)) * 20;
        this.betweenQuestionsDelayTicks = Math.max(0, cfg.getInt("between_questions_delay_seconds", 10)) * 20;
        this.fetchBatchSize = Math.max(5, cfg.getInt("fetch_batch_size", 50));
        this.chatPrefix = Objects.requireNonNullElse(cfg.getString("chat_prefix"), "&dTrivia:&r ");
        this.triviaEnabled = cfg.getBoolean("start_enabled", true);
    }
}
