package com.tradewind.load;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Access tokens of the load users, logged in once before a run (login is BCrypt-bound by design, so it is not
 * part of the measured traffic). Tokens live 15 minutes; runs are shorter.
 */
final class Tokens {

    record User(int index, String email, String token) {
    }

    private static volatile List<User> users = List.of();

    private Tokens() {
    }

    static List<User> login() throws Exception {
        List<String> lines = Files.readAllLines(LoadConfig.usersFile());
        Api api = new Api(LoadConfig.gatewayUrl());
        List<Future<User>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                int index = i;
                futures.add(pool.submit(() -> {
                    Api.Response r = api.post("/api/auth/login", null, Map.of("email", parts[0], "password", parts[1]), false);
                    if (r.status() != 200) {
                        throw new IllegalStateException("login " + parts[0] + ": " + r.status());
                    }
                    return new User(index, parts[0], r.body().get("accessToken").asText());
                }));
            }
        }
        List<User> result = new ArrayList<>();
        for (Future<User> f : futures) {
            result.add(f.get());
        }
        users = List.copyOf(result);
        System.out.printf("[load] %d load users logged in in %d ms%n", result.size(), (System.nanoTime() - start) / 1_000_000);
        return users;
    }

    static List<User> users() {
        return users;
    }

    /** Endless feeder: a random load user per virtual user (so per-user rates stay even at any total rate). */
    static Iterator<Map<String, Object>> randomFeeder() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Map<String, Object> next() {
                User u = users.get(ThreadLocalRandom.current().nextInt(users.size()));
                return Map.of("token", u.token(), "user", u.index());
            }
        };
    }
}
