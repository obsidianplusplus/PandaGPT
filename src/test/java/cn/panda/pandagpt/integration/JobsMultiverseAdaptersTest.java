package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class JobsMultiverseAdaptersTest {
    private record Fixture(Player player, Plugin installed, World world) {}

    private Fixture fixture(String name) {
        var player = mock(Player.class);
        var installed = mock(Plugin.class);
        var world = mock(World.class);
        var server = mock(Server.class);
        var manager = mock(PluginManager.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin(name)).thenReturn(installed);
        when(installed.isEnabled()).thenReturn(true);
        when(world.getName()).thenReturn("current");
        return new Fixture(player, installed, world);
    }

    private ToolContext context(Fixture f) {
        return new ToolContext(
                f.player(), new AgentTurn(f.player().getUniqueId(), AgentTurn.Kind.CONVERSATION), "en_US");
    }

    public static class FakeJob {
        final String name;

        FakeJob(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return name + " title";
        }
    }

    public static class FakeProgress {
        public FakeJob getJob() {
            return new FakeJob("Miner");
        }

        public int getLevel() {
            return 4;
        }

        public double getExperience() {
            return 20.5;
        }

        public int getMaxExperience() {
            return 100;
        }
    }

    public static class FakeProfile {
        public List<FakeProgress> getJobProgression() {
            return List.of(new FakeProgress());
        }
    }

    public static class FakeJobsManager {
        Player asked;
        FakeProfile profile;

        public FakeProfile getJobsPlayer(Player player) {
            asked = player;
            return profile;
        }
    }

    public static class FakeJobs {
        static FakeJobsManager manager;
        static List<FakeJob> jobs;

        public static FakeJobsManager getPlayerManager() {
            return manager;
        }

        public static List<FakeJob> getJobs() {
            return jobs;
        }
    }

    @Test
    void jobsOnlyReadsCallerLoadedProfileAndHandlesMissingProfilesAndPermissions() throws Exception {
        var f = fixture("Jobs");
        FakeJobs.manager = new FakeJobsManager();
        var adapter = new JobsAdapter(f.installed(), JobsAdapter.Api.bind(FakeJobs.class, FakeProgress.class));
        var progress = adapter.tools(null).getFirst();
        assertTrue(progress.independentRead());
        assertFalse(progress.available(f.player()));
        assertTrue(progress.call(context(f), new JsonObject()).get().content().startsWith("ERROR"));
        assertNull(FakeJobs.manager.asked, "denied reads must not reach API");
        when(f.player().hasPermission("jobs.command.stats")).thenReturn(true);
        assertTrue(progress.available(f.player()));
        assertTrue(progress.call(context(f), new JsonObject()).get().content().contains("not loaded"));
        FakeJobs.manager.profile = new FakeProfile();
        var result = JsonParser.parseString(
                        progress.call(context(f), new JsonObject()).get().content())
                .getAsJsonObject();
        var job = result.getAsJsonArray("jobs").get(0).getAsJsonObject();
        assertEquals("Miner", job.get("job").getAsString());
        assertEquals(4, job.get("level").getAsInt());
        assertEquals(20.5, job.get("experience").getAsDouble());
        assertSame(f.player(), FakeJobs.manager.asked);
        when(f.installed().isEnabled()).thenReturn(false);
        assertFalse(progress.available(f.player()));
    }

    @Test
    void jobsCatalogueFiltersJoinPermissionsAndBoundsNativeIteration() throws Exception {
        var f = fixture("Jobs");
        when(f.player().hasPermission("jobs.command.browse")).thenReturn(true);
        when(f.player().hasPermission("jobs.use")).thenReturn(true);
        when(f.player().hasPermission("jobs.join.miner")).thenReturn(true);
        FakeJobs.jobs = List.of(new FakeJob("Miner"), new FakeJob("AdminOnly"));
        var adapter = new JobsAdapter(f.installed(), JobsAdapter.Api.bind(FakeJobs.class, FakeProgress.class));
        var catalog = adapter.tools(null).getLast();
        String content = catalog.call(context(f), new JsonObject())
                .get(5, TimeUnit.SECONDS)
                .content();
        assertTrue(content.contains("Miner"));
        assertFalse(content.contains("AdminOnly"));
        when(f.player().hasPermission("jobs.use")).thenReturn(false);
        String denied = catalog.call(context(f), new JsonObject()).get().content();
        assertFalse(denied.contains("Miner"), "native jobs.use denial must override join permission");
        when(f.player().hasPermission("jobs.use")).thenReturn(true);
        var many = new ArrayList<FakeJob>();
        for (int i = 0; i < 1000; i++) many.add(new FakeJob("Hidden"));
        FakeJobs.jobs = many;
        var result = JsonParser.parseString(
                        catalog.call(context(f), new JsonObject()).get().content())
                .getAsJsonObject();
        assertEquals(256, result.get("scanned").getAsInt());
        assertTrue(result.get("partial").getAsBoolean());
    }

    public static class FakeOption {
        private final FakeWorld value;

        FakeOption(FakeWorld value) {
            this.value = value;
        }

        public FakeWorld getOrNull() {
            return value;
        }
    }

    public static class FakeWorld {
        String name;
        boolean hidden, loaded, fee, access = true;
        int spawnCalls;
        List<String> blacklist = List.of();

        FakeWorld(String name, boolean loaded) {
            this.name = name;
            this.loaded = loaded;
        }

        public String getName() {
            return name;
        }

        public String getAliasOrName() {
            return name + " alias";
        }

        public boolean isHidden() {
            return hidden;
        }

        public boolean isLoaded() {
            return loaded;
        }

        public GameMode getGameMode() {
            return GameMode.SURVIVAL;
        }

        public Difficulty getDifficulty() {
            return Difficulty.NORMAL;
        }

        public boolean getPvp() {
            return true;
        }

        public boolean isAllowFlight() {
            return false;
        }

        public boolean isEntryFeeEnabled() {
            return fee;
        }

        public double getPrice() {
            return 20;
        }

        public Material getCurrency() {
            return null;
        }

        public Location getSpawnLocation() {
            spawnCalls++;
            return null;
        }

        public List<String> getWorldBlacklist() {
            return blacklist;
        }
    }

    public static class FakeWorldManager {
        List<FakeWorld> worlds = List.of();

        public List<FakeWorld> getWorlds() {
            return worlds;
        }

        public FakeOption getWorld(String name) {
            return new FakeOption(
                    worlds.stream().filter(w -> w.name.equals(name)).findFirst().orElse(null));
        }
    }

    public static class FakeConfig {
        boolean enforce = true;

        public boolean getEnforceAccess() {
            return enforce;
        }
    }

    public static class FakeChecker {
        int checks;

        public boolean hasWorldAccessPermission(CommandSender sender, FakeWorld world) {
            checks++;
            return world.access;
        }
    }

    public static class FakeLocator {
        FakeChecker checker = new FakeChecker();

        public Object getActiveService(Class<?> type, Annotation... qualifiers) {
            return checker;
        }
    }

    public static class FakeCore {
        static FakeCore instance;
        FakeWorldManager manager = new FakeWorldManager();
        FakeConfig config = new FakeConfig();
        FakeLocator locator = new FakeLocator();

        public static FakeCore get() {
            return instance;
        }

        public FakeWorldManager getWorldManager() {
            return manager;
        }

        public FakeConfig getCoreConfig() {
            return config;
        }

        public FakeLocator getServiceLocator() {
            return locator;
        }
    }

    @Test
    void multiverseUsesNativeAccessHiddenAndBlacklistFiltersWithoutTouchingUnloadedSpawn() throws Exception {
        var f = fixture("Multiverse-Core");
        when(f.player().hasPermission("multiverse.core.list.worlds")).thenReturn(true);
        var current = new FakeWorld("current", true);
        current.blacklist = List.of("visible"); // This does not prevent travel from current to visible.
        var visible = new FakeWorld("visible", false);
        var hidden = new FakeWorld("hidden", true);
        hidden.hidden = true;
        var denied = new FakeWorld("denied", true);
        denied.access = false;
        var blocked = new FakeWorld("blacklisted", true);
        blocked.blacklist = List.of("current");
        FakeCore.instance = new FakeCore();
        FakeCore.instance.manager.worlds = List.of(current, visible, hidden, denied, blocked);
        var adapter = new MultiverseAdapter(
                f.installed(), MultiverseAdapter.Api.bind(FakeCore.class, FakeWorld.class, FakeChecker.class));
        var tool = adapter.tools(null).getFirst();
        assertTrue(tool.independentRead());
        var result = JsonParser.parseString(
                        tool.call(context(f), new JsonObject()).get().content())
                .getAsJsonObject();
        assertEquals(2, result.getAsJsonArray("worlds").size());
        assertEquals(0, visible.spawnCalls);
        assertEquals(0, hidden.spawnCalls);
        assertEquals(0, denied.spawnCalls);
        assertFalse(result.get("partial").getAsBoolean());
        FakeCore.instance.config.enforce = false;
        FakeCore.instance.locator.checker = null;
        var relaxed = JsonParser.parseString(
                        tool.call(context(f), new JsonObject()).get().content())
                .getAsJsonObject();
        assertEquals(3, relaxed.getAsJsonArray("worlds").size(), "disabled native enforcement must be honored");
        verify(f.player(), never()).teleport(any(Location.class));
    }

    @Test
    void multiverseReadsOnlyCurrentWorldAndDeclinesWhenNativePermissionCheckerMissing() throws Exception {
        var f = fixture("Multiverse-Core");
        var current = new FakeWorld("current", true);
        current.fee = true;
        FakeCore.instance = new FakeCore();
        FakeCore.instance.manager.worlds = List.of(current);
        var adapter = new MultiverseAdapter(
                f.installed(), MultiverseAdapter.Api.bind(FakeCore.class, FakeWorld.class, FakeChecker.class));
        var here = adapter.tools(null).getLast();
        assertTrue(here.call(context(f), new JsonObject()).get().content().startsWith("ERROR"));
        assertEquals(0, current.spawnCalls);
        when(f.player().hasPermission("multiverse.core.info")).thenReturn(true);
        String result = here.call(context(f), new JsonObject()).get().content();
        assertTrue(result.contains("current"));
        assertTrue(result.contains("20.0"));
        when(f.player().hasPermission("multiverse.core.list.worlds")).thenReturn(true);
        FakeCore.instance.locator.checker = null;
        assertTrue(adapter.tools(null)
                .getFirst()
                .call(context(f), new JsonObject())
                .get()
                .content()
                .contains("checker is unavailable"));
    }

    public static class IncompatibleConfig {
        public String getEnforceAccess() {
            return "true";
        }
    }

    public static class IncompatibleCore extends FakeCore {
        public IncompatibleConfig unsafeConfig() {
            return new IncompatibleConfig();
        }
    }

    @Test
    void returnTypeAndStaticnessChecksRejectIncompatibleSecurityFlags() {
        assertThrows(
                NoSuchMethodException.class,
                () -> VerifiedPublicApi.method(IncompatibleConfig.class, "getEnforceAccess", boolean.class, false));
        assertThrows(
                NoSuchMethodException.class,
                () -> VerifiedPublicApi.method(FakeCore.class, "get", FakeCore.class, false));
    }

    @Test
    void capabilityChecksFailForMissingNativePublicApisWithoutCallingThem() {
        var f = fixture("Jobs");
        assertThrows(ReflectiveOperationException.class, () -> new JobsAdapter().validate(f.installed()));
        assertThrows(ReflectiveOperationException.class, () -> new MultiverseAdapter().validate(f.installed()));
    }
}
