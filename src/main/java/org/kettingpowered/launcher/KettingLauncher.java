package org.kettingpowered.launcher;

import org.jetbrains.annotations.NotNull;
import org.kettingpowered.ketting.internal.*;
import org.kettingpowered.launcher.betterui.BetterUI;
import org.kettingpowered.launcher.dependency.Dependency;
import org.kettingpowered.launcher.dependency.Libraries;
import org.kettingpowered.launcher.dependency.LibraryClassLoader;
import org.kettingpowered.launcher.dependency.MavenArtifact;
import org.kettingpowered.launcher.info.MCVersion;
import org.kettingpowered.launcher.internal.utils.Hash;
import org.kettingpowered.launcher.utils.FileUtils;
import org.kettingpowered.launcher.utils.JavaHacks;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.kettingpowered.ketting.internal.MajorMinorPatchVersion.parseKettingServerVersionList;

/**
 * @author C0D3 M4513R
 */
public class KettingLauncher {
    public static final String Version = (KettingLauncher.class.getPackage().getImplementationVersion() != null) ? KettingLauncher.class.getPackage().getImplementationVersion() : "dev-env";
    public static final boolean Bundled;
    public static final String Bundled_McVersion;
    public static final String Bundled_ForgeVersion;
    public static final String Bundled_KettingVersion;

    static {
        String Bundled_KettingVersion1 = null;
        String Bundled_ForgeVersion1 = null;
        String Bundled_McVersion1 = null;
        boolean Bundled1 = false;

        
        try {
            for(final Enumeration<URL> url_enum = KettingLauncher.class.getClassLoader().getResources("META-INF/MANIFEST.MF"); url_enum.hasMoreElements(); ){
                try{
                    Attributes attr = new Manifest(url_enum.nextElement().openStream()).getMainAttributes();
                    if (!Main.class.getName().equals(attr.getValue("Main-Class")) || !Main.class.getName().equals(attr.getValue("Launcher-Agent-Class"))) continue;
                    Bundled1=Boolean.parseBoolean(attr.getValue("Bundled"));
                    if (!Bundled1) break;
                    Bundled_McVersion1 = attr.getValue("MinecraftVersion");
                    Bundled_ForgeVersion1 = attr.getValue("ForgeVersion");
                    Bundled_KettingVersion1 = attr.getValue("KettingVersion");
                } catch (IOException ignored){}
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Bundled_KettingVersion = Bundled_KettingVersion1;
        Bundled_ForgeVersion = Bundled_ForgeVersion1;
        Bundled_McVersion = Bundled_McVersion1;
        Bundled = Bundled1;
        if (Main.DEBUG && Bundled){
            System.out.println("We are in a bundled Jar, with McVersion: "+Bundled_McVersion + " ForgeVersion: "+Bundled_ForgeVersion+" KettingVersion: "+Bundled_KettingVersion);
        }
    }

    public static final String ArtifactID = "kettinglauncher";
    public static final int BufferSize = 1024*1024*32;

    //Libs added here will get ignored by lib (down-)loading and will also not get added to java.class.path  
    public static final List<String> MANUALLY_PATCHED_LIBS = List.of(
            "com/mojang/brigadier"
    );

    public static final List<MavenArtifact> AUTO_UPDATE_LIBS = List.of(
        Main.KETTINGCOMMON,
        new MavenArtifact(KettingConstants.KETTING_GROUP, "kettingcore", "1.0.0", Optional.empty(), Optional.of("jar")), 
        new MavenArtifact(KettingConstants.KETTING_GROUP, "terminal-colors", "1.0.0", Optional.empty(), Optional.of("jar"))
    );
    

    private final Path eula = new File(KettingFiles.MAIN_FOLDER_FILE, "eula.txt").toPath();
    private final ParsedArgs args;
    private final BetterUI ui;
    private final Libraries libs = new Libraries();
    
    KettingLauncher(String[] str_args) throws Exception {
        ui = new BetterUI(KettingConstants.NAME);
        args = new Args(str_args).parse(ui, eula);
    }

    /**
     * Initialized stuff, so that KettingConstants and KettingFile are usable.
     * Also takes care of server updates.
     */
    public void init() throws Exception {
        final String mc_version;
        if (Main.DEBUG && Bundled) System.out.println("We are in a bundled jar. Read Version information from manifest.");
        if (!Bundled) mc_version = MCVersion.getMc(args);
        else mc_version = Bundled_McVersion;
        //This cannot get moved past the ensureOneServerAndUpdate call. 
        //It will cause the just downloaded server to be removed, which causes issues.
        if (Patcher.checkUpdateNeeded()) {
            if (Main.DEBUG) System.out.println("Patcher needs updating.");
            //prematurely delete files to prevent errors
            FileUtils.deleteDir(KettingFiles.NMS_BASE_DIR);
            FileUtils.deleteDir(KettingFiles.KETTINGSERVER_BASE_DIR);
        }

        if(!Bundled) ensureOneServerAndUpdate(mc_version);
        else extractBundledContent();

        ui.printTitle(mc_version);

        if (!Bundled && args.enableLauncherUpdator()) updateLauncher(); //it makes very little difference where this is. This will only be applied on the next reboot anyway.

        if(!ui.checkEula(eula)) System.exit(0);
        
    }

    private static void extractBundledContent() throws Exception {
        Exception exception = new Exception("Failed to extract some of the Bundled Jar content");
        boolean failed = false;
        final String version = Bundled_McVersion+"-"+Bundled_ForgeVersion+"-"+Bundled_KettingVersion;
        deleteOtherVersions(version);
        for(final String prefix : new String[]{"fmlloader", "fmlcore", "javafmllanguage", "lowcodelanguage", "mclanguage"}){
            final String fileName = prefix+"-"+version + ".jar";
            final File file = new File(KettingFiles.KETTINGSERVER_BASE_DIR, String.format("%s/%s/%s", prefix, version, fileName));
            try {
                extractJarContent(KettingFiles.DATA_DIR+fileName, file);
            } catch (IOException e) {
                failed=true;
                exception.addSuppressed(e);
            }
        }
        final String fileName = Main.FORGE_SERVER_ARTIFACT_ID+"-"+version;
        final File folder = new File(KettingFiles.KETTINGSERVER_FORGE_DIR, version);
        try{
            extractJarContent(KettingFiles.DATA_DIR+"ketting_libraries.txt", new File(folder, fileName+"-ketting-libraries.txt"));
        } catch (IOException e) {
            failed=true;
            exception.addSuppressed(e);
        }
        try{
            extractJarContent(KettingFiles.DATA_DIR+fileName+"-universal.jar", new File(folder, fileName+"-universal.jar"));
        } catch (IOException e) {
            failed=true;
            exception.addSuppressed(e);
        }
        try{
            extractJarContent(KettingFiles.DATA_DIR+fileName+"-installscript.json", new File(folder, fileName+"-installscript.json"));
        } catch (IOException e) {
            failed=true;
            exception.addSuppressed(e);
        }
        try{
            extractJarContent(KettingFiles.DATA_DIR+"server.lzma", KettingFiles.SERVER_LZMA);
        } catch (IOException e) {
            failed=true;
            exception.addSuppressed(e);
        }
        if (failed) throw exception;
    }

    public static void extractJarContent(@NotNull String from, @NotNull File to) throws IOException {
        try (InputStream internalFile = KettingLauncher.class.getClassLoader().getResourceAsStream(from)) {
            if (internalFile == null)
                throw new IOException("Failed to extract file '" + from + "', file not found");

            byte[] internalFileContent = internalFile.readAllBytes();
            if (!to.exists() || !Hash.getHash(to, "SHA3-512").equals(Hash.getHash(new ByteArrayInputStream(internalFileContent), "SHA3-512"))) {
                //noinspection ResultOfMethodCallIgnored
                to.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                to.createNewFile();

                try (FileOutputStream fos = new FileOutputStream(to)) {
                    fos.write(internalFileContent);
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Failed to extract file '" + from + "', failed to get hash algorithm", e);
        }
    }

    private void updateLauncher() throws Exception {
        if ("dep-env".equals(Version)) return;
        final List<MajorMinorPatchVersion<String>> launcherVersions = MavenArtifact.getDepVersions(KettingConstants.KETTING_GROUP, ArtifactID)
                .stream()
                .map(MajorMinorPatchVersion::parse)
                .sorted()
                .toList();
        if(Main.DEBUG) System.out.println(launcherVersions.stream().map(MajorMinorPatchVersion::toString).collect(Collectors.joining("\n")));
        MajorMinorPatchVersion<String> version = MajorMinorPatchVersion.parse(Version);
        final int index = launcherVersions.indexOf(version);
        if (index<0) {
            System.err.println("Using unrecognised Launcher version.");
        }
        else if (index==launcherVersions.size()-1) {
            System.out.println("Already on newest Launcher version. Nothing to do.");
            return;
        }

        final MavenArtifact dep = new MavenArtifact(KettingConstants.KETTING_GROUP, ArtifactID, launcherVersions.get(launcherVersions.size()-1).toString(), Optional.empty(), Optional.of("jar"));
        if (dep.downloadDependencyAndHash().renameTo(Main.LauncherJar)) {
            System.err.println("Downloaded a Launcher update. A restart is required to apply the launcher update.");
        }else{
            System.err.println("Something went wrong whilst replacing the Launcher Jar.");
        }
    }
    private static void deleteOtherVersions(final String version){
        Arrays.stream(Objects.requireNonNullElse(KettingFiles.KETTINGSERVER_FORGE_DIR.listFiles(File::isDirectory), new File[0]))
                .filter(file -> !Objects.equals(file.getName(),version))
                .forEach(FileUtils::deleteDir);
    }
    
    private void ensureOneServerAndUpdate(final String mc_version) throws Exception {
        File[] kettingServerVersions = Objects.requireNonNullElse(KettingFiles.KETTINGSERVER_FORGE_DIR.listFiles(File::isDirectory), new File[0]);
        MajorMinorPatchVersion<Integer> mc_mmp;
        {
            MajorMinorPatchVersion<String> mc_mmp_str = MajorMinorPatchVersion.parse(mc_version);
            mc_mmp = new MajorMinorPatchVersion<>(Integer.parseInt(mc_mmp_str.major()), Integer.parseInt(mc_mmp_str.minor()), Integer.parseInt(mc_mmp_str.patch()), mc_mmp_str.other());
        }

        if (Main.DEBUG) {
            System.out.println(KettingFiles.KETTINGSERVER_FORGE_DIR.getAbsolutePath());
            System.out.println(Arrays.stream(kettingServerVersions).map(File::getName).collect(Collectors.joining("\n")));
        }
        final List<Tuple<MajorMinorPatchVersion<Integer>, MajorMinorPatchVersion<Integer>>> versions = parseKettingServerVersionList(Arrays.stream(kettingServerVersions).map(File::getName)).getOrDefault(mc_mmp, new ArrayList<>());
        Tuple<MajorMinorPatchVersion<Integer>, MajorMinorPatchVersion<Integer>> serverVersion = null;
        
        if(versions.isEmpty()) FileUtils.deleteDir(KettingFiles.KETTINGSERVER_FORGE_DIR); //we have multiple ketting versions, but 0 that match the requested minecraft version.
        else if(versions.size() > 1) {
            serverVersion = versions.get(0);
            Tuple<MajorMinorPatchVersion<Integer>, MajorMinorPatchVersion<Integer>> version = serverVersion;
            deleteOtherVersions(String.format("%s-%s-%s", mc_mmp, version.t1(), version.t2()));
        }else{
            serverVersion = versions.get(0);
        }
        final boolean needsDownload = versions.isEmpty();
        
        if (needsDownload) System.out.println("Downloading Server, since there is none currently present. Using determined Minecraft version: "+ mc_version);
        if (args.enableServerUpdator() || needsDownload) {
            final List<String> serverVersions = MavenArtifact.getDepVersions(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID);
            final List<Tuple<MajorMinorPatchVersion<Integer>, MajorMinorPatchVersion<Integer>>> parsedServerVersions = parseKettingServerVersionList(serverVersions.stream()).getOrDefault(mc_mmp, new ArrayList<>());
            if (Main.DEBUG) {
                System.out.println("Available Ketting versions");
                System.out.println(String.join("\n", serverVersions));
            }
            if (parsedServerVersions.isEmpty()) {
                System.err.println("Found no Ketting version for the requested Minecraft Version: " + mc_version);
                System.exit(1);
            }
            serverVersion = parsedServerVersions.get(0);
        }
        if (args.enableServerUpdator() || needsDownload) {
            final String mc_minecraft_forge = String.format("%s-%s-%s", mc_mmp, serverVersion.t1(), serverVersion.t2());
            final File forgeDir = new File(KettingFiles.KETTINGSERVER_FORGE_DIR,mc_minecraft_forge);
            
            //noinspection ResultOfMethodCallIgnored
            forgeDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            KettingFiles.SERVER_LZMA.getParentFile().mkdirs();

            try{
                final MavenArtifact serverBinPatchesArtifact = new MavenArtifact(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID, mc_minecraft_forge, Optional.of("server-bin-patches"), Optional.of("lzma"));
                final MavenArtifact installerJsonArtifact = new MavenArtifact(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID, mc_minecraft_forge, Optional.of("installscript"), Optional.of("json"));
                final MavenArtifact kettingLibsArtifact = new MavenArtifact(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID, mc_minecraft_forge, Optional.of("ketting-libraries"), Optional.of("txt"));
                final MavenArtifact universalJarArtifact = new MavenArtifact(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID, mc_minecraft_forge, Optional.of("universal"), Optional.of("jar"));

                //noinspection ResultOfMethodCallIgnored
                KettingFiles.SERVER_LZMA.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                KettingFiles.SERVER_LZMA.delete();
                if (!serverBinPatchesArtifact.downloadDependencyAndHash().renameTo(KettingFiles.SERVER_LZMA)) System.err.println("An error occurred, whilst moving Server Binary Patches to it's correct location. There might be errors Patching!");
                installerJsonArtifact.downloadDependencyAndHash();
                kettingLibsArtifact.downloadDependencyAndHash();
                universalJarArtifact.downloadDependencyAndHash();
            }catch (IOException|NoSuchAlgorithmException exception){
                FileUtils.deleteDir(forgeDir);
                throw exception;
            }
        }
    }

    void launch() throws Exception {
        Libraries libs = new Libraries();
        {
            StringBuilder builder = new StringBuilder();
            try (BufferedReader stream = new BufferedReader(new FileReader(KettingFileVersioned.FORGE_KETTING_LIBS))) {
                libs.downloadExternal(
                        stream.lines()
                                .map(Dependency::parse)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .filter(dep->dep.maven().isPresent())
                                .filter(dep-> MANUALLY_PATCHED_LIBS.stream().noneMatch(path -> dep.maven().get().getPath().startsWith(path)))
                                .peek(dep-> builder.append(File.pathSeparator).append(new File(KettingFiles.LIBRARIES_DIR, dep.maven().get().getPath()).getAbsolutePath()))
                                .toList(), 
                        true
                );
            }
            
            builder.append(File.pathSeparator).append(KettingFileVersioned.FORGE_UNIVERSAL_JAR.getAbsolutePath());
            MavenArtifact universalJarArtifact = new MavenArtifact(KettingConstants.KETTINGSERVER_GROUP, Main.FORGE_SERVER_ARTIFACT_ID, KettingConstants.MINECRAFT_VERSION+"-"+KettingConstants.FORGE_VERSION+"-"+KettingConstants.KETTING_VERSION, Optional.of("universal"), Optional.of("jar"));
            libs.addLoadedLib(universalJarArtifact.getDependencyPath());
            
            System.setProperty("java.class.path", builder.toString());
            System.setProperty("ketting.remapper.dump", "./.mixin.out/plugin_classes");
            addToClassPath(KettingFileVersioned.FORGE_PATCHED_JAR);
            addToClassPath(KettingFileVersioned.SERVER_JAR);
        }
        Libraries.downloadMcp();
        
        
        if (Patcher.checkUpdateNeeded()) new Patcher();
        
        System.out.println("Launching Ketting...");
        final List<String> arg_list = new ArrayList<>(args.args());
        arg_list.add("--launchTarget");
        arg_list.add(args.launchTarget());

        ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new LibraryClassLoader(libs.getLoadedLibs())) {
            Thread.currentThread().setContextClassLoader(loader);
            JavaHacks.loadExternalFileSystems(loader);
            JavaHacks.clearReservedIdentifiers();

            Class.forName("net.minecraftforge.bootstrap.ForgeBootstrap", true, loader)
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) arg_list.toArray(String[]::new));
        } catch (Throwable t) {
            throw new RuntimeException("Could not launch server", t);
        } finally{
            Thread.currentThread().setContextClassLoader(oldCL);
        }
    }

    private void addToClassPath(File file) {
        try {
            libs.addLoadedLib(file);//Yes, this is important, and fixes an issue with forge not finding language jars
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        System.setProperty("java.class.path", System.getProperty("java.class.path") + File.pathSeparator + file.getAbsolutePath());
    }
}