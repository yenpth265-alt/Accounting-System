package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;

import vn.ketoan.domain.User;

public interface UserRepository {

    Optional<User> findById(long id);

    List<User> findAll();

    void insert(User user);

    void update(User user);
}
