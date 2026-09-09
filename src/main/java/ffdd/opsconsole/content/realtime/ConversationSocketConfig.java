package ffdd.opsconsole.content.realtime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.config.annotation.*;
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ConversationSocketConfig implements WebSocketConfigurer {
    private final ConversationSocketHandler handler;
    private final Environment environment;
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        boolean dev=Arrays.stream(environment.getActiveProfiles()).anyMatch(Set.of("dev","local","test")::contains);
        String origins=environment.getProperty("nexion.websocket.allowed-origins",dev?
                environment.getProperty("nexion.cors.development-allowed-origins","http://localhost:5173,http://127.0.0.1:5173,http://localhost:3002,http://127.0.0.1:3002"):
                environment.getProperty("nexion.cors.allowed-origins",""));
        String[] allowed=Arrays.stream(origins.split(",")).map(String::trim)
                .filter(s->s.matches((dev?"https?":"https")+"://[^/*]+" )).toArray(String[]::new);
        registry.addHandler(handler,"/ws/conversations").setAllowedOrigins(allowed);
    }
}
