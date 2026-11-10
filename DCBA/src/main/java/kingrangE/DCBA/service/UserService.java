package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import java.nio.charset.StandardCharsets;

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
        if (name == null || name.isBlank() || !name.equals(name.strip())
                || name.codePointCount(0, name.length()) > 10) {
            throw new UserRequestException("닉네임은 앞뒤 공백 없이 1~10자로 입력해주세요.");
        }
        if (password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UserRequestException("비밀번호는 공백만 사용할 수 없으며 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        // 이미 사용 중인 경우 Error
        if (userRepo.findByName(name).orElse(null) != null) {
            throw new UserRequestException("해당 닉네임을 사용하는 유저가 존재합니다.");
        }
        // 암호화
        String encoded_password = BCrypt.hashpw(password, BCrypt.gensalt());
        // 암호화된 비밀번호로 로그인
        User user = new User(name, encoded_password);
        try {
            userRepo.save(user);
        } catch (DataIntegrityViolationException exception) {
            if (userRepo.findByName(name).isPresent()) {
                throw new UserRequestException("해당 닉네임을 사용하는 유저가 존재합니다.");
            }
            throw exception;
        }
    }

    /**
     * 로그인 로직
     * 
     * @param name     이름
     * @param password 비번
     * @return User 객체
     */
    public User login(String name, String password) {
        if (name == null || name.isBlank() || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UserRequestException("아이디 또는 비밀번호를 확인해주세요.");
        }
        User user = userRepo.findByName(name).orElse(null);

        if (user == null) {
            throw new UserRequestException("존재하지 않는 아이디입니다.");
        }
        String userPassword = user.getPassword();
        if (!BCrypt.checkpw(password, userPassword)) {
            throw new UserRequestException("비밀번호가 틀립니다.");
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
