package net.momirealms.customnameplates.paper.mechanic.nameplate;

import com.comphenix.protocol.ProtocolLibrary;
import me.clip.placeholderapi.PlaceholderAPI;
import net.momirealms.customnameplates.api.CustomNameplatesPlugin;
import net.momirealms.customnameplates.api.data.OnlineUser;
import net.momirealms.customnameplates.api.event.NameplateDataLoadEvent;
import net.momirealms.customnameplates.api.manager.NameplateManager;
import net.momirealms.customnameplates.api.manager.TeamTagManager;
import net.momirealms.customnameplates.api.manager.UnlimitedTagManager;
import net.momirealms.customnameplates.api.mechanic.character.CharacterArranger;
import net.momirealms.customnameplates.api.mechanic.character.ConfiguredChar;
import net.momirealms.customnameplates.api.mechanic.nameplate.CachedNameplate;
import net.momirealms.customnameplates.api.mechanic.nameplate.Nameplate;
import net.momirealms.customnameplates.api.mechanic.nameplate.TagMode;
import net.momirealms.customnameplates.api.mechanic.tag.NameplatePlayer;
import net.momirealms.customnameplates.api.mechanic.tag.unlimited.UnlimitedTagSetting;
import net.momirealms.customnameplates.api.mechanic.team.TeamColor;
import net.momirealms.customnameplates.api.scheduler.CancellableTask;
import net.momirealms.customnameplates.api.util.FontUtils;
import net.momirealms.customnameplates.api.util.LogUtils;
import net.momirealms.customnameplates.paper.mechanic.nameplate.tag.listener.*;
import net.momirealms.customnameplates.paper.mechanic.nameplate.tag.team.TeamTagManagerImpl;
import net.momirealms.customnameplates.paper.mechanic.nameplate.tag.unlimited.UnlimitedTagManagerImpl;
import net.momirealms.customnameplates.paper.setting.CNConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPoseChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NameplateManagerImpl implements NameplateManager, Listener {

    private final CustomNameplatesPlugin plugin;
    /* A map for nameplate configs */
    private final HashMap<String, Nameplate> nameplateMap;
    /* A map for cached nameplates */
    private final ConcurrentHashMap<UUID, CachedNameplate> cachedNameplateMap;
    /* A map to quickly get Entity by its EntityID */
    private final ConcurrentHashMap<Integer, Entity> entityID2EntityMap;
    /* A map to store players that have tags */
    private final ConcurrentHashMap<UUID, NameplatePlayer> nameplatePlayerMap;
    private CancellableTask nameplateRefreshTask;
    private final TeamTagManagerImpl teamTagManager;
    private final UnlimitedTagManagerImpl unlimitedTagManager;
    private final EntityDestroyListener entityDestroyListener;
    private final EntitySpawnListener entitySpawnListener;
    private final EntityMoveListener entityMoveListener;
    private final EntityLookListener entityLookListener;
    private final EntityTeleportListener entityTeleportListener;

    /**
     * Configs
     */
    private long teamRefreshFrequency;
    private String teamPrefix;
    private String teamSuffix;
    private boolean fixTab;
    /* Is proxy server working */
    private boolean proxyMode;
    /* TEAM & UNLIMITED */
    private TagMode tagMode;
    private int previewDuration;
    private String defaultNameplate;
    private String playerName, prefix, suffix;
    private long refreshFrequency;
    private final List<UnlimitedTagSetting> tagSettings;

    public NameplateManagerImpl(CustomNameplatesPlugin plugin) {
        this.plugin = plugin;
        this.nameplateMap = new HashMap<>();
        this.tagSettings = new ArrayList<>();

        this.cachedNameplateMap = new ConcurrentHashMap<>();
        this.entityID2EntityMap = new ConcurrentHashMap<>();
        this.nameplatePlayerMap = new ConcurrentHashMap<>();
        this.teamTagManager = new TeamTagManagerImpl(this);
        this.unlimitedTagManager = new UnlimitedTagManagerImpl(this);

        this.entityTeleportListener = new EntityTeleportListener(this);
        this.entityDestroyListener = new EntityDestroyListener(this);
        this.entitySpawnListener = new EntitySpawnListener(this);
        this.entityLookListener = new EntityLookListener(this);
        this.entityMoveListener = new EntityMoveListener(this);
    }

    public void reload() {
        unload();
        load();
    }

    public void unload() {
        if (this.nameplateRefreshTask != null && !this.nameplateRefreshTask.isCancelled()) {
            this.nameplateRefreshTask.cancel();
        }
        this.nameplateMap.clear();
        this.tagSettings.clear();
        this.teamTagManager.unload();
        this.unlimitedTagManager.unload();

        HandlerList.unregisterAll(this);
        ProtocolLibrary.getProtocolManager().removePacketListener(entityDestroyListener);
        ProtocolLibrary.getProtocolManager().removePacketListener(entitySpawnListener);
        ProtocolLibrary.getProtocolManager().removePacketListener(entityLookListener);
        ProtocolLibrary.getProtocolManager().removePacketListener(entityMoveListener);
        ProtocolLibrary.getProtocolManager().removePacketListener(entityTeleportListener);
    }

    public void load() {
        if (!CNConfig.nameplateModule) return;
        this.loadConfig();
        this.loadNameplates();

        this.teamTagManager.load(teamRefreshFrequency, fixTab);
        this.unlimitedTagManager.load();

        this.nameplateRefreshTask = plugin.getScheduler().runTaskAsyncTimer(() -> {
            for (OnlineUser user : plugin.getStorageManager().getOnlineUsers()) {
                updateCachedNameplate(user.getPlayer(), user.getNameplate());
            }
        }, refreshFrequency * 50, refreshFrequency * 50, TimeUnit.MILLISECONDS);

        for (Player online : Bukkit.getOnlinePlayers()) {
            createNameTag(online);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        ProtocolLibrary.getProtocolManager().addPacketListener(entityDestroyListener);
        ProtocolLibrary.getProtocolManager().addPacketListener(entitySpawnListener);
        ProtocolLibrary.getProtocolManager().addPacketListener(entityLookListener);
        ProtocolLibrary.getProtocolManager().addPacketListener(entityMoveListener);
        ProtocolLibrary.getProtocolManager().addPacketListener(entityTeleportListener);
    }

    private void loadConfig() {
        YamlConfiguration config = plugin.getConfig("configs" + File.separator + "nameplate.yml");

        tagMode = TagMode.valueOf(config.getString("mode", "TEAM").toUpperCase(Locale.ENGLISH));
        proxyMode = config.getBoolean("proxy", false);
        previewDuration = config.getInt("preview-duration", 5);
        defaultNameplate = config.getString("default-nameplate", "none");

        playerName = config.getString("nameplate.player-name", "%player_name%");
        prefix = config.getString("nameplate.prefix", "");
        suffix = config.getString("nameplate.suffix", "");
        refreshFrequency = config.getInt("nameplate.refresh-frequency", 10);

        teamPrefix = config.getString("team.prefix", "");
        teamSuffix = config.getString("team.suffix", "");
        teamRefreshFrequency = config.getInt("team.refresh-frequency", 10);
        fixTab = config.getBoolean("team.fix-Tab", true);

        ConfigurationSection unlimitedSection = config.getConfigurationSection("unlimited");
        if (unlimitedSection != null) {
            for (Map.Entry<String, Object> entry : unlimitedSection.getValues(false).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection innerSection) {
                    tagSettings.add(
                            UnlimitedTagSetting.builder()
                                .rawText(innerSection.getString("text", ""))
                                .refreshFrequency(innerSection.getInt("refresh-frequency", 20))
                                .checkFrequency(innerSection.getInt("check-frequency", 20))
                                .verticalOffset(innerSection.getDouble("vertical-offset", -1))
                                .ownerRequirements(plugin.getRequirementManager().getRequirements(innerSection.getConfigurationSection("owner-conditions")))
                                .viewerRequirements(plugin.getRequirementManager().getRequirements(innerSection.getConfigurationSection("viewer-conditions")))
                                .build()
                    );
                }
            }
        }
    }

    private void loadNameplates() {
        File npFolder = new File(plugin.getDataFolder(), "contents" + File.separator + "nameplates");
        if (!npFolder.exists() && npFolder.mkdirs()) {
            saveDefaultNameplates();
        }
        File[] npConfigFiles = npFolder.listFiles(file -> file.getName().endsWith(".yml"));
        if (npConfigFiles == null) return;
        Arrays.sort(npConfigFiles, Comparator.comparing(File::getName));
        for (File npConfigFile : npConfigFiles) {

            String key = npConfigFile.getName().substring(0, npConfigFile.getName().length() - 4);
            if (key.equals("none")) {
                LogUtils.severe("You can't use 'none' as nameplate's key");
                continue;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(npConfigFile);
            if (!registerNameplate(
                    key,
                    Nameplate.builder()
                            .displayName(config.getString("display-name", key))
                            .teamColor(TeamColor.valueOf(config.getString("name-color", "none").toUpperCase(Locale.ENGLISH)))
                            .namePrefix(config.getString("name-prefix", ""))
                            .nameSuffix(config.getString("name-suffix", ""))
                            .left(ConfiguredChar.builder()
                                    .character(CharacterArranger.getAndIncrease())
                                    .png(config.getString("left.image", key + "_left"))
                                    .height(config.getInt("left.height", 16))
                                    .ascent(config.getInt("left.ascent", 12))
                                    .width(config.getInt("left.width", 16))
                                    .build())
                            .right(ConfiguredChar.builder()
                                    .character(CharacterArranger.getAndIncrease())
                                    .png(config.getString("right.image", key + "_right"))
                                    .height(config.getInt("right.height", 16))
                                    .ascent(config.getInt("right.ascent", 12))
                                    .width(config.getInt("right.width", 16))
                                    .build())
                            .middle(ConfiguredChar.builder()
                                    .character(CharacterArranger.getAndIncrease())
                                    .png(config.getString("middle.image", key + "_middle"))
                                    .height(config.getInt("middle.height", 16))
                                    .ascent(config.getInt("middle.ascent", 12))
                                    .width(config.getInt("middle.width", 16))
                                    .build())
                            .build())
            ) {
                LogUtils.warn("Found duplicated nameplate: " + key);
            }
        }
    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDataLoaded(NameplateDataLoadEvent event) {
        OnlineUser data = event.getOnlineUser();
        String nameplate = data.getNameplateKey();
        if (nameplate.equals("none")) {
            nameplate = defaultNameplate;
        }

        if (!nameplate.equals("none") && !containsNameplate(nameplate)) {
            if (nameplate.equals(defaultNameplate)) {
                LogUtils.severe("Default nameplate doesn't exist");
                return;
            }

            LogUtils.severe("Nameplate " + nameplate + " doesn't exist. To prevent bugs, player " + event.getUUID() + " 's nameplate data is reset");
            data.setNameplate("none");
            plugin.getStorageManager().saveOnlinePlayerData(event.getUUID());
            return;
        }

        Nameplate np = getNameplate(nameplate);
        CachedNameplate cachedNameplate = new CachedNameplate();
        putCachedNameplateToMap(event.getUUID(), cachedNameplate);
        updateCachedNameplate(cachedNameplate, data.getPlayer(), np);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        this.putEntityIDToMap(player.getEntityId(), player);
        if (!CNConfig.isOtherTeamPluginHooked())
            if (!proxyMode) plugin.getTeamManager().createTeam(player);
            else plugin.getTeamManager().createProxyTeam(player);
        this.createNameTag(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        this.removeCachedNameplateFromMap(player.getUniqueId());
        this.removeEntityIDFromMap(player.getEntityId());

        this.teamTagManager.handlePlayerQuit(player);
        this.unlimitedTagManager.handlePlayerQuit(player);

        if (!proxyMode && !CNConfig.isOtherTeamPluginHooked()) {
            plugin.getTeamManager().removeTeam(player);
        }
    }

    @EventHandler (ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        var player = event.getPlayer();
        unlimitedTagManager.handlePlayerSneak(player, event.isSneaking(), player.isFlying());
    }

    @EventHandler (ignoreCancelled = true)
    public void onChangePose(EntityPoseChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            unlimitedTagManager.handlePlayerPose(player, event.getPose());
        }
    }

    @Override
    public boolean putEntityIDToMap(int entityID, Entity entity) {
        if (this.entityID2EntityMap.containsKey(entityID))
            return false;
        this.entityID2EntityMap.put(entityID, entity);
        return true;
    }

    @Override
    public Entity removeEntityIDFromMap(int entityID) {
        return this.entityID2EntityMap.remove(entityID);
    }

    @Override
    public boolean putCachedNameplateToMap(UUID uuid, CachedNameplate nameplate) {
        if (this.cachedNameplateMap.containsKey(uuid)) {
            return false;
        }
        this.cachedNameplateMap.put(uuid, nameplate);
        return true;
    }

    @Override
    public CachedNameplate removeCachedNameplateFromMap(UUID uuid) {
        return this.cachedNameplateMap.remove(uuid);
    }

    @Override
    public Player getPlayerByEntityID(int id) {
        Entity entity = entityID2EntityMap.get(id);
        if (entity instanceof Player player) {
            return player;
        }
        return null;
    }

    @Override
    public Entity getEntityByEntityID(int id) {
        return entityID2EntityMap.get(id);
    }

    @Override
    public boolean updateCachedNameplate(Player player) {
        Optional<OnlineUser> onlineUser = plugin.getStorageManager().getOnlineUser(player.getUniqueId());
        if (onlineUser.isEmpty()) return false;
        Nameplate nameplate = onlineUser.get().getNameplate();
        return updateCachedNameplate(player, nameplate);
    }

    @Override
    public boolean updateCachedNameplate(Player player, Nameplate nameplate) {
        CachedNameplate cachedNameplate = cachedNameplateMap.get(player.getUniqueId());
        if (cachedNameplate == null) return false;
        return updateCachedNameplate(cachedNameplate, player, nameplate);
    }

    @Override
    public CachedNameplate getCacheNameplate(Player player) {
        return cachedNameplateMap.get(player.getUniqueId());
    }

    @Override
    public void createNameTag(Player player) {
        if (tagMode == TagMode.TEAM) {
            putNameplatePlayerToMap(this.teamTagManager.createTagForPlayer(player, teamPrefix, teamSuffix));
        } else if (tagMode == TagMode.UNLIMITED) {
            putNameplatePlayerToMap(this.unlimitedTagManager.createTagForPlayer(player, tagSettings));
        }
    }

    @Override
    public void putNameplatePlayerToMap(NameplatePlayer player) {
        this.nameplatePlayerMap.put(player.getOwner().getUniqueId(), player);
    }

    @Override
    public NameplatePlayer removeNameplatePlayerFromMap(UUID uuid) {
        return this.nameplatePlayerMap.remove(uuid);
    }

    private boolean updateCachedNameplate(CachedNameplate cachedNameplate, Player player, Nameplate nameplate) {
        String parsePrefix = PlaceholderAPI.setPlaceholders(player, prefix);
        String parseName = PlaceholderAPI.setPlaceholders(player, playerName);
        String parseSuffix = PlaceholderAPI.setPlaceholders(player, suffix);
        if (nameplate != null) {
            int width= FontUtils.getTextWidth(
                      parsePrefix
                    + nameplate.getNamePrefix()
                    + parseName
                    + nameplate.getNameSuffix()
                    + parseSuffix
            );

            cachedNameplate.setTeamColor(nameplate.getTeamColor());
            cachedNameplate.setNamePrefix(nameplate.getNamePrefix());
            cachedNameplate.setNameSuffix(nameplate.getNameSuffix());
            cachedNameplate.setTagSuffix(parseSuffix + nameplate.getSuffixWithFont(width));
            cachedNameplate.setTagPrefix(nameplate.getPrefixWithFont(width) + parsePrefix);
        } else {
            cachedNameplate.setTeamColor(TeamColor.NONE);
            cachedNameplate.setNamePrefix("");
            cachedNameplate.setNameSuffix("");
            cachedNameplate.setTagPrefix(parsePrefix);
            cachedNameplate.setTagSuffix(parseSuffix);
        }
        cachedNameplate.setPlayerName(parseName);
        return true;
    }

    @Override
    public String getNameplatePrefix(Player player) {
        CachedNameplate cachedNameplate = cachedNameplateMap.get(player.getUniqueId());
        if (cachedNameplate == null) return "";
        return cachedNameplate.getTagPrefix();
    }

    @Override
    public String getNameplateSuffix(Player player) {
        CachedNameplate cachedNameplate = cachedNameplateMap.get(player.getUniqueId());
        if (cachedNameplate == null) return "";
        return cachedNameplate.getTagSuffix();
    }

    @Override
    public String getFullNameTag(Player player) {
        CachedNameplate cachedNameplate = cachedNameplateMap.get(player.getUniqueId());
        if (cachedNameplate == null) {
            return player.getName();
        }

        return    cachedNameplate.getTagPrefix()
                + cachedNameplate.getNamePrefix()
                + cachedNameplate.getPlayerName()
                + cachedNameplate.getNameSuffix()
                + cachedNameplate.getTagSuffix();
    }

    @Override
    public boolean registerNameplate(String key, Nameplate nameplate) {
        if (this.nameplateMap.containsKey(key)) return false;
        this.nameplateMap.put(key, nameplate);
        return true;
    }

    @Override
    public List<String> getAvailableNameplates(Player player) {
        List<String> nameplates = new ArrayList<>();
        for (String nameplate : nameplateMap.keySet()) {
            if (hasNameplate(player, nameplate)) {
                nameplates.add(nameplate);
            }
        }
        return nameplates;
    }

    @Override
    public boolean equipNameplate(Player player, String nameplateKey) {
        Nameplate nameplate = getNameplate(nameplateKey);
        if (nameplate == null) {
            return false;
        }
        plugin.getStorageManager().getOnlineUser(player.getUniqueId()).ifPresentOrElse(it -> {
            it.setNameplate(nameplateKey);
            plugin.getStorageManager().saveOnlinePlayerData(player.getUniqueId());
        }, () -> {
            LogUtils.severe("Player " + player.getName() + "'s data is not loaded.");
        });
        return true;
    }

    @Override
    public void unEquipNameplate(Player player) {
        plugin.getStorageManager().getOnlineUser(player.getUniqueId()).ifPresentOrElse(it -> {
            it.setNameplate("none");
            plugin.getStorageManager().saveOnlinePlayerData(player.getUniqueId());
        }, () -> {
            LogUtils.severe("Player " + player.getName() + "'s data is not loaded.");
        });
    }

    private void saveDefaultNameplates() {
        String[] png_list = new String[]{"cat", "egg", "cheems", "wither", "xmas", "halloween", "hutao", "starsky", "trident", "rabbit"};
        String[] part_list = new String[]{"_left.png", "_middle.png", "_right.png", ".yml"};
        for (String name : png_list) {
            for (String part : part_list) {
                plugin.saveResource("contents" + File.separator + "nameplates" + File.separator + name + part, false);
            }
        }
    }

    @Override
    public boolean unregisterNameplate(String key) {
        return this.nameplateMap.remove(key) != null;
    }

    @Override
    public boolean isProxyMode() {
        return proxyMode;
    }

    @Override
    public TagMode getTagMode() {
        return tagMode;
    }

    @Override
    @Nullable
    public Nameplate getNameplate(String key) {
        return nameplateMap.get(key);
    }

    @Override
    public Collection<Nameplate> getNameplates() {
        return nameplateMap.values();
    }

    @Override
    public boolean containsNameplate(String key) {
        return nameplateMap.containsKey(key);
    }

    @Override
    public boolean hasNameplate(Player player, String nameplate) {
        return player.hasPermission("nameplates.equip." + nameplate);
    }

    @Override
    public TeamColor getTeamColor(Player player) {
        CachedNameplate nameplate = getCacheNameplate(player);
        return nameplate == null ? TeamColor.WHITE : nameplate.getTeamColor();
    }

    @Override
    public String getDefaultNameplate() {
        return defaultNameplate;
    }

    @Override
    public TeamTagManager getTeamTagManager() {
        return teamTagManager;
    }

    @Override
    public UnlimitedTagManager getUnlimitedTagManager() {
        return unlimitedTagManager;
    }

    public void onEntityMove(Player receiver, int entityID, short x, short y, short z, boolean onGround) {
        unlimitedTagManager.handleEntityMovePacket(receiver, entityID, x, y, z, onGround);
    }

    public void onEntityDestroy(Player receiver, List<Integer> list) {
        teamTagManager.handleEntityDestroyPacket(receiver, list);
        unlimitedTagManager.handleEntityDestroyPacket(receiver, list);
    }

    public void onEntitySpawn(Player receiver, int entityID) {
        teamTagManager.handleEntitySpawnPacket(receiver, entityID);
        unlimitedTagManager.handleEntitySpawnPacket(receiver, entityID);
    }

    public void onEntityTeleport(Player receiver, int entityID, double x, double y, double z, boolean onGround) {
        unlimitedTagManager.handleEntityTeleportPacket(receiver, entityID, x, y, z, onGround);
    }
}