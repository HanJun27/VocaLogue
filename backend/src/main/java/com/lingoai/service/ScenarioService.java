package com.lingoai.service;

import com.lingoai.dto.response.ScenarioDTO;

import java.util.List;

/**
 * 场景服务接口
 */
public interface ScenarioService {

    List<ScenarioDTO> getAllScenarios();

    ScenarioDTO getScenarioById(String id);

}
