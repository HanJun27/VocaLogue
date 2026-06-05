package com.lingoai.repository;

import com.lingoai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话数据访问接口
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByUserIdOrderByStartTimeDesc(String userId);

    List<Conversation> findByScenarioIdOrderByStartTimeDesc(String scenarioId);

}
