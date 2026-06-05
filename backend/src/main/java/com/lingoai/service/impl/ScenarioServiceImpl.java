package com.lingoai.service.impl;

import com.lingoai.dto.response.ScenarioDTO;
import com.lingoai.entity.Scenario;
import com.lingoai.entity.ScenarioQuestion;
import com.lingoai.repository.ScenarioQuestionRepository;
import com.lingoai.repository.ScenarioRepository;
import com.lingoai.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioServiceImpl implements ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final ScenarioQuestionRepository scenarioQuestionRepository;

    @Override
    public List<ScenarioDTO> getAllScenarios() {
        log.debug("获取所有场景");
        List<Scenario> scenarios = scenarioRepository.findAllByOrderByDifficultyAsc();
        return scenarios.stream()
                .map(ScenarioDTO::fromEntitySimple)
                .toList();
    }

    @Override
    public ScenarioDTO getScenarioById(String id) {
        log.debug("获取场景: {}", id);
        Scenario scenario = scenarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("场景不存在: " + id));
        List<ScenarioQuestion> questions = scenarioQuestionRepository.findByScenarioIdOrderByOrderIndexAsc(id);
        return ScenarioDTO.fromEntity(scenario, questions);
    }

}
