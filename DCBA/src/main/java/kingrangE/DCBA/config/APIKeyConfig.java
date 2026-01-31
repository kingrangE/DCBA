package kingrangE.DCBA.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class APIKeyConfig {
    @Value("${exaone-api-key}")
    private String exaoneApiKey;

    @Value("${exaone-api-url}")
    private String exaoneApiUrl;

    public String getExaoneApiKey() {
        return exaoneApiKey;
    }

    public String getExaoneApiUrl() {
        return exaoneApiUrl;
    }
}
