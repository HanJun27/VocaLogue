package com.lingoai.controller;

import com.lingoai.dto.response.ApiResponse;
import com.lingoai.dto.response.ScenarioDTO;
import com.lingoai.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景管理控制器
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
@Slf4j
public class ScenarioController {

    private final ScenarioService scenarioService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ScenarioDTO>>> getAllScenarios() {
        log.info("获取所有场景列表");
        List<ScenarioDTO> scenarios = scenarioService.getAllScenarios();
        return ResponseEntity.ok(ApiResponse.success(scenarios));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScenarioDTO>> getScenarioById(@PathVariable String id) {
        log.info("获取场景详情: {}", id);
        ScenarioDTO scenario = scenarioService.getScenarioById(id);
        return ResponseEntity.ok(ApiResponse.success(scenario));
    }

}
