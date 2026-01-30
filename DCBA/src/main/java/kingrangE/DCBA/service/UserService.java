package kingrangE.DCBA.service;

import kingrangE.DCBA.domain.User;
import kingrangE.DCBA.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private UserRepository userRepo;

    @Autowired
    public UserService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public void signUp(String id, String name, String password, List<String> mainLanguages){
        if (userRepo.findById(id).orElse(null) != null){
            throw new RuntimeException("해당 ID로 가입한 유저가 존재합니다.");
        }
        if (userRepo.findByName(name).orElse(null)!=null){
            throw new RuntimeException("해당 닉네임을 사용하는 유저가 존재합니다.");
        }
        User user = new User(id, name, password, mainLanguages);
        userRepo.save(user);
    }
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
