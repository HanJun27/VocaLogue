package com.lingoai.service.impl;

import com.lingoai.dto.response.ScenarioDTO;
import com.lingoai.entity.Scenario;
import com.lingoai.repository.ScenarioRepository;
import com.lingoai.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 场景服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioServiceImpl implements ScenarioService {

    private final ScenarioRepository scenarioRepository;

    @Override
    public List<ScenarioDTO> getAllScenarios() {
        log.debug("获取所有场景");
        return scenarioRepository.findAllByOrderByDifficultyAsc()
                .stream()
                .map(ScenarioDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ScenarioDTO getScenarioById(String id) {
        log.debug("获取场景: {}", id);
        Scenario scenario = scenarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("场景不存在: " + id));
        return ScenarioDTO.fromEntity(scenario);
    }

}
