package cn.keeprising.app;

import cn.keeprising.core.Failure;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理 REST 入口：独立于 ChatController，避免单类职责过重超出行数上限。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@RequestBody(required = false) TitleRequest request) {
        String title = request == null ? null : request.title();
        Conversation created = conversationService.createConversation(AppConfig.LOCAL_USER, title);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Conversation> list() {
        return conversationService.listConversations(AppConfig.LOCAL_USER);
    }

    @PatchMapping("/{id}")
    public Conversation rename(@PathVariable UUID id, @RequestBody(required = false) TitleRequest request) {
        String title = request == null ? null : request.title();
        return conversationService.renameConversation(AppConfig.LOCAL_USER, id, title);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversationService.deleteConversation(AppConfig.LOCAL_USER, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessage> messages(@PathVariable UUID id) {
        return conversationService.getMessages(AppConfig.LOCAL_USER, id);
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, Object>> handleFailure(Failure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    public record TitleRequest(String title) {
    }
}
