package com.lingoai.repository;

import com.lingoai.entity.ScenarioQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 场景问题数据访问接口
 */
@Repository
public interface ScenarioQuestionRepository extends JpaRepository<ScenarioQuestion, String> {

    List<ScenarioQuestion> findByScenarioIdOrderByOrderIndexAsc(String scenarioId);

}
