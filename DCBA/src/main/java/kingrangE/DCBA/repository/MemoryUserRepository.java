package kingrangE.DCBA.repository;

import kingrangE.DCBA.domain.User;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MemoryUserRepository implements UserRepository{
    private ConcurrentHashMap<String,User> repo = new ConcurrentHashMap<>();

    @Override
    public String save(User user) {
        repo.put(user.getId(),user);
        return user.getId();
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(repo.get(id));
    }

    @Override
    public Optional<User> findByName(String name) {
        return repo.values().stream().filter(user -> user.getName().equals(name)).findAny();
    }

    @Override
    public User deleteById(String id) {
        return repo.remove(id);
    }

    @Override
    public void addExerciseNumber(String id,Integer exerciseNumber) {
        User user = repo.get(id);
        user.getExercises().add(exerciseNumber);
    }

    @Override
    public void addExerciseBlackList(String id,Integer exerciseNumber) {
        User user = repo.get(id);
        user.getBlacklist().add(exerciseNumber);
    }
}
