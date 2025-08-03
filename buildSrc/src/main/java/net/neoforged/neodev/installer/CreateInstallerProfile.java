package net.neoforged.neodev.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.neodev.utils.FileUtils;
import net.neoforged.neodev.utils.MavenIdentifier;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Creates the JSON profile used by legacyinstaller for installing the client into the vanilla launcher,
 * or installing a dedicated server.
 */
public abstract class CreateInstallerProfile extends DefaultTask {
    @Inject
    public CreateInstallerProfile() {
    }

    @Input
    public abstract Property<String> getMinecraftVersion();

    @Input
    public abstract Property<String> getNeoForgeVersion();

    @Input
    public abstract Property<String> getMcAndNeoFormVersion();

    @InputFile
    public abstract RegularFileProperty getIcon();

    @Nested
    protected abstract ListProperty<IdentifiedFile> getLibraryFiles();

    public void addLibraries(Configuration libraries) {
        getLibraryFiles().addAll(IdentifiedFile.listFromConfiguration(getProject(), libraries));
    }

    /**
     * The libraries shipped with the vanilla Minecraft server.
     */
    @Nested
    protected abstract ListProperty<IdentifiedFile> getMinecraftServerLibraries();

    public void addMinecraftServerLibraries(Configuration libraries) {
        getMinecraftServerLibraries().addAll(IdentifiedFile.listFromConfiguration(getProject(), libraries));
    }

    /**
     * The libraries downloaded by the vanilla Minecraft client on launch.
     */
    @Nested
    protected abstract ListProperty<IdentifiedFile> getMinecraftClientLibraries();

    public void addMinecraftClientLibraries(Configuration libraries) {
        getMinecraftClientLibraries().addAll(IdentifiedFile.listFromConfiguration(getProject(), libraries));
    }

    @Input
    public abstract ListProperty<URI> getRepositoryURLs();

    @Input
    public abstract MapProperty<InstallerProcessor, List<String>> getProcessorClasspaths();

    @Input
    public abstract MapProperty<InstallerProcessor, String> getProcessorGavs();

    @InputFile
    public abstract RegularFileProperty getUniversalJar();

    @OutputFile
    public abstract RegularFileProperty getInstallerProfile();

    private void addProcessor(List<ProcessorEntry> processors, @Nullable List<String> sides, InstallerProcessor processor, List<String> args) {
        var classpath = getProcessorClasspaths().get().get(processor);
        var mainJar = getProcessorGavs().get().get(processor);
        if (!classpath.contains(mainJar)) {
            throw new IllegalStateException("Processor %s is not included in its own classpath %s".formatted(mainJar, classpath));
        }
        processors.add(new ProcessorEntry(sides, mainJar, classpath, args));
    }

    @TaskAction
    public void createInstallerProfile() throws IOException {
        var icon = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(getIcon().getAsFile().get().toPath()));

        var clientMappingsCoordinate = new MavenIdentifier("net.minecraft", "client", getMinecraftVersion().get(), "mappings", "txt");
        var serverMappingsCoordinate = new MavenIdentifier("net.minecraft", "server", getMinecraftVersion().get(), "mappings", "txt");

        var data = new LinkedHashMap<String, LauncherDataEntry>();
        var neoFormVersion = getMcAndNeoFormVersion().get();
        data.put("MOJMAPS", new LauncherDataEntry("[" + clientMappingsCoordinate.artifactNotation() + "]", "[" + serverMappingsCoordinate.artifactNotation() + "]"));
        data.put("BINPATCH", new LauncherDataEntry("/data/client.lzma", "/data/server.lzma"));
        data.put("PATCHED", new LauncherDataEntry(String.format("[%s:%s:%s:client]", "net.neoforged", "neoforge", getNeoForgeVersion().get()), String.format("[%s:%s:%s:server]", "net.neoforged", "neoforge", getNeoForgeVersion().get())));
        data.put("MCP_VERSION", new LauncherDataEntry(String.format("'%s'", neoFormVersion), String.format("'%s'", neoFormVersion)));

        var processors = new ArrayList<ProcessorEntry>();
        BiConsumer<InstallerProcessor, List<String>> commonProcessor = (processor, args) -> addProcessor(processors, null, processor, args);
        BiConsumer<InstallerProcessor, List<String>> serverProcessor = (processor, args) -> addProcessor(processors, List.of("server"), processor, args);

        serverProcessor.accept(InstallerProcessor.INSTALLERTOOLS,
                List.of("--task", "EXTRACT_FILES", "--archive", "{INSTALLER}",
                        "--from", "data/run.sh", "--to", "{ROOT}/run.sh", "--exec", "{ROOT}/run.sh",
                        "--from", "data/run.bat", "--to", "{ROOT}/run.bat",
                        "--from", "data/user_jvm_args.txt", "--to", "{ROOT}/user_jvm_args.txt", "--optional", "{ROOT}/user_jvm_args.txt",
                        "--from", "data/win_args.txt", "--to", "{ROOT}/libraries/net/neoforged/neoforge/%s/win_args.txt".formatted(getNeoForgeVersion().get()),
                        "--from", "data/unix_args.txt", "--to", "{ROOT}/libraries/net/neoforged/neoforge/%s/unix_args.txt".formatted(getNeoForgeVersion().get()))
        );

        var neoformMappingsDependency = "net.neoforged:neoform:" + getMcAndNeoFormVersion().get() + ":mappings@tsrg.lzma";
        // Validate it will actually be downloaded
        if (getLibraryFiles().get().stream().noneMatch(l -> {
            var identifier = l.getIdentifier().get();
            return identifier.artifactNotation().equals(neoformMappingsDependency);
        })) {
            throw new GradleException("Libraries list must contain NeoForm mappings: " + neoformMappingsDependency);
        }

        commonProcessor.accept(
                InstallerProcessor.INSTALLERTOOLS,
                List.of(
                        "--task",
                        "PROCESS_MINECRAFT_JAR",
                        "--input",
                        "{MINECRAFT_JAR}",
                        "--input-mappings",
                        "{MOJMAPS}",
                        "--output",
                        "{PATCHED}",
                        "--extract-libraries-to",
                        "{ROOT}/libraries/",
                        "--neoform-data",
                        String.format("[%s]", neoformMappingsDependency),
                        "--apply-patches",
                        "{BINPATCH}"
                )
        );

        getLogger().info("Collecting libraries for Installer Profile");
        // Remove potential duplicates.
        var libraryFilesToResolve = new LinkedHashMap<MavenIdentifier, IdentifiedFile>(getLibraryFiles().get().size());
        for (var libraryFile : getLibraryFiles().get()) {
            var existingFile = libraryFilesToResolve.putIfAbsent(libraryFile.getIdentifier().get(), libraryFile);
            if (existingFile != null) {
                var existing = existingFile.getFile().getAsFile().get();
                var duplicate = libraryFile.getFile().getAsFile().get();
                if (!existing.equals(duplicate)) {
                    throw new IllegalArgumentException("Cannot resolve installer profile! Library %s has different files: %s and %s.".formatted(
                            libraryFile.getIdentifier().get(),
                            existing,
                            duplicate));
                }
            }
        }

        // Find libraries present in *both* client and server, and those will not be downloaded
        var universalLibraries = getUniversalLibraries();
        libraryFilesToResolve.values().removeIf(l -> universalLibraries.contains(l.getIdentifier().get()));

        var libraries = new ArrayList<>(
                LibraryCollector.resolveLibraries(getRepositoryURLs().get(), libraryFilesToResolve.values()));

        // Add the client/server mappings
        collectClientServerMappings(clientMappingsCoordinate, serverMappingsCoordinate, libraries);

        var universalJar = getUniversalJar().getAsFile().get().toPath();
        libraries.add(new Library(
                "net.neoforged:neoforge:%s:universal".formatted(getNeoForgeVersion().get()),
                new LibraryDownload(new LibraryArtifact(
                        LibraryCollector.sha1Hash(universalJar),
                        Files.size(universalJar),
                        "https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-universal.jar".formatted(
                                getNeoForgeVersion().get(),
                                getNeoForgeVersion().get()),
                        "net/neoforged/neoforge/%s/neoforge-%s-universal.jar".formatted(
                                getNeoForgeVersion().get(),
                                getNeoForgeVersion().get())
                ))));

        printDownloadStatistic(libraries);

        var profile = new InstallerProfile(
                1,
                "NeoForge",
                "neoforge-%s".formatted(getNeoForgeVersion().get()),
                icon,
                getMinecraftVersion().get(),
                "/version.json",
                "/big_logo.png",
                "Welcome to the simple NeoForge installer",
                "https://mirrors.neoforged.net",
                true,
                data,
                processors,
                libraries,
                "{LIBRARY_DIR}/net/minecraft/server/{MINECRAFT_VERSION}/server-{MINECRAFT_VERSION}.jar"
        );

        FileUtils.writeStringSafe(
                getInstallerProfile().getAsFile().get().toPath(),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(profile),
                StandardCharsets.UTF_8
        );
    }

    private Set<MavenIdentifier> getUniversalLibraries() {
        Set<MavenIdentifier> clientLibraries = new HashSet<>();
        Set<MavenIdentifier> universalLibraries = new HashSet<>();
        for (IdentifiedFile identifiedFile : getMinecraftClientLibraries().get()) {
            clientLibraries.add(identifiedFile.getIdentifier().get());
        }
        for (IdentifiedFile identifiedFile : getMinecraftServerLibraries().get()) {
            var identifier = identifiedFile.getIdentifier().get();
            if (clientLibraries.contains(identifier)) {
                universalLibraries.add(identifier);
            }
        }
        return universalLibraries;
    }

    /**
     * Find the Mojang URLs to download client and server mappings for this minecraft version and
     * put them into the libraries list of the installer profile.
     */
    private void collectClientServerMappings(MavenIdentifier clientMappingsCoordinate, MavenIdentifier serverMappingsCoordinate, List<Library> libraries) throws IOException {

        var client = HttpClient.newHttpClient();

        var versionManifest = getJson(client, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        var minecraftVersion = getMinecraftVersion().get();

        for (JsonElement versionEl : versionManifest.getAsJsonArray("versions")) {
            JsonObject versionObj = versionEl.getAsJsonObject();
            if (versionObj.getAsJsonPrimitive("id").getAsString().equals(minecraftVersion)) {
                var versionUrl = versionObj.getAsJsonPrimitive("url").getAsString();
                var versionDoc = getJson(client, versionUrl);

                var clientMappings = versionDoc.getAsJsonObject("downloads").getAsJsonObject("client_mappings");
                libraries.add(new Library(
                        clientMappingsCoordinate.artifactNotation(),
                        new LibraryDownload(new LibraryArtifact(
                                clientMappings.get("sha1").getAsString(),
                                clientMappings.get("size").getAsLong(),
                                clientMappings.get("url").getAsString(),
                                clientMappingsCoordinate.repositoryPath()
                        ))
                ));

                var serverMappings = versionDoc.getAsJsonObject("downloads").getAsJsonObject("server_mappings");
                libraries.add(new Library(
                        serverMappingsCoordinate.artifactNotation(),
                        new LibraryDownload(new LibraryArtifact(
                                serverMappings.get("sha1").getAsString(),
                                serverMappings.get("size").getAsLong(),
                                serverMappings.get("url").getAsString(),
                                serverMappingsCoordinate.repositoryPath()
                        ))
                ));
                return;
            }
        }

        throw new IOException("No matching version found for " + minecraftVersion);
    }

    private JsonObject getJson(HttpClient client, String url) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = client.send(HttpRequest.newBuilder().uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Failed HTTP request to " + url + " with status " + response.statusCode());
        }

        var reader = new InputStreamReader(new ByteArrayInputStream(response.body()), StandardCharsets.UTF_8);
        return new Gson().fromJson(reader, JsonObject.class);
    }

    private void printDownloadStatistic(List<Library> libraries) {
        var downloads = libraries.stream().map(l -> l.downloads().artifact()).toList();
        long downloadSize = downloads.stream().mapToLong(LibraryArtifact::size).sum();
        getLogger().lifecycle("Overall installer download size: {} MB",
                downloadSize / 1024 / 1024);
        var downloadsByHost = downloads.stream().collect(Collectors.groupingBy(
                l -> URI.create(l.url()).getHost(),
                Collectors.summingLong(LibraryArtifact::size)
        ));
        for (var entry : downloadsByHost.entrySet()) {
            getLogger().lifecycle("  from {} = {} MB", entry.getKey(), entry.getValue() / 1024 / 1024);
        }
    }
}

record InstallerProfile(
        int spec,
        String profile,
        String version,
        String icon,
        String minecraft,
        String json,
        String logo,
        String welcome,
        String mirrorList,
        boolean hideExtract,
        Map<String, LauncherDataEntry> data,
        List<ProcessorEntry> processors,
        List<Library> libraries,
        String serverJarPath) {
}

record LauncherDataEntry(
        String client,
        String server) {
}

record ProcessorEntry(
        @Nullable
        List<String> sides,
        String jar,
        List<String> classpath,
        List<String> args) {
}
