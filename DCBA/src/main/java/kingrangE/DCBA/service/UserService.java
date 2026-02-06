package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepo;

    /**
     * 회원가입 로직
     * @param name 이름
     * @param password 비번
     */
    public void signUp(String name, String password){
        // 이미 사용 중인 경우 Error
        if (userRepo.findByName(name).orElse(null)!=null){
            throw new RuntimeException("해당 닉네임을 사용하는 유저가 존재합니다.");
        }
        User user = new User(name, password);
        userRepo.save(user);
    }

    /**
     * 로그인 로직
     * @param name 이름
     * @param password 비번
     * @return User 객체
     */
    public User login(String name, String password){
        User user = userRepo.findByName(name).orElse(null);
        if (user == null){
            throw new RuntimeException("존재하지 않는 아이디입니다.");
        }
        if (!user.getPassword().equals(password)){
            throw new RuntimeException("비밀번호가 틀립니다.");
        }
        return user;
    }
}
