package com.tradewind.account.auth;

import com.tradewind.account.auth.AuthDtos.LoginRequest;
import com.tradewind.account.auth.AuthDtos.RegisterRequest;
import com.tradewind.account.auth.AuthDtos.RegisterResponse;
import com.tradewind.account.auth.AuthExceptions.AccountLockedException;
import com.tradewind.account.auth.AuthExceptions.EmailAlreadyRegisteredException;
import com.tradewind.account.auth.AuthExceptions.InvalidCredentialsException;
import com.tradewind.account.token.TokenPair;
import com.tradewind.account.token.TokenService;
import com.tradewind.account.user.AccountEntity;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import com.tradewind.account.user.UserEntity;
import com.tradewind.account.user.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final AccountRepository accounts;
    private final TokenService tokens;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash = passwordEncoder.encode("timing-equalizer");

    public AuthService(UserRepository users, AccountRepository accounts, TokenService tokens,
                       TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.tokens = tokens;
        this.transactions = transactions;
        this.clock = clock;
    }

    public RegisterResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        // Hash outside the transaction: BCrypt is deliberately slow and must not hold a DB connection.
        String passwordHash = passwordEncoder.encode(request.password());
        return transactions.execute(status -> {
            if (users.existsByEmail(email)) {
                throw new EmailAlreadyRegisteredException();
            }
            Instant now = clock.instant();
            UserEntity user = users.saveAndFlush(new UserEntity(UUID.randomUUID(), email, passwordHash, now));
            AccountEntity account = accounts.save(
                    new AccountEntity(UUID.randomUUID(), user.getId(), AccountStatus.ACTIVE, now));
            return new RegisterResponse(user.getId(), account.getId(), email);
        });
    }

    public TokenPair login(LoginRequest request) {
        Optional<UserEntity> user = users.findByEmail(normalize(request.email()));
        String hash = user.map(UserEntity::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        AccountEntity account = accounts.findByUserId(user.get().getId())
                .orElseThrow(() -> new IllegalStateException("User without account: " + user.get().getId()));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountLockedException();
        }
        return tokens.issue(user.get().getId(), account.getId());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
