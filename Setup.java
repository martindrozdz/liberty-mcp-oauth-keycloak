///usr/bin/env jbang "$0" "$@" ; exit $?
//DESCRIPTION Cross-platform setup orchestrator for the Open Liberty MCP Demo.
//DESCRIPTION Runs on Windows (CMD/PowerShell), macOS, and Linux without requiring bash.
//DESCRIPTION Run from the project root: jbang Setup.java

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.*;
import java.util.*;

/**
 * Single-file orchestrator that drives the full local setup without bash.
 *
 * Steps:
 *   1. Create .env if missing
 *   2. Generate TLS certificate via keytool (ships with the JDK)
 *   3. Export PEM certificate via keytool
 *   4. Copy keystores into place for Liberty and Keycloak
 *   5. Import certificate into OS trust store (macOS / Linux / Windows)
 *   6. Start Keycloak via podman/docker compose
 *   7. Configure Keycloak (delegates to SetupLocalKeycloak.java)
 *
 * Run from CMD or PowerShell as: jbang Setup.java
 */
public class Setup {

    private static final String  OS_NAME    = System.getProperty("os.name").toLowerCase();
    private static final Path    WORK_DIR   = Path.of(".");

    public static void main(String... args) throws Exception {
        System.out.println("========================================================================");
        System.out.println("Open Liberty MCP Demo - Full Local Setup (cross-platform)");
        System.out.println("========================================================================");

        // ── 1. Create .env if missing ─────────────────────────────────────────
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) {
            System.out.println("[1/7] Creating .env with default passwords...");
            Files.writeString(envFile,
                    "keystore_password=secretpassword123\n" +
                    "keycloak_admin_password=admin\n");
            System.out.println("      .env created");
        } else {
            System.out.println("[1/7] .env already exists — skipping");
        }

        String keystorePassword = readEnv("keystore_password", "secretpassword123");

        // keytool ships with every JDK — located via java.home for reliable cross-platform resolution
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Path p12 = Path.of("localhost.p12");
        Path crt = Path.of("localhost.crt");

        // ── 2. Generate keystore ──────────────────────────────────────────────
        if (Files.exists(p12)) {
            System.out.println("[2/7] localhost.p12 already exists — skipping generation");
        } else {
            System.out.println("[2/7] Generating self-signed certificate...");
            runCommand(List.of(keytool,
                "-genkeypair",
                "-alias",     "localhost",
                "-keyalg",    "RSA",
                "-keysize",   "2048",
                "-validity",  "365",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-dname",     "CN=localhost",
                "-ext",       "SAN=DNS:localhost,IP:127.0.0.1",
                "-ext",       "BC=ca:true"
            ), true);
        }

        // ── 3. Export PEM certificate ─────────────────────────────────────────
        if (Files.exists(crt)) {
            System.out.println("[3/7] localhost.crt already exists — skipping export");
        } else {
            System.out.println("[3/7] Exporting PEM certificate...");
            runCommand(List.of(keytool,
                "-exportcert",
                "-alias",     "localhost",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-rfc",
                "-file",      crt.toString()
            ), true);
        }

        // ── 4. Copy keystores into place ──────────────────────────────────────
        System.out.println("[4/7] Copying keystores...");
        Path libertyDir = Path.of("src/main/liberty/config/resources/security");
        Files.createDirectories(libertyDir);
        Files.copy(p12, libertyDir.resolve("localhost.p12"), StandardCopyOption.REPLACE_EXISTING);

        Path keycloakDir = Path.of("src/main/keycloak");
        Files.createDirectories(keycloakDir);
        Files.copy(p12, keycloakDir.resolve("localhost.p12"), StandardCopyOption.REPLACE_EXISTING);

        // ── 5. Import certificate into OS trust store ─────────────────────────
        // Bob (Electron/Chromium) requires the cert in the login keychain with -p ssl.
        // The System keychain alone is silently ignored by Chromium/Node.js.
        System.out.println("[5/7] Importing certificate into OS trust store...");
        String loginKeychain = System.getProperty("user.home") + "/Library/Keychains/login.keychain-db";
        boolean inSystem = isCertInKeychain(keytool, crt, "/Library/Keychains/System.keychain");
        boolean inLogin  = isCertInKeychain(keytool, crt, loginKeychain);
        if (inSystem && inLogin) {
            System.out.println("      Certificate already in System and login keychains — skipping");
        } else {
            importCertIntoTrustStore(keytool, crt, loginKeychain, inSystem, inLogin);
        }

        // ── 6. Start Keycloak container ───────────────────────────────────────
        System.out.println("[6/7] Starting Keycloak container...");
        startKeycloak();

        // ── 7. Configure Keycloak ─────────────────────────────────────────────
        System.out.println("[7/7] Configuring Keycloak (includes readiness check)...");
        runJBang("SetupLocalKeycloak.java");

        System.out.println("========================================================================");
        System.out.println("Setup complete! Run './mvnw liberty:dev' to start Open Liberty.");
        System.out.println("========================================================================");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Runs a JBang script, streaming output to stdout. Fails fast on non-zero exit. */
    private static void runJBang(String scriptPath) throws Exception {
        String jbang = "jbang";
        int exit = runCommand(List.of(jbang, scriptPath), false);
        if (exit != 0) {
            System.err.println("Script failed: " + scriptPath);
            System.exit(exit);
        }
    }

    /** Reads a key from .env, returning defaultValue if not found. */
    private static String readEnv(String key, String defaultValue) throws IOException {
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    if (parts[0].trim().equals(key)) return parts[1].trim();
                }
            }
        }
        return defaultValue;
    }

    /**
     * Returns true if the certificate's SHA-1 fingerprint is already present
     * in the given keychain file.
     */
    private static boolean isCertInKeychain(String keytool, Path crt, String keychain) throws Exception {
        String fingerprint = getCertFingerprint(keytool, crt);
        Process findProc = new ProcessBuilder(
                "security", "find-certificate", "-a", "-Z", keychain)
                .redirectErrorStream(true).start();
        String keychainOutput = new String(findProc.getInputStream().readAllBytes());
        findProc.waitFor();
        String normFingerprint = fingerprint.replace(":", "").replace(" ", "").toUpperCase();
        return keychainOutput.toUpperCase().replaceAll("[:\\s]", "").contains(normFingerprint);
    }

    /**
     * Imports localhost.crt into the macOS System keychain (sudo) and the
     * login keychain with -p ssl (no sudo) so that Chromium/Bob trusts it.
     */
    private static void importCertIntoTrustStore(String keytool, Path crt,
            String loginKeychain, boolean inSystem, boolean inLogin) throws Exception {
        if (!inSystem) {
            System.out.println("      Adding certificate to System keychain (sudo required)...");
            runCommand(List.of(
                    "sudo", "security", "add-trusted-cert",
                    "-d", "-r", "trustRoot", "-p", "ssl",
                    "-k", "/Library/Keychains/System.keychain",
                    crt.toString()), true);
        }
        if (!inLogin) {
            System.out.println("      Adding certificate to login keychain (for Bob/Chromium)...");
            runCommand(List.of(
                    "security", "add-trusted-cert",
                    "-r", "trustRoot", "-p", "ssl",
                    "-k", loginKeychain,
                    crt.toString()), true);
        }
        System.out.println("      Certificate imported. Restart Bob/browsers after setup completes.");
    }

    /**
     * Extracts the SHA-1 fingerprint from a PEM certificate using keytool -printcert.
     * Returns a string like "AB:CD:EF:..." (uppercase, colon-separated).
     */
    private static String getCertFingerprint(String keytool, Path crt) throws Exception {
        Process proc = new ProcessBuilder(keytool, "-printcert", "-file", crt.toString())
                .redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();
        // keytool output contains a line like: SHA1: AB:CD:EF:...
        for (String line : output.split("\n")) {
            if (line.trim().toUpperCase().startsWith("SHA1:")) {
                return line.trim().substring(5).trim();
            }
        }
        throw new RuntimeException("Could not extract SHA-1 fingerprint from " + crt + "\nkeytool output:\n" + output);
    }

    /**
     * Starts Keycloak using whichever compose command is available.
     * Tries: podman-compose, podman compose, docker compose, docker-compose.
     */
    private static void startKeycloak() throws Exception {
        List<List<String>> candidates = List.of(
            List.of("podman-compose", "up", "-d"),
            List.of("podman",         "compose", "up", "-d"),
            List.of("docker",         "compose", "up", "-d"),
            List.of("docker-compose", "up", "-d")
        );

        for (List<String> cmd : candidates) {
            if (commandExists(cmd.get(0))) {
                int exit = runCommand(cmd, true);
                if (exit == 0) return;
                System.err.println("compose command '" + cmd.get(0) + "' failed with exit " + exit);
                System.exit(exit);
            }
        }

        System.err.println("Error: No compose command found.");
        System.err.println("Install podman-compose or ensure Podman/Docker with compose support is on your PATH.");
        System.err.println("See the Prerequisites section in docs/DOCUMENTATION.md for install instructions.");
        System.exit(1);
    }

    /** Checks whether a command exists on the PATH without executing it. */
    private static boolean commandExists(String command) {
        try {
            List<String> cmd = List.of("which", command);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs a command in the project root, streaming output to stdout/stderr.
     * If failFast is true, exits immediately on non-zero exit code.
     */
    private static int runCommand(List<String> cmd, boolean failFast) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(WORK_DIR.toFile())
                .redirectOutput(Redirect.INHERIT)
                .redirectError(Redirect.INHERIT);
        int exit = pb.start().waitFor();
        if (failFast && exit != 0) {
            System.err.println("Command failed (exit " + exit + "): " + String.join(" ", cmd));
            System.exit(exit);
        }
        return exit;
    }
}
