package dev.arctic.local;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class HealthController {

    private final String version;

    HealthController(@Value("${arctic.version}") String version) {
        this.version = version;
    }

    @GetMapping("/health")
    Health health() {
        return new Health("ready", version);
    }

    record Health(String status, String version) {
    }
}
