package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.tools.ToolFailure;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.*;

@RestControllerAdvice(assignableTypes={com.agenticvideo.business.render.DraftPreviewController.class,AgentRunController.class,DraftAcceptanceController.class,com.agenticvideo.business.conversation.ConversationController.class})
class AgentRunErrors {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(BAD_REQUEST)
    Object invalid(IllegalArgumentException failure){return Map.of("error",Map.of("code","INVALID_AGENT_CONTRACT","message",failure.getMessage()));}
    @ExceptionHandler(ToolFailure.class) @ResponseStatus(CONFLICT)
    Object dependency(ToolFailure failure){return Map.of("error",Map.of("code",failure.code,"category",failure.category,"message",failure.getMessage()));}
}
