package kingrangE.DCBA.repository;


import kingrangE.DCBA.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 유저 name을 기반으로 찾기
     * @param name 유저 name
     * @return 유저 객체(Optional)
     */
    Optional<User> findByName(String name); // 유저 Name을 받아 User 정보 반환
}
