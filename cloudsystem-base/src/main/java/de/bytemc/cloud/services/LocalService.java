package de.bytemc.cloud.services;

import de.bytemc.cloud.Base;
import de.bytemc.cloud.api.CloudAPI;
import de.bytemc.cloud.api.common.ConfigSplitSpacer;
import de.bytemc.cloud.api.common.ConfigurationFileEditor;
import de.bytemc.cloud.api.groups.IServiceGroup;
import de.bytemc.cloud.api.groups.utils.ServiceTypes;
import de.bytemc.cloud.api.json.Document;
import de.bytemc.cloud.api.services.IService;
import de.bytemc.cloud.api.services.utils.ServiceState;
import de.bytemc.cloud.api.services.utils.ServiceVisibility;
import de.bytemc.cloud.services.properties.BungeeProperties;
import de.bytemc.cloud.services.properties.SpigotProperties;
import de.bytemc.cloud.services.statistics.SimpleStatisticManager;
import de.bytemc.network.packets.IPacket;
import de.bytemc.network.promise.CommunicationPromise;
import de.bytemc.network.promise.ICommunicationPromise;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

@Getter
@Setter
public class LocalService implements IService {

    private IServiceGroup serviceGroup;
    private int serviceID;

    private int port;
    private String hostName;
    private int maxPlayers;
    private String motd;

    private ServiceState serviceState = ServiceState.PREPARED;
    private ServiceVisibility serviceVisibility = ServiceVisibility.BLANK;

    private Process process;

    public LocalService(final IServiceGroup group, final int id, final int port, final String hostname) {
        this.serviceGroup = group;
        this.serviceID = id;
        this.port = port;
        this.hostName = hostname;
        assert serviceGroup != null;
        this.motd = this.serviceGroup.getMotd();
        this.maxPlayers = this.serviceGroup.getDefaultMaxPlayers();
    }

    @SneakyThrows
    public ICommunicationPromise<IService> start() {
        this.setServiceState(ServiceState.STARTING);

        // add statistic to service
        SimpleStatisticManager.registerStartingProcess(this);

        this.getServiceGroup().getGameServerVersion().download();

        // create tmp file
        final File tmpFolder = new File("tmp/" + this.getName());
        FileUtils.forceMkdir(tmpFolder);

        // load all current group templates
        Base.getInstance().getGroupTemplateService().copyTemplates(this);

        final var storageFolder = new File("storage/jars");

        final var jar = this.serviceGroup.getGameServerVersion().getJar();
        FileUtils.copyFile(new File(storageFolder, jar), new File(tmpFolder, jar));

        // copy plugin
        FileUtils.copyFile(new File(storageFolder, "/plugin.jar"), new File(tmpFolder, "plugins/plugin.jar"));

        // write property for identify service
        new Document()
            .set("service", this.getName())
            .set("node", this.serviceGroup.getNode())
            .set("hostname", Base.getInstance().getNode().getHostName())
            .set("port", Base.getInstance().getNode().getPort())
            .write(new File(tmpFolder, "property.json"));

        // check properties and modify
        if (this.serviceGroup.getGameServerVersion().isProxy()) {
            final var file = new File(tmpFolder, "config.yml");
            if (file.exists()) {
                var editor = new ConfigurationFileEditor(file, ConfigSplitSpacer.YAML);
                editor.setValue("host", "0.0.0.0:" + this.port);
                editor.saveFile();
            } else new BungeeProperties(tmpFolder, this.port);
        } else {
            final var file = new File(tmpFolder, "server.properties");
            if (file.exists()) {
                var editor = new ConfigurationFileEditor(file, ConfigSplitSpacer.PROPERTIES);
                editor.setValue("server-port", String.valueOf(this.port));
                editor.saveFile();
            } else new SpigotProperties(tmpFolder, this.port);
        }

        final var communicationPromise = new CommunicationPromise<IService>();
        final var processBuilder = new ProcessBuilder(this.arguments(this, tmpFolder))
            .directory(tmpFolder);
        processBuilder.redirectOutput(new File(tmpFolder, "/wrapper.log"));

        this.process = processBuilder.start();
        communicationPromise.setSuccess(this);
        return communicationPromise;
    }

    @Override
    public @NotNull String getName() {
        return this.serviceGroup.getName() + "-" + this.serviceID;
    }

    @Override
    public void edit(final @NotNull Consumer<IService> serviceConsumer) {
        serviceConsumer.accept(this);
        this.update();
    }

    public void update() {
        CloudAPI.getInstance().getServiceManager().updateService(this);
    }

    @Override
    public void sendPacket(@NotNull IPacket packet) {
        CloudAPI.getInstance().getServiceManager().sendPacketToService(this, packet);
    }

    @Override
    public void executeCommand(@NotNull String command) {
        if (this.process != null) {
            final var outputStream = this.process.getOutputStream();
            try {
                outputStream.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void stop() {
        if (this.process != null) {
            this.executeCommand(this.serviceGroup.getGameServerVersion().isProxy() ? "end" : "stop");
            try {
                if (this.process.waitFor(5, TimeUnit.SECONDS)) this.process = null;
                return;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            this.process.destroy();
            this.process = null;
        }
    }

    private List<String> arguments(final IService service, final File directory) {
        final List<String> arguments = new ArrayList<>(Arrays.asList(
            "java",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:+AlwaysPreTouch",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1",
            "-Dusing.aikars.flags=https://mcflags.emc.gs",
            "-Daikars.new.flags=true",
            "-XX:-UseAdaptiveSizePolicy",
            "-XX:CompileThreshold=100",
            "-Dcom.mojang.eula.agree=true",
            "-Dio.netty.recycler.maxCapacity=0",
            "-Dio.netty.recycler.maxCapacity.default=0",
            "-Djline.terminal=jline.UnsupportedTerminal",
            "-DIReallyKnowWhatIAmDoingISwear=true",
            "-Xms" + service.getServiceGroup().getMemory() + "M",
            "-Xmx" + service.getServiceGroup().getMemory() + "M"));

        final var wrapperFile = Paths.get("storage", "jars", "wrapper.jar");
        final var applicationFile = new File(directory, service.getServiceGroup().getGameServerVersion().getJar());

        arguments.addAll(Arrays.asList(
            "-cp", wrapperFile.toAbsolutePath().toString(),
            "-javaagent:" + wrapperFile.toAbsolutePath()));

        try (final JarInputStream jarInputStream = new JarInputStream(Files.newInputStream(wrapperFile))) {
            arguments.add(jarInputStream.getManifest().getMainAttributes().getValue("Main-Class"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        boolean preLoadClasses = false;

        try (final JarFile jarFile = new JarFile(applicationFile)) {
            arguments.add(jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
            preLoadClasses = jarFile.getEntry("META-INF/versions.list") != null;
        } catch (IOException exception) {
            exception.printStackTrace();
        }

        arguments.add(applicationFile.toPath().toAbsolutePath().toString());

        arguments.add(Boolean.toString(preLoadClasses));

        if (service.getServiceGroup().getGameServerVersion().getServiceTypes() == ServiceTypes.SERVER) {
            arguments.add("nogui");
        }

        return arguments;
    }

}
