package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepo;

    /**
     * 회원가입 로직
     * 
     * @param name     이름
     * @param password 비번
     */
    public void signUp(String name, String password) {
        // 이미 사용 중인 경우 Error
        if (userRepo.findByName(name).orElse(null) != null) {
            throw new RuntimeException("해당 닉네임을 사용하는 유저가 존재합니다.");
        }
        // 암호화
        String encoded_password = BCrypt.hashpw(password, BCrypt.gensalt());
        // 암호화된 비밀번호로 로그인
        User user = new User(name, encoded_password);
        userRepo.save(user);
    }

    /**
     * 로그인 로직
     * 
     * @param name     이름
     * @param password 비번
     * @return User 객체
     */
    public User login(String name, String password) {
        User user = userRepo.findByName(name).orElse(null);

        if (user == null) {
            throw new RuntimeException("존재하지 않는 아이디입니다.");
        }
        String userPassword = user.getPassword();
        if (!BCrypt.checkpw(password, userPassword)) {
            throw new RuntimeException("비밀번호가 틀립니다.");
        }
        return user;
    }

    /**
     * Slack ID 업데이트 로직
     *
     * @param userId user Id
     * @param slackId 업데이트할 slackId
     */
    public void updateSlackId(Long userId, String slackId) {
        if (slackId.isEmpty()){
            return;
        }
        User user = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.updateSlackId(slackId);
        userRepo.save(user);
    }

    public User getUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
}
