///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.glassfish:jakarta.json:2.0.1

import java.io.StringReader;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import jakarta.json.*;

public class SetupLocalKeycloak {

    private static HttpClient client;
    private static String adminToken;

    public static void main(String... args) throws Exception {
        System.out.println("Executing Keycloak Configuration via JBang...");

        // 1. SSL trust-all context for localhost self-signed certificate
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new java.security.SecureRandom());

        client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        // 2. Wait for Keycloak to become ready
        System.out.println("[1/8] Waiting for Keycloak on https://localhost:8443...");
        boolean keycloakReady = false;
        for (int i = 0; i < 30; i++) {
            try {
                HttpRequest probe = HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:8443/realms/master"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
                if (client.send(probe, BodyHandlers.discarding()).statusCode() < 500) {
                    keycloakReady = true;
                    break;
                }
            } catch (Exception ignored) {}
            System.out.print(".");
            System.out.flush();
            Thread.sleep(2000);
        }
        if (!keycloakReady) {
            System.err.println("\nTimeout waiting for Keycloak to start. Is the container running?");
            System.exit(1);
        }
        System.out.println("\nKeycloak is up and responding.");

        // 3. Read .env file
        Map<String, String> env = new HashMap<>();
        if (Files.exists(Path.of(".env"))) {
            for (String line : Files.readAllLines(Path.of(".env"))) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    env.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        String adminPassword    = env.getOrDefault("keycloak_admin_password", "admin");
        String keystorePassword = env.getOrDefault("keystore_password",       "secretpassword123");

        // 4. Acquire admin token
        String tokenBody = "client_id=admin-cli&username=admin&password=" + adminPassword + "&grant_type=password";
        HttpResponse<String> tokenResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:8443/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(tokenBody))
                .build(),
            BodyHandlers.ofString());
        if (tokenResp.statusCode() != 200) {
            System.err.println("Failed to authenticate to Keycloak: " + tokenResp.body());
            System.exit(1);
        }
        adminToken = parseObject(tokenResp.body()).getString("access_token");

        // 5. Create realm 'liberty-demo' if not exists
        System.out.println("[2/8] Configuring realm...");
        JsonObject realmPayload = Json.createObjectBuilder()
            .add("realm", "liberty-demo")
            .add("enabled", true)
            .add("sslRequired", "all")
            .build();
        if (apiGet("/liberty-demo").statusCode() == 404) {
            apiPost("", realmPayload.toString());
            System.out.println(" - Created realm 'liberty-demo' (sslRequired=all)");
        } else {
            apiPut("/liberty-demo", realmPayload.toString());
            System.out.println(" - Realm 'liberty-demo' updated (sslRequired=all)");
        }

        // 6. Create roles: taskuser, taskadmin
        System.out.println("[3/8] Configuring roles...");
        for (String roleName : List.of("taskuser", "taskadmin")) {
            if (apiGet("/liberty-demo/roles/" + roleName).statusCode() == 404) {
                apiPost("/liberty-demo/roles",
                    Json.createObjectBuilder().add("name", roleName).build().toString());
                System.out.println(" - Created role '" + roleName + "'");
            }
        }

        // 7. Create / update client: liberty-mcp-server
        System.out.println("[4/8] Configuring client...");
        JsonArray clientArr = parseArray(apiGet("/liberty-demo/clients?clientId=liberty-mcp-server").body());
        JsonObject clientPayload = Json.createObjectBuilder()
            .add("clientId",                "liberty-mcp-server")
            .add("enabled",                 true)
            .add("publicClient",            false)
            .add("serviceAccountsEnabled",  true)
            .add("directAccessGrantsEnabled", true)
            .add("standardFlowEnabled",     true)
            .add("rootUrl",                 "https://localhost:9443/")
            .add("baseUrl",                 "https://localhost:9443/")
            .add("redirectUris", Json.createArrayBuilder()
                .add("https://localhost:9443/oidcclient/redirect/liberty-mcp-server")
                .add("https://localhost:9443/*")
                .add("http://127.0.0.1:6724/*"))
            .add("webOrigins", Json.createArrayBuilder().add("+"))
            .build();

        String clientInternalId;
        if (!clientArr.isEmpty()) {
            clientInternalId = clientArr.getJsonObject(0).getString("id");
            apiPut("/liberty-demo/clients/" + clientInternalId, clientPayload.toString());
        } else {
            apiPost("/liberty-demo/clients", clientPayload.toString());
            clientInternalId = parseArray(
                apiGet("/liberty-demo/clients?clientId=liberty-mcp-server").body())
                .getJsonObject(0).getString("id");
        }
        String clientSecret = parseObject(
            apiGet("/liberty-demo/clients/" + clientInternalId + "/client-secret").body())
            .getString("value");
        System.out.println(" - Configured client 'liberty-mcp-server'");

        // 8. Create client scope 'mcp-demo-server' and attach mappers
        System.out.println("[5/8] Configuring client scope...");
        String scopeId = findId(parseArray(apiGet("/liberty-demo/client-scopes").body()), "mcp-demo-server");
        if (scopeId == null) {
            apiPost("/liberty-demo/client-scopes", Json.createObjectBuilder()
                .add("name",     "mcp-demo-server")
                .add("protocol", "openid-connect")
                .add("attributes", Json.createObjectBuilder()
                    .add("include.in.token.scope",    "true")
                    .add("display.on.consent.screen", "true"))
                .build().toString());
            scopeId = findId(parseArray(apiGet("/liberty-demo/client-scopes").body()), "mcp-demo-server");
            System.out.println(" - Created client scope 'mcp-demo-server'");
        }

        // Assigned type = Optional: realm-wide default for new clients, plus the existing client
        apiPut("/liberty-demo/default-optional-client-scopes/" + scopeId, null);
        apiPut("/liberty-demo/clients/" + clientInternalId + "/optional-client-scopes/" + scopeId, null);

        JsonArray mappersArr = parseArray(
            apiGet("/liberty-demo/client-scopes/" + scopeId + "/protocol-mappers/models").body());
        boolean hasAudMapper   = findId(mappersArr, "MCP server audience") != null;
        boolean hasGroupMapper = findId(mappersArr, "groups") != null;

        if (!hasAudMapper) {
            // Audience mapper: injects aud: liberty-mcp-server into the token so Liberty
            // can verify the token was issued specifically for this server (audiences= in server.xml).
            apiPost("/liberty-demo/client-scopes/" + scopeId + "/protocol-mappers/models",
                Json.createObjectBuilder()
                    .add("name",           "MCP server audience")
                    .add("protocol",       "openid-connect")
                    .add("protocolMapper", "oidc-audience-mapper")
                    .add("consentRequired", false)
                    .add("config", Json.createObjectBuilder()
                        .add("included.client.audience", "liberty-mcp-server")
                        .add("id.token.claim",     "true")
                        .add("access.token.claim", "true"))
                    .build().toString());
            System.out.println(" - Added Audience Mapper to scope");
        }

        if (!hasGroupMapper) {
            // Role mapper: copies the user's realm roles into a flat groupIds claim array.
            //
            // MUST use oidc-usermodel-realm-role-mapper (User Realm Role), NOT the predefined
            // oidc-group-membership-mapper (Group Membership). The test users hold realm roles
            // (taskuser / taskadmin) but are not members of any Keycloak Group. The group
            // membership mapper would always emit groupIds: null for these users, causing
            // Liberty to find no roles and reject every request with CWWKS9104A.
            //
            // Liberty's openidConnectClient uses groupIds as its default groupIdentifier,
            // mapping the array values to Jakarta Security roles so that
            // httpRequest.isUserInRole("taskadmin") works inside tool methods.
            apiPost("/liberty-demo/client-scopes/" + scopeId + "/protocol-mappers/models",
                Json.createObjectBuilder()
                    .add("name",           "groups")
                    .add("protocol",       "openid-connect")
                    .add("protocolMapper", "oidc-usermodel-realm-role-mapper")  // NOT oidc-group-membership-mapper
                    .add("consentRequired", false)
                    .add("config", Json.createObjectBuilder()
                        .add("claim.name",           "groupIds")
                        .add("jsonType.label",       "String")
                        .add("multivalued",          "true")
                        .add("id.token.claim",       "true")
                        .add("access.token.claim",   "true")
                        .add("userinfo.token.claim", "true"))
                    .build().toString());
            System.out.println(" - Added Realm Role Mapper (groupIds) to scope");
        }

        // Assign roles taskuser + taskadmin to the scope's scope-mappings (idempotent)
        JsonArray allRoles = parseArray(apiGet("/liberty-demo/roles").body());
        JsonArray existingScopeMappings = parseArray(
            apiGet("/liberty-demo/client-scopes/" + scopeId + "/scope-mappings/realm").body());
        Set<String> alreadyMapped = existingScopeMappings.stream()
            .map(v -> v.asJsonObject().getString("name", ""))
            .collect(java.util.stream.Collectors.toSet());
        JsonObject taskuserRole  = alreadyMapped.contains("taskuser")  ? null : findObject(allRoles, "taskuser");
        JsonObject taskadminRole = alreadyMapped.contains("taskadmin") ? null : findObject(allRoles, "taskadmin");
        if (taskuserRole != null || taskadminRole != null) {
            JsonArrayBuilder rolesToAssign = Json.createArrayBuilder();
            if (taskuserRole  != null) rolesToAssign.add(taskuserRole);
            if (taskadminRole != null) rolesToAssign.add(taskadminRole);
            apiPost("/liberty-demo/client-scopes/" + scopeId + "/scope-mappings/realm",
                rolesToAssign.build().toString());
            System.out.println(" - Assigned roles (taskuser, taskadmin) to scope 'mcp-demo-server'");
        }

        // 9. Configure dynamic client registration policies
        System.out.println("[6/8] Configuring dynamic client registration...");
        JsonArray components = parseArray(
            apiGet("/liberty-demo/components?providerType=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy").body());

        // Delete the "Trusted Hosts" policy component (README: "Delete the Allowed hosts check")
        String trustedHostsId = findId(components, "Trusted Hosts");
        if (trustedHostsId != null) {
            apiDelete("/liberty-demo/components/" + trustedHostsId);
            System.out.println(" - Deleted Trusted Hosts policy");
        }

        // RC-4: Set allowed client scopes for dynamic registration.
        // The provider ID is the legacy "allowed-client-templates", but the config KEY Keycloak
        // reads is "allowed-client-scopes" (ClientScopesClientRegistrationPolicyFactory.ALLOWED_CLIENT_SCOPES).
        // One policy exists per subType (anonymous + authenticated); update all of them.
        for (JsonValue v : components) {
            JsonObject comp = v.asJsonObject();
            if (!"allowed-client-templates".equals(comp.getString("providerId", ""))) continue;
            // Keep other existing config entries, replace the scope list and force
            // "allow-default-scopes" on (realm default scopes are then allowed in addition to the list).
            JsonObjectBuilder cfg = Json.createObjectBuilder();
            JsonObject oldCfg = comp.getJsonObject("config");
            if (oldCfg != null) {
                oldCfg.forEach((k, val) -> {
                    if (!k.equals("allowed-client-scopes") && !k.equals("allowed-client-templates")
                            && !k.equals("allow-default-scopes")) cfg.add(k, val);
                });
            }
            cfg.add("allowed-client-scopes", Json.createArrayBuilder()
                .add("mcp-demo-server").add("profile").add("openid"));
            cfg.add("allow-default-scopes", Json.createArrayBuilder().add("true"));
            apiPut("/liberty-demo/components/" + comp.getString("id"),
                Json.createObjectBuilder()
                    .add("id",           comp.getString("id"))
                    .add("name",         comp.getString("name", "Allowed Client Scopes"))
                    .add("providerId",   "allowed-client-templates")
                    .add("providerType", "org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy")
                    .add("parentId",     comp.getString("parentId", "liberty-demo"))
                    .add("subType",      comp.getString("subType",  "anonymous"))
                    .add("config", cfg)
                    .build().toString());
            System.out.println(" - Configured allowed client scopes (" + comp.getString("subType", "?") + ")");
        }

        // 10. Create users: testuser, testadmin
        System.out.println("[7/8] Configuring users...");
        JsonArray rolesArr = parseArray(apiGet("/liberty-demo/roles").body());
        createUserIfNotExists("testuser", "User",  "testuser123",  findObject(rolesArr, "taskuser"));
        createUserIfNotExists("testadmin","Admin", "testadmin123", findObject(rolesArr, "taskadmin"));

        // 11. Write server.env
        System.out.println("[8/8] Writing server.env...");
        Files.createDirectories(Path.of("src/main/liberty/config"));
        Files.writeString(Path.of("src/main/liberty/config/server.env"),
            "OAUTH_CLIENT_SECRET=" + clientSecret + "\nkeystore_password=" + keystorePassword + "\n");
        System.out.println(" - Wrote src/main/liberty/config/server.env");
        System.out.println("Keycloak configuration completed successfully!");
    }

    // ── User creation ─────────────────────────────────────────────────────────

    private static void createUserIfNotExists(String username, String lastName,
                                              String password, JsonObject role) throws Exception {
        JsonArray uArr = parseArray(apiGet("/liberty-demo/users?username=" + username).body());
        String userId;
        if (uArr.isEmpty()) {
            apiPost("/liberty-demo/users", Json.createObjectBuilder()
                .add("username",      username)
                .add("email",         username + "@example.com")
                .add("firstName",     "Test")
                .add("lastName",      lastName)
                .add("enabled",       true)
                .add("emailVerified", true)
                .add("credentials", Json.createArrayBuilder()
                    .add(Json.createObjectBuilder()
                        .add("type",      "password")
                        .add("value",     password)
                        .add("temporary", false)))
                .build().toString());
            userId = parseArray(apiGet("/liberty-demo/users?username=" + username).body())
                .getJsonObject(0).getString("id");
            System.out.println(" - Created user '" + username + "'");
        } else {
            userId = uArr.getJsonObject(0).getString("id");
        }
        if (role != null) {
            // Check whether the role is already assigned before posting (idempotent)
            JsonArray existingRoles = parseArray(
                apiGet("/liberty-demo/users/" + userId + "/role-mappings/realm").body());
            boolean alreadyAssigned = existingRoles.stream()
                .map(JsonValue::asJsonObject)
                .anyMatch(o -> role.getString("name", "").equals(o.getString("name", "")));
            if (!alreadyAssigned) {
                apiPost("/liberty-demo/users/" + userId + "/role-mappings/realm",
                    Json.createArrayBuilder().add(role).build().toString());
            }
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static JsonObject parseObject(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private static JsonArray parseArray(String json) {
        return Json.createReader(new StringReader(json)).readArray();
    }

    /** Returns the "id" field of the first element whose "name" matches, or null. */
    private static String findId(JsonArray arr, String name) {
        return arr.stream()
            .map(JsonValue::asJsonObject)
            .filter(o -> name.equals(o.getString("name", "")))
            .map(o -> o.getString("id"))
            .findFirst().orElse(null);
    }

    /** Returns the first element whose "name" matches, or null. */
    private static JsonObject findObject(JsonArray arr, String name) {
        return arr.stream()
            .map(JsonValue::asJsonObject)
            .filter(o -> name.equals(o.getString("name", "")))
            .findFirst().orElse(null);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static HttpResponse<String> apiGet(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:8443/admin/realms" + path))
            .header("Authorization", "Bearer " + adminToken)
            .GET().build(), BodyHandlers.ofString());
    }

    private static HttpResponse<String> apiPost(String path, String jsonBody) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:8443/admin/realms" + path))
            .header("Authorization",  "Bearer " + adminToken)
            .header("Content-Type",   "application/json")
            .POST(BodyPublishers.ofString(jsonBody))
            .build(), BodyHandlers.ofString());
    }

    private static HttpResponse<String> apiPut(String path, String jsonBody) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:8443/admin/realms" + path))
            .header("Authorization", "Bearer " + adminToken)
            .header("Content-Type",  "application/json")
            .PUT(jsonBody != null ? BodyPublishers.ofString(jsonBody) : BodyPublishers.noBody())
            .build(), BodyHandlers.ofString());
    }

    private static HttpResponse<String> apiDelete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:8443/admin/realms" + path))
            .header("Authorization", "Bearer " + adminToken)
            .DELETE().build(), BodyHandlers.ofString());
    }
}
