package kingrangE.DCBA.repository;


import kingrangE.DCBA.domain.User;

import java.util.Optional;

public interface UserRepository {

    String save(User user); // 유저 객체를 받아 저장
    Optional<User> findById(String id); // 유저 ID를 받아 User 정보 반환
    Optional<User> findByName(String name); // 유저 Name을 받아 User 정보 반환
    User deleteById(String id); // 유저 ID를 받아 User 정보 삭제 후, 삭제된 유저 정보 반환
    void addExerciseNumber(Integer exerciseNumber); // 문제 번호를 전달받아 유저가 받은 문제 리스트에 추가
    void addExerciseBlackList(Integer exerciseNumber); // Exercise BlackList에 번호 추가
}
