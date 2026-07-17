package me.cliff.johnaicontroller;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.stream.Collectors;

public class JohnAIControllerPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getLogger().info("JohnAIController enabled.");
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
            sender.sendMessage("§e/john vulnerable <true|false>");
            sender.sendMessage("§e/john respawndelay <seconds>");
            sender.sendMessage("§e/john skinname <minecraftName>");
            sender.sendMessage("§e/john skintex <value> <signature>");
            sender.sendMessage("§e/john respawnhere");
            return true;
        }

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

                    sender.sendMessage("§aMoved John to " + x + " " + y + " " + z + " (" + world.getName() + ")");
                } catch (Exception ex) {
                    sender.sendMessage("§cInvalid coordinates.");
                }
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

            case "vulnerable": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john vulnerable <true|false>");
                    return true;
                }
                boolean vulnerable = Boolean.parseBoolean(args[1]);
                john.data().setPersistent(NPC.DEFAULT_PROTECTED_METADATA, !vulnerable);
                sender.sendMessage("§aJohn vulnerable = " + vulnerable);
                return true;
            }

            case "respawndelay": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john respawndelay <seconds>");
                    return true;
                }
                try {
                    int sec = Integer.parseInt(args[1]);
                    john.data().setPersistent(NPC.RESPAWN_DELAY_METADATA, sec);
                    sender.sendMessage("§aJohn respawn delay = " + sec + "s");
                } catch (Exception ex) {
                    sender.sendMessage("§cInvalid number.");
                }
                return true;
            }

            case "skinname": {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /john skinname <minecraftName>");
                    return true;
                }
                String skinName = args[1];
                SkinTrait skinTrait = john.getOrAddTrait(SkinTrait.class);
                skinTrait.setSkinName(skinName, true);
                sender.sendMessage("§aJohn skin set to " + skinName);
                return true;
            }

            case "skintex": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /john skintex <value> <signature>");
                    return true;
                }
                String value = args[1];
                String signature = args[2];

                SkinTrait skinTrait = john.getOrAddTrait(SkinTrait.class);
                skinTrait.setSkinPersistent("JohnCustomSkin", signature, value);
                sender.sendMessage("§aJohn skin texture/signature applied.");
                return true;
            }

            case "respawnhere": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cOnly players can use /john respawnhere");
                    return true;
                }
                Player p = (Player) sender;
                Location loc = p.getLocation();

                john.data().setPersistent(NPC.RESPAWN_LOCATION_METADATA, loc);
                sender.sendMessage("§aSet John respawn location to your current position.");
                return true;
            }

            default:
                sender.sendMessage("§cUnknown subcommand.");
                return true;
        }
    }
}
