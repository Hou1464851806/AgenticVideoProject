package com.agenticvideo.business.agentrun;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class DraftAcceptanceController {
    private final DraftAcceptances acceptances;
    public DraftAcceptanceController(DraftAcceptances acceptances){this.acceptances=acceptances;}
    @PostMapping("/api/v1/conversations/{conversation}/drafts/accept")
    Object accept(@PathVariable UUID conversation,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AgentRunContracts.DraftBinding request,Authentication account){return Map.of("data",acceptances.accept(conversation,key,request,account),"requestId",UUID.randomUUID());}
}
