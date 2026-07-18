package me.cliff.johnaicontroller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class JohnAIControllerPlugin extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private String bridgeUrl = "http://127.0.0.1:3001/chat";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridgeUrl = getConfig().getString("bridgeUrl", bridgeUrl);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("JohnAIController enabled. bridgeUrl=" + bridgeUrl);
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

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String player = event.getPlayer().getName();
        String content = event.getMessage().trim();
        if (content.isEmpty()) return;

        // Send EVERY chat message to bridge, AI decides whether to react
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("player", player);
                body.addProperty("message", content);

                String response = postJson(bridgeUrl, gson.toJson(body));
                BridgeResponse br = gson.fromJson(response, BridgeResponse.class);

                if (br == null || br.commands == null || br.commands.isEmpty()) return;

                Bukkit.getScheduler().runTask(this, () -> {
                    for (String cmd : br.commands) {
                        if (cmd == null) continue;
                        String clean = cmd.trim();
                        if (clean.isEmpty()) continue;
                        if (clean.startsWith("/")) clean = clean.substring(1);
                        runConsole(clean);
                    }
                });

            } catch (Exception e) {
                getLogger().warning("Bridge error: " + e.getMessage());
            }
        });
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

        byte[] bytes = is.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("johnaicontroller.use")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        NPC john = getJohn();
        if (john == null) {
            sender.sendMessage("§cNPC 'John' not found. Create it first: /npc create John --type player");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e/john say <text>");
            sender.sendMessage("§e/john move <x> <y> <z> [world]");
            sender.sendMessage("§e/john tp <player>");
            sender.sendMessage("§e/john look <player>");
            sender.sendMessage("§e/john vulnerable");
            sender.sendMessage("§e/john respawndelay <seconds>");
            sender.sendMessage("§e/john skinname <minecraftName>");
            sender.sendMessage("§e/john skintex <value> <signature>");
            return true;
        }

        runConsole("npc select " + john.getId());
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "say": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john say <text>");
                    return true;
                }
                String msg = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                Bukkit.broadcastMessage("§bJohn§7: " + msg);
                return true;
            }

            case "tp": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john tp <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not online.");
                    return true;
                }
                Location loc = target.getLocation();
                if (!john.isSpawned()) john.spawn(loc);
                else john.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                sender.sendMessage("§aTeleported John to " + target.getName());
                return true;
            }

            case "look": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john look <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not online.");
                    return true;
                }
                if (!john.isSpawned() || john.getEntity() == null) {
                    sender.sendMessage("§cJohn is not spawned.");
                    return true;
                }

                Entity je = john.getEntity();
                Location from = je.getLocation();
                Location to = target.getEyeLocation();

                Vector dir = to.toVector().subtract(from.toVector());
                Location newLoc = from.clone();
                newLoc.setDirection(dir);
                je.teleport(newLoc);

                sender.sendMessage("§aJohn now looks at " + target.getName());
                return true;
            }

            case "move": {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /john move <x> <y> <z> [world]");
                    return true;
                }
                try {
                    double x = Double.parseDouble(args[1]);
                    double y = Double.parseDouble(args[2]);
                    double z = Double.parseDouble(args[3]);

                    World world;
                    if (args.length >= 5) {
                        world = Bukkit.getWorld(args[4]);
                    } else {
                        Entity e = john.getEntity();
                        world = (e != null) ? e.getWorld() : Bukkit.getWorlds().get(0);
                    }

                    if (world == null) {
                        sender.sendMessage("§cWorld not found.");
                        return true;
                    }

                    Location loc = new Location(world, x, y, z);
                    if (!john.isSpawned()) john.spawn(loc);
                    else john.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);

                    sender.sendMessage("§aMoved John.");
                } catch (Exception ex) {
                    sender.sendMessage("§cInvalid coordinates.");
                }
                return true;
            }

            case "vulnerable": {
                runConsole("npc vulnerable"); // your citizens build toggles
                sender.sendMessage("§aToggled John vulnerability.");
                return true;
            }

            case "respawndelay": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john respawndelay <seconds>");
                    return true;
                }
                try {
                    int sec = Integer.parseInt(args[1]);
                    boolean ok = runConsole("npc respawndelay " + sec);
                    if (!ok) runConsole("npc respawn " + sec);
                    sender.sendMessage("§aSet respawn delay.");
                } catch (Exception ex) {
                    sender.sendMessage("§cInvalid number.");
                }
                return true;
            }

            case "skinname": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john skinname <name>");
                    return true;
                }
                runConsole("npc skin " + args[1]);
                sender.sendMessage("§aSkin name set.");
                return true;
            }

            case "skintex": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /john skintex <value> <signature>");
                    return true;
                }
                String value = args[1];
                String signature = args[2];
                runConsole("npc skin John -t " + value + " " + signature);
                sender.sendMessage("§aSkin texture/signature applied.");
                return true;
            }

            default:
                sender.sendMessage("§cUnknown subcommand.");
                return true;
        }
    }

    static class BridgeResponse {
        List<String> commands;
    }
}
