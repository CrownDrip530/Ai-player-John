package me.cliff.johnaicontroller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
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

    // ---------- Simulated inventory ----------
    private final Map<Material, Integer> johnInv = new HashMap<>();
    private Material equippedMainHand = null;
    private Material equippedOffHand = null;
    private Material equippedHelmet = null;
    private Material equippedChest = null;
    private Material equippedLegs = null;
    private Material equippedBoots = null;

    private File dataFile;
    private FileConfiguration dataCfg;

    // ---------- Movement/behavior ----------
    private boolean idleEnabled = true;
    private UUID followTarget = null;
    private double followSpeed = 1.3;
    private BukkitTask followTask;

    // ---------- Inventory UI ----------
    private static final String JOHN_INV_TITLE = "John Inventory";
    private final Set<UUID> johnInvViewers = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bridgeUrl = getConfig().getString("bridgeUrl", bridgeUrl);

        setupDataFile();
        loadJohnData();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Follow scheduler (API-based, reliable)
        followTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (followTarget == null) return;

            NPC john = getJohn();
            if (john == null || !john.isSpawned() || john.getEntity() == null) return;

            Player target = Bukkit.getPlayer(followTarget);
            if (target == null || !target.isOnline()) return;
            if (!target.getWorld().equals(john.getEntity().getWorld())) return;

            double dist = john.getEntity().getLocation().distance(target.getLocation());
            Navigator nav = john.getNavigator();
            nav.getDefaultParameters().speedModifier((float) clamp(followSpeed, 0.7, 2.0));
            nav.getDefaultParameters().distanceMargin(1.6);

            if (dist > 2.0) nav.setTarget(target, false);
            else nav.cancelNavigation();
        }, 10L, 10L);

        getLogger().info("JohnAIController fixed build enabled.");
    }

    @Override
    public void onDisable() {
        if (followTask != null) followTask.cancel();
        saveJohnData();
        getLogger().info("JohnAIController disabled.");
    }

    // ---------- Files ----------
    private void setupDataFile() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "john-data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (Exception ignored) {}
        }
        dataCfg = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveJohnData() {
        dataCfg.set("inventory", null);
        for (Map.Entry<Material, Integer> e : johnInv.entrySet()) {
            dataCfg.set("inventory." + e.getKey().name(), e.getValue());
        }

        dataCfg.set("equip.main", materialName(equippedMainHand));
        dataCfg.set("equip.off", materialName(equippedOffHand));
        dataCfg.set("equip.helmet", materialName(equippedHelmet));
        dataCfg.set("equip.chest", materialName(equippedChest));
        dataCfg.set("equip.legs", materialName(equippedLegs));
        dataCfg.set("equip.boots", materialName(equippedBoots));

        try { dataCfg.save(dataFile); }
        catch (Exception e) { getLogger().warning("Failed to save john-data.yml: " + e.getMessage()); }
    }

    private void loadJohnData() {
        johnInv.clear();
        if (dataCfg.isConfigurationSection("inventory")) {
            for (String key : Objects.requireNonNull(dataCfg.getConfigurationSection("inventory")).getKeys(false)) {
                Material m = Material.matchMaterial(key);
                int count = dataCfg.getInt("inventory." + key, 0);
                if (m != null && count > 0) johnInv.put(m, count);
            }
        }

        equippedMainHand = parseMaterial(dataCfg.getString("equip.main"));
        equippedOffHand = parseMaterial(dataCfg.getString("equip.off"));
        equippedHelmet = parseMaterial(dataCfg.getString("equip.helmet"));
        equippedChest = parseMaterial(dataCfg.getString("equip.chest"));
        equippedLegs = parseMaterial(dataCfg.getString("equip.legs"));
        equippedBoots = parseMaterial(dataCfg.getString("equip.boots"));

        applyVisualEquipment();
    }

    private String materialName(Material m) { return m == null ? null : m.name(); }
    private Material parseMaterial(String s) { return (s == null || s.isEmpty()) ? null : Material.matchMaterial(s); }

    // ---------- NPC ----------
    private NPC getJohn() {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if ("John".equals(npc.getName())) return npc;
        }
        return null;
    }

    private boolean runConsole(String cmd) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    // ---------- Inventory helpers ----------
    private int getCount(Material m) { return johnInv.getOrDefault(m, 0); }

    private void addItem(Material m, int amount) {
        if (m == null || amount <= 0) return;
        johnInv.put(m, getCount(m) + amount);
    }

    private boolean removeItem(Material m, int amount) {
        int have = getCount(m);
        if (m == null || amount <= 0 || have < amount) return false;
        int left = have - amount;
        if (left <= 0) johnInv.remove(m);
        else johnInv.put(m, left);
        return true;
    }

    private boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private boolean isFood(Material m) {
        return switch (m) {
            case APPLE, BREAD, COOKED_BEEF, COOKED_CHICKEN, COOKED_MUTTON, COOKED_PORKCHOP, COOKED_SALMON,
                    BAKED_POTATO, CARROT, GOLDEN_CARROT, BEETROOT, COOKED_RABBIT, MUSHROOM_STEW, RABBIT_STEW,
                    PUMPKIN_PIE, SWEET_BERRIES, MELON_SLICE, DRIED_KELP -> true;
            default -> false;
        };
    }

    private String armorSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return "helmet";
        if (n.endsWith("_CHESTPLATE")) return "chest";
        if (n.endsWith("_LEGGINGS")) return "legs";
        if (n.endsWith("_BOOTS")) return "boots";
        return null;
    }

    private ItemStack itemOrAir(Material m) {
        return (m == null) ? new ItemStack(Material.AIR) : new ItemStack(m, 1);
    }

    private void applyVisualEquipment() {
        NPC john = getJohn();
        if (john == null || !john.isSpawned() || john.getEntity() == null) return;
        if (!(john.getEntity() instanceof LivingEntity le)) return;

        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;

        eq.setItemInMainHand(itemOrAir(equippedMainHand));
        eq.setItemInOffHand(itemOrAir(equippedOffHand));
        eq.setHelmet(itemOrAir(equippedHelmet));
        eq.setChestplate(itemOrAir(equippedChest));
        eq.setLeggings(itemOrAir(equippedLegs));
        eq.setBoots(itemOrAir(equippedBoots));
    }

    private JsonArray inventoryToJson() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<Material, Integer> e : johnInv.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("item", e.getKey().name());
            o.addProperty("count", e.getValue());
            arr.add(o);
        }
        return arr;
    }

    private JsonObject equipmentToJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mainHand", materialName(equippedMainHand));
        o.addProperty("offHand", materialName(equippedOffHand));
        o.addProperty("helmet", materialName(equippedHelmet));
        o.addProperty("chestplate", materialName(equippedChest));
        o.addProperty("leggings", materialName(equippedLegs));
        o.addProperty("boots", materialName(equippedBoots));
        return o;
    }

    // ---------- Chat -> bridge ----------
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String playerName = event.getPlayer().getName();
        String message = event.getMessage().trim();
        if (message.isEmpty()) return;

        // Build context ON MAIN THREAD
        Bukkit.getScheduler().runTask(this, () -> {
            JsonObject payload = buildContextPayload(playerName, message);

            // Send HTTP async
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String response = postJson(bridgeUrl, gson.toJson(payload));
                    BridgeResponse br = gson.fromJson(response, BridgeResponse.class);
                    if (br == null || br.commands == null || br.commands.isEmpty()) return;

                    // Execute commands ON MAIN THREAD
                    Bukkit.getScheduler().runTask(this, () -> {
                        for (String cmd : br.commands) {
                            if (cmd == null || cmd.isBlank()) continue;
                            String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                            runConsole(clean);
                        }
                    });
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
        } else {
            johnObj.addProperty("spawned", false);
        }

        johnObj.add("inventory", inventoryToJson());
        johnObj.add("equipment", equipmentToJson());
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

        return root;
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

    private String blockUnder(Location l) {
        return l.clone().subtract(0, 1, 0).getBlock().getType().name();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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

    // ---------- Commands ----------
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
            sender.sendMessage("§e/john inv");
            sender.sendMessage("§e/john pickup [radius]");
            sender.sendMessage("§e/john drop <item> [count]");
            sender.sendMessage("§e/john equip <item>");
            sender.sendMessage("§e/john offhand <item>");
            sender.sendMessage("§e/john use <item>");
            sender.sendMessage("§e/john say <text>");
            sender.sendMessage("§e/john look <player>");
            sender.sendMessage("§e/john goto <x> <y> <z> [world] [speed]");
            sender.sendMessage("§e/john follow <player> [speed]");
            sender.sendMessage("§e/john stop");
            sender.sendMessage("§e/john idle <on|off>");
            sender.sendMessage("§e/john vulnerable");
            sender.sendMessage("§e/john respawndelay <seconds>");
            sender.sendMessage("§e/john skinname <name>");
            sender.sendMessage("§e/john skintex <value> <signature>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "inv": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Player only.");
                    return true;
                }
                openJohnInventoryUI(p);
                return true;
            }

            case "pickup": {
                if (!john.isSpawned() || john.getEntity() == null) {
                    sender.sendMessage("John is not spawned.");
                    return true;
                }
                double radius = 3.0;
                if (args.length >= 2) {
                    try { radius = Double.parseDouble(args[1]); } catch (Exception ignored) {}
                }
                int picked = pickupNearbyItems(john.getEntity().getLocation(), radius);
                saveJohnData();
                sender.sendMessage("§aJohn picked up " + picked + " item stacks.");
                return true;
            }

            case "drop": {
                if (!john.isSpawned() || john.getEntity() == null) {
                    sender.sendMessage("John is not spawned.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john drop <item> [count]");
                    return true;
                }
                Material m = Material.matchMaterial(args[1]);
                if (m == null) {
                    sender.sendMessage("Unknown item.");
                    return true;
                }
                int count = 1;
                if (args.length >= 3) {
                    try { count = Math.max(1, Integer.parseInt(args[2])); } catch (Exception ignored) {}
                }
                if (!removeItem(m, count)) {
                    sender.sendMessage("John doesn't have enough " + m.name());
                    return true;
                }

                Location l = john.getEntity().getLocation().clone().add(0, 1.0, 0);
                l.getWorld().dropItemNaturally(l, new ItemStack(m, count));

                saveJohnData();
                sender.sendMessage("§aJohn dropped " + count + " " + m.name());
                return true;
            }

            case "equip": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john equip <item>");
                    return true;
                }
                Material m = Material.matchMaterial(args[1]);
                if (m == null) {
                    sender.sendMessage("Unknown item.");
                    return true;
                }
                if (getCount(m) <= 0) {
                    sender.sendMessage("John doesn't have that item.");
                    return true;
                }

                if (isArmor(m)) {
                    String slot = armorSlot(m);
                    if ("helmet".equals(slot)) equippedHelmet = m;
                    else if ("chest".equals(slot)) equippedChest = m;
                    else if ("legs".equals(slot)) equippedLegs = m;
                    else if ("boots".equals(slot)) equippedBoots = m;
                } else {
                    equippedMainHand = m;
                }

                applyVisualEquipment();
                saveJohnData();
                sender.sendMessage("§aEquipped " + m.name());
                return true;
            }

            case "offhand": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john offhand <item>");
                    return true;
                }
                Material m = Material.matchMaterial(args[1]);
                if (m == null) {
                    sender.sendMessage("Unknown item.");
                    return true;
                }
                if (getCount(m) <= 0) {
                    sender.sendMessage("John doesn't have that item.");
                    return true;
                }

                equippedOffHand = m;
                applyVisualEquipment();
                saveJohnData();
                sender.sendMessage("§aOffhand set to " + m.name());
                return true;
            }

            case "use": {
                if (!john.isSpawned() || john.getEntity() == null) {
                    sender.sendMessage("John is not spawned.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john use <item>");
                    return true;
                }
                Material m = Material.matchMaterial(args[1]);
                if (m == null) {
                    sender.sendMessage("Unknown item.");
                    return true;
                }
                if (getCount(m) <= 0) {
                    sender.sendMessage("John doesn't have that item.");
                    return true;
                }

                boolean ok = simulateUseItem(john.getEntity(), m);
                if (ok) {
                    removeItem(m, 1);
                    saveJohnData();
                    sender.sendMessage("§aJohn used " + m.name());
                } else {
                    sender.sendMessage("§eJohn can't really use that item yet.");
                }
                return true;
            }

            case "say": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john say <text>");
                    return true;
                }
                String msg = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                Bukkit.broadcastMessage("§bJohn§7: " + msg);
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
                Location from = je.getLocation();
                Location to = target.getLocation().clone().add(0, 0.6, 0); // lower to avoid looking too high
                Vector dir = to.toVector().subtract(from.toVector());
                Location out = from.clone();
                out.setDirection(dir);
                je.teleport(out);

                sender.sendMessage("John now looks at " + target.getName());
                return true;
            }

            case "goto": {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /john goto <x> <y> <z> [world] [speed]");
                    return true;
                }
                try {
                    double x = Double.parseDouble(args[1]);
                    double y = Double.parseDouble(args[2]);
                    double z = Double.parseDouble(args[3]);

                    World world = null;
                    double speed = 1.2;

                    if (args.length >= 5) {
                        World maybe = Bukkit.getWorld(args[4]);
                        if (maybe != null) world = maybe;
                        else speed = Double.parseDouble(args[4]);
                    }
                    if (args.length >= 6) speed = Double.parseDouble(args[5]);

                    if (world == null) {
                        Entity e = john.getEntity();
                        world = (e != null) ? e.getWorld() : Bukkit.getWorlds().get(0);
                    }

                    if (!john.isSpawned()) {
                        john.spawn(new Location(world, x, y, z));
                    }

                    followTarget = null; // stop follow mode when goto
                    Navigator nav = john.getNavigator();
                    nav.getDefaultParameters().speedModifier((float) clamp(speed, 0.7, 2.0));
                    nav.getDefaultParameters().distanceMargin(1.3);
                    nav.setTarget(new Location(world, x, y, z));

                    sender.sendMessage("John walking to target.");
                } catch (Exception ex) {
                    sender.sendMessage("Invalid args.");
                }
                return true;
            }

            case "follow": {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /john follow <player> [speed]");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not online.");
                    return true;
                }

                double speed = 1.3;
                if (args.length >= 3) {
                    try { speed = Double.parseDouble(args[2]); } catch (Exception ignored) {}
                }
                speed = clamp(speed, 0.7, 2.0);

                if (!john.isSpawned()) john.spawn(target.getLocation());

                followTarget = target.getUniqueId();
                followSpeed = speed;
                sender.sendMessage("John now following " + target.getName() + " at speed " + speed);
                return true;
            }

            case "stop": {
                followTarget = null;
                if (john.isSpawned()) john.getNavigator().cancelNavigation();
                sender.sendMessage("John stopped.");
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

            case "vulnerable": {
                runConsole("npc select " + john.getId());
                runConsole("npc vulnerable"); // your Citizens build uses toggle
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
                    runConsole("npc select " + john.getId());
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
                runConsole("npc select " + john.getId());
                runConsole("npc skin " + args[1]);
                sender.sendMessage("Skin name set.");
                return true;
            }

            case "skintex": {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /john skintex <value> <signature>");
                    return true;
                }
                runConsole("npc select " + john.getId());
                runConsole("npc skin John -t " + args[1] + " " + args[2]);
                sender.sendMessage("Skin texture applied.");
                return true;
            }

            default:
                sender.sendMessage("Unknown subcommand.");
                return true;
        }
    }

    // ---------- Inventory GUI persistence ----------
    @EventHandler
    public void onJohnInvClick(InventoryClickEvent e) {
        if (e.getView() == null) return;
        if (!JOHN_INV_TITLE.equals(e.getView().getTitle())) return;
        if (e.getClickedInventory() == null) return;

        // Only top inv (John UI) editable check
        if (!e.getClickedInventory().equals(e.getView().getTopInventory())) return;

        // lock equipment display slots
        int s = e.getSlot();
        if (s == 45 || s == 46 || s == 50 || s == 51 || s == 52 || s == 53) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJohnInvClose(InventoryCloseEvent e) {
        if (!JOHN_INV_TITLE.equals(e.getView().getTitle())) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!johnInvViewers.remove(p.getUniqueId())) return;

        Inventory top = e.getView().getTopInventory();

        // rebuild from storage slots 0..44 only
        johnInv.clear();
        for (int i = 0; i <= 44; i++) {
            ItemStack it = top.getItem(i);
            if (it == null || it.getType() == Material.AIR || it.getAmount() <= 0) continue;
            addItem(it.getType(), it.getAmount());
        }

        saveJohnData();
    }

    // ---------- Feature impl ----------
    private int pickupNearbyItems(Location center, double radius) {
        int pickedStacks = 0;
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Item item)) continue;
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) continue;

            addItem(stack.getType(), stack.getAmount());
            item.remove();
            pickedStacks++;
        }
        applyVisualEquipment();
        return pickedStacks;
    }

    private void openJohnInventoryUI(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 54, JOHN_INV_TITLE);

        int slot = 0;
        for (Map.Entry<Material, Integer> e : johnInv.entrySet()) {
            if (slot >= 45) break; // reserve bottom row for equipment display
            inv.setItem(slot++, new ItemStack(e.getKey(), Math.min(64, e.getValue())));
        }

        // equipment display row
        inv.setItem(45, itemOrAir(equippedMainHand));
        inv.setItem(46, itemOrAir(equippedOffHand));
        inv.setItem(50, itemOrAir(equippedHelmet));
        inv.setItem(51, itemOrAir(equippedChest));
        inv.setItem(52, itemOrAir(equippedLegs));
        inv.setItem(53, itemOrAir(equippedBoots));

        johnInvViewers.add(viewer.getUniqueId());
        viewer.openInventory(inv);
    }

    private boolean simulateUseItem(Entity johnEntity, Material m) {
        Location l = johnEntity.getLocation();
        World w = l.getWorld();

        if (isFood(m)) {
            w.playSound(l, Sound.ENTITY_GENERIC_EAT, 1f, 1f);
            return true;
        }

        if (m == Material.SHIELD) {
            equippedOffHand = Material.SHIELD;
            applyVisualEquipment();
            w.playSound(l, Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.0f);
            return true;
        }

        if (m == Material.WATER_BUCKET) {
            Block b = l.getBlock();
            if (b.getType() == Material.AIR) b.setType(Material.WATER);
            w.playSound(l, Sound.ITEM_BUCKET_EMPTY, 1f, 1f);
            return true;
        }

        if (m == Material.TORCH) {
            Block b = l.getBlock().getRelative(0, 1, 0);
            if (b.getType() == Material.AIR) {
                b.setType(Material.TORCH);
                w.playSound(l, Sound.BLOCK_WOOD_PLACE, 0.8f, 1.1f);
                return true;
            }
        }

        if (m == Material.POTION) {
            if (johnEntity instanceof LivingEntity le) {
                le.addPotionEffect(PotionEffectType.REGENERATION.createEffect(100, 0));
            }
            w.playSound(l, Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
            return true;
        }

        return false;
    }

    static class BridgeResponse {
        List<String> commands;
    }
}
