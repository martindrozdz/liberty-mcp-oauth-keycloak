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
 *   1. (Re)generate .env with the demo passwords
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
    private static List<String>  composeBase = null;

    public static void main(String... args) throws Exception {
        System.out.println("========================================================================");
        System.out.println("Open Liberty MCP Demo - Full Local Setup (cross-platform)");
        System.out.println("========================================================================");

        // Clean is the default; pass --no-clean to keep existing certificates.
        boolean clean = !Arrays.asList(args).contains("--no-clean");

        // ── 1. (Re)generate .env ──────────────────────────────────────────────
        Path envFile = Path.of(".env");
        System.out.println("[1/7] (Re)generating .env with demo passwords...");
        Files.writeString(envFile,
                "keystore_password=secretpassword123\n" +
                "keycloak_admin_username=admin\n" +
                "keycloak_admin_password=admin\n");
        System.out.println("      .env written");

        String keystorePassword = readEnv("keystore_password", "secretpassword123");

        // keytool ships with every JDK — located via java.home for reliable cross-platform resolution
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Path caP12 = Path.of("ca.p12");
        Path caCrt = Path.of("ca.crt");
        Path p12 = Path.of("localhost.p12");
        Path serverCsr = Path.of("localhost.csr");
        Path serverSignedCrt = Path.of("localhost-signed.crt");

        if (clean) {
            System.out.println("      Clean flag detected — removing existing certificates...");
            Files.deleteIfExists(caP12);
            Files.deleteIfExists(caCrt);
            Files.deleteIfExists(p12);
            Files.deleteIfExists(serverCsr);
            Files.deleteIfExists(serverSignedCrt);
            removeCertFromKeychain("Local Open Liberty MCP Root CA");
            removeCertFromKeychain("localhost");
        }

        // ── 2. Generate Local Root CA ─────────────────────────────────────────
        if (Files.exists(caP12) && Files.exists(caCrt)) {
            System.out.println("[2/7] Local Root CA (ca.p12 / ca.crt) already exists — skipping generation");
        } else {
            System.out.println("[2/7] Generating Local Root CA (ca.p12 / ca.crt)...");
            runCommand(List.of(keytool,
                "-genkeypair",
                "-alias",     "rootca",
                "-keyalg",    "RSA",
                "-keysize",   "2048",
                "-validity",  "3650",
                "-keystore",  caP12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-dname",     "CN=Local Open Liberty MCP Root CA",
                "-ext",       "BC=ca:true",
                "-ext",       "KU=keyCertSign,cRLSign"
            ), true);

            runCommand(List.of(keytool,
                "-exportcert",
                "-alias",     "rootca",
                "-keystore",  caP12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-rfc",
                "-file",      caCrt.toString()
            ), true);
        }

        // ── 3. Generate & Sign Server Certificate ──────────────────────────────
        if (Files.exists(p12)) {
            System.out.println("[3/7] localhost.p12 already exists — skipping generation");
        } else {
            System.out.println("[3/7] Generating and signing server certificate (localhost.p12)...");
            // Generate server keypair
            runCommand(List.of(keytool,
                "-genkeypair",
                "-alias",     "localhost",
                "-keyalg",    "RSA",
                "-keysize",   "2048",
                "-validity",  "365",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-dname",     "CN=localhost"
            ), true);

            // Generate CSR
            runCommand(List.of(keytool,
                "-certreq",
                "-alias",     "localhost",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-file",      serverCsr.toString()
            ), true);

            // Sign CSR with Local Root CA
            runCommand(List.of(keytool,
                "-gencert",
                "-alias",     "rootca",
                "-keystore",  caP12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-infile",    serverCsr.toString(),
                "-outfile",   serverSignedCrt.toString(),
                "-validity",  "365",
                "-ext",       "SAN=DNS:localhost,IP:127.0.0.1",
                "-ext",       "EKU=serverAuth",
                "-ext",       "KU=digitalSignature,keyEncipherment"
            ), true);

            // Import Root CA into server keystore
            runCommand(List.of(keytool,
                "-importcert",
                "-alias",     "rootca",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-file",      caCrt.toString(),
                "-noprompt"
            ), true);

            // Import signed server certificate reply into server keystore
            runCommand(List.of(keytool,
                "-importcert",
                "-alias",     "localhost",
                "-keystore",  p12.toString(),
                "-storetype", "PKCS12",
                "-storepass", keystorePassword,
                "-file",      serverSignedCrt.toString(),
                "-noprompt"
            ), true);

            Files.deleteIfExists(serverCsr);
            Files.deleteIfExists(serverSignedCrt);
        }

        // ── 4. Copy keystores into place ──────────────────────────────────────
        System.out.println("[4/7] Copying keystores...");
        Path libertyDir = Path.of("src/main/liberty/config/resources/security");
        Files.createDirectories(libertyDir);
        Files.copy(p12, libertyDir.resolve("localhost.p12"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(p12, Path.of("src/main/liberty/config/localhost.p12"), StandardCopyOption.REPLACE_EXISTING);

        Path keycloakDir = Path.of("src/main/keycloak");
        Files.createDirectories(keycloakDir);
        Files.copy(p12, keycloakDir.resolve("localhost.p12"), StandardCopyOption.REPLACE_EXISTING);

        // ── 5. Import Root CA into OS trust store & configure Node.js ─────────
        System.out.println("[5/7] Importing Root CA into OS trust store...");
        boolean inSystem = isCertInKeychain(keytool, caCrt, "/Library/Keychains/System.keychain");
        if (inSystem) {
            System.out.println("      Root CA already in System keychain — skipping");
        } else {
            importCertIntoTrustStore(caCrt);
        }
        configureNodeExtraCaCerts(caCrt);

        // ── 6. Start Keycloak container ───────────────────────────────────────
        System.out.println("[6/7] Starting Keycloak container...");
        startKeycloak();

        // ── 7. Configure Keycloak ─────────────────────────────────────────────
        System.out.println("[7/7] Configuring Keycloak (includes readiness check)...");
        runJBang("SetupLocalKeycloak.java");

        System.out.println("========================================================================");
        System.out.println("Setup complete!");
        System.out.println("1. Fully QUIT and relaunch Bob IDE (Cmd+Q) to apply NODE_EXTRA_CA_CERTS.");
        System.out.println("2. In Bob MCP settings, click 'Reset Auth' / 'Re-authenticate' next to the server.");
        System.out.println("3. Start Open Liberty in another terminal: './mvnw liberty:dev'");
        System.out.println("Streaming Keycloak logs below (press Ctrl+C to stop viewing logs)...");
        System.out.println("========================================================================");
        tailKeycloakLogs();
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
     * Deletes existing certificates matching commonName from macOS System Keychain.
     */
    private static void removeCertFromKeychain(String commonName) {
        if (!OS_NAME.contains("mac") && !OS_NAME.contains("darwin")) return;
        try {
            Process findProc = new ProcessBuilder(
                    "security", "find-certificate", "-c", commonName, "/Library/Keychains/System.keychain")
                    .redirectErrorStream(true).start();
            if (findProc.waitFor() == 0) {
                System.out.println("      Removing existing '" + commonName + "' from System Keychain (sudo required)...");
                runCommand(List.of("sudo", "security", "delete-certificate",
                        "-c", commonName, "/Library/Keychains/System.keychain"), false);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Imports Root CA certificate into the macOS System keychain with root trust.
     */
    private static void importCertIntoTrustStore(Path crt) throws Exception {
        System.out.println("      Adding Root CA to System keychain (sudo required)...");
        runCommand(List.of(
                "sudo", "security", "add-trusted-cert",
                "-d", "-r", "trustRoot", "-p", "ssl", "-p", "basic",
                "-k", "/Library/Keychains/System.keychain",
                crt.toString()), true);
        System.out.println("      Root CA imported. Restart Bob/browsers after setup completes.");
    }

    /**
     * Configures NODE_EXTRA_CA_CERTS in macOS user environment (~/.config/environment.plist)
     * and sets it via launchctl for immediate and reboot-persistent trust in Node.js / Bob IDE.
     */
    private static void configureNodeExtraCaCerts(Path crt) throws Exception {
       if (!OS_NAME.contains("mac") && !OS_NAME.contains("darwin")) return;

      String absoluteCrtPath = crt.toAbsolutePath().normalize().toString();
      Path plistDir = Path.of(System.getProperty("user.home"), ".config");
      Files.createDirectories(plistDir);
      Path plistFile = plistDir.resolve("environment.plist");

      boolean needWrite = true;
      if (Files.exists(plistFile)) {
          String content = Files.readString(plistFile);
          if (content.contains("NODE_EXTRA_CA_CERTS") && content.contains(absoluteCrtPath)) {
              needWrite = false;
          } else if (content.contains("<dict>")) {
              String updated;
              if (content.contains("<key>NODE_EXTRA_CA_CERTS</key>")) {
                  updated = content.replaceAll("(?s)<key>NODE_EXTRA_CA_CERTS</key>\\s*<string>[^<]*</string>",
                          "<key>NODE_EXTRA_CA_CERTS</key>\n    <string>" + absoluteCrtPath + "</string>");
              } else {
                  updated = content.replace("<dict>", "<dict>\n    <key>NODE_EXTRA_CA_CERTS</key>\n    <string>" + absoluteCrtPath + "</string>");
              }
              Files.writeString(plistFile, updated);
              needWrite = false;
          }
      }

      if (needWrite) {
          String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                  "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                  "<plist version=\"1.0\">\n" +
                  "<dict>\n" +
                  "    <key>NODE_EXTRA_CA_CERTS</key>\n" +
                  "    <string>" + absoluteCrtPath + "</string>\n" +
                  "</dict>\n" +
                  "</plist>\n";
          Files.writeString(plistFile, plist);
      }

      runCommand(List.of("launchctl", "setenv", "NODE_EXTRA_CA_CERTS", absoluteCrtPath), false);
      System.out.println("      NODE_EXTRA_CA_CERTS configured in environment.plist & launchctl (" + absoluteCrtPath + ")");   
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
            List.of("podman-compose", "up", "-d", "--force-recreate"),
            List.of("podman",         "compose", "up", "-d", "--force-recreate"),
            List.of("docker",         "compose", "up", "-d", "--force-recreate"),
            List.of("docker-compose", "up", "-d", "--force-recreate")
        );

        for (List<String> cmd : candidates) {
            if (commandExists(cmd.get(0))) {
                int exit = runCommand(cmd, true);
                if (exit == 0) {
                    composeBase = cmd.subList(0, cmd.size() - 3);
                    return;
                }
                System.err.println("compose command '" + cmd.get(0) + "' failed with exit " + exit);
                System.exit(exit);
            }
        }

        System.err.println("Error: No compose command found.");
        System.err.println("Install podman-compose or ensure Podman/Docker with compose support is on your PATH.");
        System.err.println("See the Prerequisites section in docs/DOCUMENTATION.md for install instructions.");
        System.exit(1);
    }

    /**
     * Tails Keycloak container logs in real-time.
     */
    private static void tailKeycloakLogs() throws Exception {
        if (composeBase != null) {
            List<String> cmd = new ArrayList<>(composeBase);
            cmd.addAll(List.of("logs", "-f", "keycloak"));
            runCommand(cmd, false);
            return;
        }

        List<List<String>> candidates = List.of(
            List.of("podman-compose", "logs", "-f", "keycloak"),
            List.of("podman",         "compose", "logs", "-f", "keycloak"),
            List.of("docker",         "compose", "logs", "-f", "keycloak"),
            List.of("docker-compose", "logs", "-f", "keycloak")
        );

        for (List<String> cmd : candidates) {
            if (commandExists(cmd.get(0))) {
                runCommand(cmd, false);
                return;
            }
        }
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
