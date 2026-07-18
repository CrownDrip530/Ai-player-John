package me.cliff.johnaicontroller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class JohnAIControllerPlugin extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private String bridgeUrl = "http://127.0.0.1:3001/chat";
    private String autoTalkUrl = "http://127.0.0.1:3001/autotalk";

    private final Random rng = new Random();
    private boolean idleEnabled = true;
    private Location johnHome = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridgeUrl = getConfig().getString("bridgeUrl", bridgeUrl);
        autoTalkUrl = getConfig().getString("autoTalkUrl", autoTalkUrl);

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("JohnAIController enabled. bridgeUrl=" + bridgeUrl);

        // Auto-talk poll every 5s: HTTP async -> execute sync
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                String resp = getJson(autoTalkUrl);
                BridgeResponse br = gson.fromJson(resp, BridgeResponse.class);
                if (br == null || br.commands == null || br.commands.isEmpty()) return;
                Bukkit.getScheduler().runTask(this, () -> executeCommands(br.commands));
            } catch (Exception ignored) {}
        }, 100L, 100L);

        // Idle natural behavior every 3s
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!idleEnabled) return;
            NPC john = getJohn();
            if (john == null || !john.isSpawned() || john.getEntity() == null) return;

            Entity je = john.getEntity();
            Location cur = je.getLocation();

            if (johnHome == null || !johnHome.getWorld().equals(cur.getWorld())) {
                johnHome = cur.clone();
            }

            int roll = rng.nextInt(100);

            // 0-44: natural look around
            if (roll < 45) {
                float yaw = cur.getYaw() + (rng.nextFloat() * 50f - 25f);
                float pitch = Math.max(-30f, Math.min(25f, cur.getPitch() + (rng.nextFloat() * 14f - 7f)));
                Location n = cur.clone();
                n.setYaw(yaw);
                n.setPitch(pitch);
                je.teleport(n);
                return;
            }

            // 45-84: wander near home (small, cleaner movements)
            if (roll < 85) {
                double dx = rng.nextDouble() * 6.0 - 3.0;
                double dz = rng.nextDouble() * 6.0 - 3.0;
                Location target = johnHome.clone().add(dx, 0, dz);
                target.setY(cur.getWorld().getHighestBlockYAt(target) + 1.0);
                john.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                return;
            }

            // 85-99: tiny jump-like bounce
            Location up = cur.clone().add(0, 0.42, 0);
            je.teleport(up);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (je.isValid()) je.teleport(cur);
            }, 4L);

        }, 60L, 60L);
    }

    @Override
    public void onDisable() {
        getLogger().info("JohnAIController disabled.");
    }

    private NPC getJohn() {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if ("John".equals(npc.getName())) return npc;
        }
        return null;
    }

    private boolean runConsole(String cmd) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private void executeCommands(List<String> commands) {
        for (String cmd : commands) {
            if (cmd == null) continue;
            String clean = cmd.trim();
            if (clean.isEmpty()) continue;
            if (clean.startsWith("/")) clean = clean.substring(1);
            runConsole(clean);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String playerName = event.getPlayer().getName();
        String message = event.getMessage().trim();
        if (message.isEmpty()) return;

        // Build Bukkit context ON MAIN THREAD
        Bukkit.getScheduler().runTask(this, () -> {
            JsonObject payload;
            try {
                payload = buildContextPayload(playerName, message);
            } catch (Exception ex) {
                getLogger().warning("Context build failed: " + ex.getMessage());
                return;
            }

            // HTTP call OFF MAIN THREAD
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String response = postJson(bridgeUrl, gson.toJson(payload));
                    BridgeResponse br = gson.fromJson(response, BridgeResponse.class);
                    if (br == null || br.commands == null || br.commands.isEmpty()) return;

                    // Execute ON MAIN THREAD
                    Bukkit.getScheduler().runTask(this, () -> executeCommands(br.commands));
                } catch (Exception e) {
                    getLogger().warning("Bridge error: " + e.getMessage());
                }
            });
        });
    }

    private JsonObject buildContextPayload(String playerName, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("player", playerName);
        root.addProperty("message", message);

        NPC john = getJohn();
        JsonObject johnObj = new JsonObject();

        if (john != null && john.isSpawned() && john.getEntity() != null) {
            Entity je = john.getEntity();
            Location jl = je.getLocation();

            johnObj.addProperty("spawned", true);
            johnObj.add("location", locToJson(jl));
            johnObj.addProperty("blockUnder", blockUnder(jl));

            // real block raycast from John forward
            RayTraceResult ray = jl.getWorld().rayTraceBlocks(
                    jl.clone().add(0, 1.62, 0),
                    jl.getDirection(),
                    32.0
            );

            if (ray != null && ray.getHitBlock() != null) {
                Block b = ray.getHitBlock();
                JsonObject lookBlock = new JsonObject();
                lookBlock.addProperty("type", b.getType().name());
                lookBlock.add("location", locToJson(b.getLocation()));
                johnObj.add("lookingAtBlock", lookBlock);
            } else {
                johnObj.add("lookingAtBlock", null);
            }

            // nearby entities (main thread safe here)
            JsonArray nearbyEntities = new JsonArray();
            for (Entity e : je.getNearbyEntities(16, 16, 16)) {
                JsonObject eo = new JsonObject();
                eo.addProperty("type", e.getType().name());
                eo.addProperty("name", e.getName());
                if (e.getWorld().equals(jl.getWorld())) {
                    eo.addProperty("distance", round(jl.distance(e.getLocation())));
                }
                eo.add("location", locToJson(e.getLocation()));
                nearbyEntities.add(eo);
            }
            johnObj.add("nearbyEntities", nearbyEntities);

            johnObj.add("nearbyBlockSummary", sampleBlockSummary(jl, 4, 220));
        } else {
            johnObj.addProperty("spawned", false);
        }

        root.add("john", johnObj);

        Player p = Bukkit.getPlayerExact(playerName);
        if (p != null) {
            JsonObject speaker = new JsonObject();
            Location pl = p.getLocation();
            speaker.add("location", locToJson(pl));
            speaker.addProperty("world", pl.getWorld().getName());
            speaker.addProperty("blockUnder", blockUnder(pl));

            if (john != null && john.isSpawned() && john.getEntity() != null) {
                Location jl = john.getEntity().getLocation();
                if (jl.getWorld().equals(pl.getWorld())) {
                    speaker.addProperty("distanceToJohn", round(jl.distance(pl)));
                }
            }

            root.add("speaker", speaker);
        }

        World w = (p != null) ? p.getWorld() : Bukkit.getWorlds().get(0);
        JsonObject world = new JsonObject();
        world.addProperty("name", w.getName());
        world.addProperty("time", w.getTime());
        world.addProperty("isDay", w.getTime() < 12300 || w.getTime() > 23850);
        world.addProperty("weather", w.hasStorm() ? "rain_or_thunder" : "clear");
        root.add("world", world);

        JsonArray players = new JsonArray();
        for (Player op : Bukkit.getOnlinePlayers()) {
            JsonObject po = new JsonObject();
            po.addProperty("name", op.getName());
            po.add("location", locToJson(op.getLocation()));
            po.addProperty("blockUnder", blockUnder(op.getLocation()));
            players.add(po);
        }
        root.add("onlinePlayers", players);

        return root;
    }

    private String blockUnder(Location l) {
        Location b = l.clone().subtract(0, 1, 0);
        return b.getBlock().getType().name();
    }

    private void smoothLookAt(Entity npcEntity, Location target, double yOffset) {
        Location from = npcEntity.getLocation();
        Location to = target.clone().add(0, yOffset, 0);

        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 1.0e-6) return;

        Location wanted = from.clone();
        wanted.setDirection(dir);

        float cy = from.getYaw(), cp = from.getPitch();
        float wy = wanted.getYaw(), wp = wanted.getPitch();

        float ny = lerpAngle(cy, wy, 0.35f);
        float np = cp + (wp - cp) * 0.35f;

        Location out = from.clone();
        out.setYaw(ny);
        out.setPitch(np);
        npcEntity.teleport(out);
    }

    private float lerpAngle(float a, float b, float t) {
        float d = b - a;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return a + d * t;
    }

    private JsonObject sampleBlockSummary(Location center, int radius, int maxSamples) {
        JsonObject summary = new JsonObject();
        Map<String, Integer> counts = new HashMap<>();

        World w = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int sampled = 0;
        for (int i = 0; i < maxSamples; i++) {
            int x = cx + rng.nextInt(radius * 2 + 1) - radius;
            int y = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight(), cy + rng.nextInt(radius * 2 + 1) - radius));
            int z = cz + rng.nextInt(radius * 2 + 1) - radius;

            Material m = w.getBlockAt(x, y, z).getType();
            if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) continue;

            counts.put(m.name(), counts.getOrDefault(m.name(), 0) + 1);
            sampled++;
        }

        List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .collect(Collectors.toList());

        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Integer> e : top) {
            JsonObject o = new JsonObject();
            o.addProperty("type", e.getKey());
            o.addProperty("count", e.getValue());
            arr.add(o);
        }

        summary.addProperty("sampledNonAir", sampled);
        summary.add("topBlocks", arr);
        return summary;
    }

    private JsonObject locToJson(Location l) {
        JsonObject o = new JsonObject();
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", round(l.getX()));
        o.addProperty("y", round(l.getY()));
        o.addProperty("z", round(l.getZ()));
        o.addProperty("yaw", round(l.getYaw()));
        o.addProperty("pitch", round(l.getPitch()));
        return o;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String postJson(String targetUrl, String json) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(targetUrl).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setDoOutput(true);
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);

        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        InputStream is = (con.getResponseCode() >= 200 && con.getResponseCode() < 300)
                ? con.getInputStream()
                : con.getErrorStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getJson(String targetUrl) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(targetUrl).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(4000);
        con.setReadTimeout(8000);

        InputStream is = (con.getResponseCode() >= 200 && con.getResponseCode() < 300)
                ? con.getInputStream()
                : con.getErrorStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("johnaicontroller.use")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        NPC john = getJohn();
        if (john == null) {
            sender.sendMessage("§cNPC 'John' not found. Create first: /npc create John --type player");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e/john say <text>");
            sender.sendMessage("§e/john move <x> <y> <z> [world]");
            sender.sendMessage("§e/john tp <player>");
            sender.sendMessage("§e/john look <player>");
            sender.sendMessage("§e/john vulnerable");
            sender.sendMessage("§e/john respawndelay <seconds>");
            sender.sendMessage("§e/john skinname <name>");
            sender.sendMessage("§e/john skintex <value> <signature>");
            sender.sendMessage("§e/john idle <on|off>");
            return true;
        }

        runConsole("npc select " + john.getId());
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "say": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john say <text>");
                    return true;
                }
                String msg = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                Bukkit.broadcastMessage("§bJohn§7: " + msg);
                return true;
            }
            case "tp": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john tp <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }
                Location loc = target.getLocation();
                if (!john.isSpawned()) john.spawn(loc);
                else john.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                sender.sendMessage("Teleported John to " + target.getName());
                return true;
            }
            case "look": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john look <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }
                if (!john.isSpawned() || john.getEntity() == null) {
                    sender.sendMessage("John is not spawned.");
                    return true;
                }

                Entity je = john.getEntity();
                Location targetLoc = target.getLocation();

                for (int i = 0; i < 4; i++) {
                    int delay = i;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (je.isValid()) smoothLookAt(je, targetLoc, 1.0);
                    }, delay);
                }

                sender.sendMessage("John now looks at " + target.getName());
                return true;
            }
            case "move": {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /john move <x> <y> <z> [world]");
                    return true;
                }
                try {
                    double x = Double.parseDouble(args[1]);
                    double y = Double.parseDouble(args[2]);
                    double z = Double.parseDouble(args[3]);

                    World world;
                    if (args.length >= 5) world = Bukkit.getWorld(args[4]);
                    else {
                        Entity e = john.getEntity();
                        world = (e != null) ? e.getWorld() : Bukkit.getWorlds().get(0);
                    }

                    if (world == null) {
                        sender.sendMessage("World not found.");
                        return true;
                    }

                    Location loc = new Location(world, x, y, z);
                    if (!john.isSpawned()) john.spawn(loc);
                    else john.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);

                    sender.sendMessage("Moved John.");
                } catch (Exception ex) {
                    sender.sendMessage("Invalid coordinates.");
                }
                return true;
            }
            case "vulnerable": {
                runConsole("npc vulnerable"); // toggle on your build
                sender.sendMessage("Toggled vulnerability.");
                return true;
            }
            case "respawndelay": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john respawndelay <seconds>");
                    return true;
                }
                try {
                    int sec = Integer.parseInt(args[1]);
                    boolean ok = runConsole("npc respawndelay " + sec);
                    if (!ok) runConsole("npc respawn " + sec);
                    sender.sendMessage("Respawn delay set.");
                } catch (Exception ex) {
                    sender.sendMessage("Invalid number.");
                }
                return true;
            }
            case "skinname": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john skinname <name>");
                    return true;
                }
                runConsole("npc skin " + args[1]);
                sender.sendMessage("Skin name set.");
                return true;
            }
            case "skintex": {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /john skintex <value> <signature>");
                    return true;
                }
                String value = args[1];
                String signature = args[2];
                runConsole("npc skin John -t " + value + " " + signature);
                sender.sendMessage("Skin texture applied.");
                return true;
            }
            case "idle": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john idle <on|off>");
                    return true;
                }
                idleEnabled = args[1].equalsIgnoreCase("on");
                sender.sendMessage("John idle = " + idleEnabled);
                return true;
            }
            default:
                sender.sendMessage("Unknown subcommand.");
                return true;
        }
    }

    static class BridgeResponse {
        List<String> commands;
    }
}
