package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import vn.ketoan.application.port.UserRepository;
import vn.ketoan.domain.User;

/** Bootstrap/demo identities, not an authentication provider. */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<Long, User> users = new LinkedHashMap<>();

    @Override
    public synchronized Optional<User> findById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("User id must be positive");
        }
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized List<User> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    public synchronized void insert(User user) {
        Objects.requireNonNull(user, "User is required");
        if (users.containsKey(user.getId())) {
            throw new IllegalStateException("User id already exists: " + user.getId());
        }
        requireUniqueUsername(user);
        users.put(user.getId(), user);
    }

    @Override
    public synchronized void update(User user) {
        Objects.requireNonNull(user, "User is required");
        if (!users.containsKey(user.getId())) {
            throw new IllegalArgumentException("User not found: " + user.getId());
        }
        requireUniqueUsername(user);
        users.put(user.getId(), user);
    }

    private void requireUniqueUsername(User user) {
        if (users.values().stream().anyMatch(existing -> existing.getId() != user.getId()
                && existing.getUsername().equals(user.getUsername()))) {
            throw new IllegalStateException("Username already exists: " + user.getUsername());
        }
    }
}
