package com.lingoai.repository;

import com.lingoai.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 场景数据访问接口
 */
@Repository
public interface ScenarioRepository extends JpaRepository<Scenario, String> {

    List<Scenario> findAllByOrderByDifficultyAsc();

}
