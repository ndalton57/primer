package dev.primer;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Primer - a tiny "meta-agent" that loads other Java agents from a folder, so a
 * JVM application only needs a single {@code -javaagent} flag to run any number of them.
 *
 * <p>Usage in the launch command (once):
 * <pre>java -javaagent:primer.jar -jar your-app.jar</pre>
 *
 * <p>At {@code premain}, Primer scans the agents directory (default {@code ./agents},
 * overridable via {@code -javaagent:primer.jar=<dir>}). For each {@code *.jar} it finds:
 * <ol>
 *   <li>reads the jar's {@code Premain-Class} manifest attribute (jars without one are skipped);</li>
 *   <li>if a sibling config {@code <jarBaseName>.conf} is missing and the jar declares a
 *       {@code Primer-Default-Config} resource, writes that resource out as the agent's
 *       default config on first run;</li>
 *   <li>adds the jar to the system class loader search;</li>
 *   <li>reads the {@code .conf} (simple {@code key=value} lines) into a {@code k=v,k=v}
 *       string and invokes the agent's {@code premain(String, Instrumentation)} with it
 *       and the shared {@link Instrumentation}.</li>
 * </ol>
 *
 * <p>Primer is intentionally minimal and framework-agnostic: it loads plain
 * {@code java.lang.instrument} agents and gets out of the way. One failing agent never
 * stops the others, and Primer never aborts the host application's startup.
 */
public final class PrimerAgent {

    private static final String DEFAULT_AGENTS_DIR = "agents";
    private static final String CONFIG_EXTENSION = ".conf";

    private PrimerAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        String dirName = (agentArgs == null || agentArgs.isBlank()) ? DEFAULT_AGENTS_DIR : agentArgs.trim();
        Path agentsDir = Paths.get(dirName).toAbsolutePath().normalize();
        System.out.println("[Primer] Looking for agents in: " + agentsDir);

        if (!Files.isDirectory(agentsDir)) {
            try {
                Files.createDirectories(agentsDir);
                System.out.println("[Primer] Created empty agents directory (drop agent jars here): " + agentsDir);
            } catch (Exception e) {
                System.err.println("[Primer] Could not create agents directory " + agentsDir + ": " + e);
            }
            return;
        }

        Path selfJar = locateSelfJar();
        List<Path> jars = listAgentJars(agentsDir, selfJar);

        if (jars.isEmpty()) {
            System.out.println("[Primer] No agent jars found in " + agentsDir + " - nothing to load.");
            return;
        }

        int loaded = 0;
        for (Path jar : jars) {
            try {
                if (loadAgent(jar, agentsDir, inst)) {
                    loaded++;
                }
            } catch (Throwable t) {
                System.err.println("[Primer] Failed to load agent '" + jar.getFileName() + "': " + t);
                t.printStackTrace();
            }
        }
        System.out.println("[Primer] Done - loaded " + loaded + " of " + jars.size() + " agent jar(s).");
    }

    private static List<Path> listAgentJars(Path agentsDir, Path selfJar) {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> stream = Files.list(agentsDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(p -> !isSameFile(p, selfJar))
                    .sorted()
                    .forEach(jars::add);
        } catch (Exception e) {
            System.err.println("[Primer] Failed to list agents directory: " + e);
        }
        return jars;
    }

    /** Loads a single agent jar. Returns true if its premain was invoked. */
    private static boolean loadAgent(Path jar, Path agentsDir, Instrumentation inst) throws Exception {
        String baseName = stripExtension(jar.getFileName().toString());
        Path configFile = agentsDir.resolve(baseName + CONFIG_EXTENSION);

        String premainClass;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            Attributes attrs = (mf == null) ? null : mf.getMainAttributes();
            premainClass = (attrs == null) ? null : attrs.getValue("Premain-Class");
            if (premainClass == null || premainClass.isBlank()) {
                System.out.println("[Primer] Skipping '" + jar.getFileName() + "' (no Premain-Class in manifest).");
                return false;
            }
            // Generate the default config on first run, if the agent ships one.
            if (!Files.exists(configFile)) {
                String resource = (attrs == null) ? null : attrs.getValue("Primer-Default-Config");
                writeDefaultConfig(jf, resource, configFile);
            }
        }

        // Make the agent's classes available where agents are expected to live.
        inst.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));

        String args = readConfigAsArgs(configFile);
        Class<?> agentClass = Class.forName(premainClass, true, ClassLoader.getSystemClassLoader());
        invokePremain(agentClass, args, inst);

        System.out.println("[Primer] Loaded agent '" + baseName + "' (" + premainClass + ")"
                + (args.isEmpty() ? "" : " with config [" + args + "]"));
        return true;
    }

    private static void writeDefaultConfig(JarFile jf, String resource, Path configFile) {
        if (resource == null || resource.isBlank()) {
            return;
        }
        String entryName = resource.startsWith("/") ? resource.substring(1) : resource;
        JarEntry entry = jf.getJarEntry(entryName);
        if (entry == null) {
            System.err.println("[Primer] Agent declared Primer-Default-Config '" + resource
                    + "' but it was not found in the jar; skipping config generation.");
            return;
        }
        try (InputStream in = jf.getInputStream(entry)) {
            Files.copy(in, configFile);
            System.out.println("[Primer] Generated default config: " + configFile.getFileName());
        } catch (Exception e) {
            System.err.println("[Primer] Could not write default config " + configFile + ": " + e);
        }
    }

    private static void invokePremain(Class<?> agentClass, String args, Instrumentation inst) throws Exception {
        Method method;
        try {
            method = agentClass.getDeclaredMethod("premain", String.class, Instrumentation.class);
            method.setAccessible(true);
            method.invoke(null, args, inst);
            return;
        } catch (NoSuchMethodException ignored) {
            // fall through to the single-arg form
        }
        method = agentClass.getDeclaredMethod("premain", String.class);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static String readConfigAsArgs(Path configFile) {
        if (!Files.exists(configFile)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = s.substring(0, eq).trim();
                String value = s.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    parts.add(key + "=" + value);
                }
            }
        } catch (Exception e) {
            System.err.println("[Primer] Could not read config " + configFile + ": " + e);
        }
        return String.join(",", parts);
    }

    private static Path locateSelfJar() {
        try {
            return Paths.get(PrimerAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSameFile(Path a, Path b) {
        if (b == null) {
            return false;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (Exception e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }
}
