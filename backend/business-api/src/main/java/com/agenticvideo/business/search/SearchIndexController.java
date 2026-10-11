package com.agenticvideo.business.search;

import com.agenticvideo.business.project.ProjectAccess;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class SearchIndexController {
    private final JdbcTemplate database;private final ProjectAccess access;private final SearchIndexes indexes;private final TransactionTemplate transactions;
    SearchIndexController(JdbcTemplate database,ProjectAccess access,SearchIndexes indexes,PlatformTransactionManager manager) {this.database=database;this.access=access;this.indexes=indexes;this.transactions=new TransactionTemplate(manager);}
    @GetMapping("/api/v1/assets/{asset}/search-index")
    Map<String,Object> status(@PathVariable UUID asset,Authentication account) {
        requireOwned(asset,account);
        return Map.of("data",indexes.status(asset),"requestId",UUID.randomUUID());
    }
    @PostMapping("/api/v1/assets/{asset}/search-index")
    ResponseEntity<?> rebuild(@PathVariable UUID asset,Authentication account) {
        requireOwned(asset,account);
        return ResponseEntity.accepted().body(Map.of("data",transactions.execute(tx->indexes.enqueue(asset)),"requestId",UUID.randomUUID()));
    }
    private void requireOwned(UUID asset,Authentication account) {
        var rows=database.queryForList("SELECT project_id FROM assets WHERE id=?",asset);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"素材不存在或无权访问");
        access.requireOwned((UUID)rows.get(0).get("project_id"),account);
    }
}
