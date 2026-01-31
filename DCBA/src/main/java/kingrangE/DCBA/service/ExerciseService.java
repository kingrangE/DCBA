package kingrangE.DCBA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import kingrangE.DCBA.config.APIKeyConfig;
import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import kingrangE.DCBA.repository.ExerciseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ExerciseService {
    private final ExerciseRepository exerciseRepository;
    private final APIKeyConfig apiKeyConfig;
    ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    public ExerciseService(ExerciseRepository exerciseRepository, APIKeyConfig apiKeyConfig) {
        this.exerciseRepository = exerciseRepository;
        this.apiKeyConfig = apiKeyConfig;
    }

    public Exercise generateExercise(Subject subject, Level level) {
        try {
            String jsonBody = createRequestBody(subject,level);

            HttpResponse<String> response = getResponse(jsonBody);

            return getExerciseWithResponse(response,subject,level);
        } catch (Exception e) {
            System.out.println("[Error] Generating Exercise : " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private Boolean validateExerciseDuplicate() throws Exception{
        // 문제 중복 여부 검사
        // Redis 와 형태소 분석 NLP 서버를 활용하여 중복 검사
        return null;
    }

    public List<Exercise> getAllExercise(){
        // 모든 문제 Get
        return exerciseRepository.findAllExercises();
    }

    public Exercise getExercise(Subject subject,Level level) throws Exception{
        // 유저에게 맞는 문제 하나 반환
        return exerciseRepository.findExercisesBySubjectAndLevel(subject,level).get(0);
    }

    private Exercise getExerciseWithResponse(HttpResponse<String> response,Subject subject,Level level) throws Exception {
        try {
            JsonNode rootNode = objectMapper.readTree(response.body());
            String content = rootNode.path("choices").get(0).path("message").path("content").asText();
            String question = "";
            String answer = "";
            for (String line : content.split("\n")) {
                String processedLine = line.trim(); // 앞 뒤 공백 제거
                if (processedLine.startsWith("Q:")) {
                    question = processedLine.replace("Q:", "");
                } else if (processedLine.startsWith("A:")) {
                    answer = processedLine.replace("A:", "");
                }
            }
            if (question.isEmpty() || answer.isEmpty()) {
                throw new Exception("[Error] Cannot found question-answer set \n Answers received : " + content);
            }
            return new Exercise(1,question,answer,subject,level);
        }catch (Exception e){
            throw new Exception("[Error] Cannot parse the answer : "+e.getMessage());
        }

    }

    private HttpResponse<String> getResponse(String jsonBody) throws IOException, InterruptedException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiKeyConfig.getExaoneApiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKeyConfig.getExaoneApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response;
        }catch (IOException e){
            throw new IOException("[Error] Receiving Response :"+e.getMessage());
        }catch (InterruptedException e){
            throw new InterruptedException("[Error] Interrupt :" +e.getMessage());
        }
    }

    private String createRequestBody(Subject subject, Level level) throws Exception{
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "LGAI-EXAONE/K-EXAONE-236B-A23B");
            requestBody.put("parse_reasoning", true);
            requestBody.put("chat_template_kwargs", Map.of("enable_thinking", true));
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "너는 국내외 빅테크 기업에서 15년 이상 근무하며 수많은 기술 면접을 진행해 온 '수석 소프트웨어 엔지니어'이자 '베테랑 면접관'이야. \n" +
                            "단순히 암기한 지식이 아니라, 지원자가 기술의 본질을 이해하고 실무에 적용할 수 있는지를 판단할 수 있는 문제를 만든다."),
                    Map.of("role", "user", "content", """
                            # Task
                            제시된 [과목명]과 [난이도]에 따라, 실제 대기업 기술 면접에서 나올 법한 수준 높은 질문을 생성하라.
                            
                            # 난이도별 가이드라인
                            - 난이도 1 (기초): "신입 개발자가 반드시 알아야 할 핵심 용어의 정의와 목적을 설명할 수 있는가?"
                            - 난이도 2 (기본): "특정 기술의 동작 원리를 이해하고, 장단점이나 트레이드오프(Trade-off)를 명확히 설명할 수 있는가?"
                            - 난이도 3 (심화): "복잡한 시스템 설계나 대용량 트래픽 상황에서 발생할 수 있는 문제 해결 능력을 묻는가? 혹은 상충하는 두 기술을 깊이 있게 비교 분석할 수 있는가?"
                            
                            # Input Data
                            - 과목명: {subject}
                            - 난이도: {level}
                            
                            # Response Format (반드시 이 형식을 유지할 것)
                            Q: (면접관이 질문하듯 구어체로 작성)
                            A: (시니어 엔지니어 입장에서 면접자에게 기대하는 정석적이고 논리적인 답변을 서술)"""
                            .replace("{subject}", subject.getSubjectName())
                            .replace("{level}", String.valueOf(level.getLevel())))
            ));

            return objectMapper.writeValueAsString(requestBody);
        }
        catch (Exception e){
            throw new Exception("[Error] Creating Request body :",e);
        }
    }
}
