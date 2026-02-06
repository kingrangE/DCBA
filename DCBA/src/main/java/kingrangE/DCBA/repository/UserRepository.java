package kingrangE.DCBA.repository;


import kingrangE.DCBA.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {
    /**
     * 유저 name을 기반으로 찾기
     * @param name 유저 name
     * @return 유저 객체(Optional)
     */
    Optional<User> findByName(String name); // 유저 Name을 받아 User 정보 반환
}
