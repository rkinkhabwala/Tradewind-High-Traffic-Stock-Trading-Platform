package com.tradewind.load;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates (or tops up) the load-test users through the public API: register (409 = already exists), log in, and
 * deposit until each holds at least $500,000 of buying power ($1,000,000 per deposit, the funding cap). Writes
 * the users' credentials to {@code load-tests/work.nosync/load-users.csv} for the simulations. Safe to re-run.
 * <p>
 * {@code mvn -pl load-tests exec:java -Dexec.mainClass=com.tradewind.load.LoadUsers -Dload.users=1000}
 */
public final class LoadUsers {

    static final long TARGET_BUYING_POWER_CENTS = 50_000_000L;
    static final long DEPOSIT_CENTS = 100_000_000L;

    public static void main(String[] args) throws Exception {
        int count = LoadConfig.userCount();
        String password = LoadConfig.userPassword();
        Api api = new Api(LoadConfig.gatewayUrl());
        AtomicInteger created = new AtomicInteger();
        AtomicInteger funded = new AtomicInteger();
        long start = System.nanoTime();

        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 1; i <= count; i++) {
                String email = LoadConfig.userEmail(i);
                results.add(pool.submit(() -> prepare(api, email, password, created, funded)));
            }
        }
        List<String> lines = new ArrayList<>(List.of("email,password"));
        for (Future<String> result : results) {
            lines.add(result.get() + "," + password);
        }
        Files.createDirectories(LoadConfig.usersFile().toAbsolutePath().getParent());
        Files.write(LoadConfig.usersFile(), lines);
        System.out.printf("%d load users ready (%d created, %d funded) in %ds -> %s%n", count, created.get(),
                funded.get(), (System.nanoTime() - start) / 1_000_000_000L, LoadConfig.usersFile().toAbsolutePath());
    }

    private static String prepare(Api api, String email, String password, AtomicInteger created, AtomicInteger funded)
            throws IOException, InterruptedException {
        Api.Response registered = api.post("/api/auth/register", null, Map.of("email", email, "password", password), false);
        if (registered.status() == 201) {
            created.incrementAndGet();
        } else if (registered.status() != 409) {
            throw new IllegalStateException("register " + email + ": " + registered.status() + " " + registered.body());
        }
        Api.Response login = api.post("/api/auth/login", null, Map.of("email", email, "password", password), false);
        if (login.status() != 200) {
            throw new IllegalStateException("login " + email + ": " + login.status() + " " + login.body());
        }
        String token = login.body().get("accessToken").asText();
        long buyingPower = api.get("/api/portfolio", token).body().get("buyingPowerCents").asLong();
        if (buyingPower < TARGET_BUYING_POWER_CENTS) {
            Api.Response deposit = api.post("/api/account/deposits", token, Map.of("amountCents", DEPOSIT_CENTS), true);
            if (deposit.status() != 200 && deposit.status() != 201) {
                throw new IllegalStateException("deposit " + email + ": " + deposit.status() + " " + deposit.body());
            }
            funded.incrementAndGet();
        }
        return email;
    }

    private LoadUsers() {
    }
}
